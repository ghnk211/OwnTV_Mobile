package tv.own.owntv.mobile.tvbox.repository

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.config.model.TvBoxConfig
import tv.own.owntv.mobile.tvbox.config.parser.ConfigParser
import tv.own.owntv.mobile.tvbox.config.parser.FindResult
import tv.own.owntv.mobile.tvbox.data.prefs.TvboxPrefs
import tv.own.owntv.mobile.tvbox.net.AddressFetcher
import tv.own.owntv.mobile.tvbox.net.NetApi
import tv.own.owntv.mobile.tvbox.util.Aes
import tv.own.owntv.mobile.tvbox.util.AppScope
import tv.own.owntv.mobile.tvbox.util.FileUtil
import tv.own.owntv.mobile.tvbox.util.HtmlGuard
import tv.own.owntv.mobile.tvbox.util.LocalRead
import tv.own.owntv.mobile.tvbox.util.LocalSource
import tv.own.owntv.mobile.tvbox.util.LocalSourceResolver
import tv.own.owntv.mobile.tvbox.util.Md5Util
import tv.own.owntv.mobile.tvbox.util.RemoteArtifact
import tv.own.owntv.mobile.tvbox.util.SourceHealth
import tv.own.owntv.mobile.tvbox.util.SpiderSpec
import tv.own.owntv.mobile.tvbox.util.TbLog
import tv.own.owntv.mobile.tvbox.util.UaUtil
import tv.own.owntv.mobile.tvbox.util.toHeaderMap
import java.io.File

/**
 * 配置中心（TVbox 精简移植，对应 VodPlayer `ConfigRepository`）。
 *
 * 与源实现的差异（方案 §2.2/§12.1 拍板）：
 * - 持久化不进 Room：配置元数据存 `files/tvbox/configs/index.json`，正文缓存存
 *   `files/tvbox/configs/{id}.json`，活跃 id / 来源 URL 存独立 `tvbox_prefs`。
 * - 不带备份导出、py 分流、TamperGuard、SourceStat（无效源标记用 [TvboxSourceStats] 空实现）。
 * - `importFromUrl` 全顺序保留：去重 → 抓取 → 解密 → HTML 门控 → fixContentPath →
 *   ConfigParser.parse → 保存来源 URL → 后台 jar 预下载；`resolveAgainst` 的两处
 *   使用点兜底（[ensureSiteJar] / [downloadSpiderLocked]）同样保留。
 */
class TvboxConfigRepository(
    private val context: Context,
    private val net: NetApi,
    private val prefs: TvboxPrefs,
    private val addressFetcher: AddressFetcher,
) {

    private val mutex = Mutex()
    private val jarDownloadMutex = Mutex()

    // ---------------- 配置元数据（index.json 持久化） ----------------

    /** 一条已导入配置的元数据（对应 VodPlayer 的 Room ConfigEntity）。 */
    data class ConfigMeta(
        val id: Long,
        val name: String,
        val url: String?,
        val siteCount: Int,
        val source: String,
        val addedAt: Long,
    )

    private val _configs = MutableStateFlow<List<ConfigMeta>>(emptyList())
    val configs: StateFlow<List<ConfigMeta>> = _configs.asStateFlow()

    private val indexFile: File get() = File(FileUtil.configDir(context), "index.json")

    private fun loadIndexLocked(): MutableList<ConfigMeta> = runCatching {
        val text = FileUtil.readText(indexFile) ?: return mutableListOf()
        val arr = JSONArray(text)
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ConfigMeta(
                id = o.getLong("id"),
                name = o.getString("name"),
                url = o.optString("url").takeIf { it.isNotEmpty() },
                siteCount = o.optInt("siteCount"),
                source = o.optString("source", "url"),
                addedAt = o.optLong("addedAt"),
            )
        }
    }.getOrDefault(mutableListOf())

    private fun persistIndexLocked(list: List<ConfigMeta>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("url", m.url ?: "")
                    .put("siteCount", m.siteCount)
                    .put("source", m.source)
                    .put("addedAt", m.addedAt),
            )
        }
        FileUtil.writeText(indexFile, arr.toString())
        _configs.value = list
    }

    private fun metaById(id: Long): ConfigMeta? = _configs.value.firstOrNull { it.id == id }

    /** Currently active config id, or -1 when none. */
    fun activeId(): Long = prefs.activeConfigId

    private fun metaByUrl(url: String): ConfigMeta? = _configs.value.firstOrNull { it.url == url }

    private fun activeMeta(): ConfigMeta? = _configs.value.firstOrNull { it.id == prefs.activeConfigId }

    // ---------------- 内存态 ----------------

    private val _activeConfig = MutableStateFlow<TvBoxConfig?>(null)
    val activeConfig: StateFlow<TvBoxConfig?> = _activeConfig.asStateFlow()

    /** 后台预下载是否仍在进行（导入结果显示"正在下载"，结束后要及时收回） */
    private val _spiderPrefetching = MutableStateFlow(false)
    val spiderPrefetching: StateFlow<Boolean> = _spiderPrefetching.asStateFlow()
    private val prefetchActive = java.util.concurrent.atomic.AtomicInteger(0)

    private val _sites = MutableStateFlow<List<SiteConfig>>(emptyList())
    val sites: StateFlow<List<SiteConfig>> = _sites.asStateFlow()

    /** 首页源变更信号（换源 / 切配置 / 重新导入 / 删除配置都会 +1）。 */
    private val _homeSiteVersion = MutableStateFlow(0)
    val homeSiteVersion: StateFlow<Int> = _homeSiteVersion.asStateFlow()

    /** 站点 key → 配置，供详情/播放时反查 */
    val siteMap: Map<String, SiteConfig>
        get() = _sites.value.associateBy { it.key }

    /** 配置生效回调（注入嗅探引擎等），由 TvboxModule 赋值 */
    var onConfigApplied: (TvBoxConfig) -> Unit = {}

    /** spider jar 就绪后的 ClassLoader 预热钩子（注入 JarEngine.warmUp），由 TvboxModule 赋值 */
    var onSpiderWarmUp: suspend () -> Unit = {}

    /** 配置变更后的清理动作，由 TvboxModule 注入引擎池与网络层 */
    var onSwitchHook: suspend (Long) -> Unit = {}

    // ---------------- 冷启动恢复 ----------------

    /**
     * 启动时恢复激活配置。缓存优先；缓存失效按原地址回源刷新（来源 URL 从 prefs 取）。
     */
    suspend fun restoreActive() {
        if (_configs.value.isEmpty()) {
            val list = mutex.withLock { loadIndexLocked() }
            if (_configs.value.isEmpty()) _configs.value = list
        }
        val entity = activeMeta()
        if (entity == null) {
            TbLog.i("无可用配置，等待用户导入")
            return
        }
        val cached = loadFromCache(entity, allowStale = entity.url.isNullOrBlank())
        if (cached != null) {
            mutex.withLock { applyConfig(cached) }
            TbLog.i("已从缓存恢复配置：%s（%d 站点）", entity.name, cached.sites.size)
            return
        }
        val url = entity.url
        if (url.isNullOrBlank()) {
            TbLog.w("配置 %s 无缓存且无 URL，无法恢复", entity.name)
            return
        }
        val refreshed = when (val fetched = fetchConfig(url)) {
            is FetchOutcome.Ok -> ConfigParser.parse(
                LocalSourceResolver.fixContentPath(
                    FindResult.decode(fetched.text, fetched.configKey),
                    fetched.dirPrefix,
                ),
            ).config

            is FetchOutcome.Err -> {
                TbLog.w("配置刷新失败：%s", fetched.message)
                null
            }
        }
        if (refreshed != null) {
            FileUtil.writeText(File(FileUtil.configDir(context), "${entity.id}.json"), encodeConfig(refreshed))
            mutex.withLock {
                // 回写前复查 active id 未变：网络期间用户可能已切到别的配置
                val stillActive = prefs.activeConfigId == entity.id
                if (stillActive) {
                    applyConfig(refreshed)
                    TbLog.i("配置已刷新：%s（%d 站点）", entity.name, refreshed.sites.size)
                    launchSpiderPrefetch(refreshed, entity.url)
                } else {
                    TbLog.i("恢复期间 active 配置已变更（%d），跳过回写", entity.id)
                }
            }
        } else {
            TbLog.w("配置刷新失败：无可用缓存且原地址已失效")
        }
    }

    // ---------------- 抓取 ----------------

    private sealed interface FetchOutcome {
        data class Ok(
            val text: String,
            val configKey: String?,
            /** 配置文件所在目录前缀，用于修正正文里的 `./` */
            val dirPrefix: String?,
        ) : FetchOutcome

        data class Err(val message: String, val needsPermission: Boolean = false) : FetchOutcome
    }

    private suspend fun fetchConfig(address: String): FetchOutcome {
        val src = LocalSourceResolver.parse(address)
        val configKey = FindResult.configKeyOf(address)
        // 目录前缀：本地形态由 LocalSource 提供；http(s) 形态按配置 URL 推导
        val dirPrefix = src?.dirPrefix ?: LocalSourceResolver.httpDirPrefix(address)

        val httpUrl = when (src) {
            is LocalSource.RemoteClan -> src.httpUrl
            null -> address
            else -> null
        }

        if (httpUrl != null) {
            val resp = net.getText(httpUrl, timeoutMs = 30_000)
            // UA 门控兜底：门控站点对浏览器 UA 返回 HTML 落地页，okhttp 系 UA 才给真配置
            if (HtmlGuard.looksLikeHtml(resp.body)) {
                TbLog.tag(TAG).w("配置地址返回 HTML 页面，以 okhttp UA 重试：%s", httpUrl)
                val retry = net.getText(httpUrl, timeoutMs = 30_000, ua = UaUtil.GATE_UA)
                if (retry.isSuccess && !HtmlGuard.looksLikeHtml(retry.body)) {
                    TbLog.tag(TAG).i("UA 门控兜底成功，拿到配置正文：%d 字节", retry.body.length)
                    return FetchOutcome.Ok(retry.body, configKey, dirPrefix)
                }
                return if (resp.isSuccess) {
                    FetchOutcome.Err("配置地址返回的是网页而非配置数据（站点按 User-Agent 分流，已自动重试）")
                } else {
                    FetchOutcome.Err("下载配置失败：${resp.error ?: "HTTP ${resp.code}"}")
                }
            }
            return if (resp.isSuccess) {
                FetchOutcome.Ok(resp.body, configKey, dirPrefix)
            } else {
                FetchOutcome.Err("下载配置失败：${resp.error ?: "HTTP ${resp.code}"}")
            }
        }

        return when (val r = addressFetcher.fetch(address)) {
            is AddressFetcher.Outcome.Ok -> FetchOutcome.Ok(
                String(r.bytes, Charsets.UTF_8),
                configKey,
                dirPrefix,
            )

            is AddressFetcher.Outcome.Err -> FetchOutcome.Err(
                r.message,
                needsPermission = r.needsPermission,
            )
        }
    }

    // ---------------- 导入 ----------------

    sealed interface ImportResult {
        data class Success(
            val configId: Long,
            val siteCount: Int,
            val warnings: List<String> = emptyList(),
            val spiderDownloading: Boolean = false,
        ) : ImportResult

        data class Failure(
            val message: String,
            val warnings: List<String> = emptyList(),
            val needsPermission: Boolean = false,
        ) : ImportResult
    }

    /**
     * 按地址导入配置。支持 http(s) / clan:// / file:// / 裸绝对路径。
     */
    suspend fun importFromUrl(
        url: String,
        activate: Boolean = true,
        persist: Boolean = true,
    ): ImportResult = withContext(Dispatchers.IO) {
        val target = url.trim()
        if (target.isEmpty()) return@withContext ImportResult.Failure("地址为空")

        // 同一地址不重复导入（只拦 http(s) 形态；本地地址内容可能已变）。
        if (persist && LocalSourceResolver.parse(target) == null) {
            metaByUrl(target)?.let { exist ->
                TbLog.i("配置已存在，跳过导入：%s（id=%d）", target, exist.id)
                return@withContext ImportResult.Failure("此配置已存在：${exist.name}")
            }
        }

        val fetched = fetchConfig(target)
        val rawText = when (fetched) {
            is FetchOutcome.Ok -> fetched.text
            is FetchOutcome.Err -> return@withContext ImportResult.Failure(
                fetched.message,
                needsPermission = fetched.needsPermission,
            )
        }

        val decoded = FindResult.decode(rawText, fetched.configKey)
        if (!Aes.isJson(decoded)) {
            TbLog.tag(TAG).w("配置解密后仍非 JSON（前 80 字符：%s）", decoded.take(80))
        }
        if (HtmlGuard.looksLikeHtml(decoded)) {
            return@withContext ImportResult.Failure("配置内容是网页而非配置数据（请检查地址或文件是否正确）")
        }

        val fixed = LocalSourceResolver.fixContentPath(decoded, fetched.dirPrefix)

        val parsed = ConfigParser.parse(fixed)
        if (!parsed.isSuccess) {
            return@withContext ImportResult.Failure(
                parsed.error ?: "配置解析失败",
                parsed.warnings,
            )
        }

        val config = parsed.config!!
        val name = guessName(target)
        val spiderDownloading = if (persist) {
            val kind = if (LocalSourceResolver.isLocalPath(target)) "local" else "url"
            saveConfig(config, name, target, kind, activate)
            val needDownload = config.spider?.isNotBlank() == true && !hasCachedSpider(config.spider)
            if (needDownload) launchSpiderPrefetch(config, target)
            needDownload
        } else {
            applyConfig(config)
            false
        }

        ImportResult.Success(
            configId = prefs.activeConfigId,
            siteCount = config.supportedSites().size,
            warnings = parsed.warnings,
            spiderDownloading = spiderDownloading,
        )
    }

    /** 本地文件导入（SAF 读取的文本） */
    suspend fun importFromText(
        text: String,
        fileName: String?,
        activate: Boolean = true,
    ): ImportResult = withContext(Dispatchers.IO) {
        val parsed = ConfigParser.parse(text)
        if (!parsed.isSuccess) {
            return@withContext ImportResult.Failure(parsed.error ?: "配置解析失败", parsed.warnings)
        }
        val config = parsed.config!!
        val name = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "本地配置"
        val spiderDownloading = if (saveConfig(config, name, null, "file", activate)) {
            val needDownload = config.spider?.isNotBlank() == true && !hasCachedSpider(config.spider)
            if (needDownload) launchSpiderPrefetch(config)
            needDownload
        } else false

        ImportResult.Success(
            configId = prefs.activeConfigId,
            siteCount = config.supportedSites().size,
            warnings = parsed.warnings,
            spiderDownloading = spiderDownloading,
        )
    }

    /** SAF 本地文件导入统一入口 */
    suspend fun importLocalFile(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val text = FileUtil.readTextFromUri(context, uri)
            ?: return@withContext ImportResult.Failure("无法读取所选文件（权限不足或文件已移动）")
        val name = displayNameOf(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
        importFromText(text, name, activate = true)
    }

    /** 查询 SAF 的真实文件名（失败/无权限时返回 null，由调用方回退） */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    // ---------------- 入库 / 生效 ----------------

    /** 入库 + 落缓存 + 激活 */
    private suspend fun saveConfig(
        config: TvBoxConfig,
        name: String,
        url: String?,
        source: String,
        activate: Boolean,
    ): Boolean = mutex.withLock {
        val id = prefs.nextConfigId
        prefs.nextConfigId = id + 1
        val list = loadIndexLocked()
        if (activate) {
            list.forEachIndexed { i, m -> if (m.id == prefs.activeConfigId) list[i] = m }
        }
        list.add(ConfigMeta(id, name, url, config.sites.size, source, System.currentTimeMillis()))
        prefs.setSourceUrl(id, url)
        prefs.setSourceKind(id, source)
        persistIndexLocked(list)

        val cacheFile = File(FileUtil.configDir(context), "$id.json")
        FileUtil.writeText(cacheFile, encodeConfig(config))
        if (activate) {
            prefs.activeConfigId = id
            applyConfig(config)
        }
        true
    }

    private fun applyConfig(config: TvBoxConfig) {
        _homeSiteVersion.value++
        _activeConfig.value = config
        _sites.value = config.sites
        onConfigApplied(config)

        // P0-3：配置生效后立刻后台预热 jar（下载 + dexopt 移出首个 homeContent 的超时预算）。
        if (!config.spider.isNullOrBlank()) {
            AppScope.io.launch {
                runCatching { ensureSpiderDir() }
                runCatching { onSpiderWarmUp() }
            }
        }
    }

    // ---------------- 首页绑定源 ----------------

    /**
     * 当前首页绑定的站点：用户显式选过的优先，否则按 jar 可用性排序取最优。
     */
    suspend fun resolveHomeSite(): SiteConfig? {
        val usable = _sites.value.filter { it.isSupported }
        if (usable.isEmpty()) return null
        val saved = prefs.homeSiteKey
        usable.firstOrNull { it.key == saved }?.let { return it }
        val hasCommonJar = hasValidCommonJar()
        return usable.minByOrNull { siteHomeRank(it, hasCommonJar) } ?: usable.first()
    }

    /** 首页源候选排序权重：0 自带私有 jar；1 公共 jar 已就绪；2 都没有。 */
    private fun siteHomeRank(site: SiteConfig, hasCommonJar: Boolean): Int {
        site.jar?.takeIf { it.isNotBlank() }?.let { return 0 }
        return if (hasCommonJar) 1 else 2
    }

    /** 用户显式指定首页源（配置页「设为首页」用）。 */
    suspend fun setHomeSite(siteKey: String) {
        val site = _sites.value.firstOrNull { it.key == siteKey } ?: return
        if (!site.isSupported) return
        prefs.homeSiteKey = siteKey
        SourceHealth.clearCooldown(siteKey)
        _homeSiteVersion.value++
    }

    fun notifySourceFilterChanged() {
        _homeSiteVersion.value++
    }

    fun currentHomeSiteKey(): String = prefs.homeSiteKey.orEmpty()

    /** 公共 spider jar 是否已缓存且魔数合法（站点选择器打标用） */
    fun isCommonJarReady(): Boolean = hasValidCommonJar()

    /**
     * 配置声明的 spider jar 是否已落盘。
     */
    fun hasCachedSpider(spiderField: String): Boolean {
        val artifacts = SpiderSpec.parse(spiderField)
        if (artifacts.isEmpty()) return false

        if (artifacts.any { LocalSourceResolver.isLocalPath(it.url) }) {
            val localDir = localJarDir(spiderField)
            if (localDir.listFiles()?.any { isValidJarFile(it) } == true) return true
        }

        val jars = FileUtil.spiderDir(context)
            .listFiles { f -> f.isFile && f.name.endsWith(".jar", true) && f.length() > 0 }
            ?.filter { looksLikeJarFile(it) }
            .orEmpty()
        if (jars.isEmpty()) return false

        val wanted = artifacts.mapNotNull { it.fixedMd5?.lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        if (wanted.isEmpty()) return true
        return jars.any { f ->
            val stem = f.name.lowercase().removeSuffix(".jar")
            wanted.any { m -> stem == m || stem.startsWith("$m.") || stem.startsWith("${m}_") }
        }
    }

    /** 当前配置的 spider jar 是否已就绪（配置页轮询下载状态用） */
    fun isActiveSpiderReady(): Boolean {
        val config = _activeConfig.value ?: return false
        return config.spider.isNullOrBlank() || hasCachedSpider(config.spider)
    }

    /** 站点私有 jar 是否已落盘（选择器打标用，不触发下载）。 */
    fun isSiteJarCached(site: SiteConfig): Boolean {
        val spec = site.jar?.takeIf { it.isNotBlank() } ?: return false
        val artifacts = SpiderSpec.parse(spec)
        if (artifacts.isEmpty()) return false
        val dir = File(FileUtil.spiderDir(context), "site")
        return artifacts.any { a ->
            a.fixedMd5?.let { m ->
                dir.listFiles()?.any {
                    it.name == "$m.jar" && it.length() > 0 && looksLikeJarFile(it)
                }
            } ?: false
        }
    }

    // ---------------- 切换 / 删除 / 重命名 ----------------

    /** 切换配置：级联失效 */
    suspend fun switchConfig(id: Long) = mutex.withLock {
        prefs.activeConfigId = id
        onConfigSwitched(id)
    }

    private suspend fun onConfigSwitched(id: Long) {
        SourceHealth.clear()
        onSwitchHook(id)
        val entity = metaById(id)
        val cached = entity?.let { loadFromCache(it, allowStale = it.url.isNullOrBlank()) }
        if (cached != null) {
            applyConfig(cached)
        } else {
            val url = entity?.url
            if (!url.isNullOrBlank()) {
                val refreshed = when (val fetched = fetchConfig(url)) {
                    is FetchOutcome.Ok -> ConfigParser.parse(
                        LocalSourceResolver.fixContentPath(
                            FindResult.decode(fetched.text, fetched.configKey),
                            fetched.dirPrefix,
                        ),
                    ).config

                    is FetchOutcome.Err -> {
                        TbLog.w("切换配置后刷新失败：%s", fetched.message)
                        null
                    }
                }
                refreshed?.let { applyConfig(it) }
            }
        }
    }

    suspend fun deleteConfig(id: Long) = mutex.withLock {
        val entity = metaById(id) ?: return@withLock
        FileUtil.deleteQuietly(File(FileUtil.configDir(context), "$id.json"))
        val list = loadIndexLocked().filterNot { it.id == id }
        prefs.setSourceUrl(id, null)
        prefs.setSourceKind(id, null)
        if (entity.id == prefs.activeConfigId) {
            val next = list.maxByOrNull { it.addedAt }
            if (next != null) {
                persistIndexLocked(list)
                prefs.activeConfigId = next.id
                SourceHealth.clear()
                onSwitchHook(next.id)
                loadFromCache(next, allowStale = next.url.isNullOrBlank())?.let { applyConfig(it) } ?: run {
                    val url = next.url
                    val refreshed = if (!url.isNullOrBlank()) {
                        when (val fetched = fetchConfig(url)) {
                            is FetchOutcome.Ok -> ConfigParser.parse(
                                LocalSourceResolver.fixContentPath(
                                    FindResult.decode(fetched.text, fetched.configKey),
                                    fetched.dirPrefix,
                                ),
                            ).config

                            is FetchOutcome.Err -> null
                        }
                    } else null
                    applyConfig(refreshed ?: TvBoxConfig())
                }
            } else {
                persistIndexLocked(list)
                SourceHealth.clear()
                onSwitchHook(-1L)
                _homeSiteVersion.value++
                _activeConfig.value = null
                _sites.value = emptyList()
                prefs.activeConfigId = -1L
            }
        } else {
            persistIndexLocked(list)
        }
    }

    suspend fun renameConfig(id: Long, newName: String) {
        val entity = metaById(id) ?: return
        mutex.withLock {
            val list = loadIndexLocked()
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                list[idx] = entity.copy(name = newName.trim().ifEmpty { entity.name })
                persistIndexLocked(list)
            }
        }
    }

    // ---------------- 缓存读写 ----------------

    private fun loadFromCache(entity: ConfigMeta, allowStale: Boolean = false): TvBoxConfig? {
        val file = File(FileUtil.configDir(context), "${entity.id}.json")
        val text = FileUtil.readText(file) ?: return null
        val version = TvboxConfigCacheCodec.VERSION_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (version != TvboxConfigCacheCodec.VERSION && !allowStale) {
            TbLog.i(
                "配置缓存版本过期（缓存=%s，当前=%d），将按原地址重新拉取：%s",
                version?.toString() ?: "无标记", TvboxConfigCacheCodec.VERSION, entity.name,
            )
            return null
        }
        return ConfigParser.parse(text).config
    }

    /** 序列化配置回缓存：见 [TvboxConfigCacheCodec.encode]（字段集由 round-trip 单测锁定）。 */
    private fun encodeConfig(config: TvBoxConfig): String = TvboxConfigCacheCodec.encode(config)

    // ---------------- spider jar 预下载 ----------------

    private fun launchSpiderPrefetch(config: TvBoxConfig, sourceUrl: String? = null) {
        prefetchActive.incrementAndGet()
        _spiderPrefetching.value = true
        AppScope.io.launch {
            try {
                runCatching { prefetchSpider(config, sourceUrl) }
                    .onFailure { TbLog.w("spider jar 后台预下载异常：%s", it.message) }
            } finally {
                if (prefetchActive.decrementAndGet() <= 0) _spiderPrefetching.value = false
            }
        }
    }

    private suspend fun prefetchSpider(config: TvBoxConfig, sourceUrl: String?): Boolean {
        val artifacts = SpiderSpec.parse(config.spider)
        if (artifacts.isEmpty()) return false
        if (artifacts.any { LocalSourceResolver.isLocalPath(it.url) }) {
            if (materializeLocalJar(artifacts, localJarDir(config.spider)) != null) return true
        }
        val ok = downloadSpider(artifacts, baseUrl = sourceUrl) != null
        if (!ok) TbLog.w("spider jar 预下载失败，将在 obtain 时重试")
        return ok
    }

    /** 本地 spider jar 的落点目录：按 spider 字段 md5 分子目录，避免跨配置误挑。 */
    private fun localJarDir(spiderField: String?): File =
        File(FileUtil.spiderDir(context), "local/${Md5Util.md5(spiderField.orEmpty())}")

    private fun localTreeUri(): android.net.Uri? = runCatching {
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { prefs.localTreeUriOrNull() }
    }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }

    /** 保存 SAF 目录树授权（授权后本地配置无需全盘存储权限即可读取） */
    suspend fun setLocalTreeUri(uri: String?) {
        prefs.localTreeUri = uri
        _activeConfig.value?.let {
            if (!it.spider.isNullOrBlank()) {
                prefetchSpider(it, activeMeta()?.url)
            }
        }
    }

    suspend fun localTreeUriValue(): String = prefs.localTreeUriOrNull().orEmpty()

    /**
     * 把本地文件形态的 spider jar 拷进私有目录（魔数 + md5 校验同下载路径）。
     * @return 落盘后的 jar 文件
     */
    private fun materializeLocalJar(artifacts: List<RemoteArtifact>, dir: File): File? {
        for (a in artifacts) {
            if (!LocalSourceResolver.isLocalPath(a.url)) continue
            val src = LocalSourceResolver.parse(a.url)
            if (src is LocalSource.RemoteClan) continue

            val target = File(dir, "${Md5Util.md5(a.url)}.jar")
            // 缓存新鲜度必须看源文件：长度 + 修改时间判定，SAF/content 形态退化读完比摘要。
            val srcFile = (src as? LocalSource.LocalFile)?.file
            if (srcFile != null && srcFile.canRead() && isValidJarFile(target) &&
                target.length() == srcFile.length() &&
                target.lastModified() >= srcFile.lastModified()
            ) {
                TbLog.i("本地 spider jar 已缓存：%s", target.name)
                return target
            }

            when (val r = LocalSourceResolver.read(context, src ?: continue, localTreeUri())) {
                is LocalRead.Ok -> {
                    val bytes = r.bytes
                    if (!looksLikeJar(bytes)) {
                        TbLog.e(
                            "本地 spider 不是 jar/dex（%d 字节）：%s —— 请确认选的是 .jar 文件",
                            bytes.size, a.url,
                        )
                        continue
                    }
                    if (!Md5Util.verifyAgainst(bytes, a.fixedMd5)) {
                        TbLog.e("本地 spider 摘要校验失败：%s", a.url)
                        continue
                    }
                    if (isValidJarFile(target) && Md5Util.md5(target) == Md5Util.md5(bytes)) {
                        TbLog.i("本地 spider jar 已缓存：%s", target.name)
                        return target
                    }
                    if (FileUtil.writeBytes(target, bytes)) {
                        TbLog.i("重新拷贝本地 spider jar：%s（%d 字节）", target.name, bytes.size)
                        return target
                    }
                }

                is LocalRead.Fail -> {
                    TbLog.w("本地 spider jar 读取失败（%s）：%s", r.kind, r.message)
                    if (isValidJarFile(target)) {
                        TbLog.i("本地 spider jar 沿用缓存：%s", target.name)
                        return target
                    }
                }
            }
        }
        return null
    }

    /**
     * 确保 spider jar 就绪，返回缓存目录（供 JarEngine 建 DexClassLoader）。
     */
    suspend fun ensureSpiderDir(): File? {
        val config = _activeConfig.value ?: return null
        val artifacts = SpiderSpec.parse(config.spider)
        if (artifacts.isEmpty()) {
            TbLog.d("配置无 spider 字段，csp_* 源不可用")
            return null
        }

        if (artifacts.any { LocalSourceResolver.isLocalPath(it.url) }) {
            val localDir = localJarDir(config.spider)
            if (materializeLocalJar(artifacts, localDir) != null) return localDir
            TbLog.w("本地 spider jar 不可用，回退到网络候选")
        }

        val dir = FileUtil.spiderDir(context)
        if (!hasCachedSpider(config.spider.orEmpty())) {
            TbLog.i("spider jar 未命中配置声明 md5，按需下载（%d 个候选）", artifacts.size)
            downloadSpider(artifacts, baseUrl = activeMeta()?.url)
        }
        return dir
    }

    /** 配置 spider 字段声明的固定 md5 集合（小写）。 */
    fun spiderMd5s(): Set<String> {
        val config = _activeConfig.value ?: return emptySet()
        return SpiderSpec.parse(config.spider)
            .mapNotNull { it.fixedMd5?.lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * 确保站点私有 jar（site.jar 字段）就绪，返回本次生效的 jar 文件。
     */
    suspend fun ensureSiteJar(site: SiteConfig): File? {
        val spec = site.jar?.takeIf { it.isNotBlank() } ?: return null
        val artifacts = SpiderSpec.parse(spec)
        if (artifacts.isEmpty()) return null
        // 相对地址使用点兜底：按活跃配置来源 URL 解析（方案核对的 resolveAgainst 两处调用点之一）。
        val sourceUrl = activeMeta()?.url
        val prepared = if (sourceUrl.isNullOrBlank()) artifacts else artifacts.map { a ->
            a.copy(url = LocalSourceResolver.resolveAgainst(a.url, sourceUrl))
        }

        val dir = File(FileUtil.spiderDir(context), "site").apply { if (!exists()) mkdirs() }
        prepared.forEach { a ->
            val hit = a.fixedMd5?.let { m ->
                dir.listFiles()?.firstOrNull { it.name == "$m.jar" && it.length() > 0 && isValidJarFile(it) }
            }
            if (hit != null) return hit
        }

        if (prepared.any { LocalSourceResolver.isLocalPath(it.url) }) {
            val localDir = File(dir, "local").apply { if (!exists()) mkdirs() }
            materializeLocalJar(prepared, localDir)?.let { return it }
        }

        return downloadSpider(prepared, dir, baseUrl = sourceUrl)
    }

    /** 是否是一个"能交给 DexClassLoader"的 jar 文件（后缀 + 大小 + 魔数三重判定） */
    private fun isValidJarFile(f: File): Boolean =
        f.isFile && f.name.endsWith(".jar", true) && f.length() > 0 && looksLikeJarFile(f)

    /**
     * spider jar 下载。多候选依次兜底；并发由 [jarDownloadMutex] 去重。
     * @return 成功落盘的 jar 文件；全部候选失败返回 null。
     */
    suspend fun downloadSpider(
        artifacts: List<RemoteArtifact>,
        dirOverride: File? = null,
        baseUrl: String? = null,
    ): File? {
        if (artifacts.isEmpty()) return null
        return jarDownloadMutex.withLock {
            downloadSpiderLocked(artifacts, dirOverride, baseUrl)
        }
    }

    private suspend fun downloadSpiderLocked(
        artifacts: List<RemoteArtifact>,
        dirOverride: File? = null,
        baseUrl: String? = null,
    ): File? {
        val dir = dirOverride ?: FileUtil.spiderDir(context)
        // 相对地址使用点兜底（方案核对的 resolveAgainst 两处调用点之二）。
        val prepared = if (baseUrl.isNullOrBlank()) artifacts else artifacts.map { a ->
            a.copy(url = LocalSourceResolver.resolveAgainst(a.url, baseUrl))
        }
        for (a in prepared) {
            val source = LocalSourceResolver.parse(a.url)
            val downloadUrl = when (source) {
                is LocalSource.RemoteClan -> source.httpUrl
                null -> a.url
                else -> continue
            }

            a.fixedMd5?.let { expected ->
                val hit = dir.listFiles()?.firstOrNull {
                    it.name == "$expected.jar" && it.length() > 0
                }
                if (hit != null) {
                    if (looksLikeJarFile(hit)) {
                        TbLog.i("spider jar 缓存命中：%s", hit.name)
                        return hit
                    }
                    TbLog.w("缓存内容不是合法 jar，已删除重下：%s", hit.name)
                    FileUtil.deleteQuietly(hit)
                }
            }

            val remoteMd5Url = a.md5
            val expectedMd5 = if (a.md5IsRemote && remoteMd5Url != null) {
                fetchRemoteMd5(remoteMd5Url).also {
                    if (it == null) TbLog.w("远程 md5 取回失败：%s", remoteMd5Url)
                }
            } else a.fixedMd5

            val bytes = fetchArtifactBytes(downloadUrl) ?: continue

            // 摘要不匹配不丢弃：生态 jar 热更新，配置里的 md5 常滞后于服务端。
            if (!Md5Util.verifyAgainst(bytes, expectedMd5)) {
                TbLog.w(
                    "spider 摘要校验失败，仍按声明名落盘（jar 可能已热更新）：%s 期望 %s",
                    downloadUrl, expectedMd5,
                )
            }

            val name = expectedMd5 ?: Md5Util.md5(bytes)
            val target = File(dir, "$name.jar")
            if (FileUtil.writeBytes(target, bytes)) {
                TbLog.i("spider jar 已缓存：%s（%d 字节）", target.name, bytes.size)
                return target
            } else {
                TbLog.e("spider jar 落盘失败：%s", target.absolutePath)
            }
        }
        return null
    }

    /** 远程 md5 取回（带 UA 门控兜底）。 */
    private suspend fun fetchRemoteMd5(url: String): String? {
        val resp = net.getText(url, timeoutMs = 10_000)
        if (!HtmlGuard.looksLikeHtml(resp.body)) {
            return resp.body.takeIf { resp.isSuccess && it.isNotBlank() }?.trim()
        }
        TbLog.tag(TAG).w("远程 md5 返回 HTML 页面，以 okhttp UA 重试：%s", url)
        val retry = net.getText(url, timeoutMs = 10_000, ua = UaUtil.GATE_UA)
        return retry.body
            .takeIf { retry.isSuccess && !HtmlGuard.looksLikeHtml(it) && it.isNotBlank() }
            ?.trim()
    }

    /** 取 spider 产物字节：img+ 解包 + UA 门控兜底重试。 */
    private suspend fun fetchArtifactBytes(downloadUrl: String): ByteArray? {
        val resp = fetchArtifactRaw(downloadUrl, ua = null) ?: return null
        val bytes = resp.bytes?.takeIf { it.isNotEmpty() } ?: run {
            TbLog.w("spider 下载失败：%s (%s)", downloadUrl, resp.error)
            return null
        }
        if (looksLikeJar(bytes)) return bytes

        if (HtmlGuard.looksLikeHtml(bytes)) {
            TbLog.tag(TAG).w(
                "spider 下载到 HTML 落地页（%d 字节），以 okhttp UA 重试：%s",
                bytes.size, downloadUrl,
            )
            val retryBytes = fetchArtifactRaw(downloadUrl, ua = UaUtil.GATE_UA)?.bytes
            if (retryBytes != null && looksLikeJar(retryBytes)) {
                TbLog.tag(TAG).i("UA 门控兜底成功，拿到合法 jar：%d 字节", retryBytes.size)
                return retryBytes
            }
        }

        TbLog.tag(TAG).e(
            "spider 下载内容不是 jar/dex（%d 字节，疑似 HTML 落地页），已丢弃：%s",
            bytes.size, downloadUrl,
        )
        return null
    }

    /** 单次取字节：处理 img+ 图片藏 jar 前缀。 */
    private suspend fun fetchArtifactRaw(
        downloadUrl: String,
        ua: String?,
    ): NetApi.BytesResponse? {
        if (!FindResult.isImgJar(downloadUrl)) return net.getBytes(downloadUrl, ua = ua)
        val real = FindResult.stripImgPrefix(downloadUrl)
        val raw = net.getBytes(real, ua = ua)
        val inner = FindResult.decodeImgJar(raw.bytes)
        if (inner == null) {
            TbLog.w("img+ 图片藏 jar 解析失败：%s", real)
            return null
        }
        TbLog.i("img+ 形态已解出 jar：%d 字节", inner.size)
        return NetApi.BytesResponse(raw.code, inner, raw.finalUrl, "image/*")
    }

    /** 启动时清理非法的 jar 缓存（按魔数判定）。 */
    fun purgeInvalidJars(): List<String> {
        val removed = mutableListOf<String>()
        val localRoot = File(FileUtil.spiderDir(context), "local")
        val localDirs = localRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        val roots = listOf(
            FileUtil.spiderDir(context),
            File(FileUtil.spiderDir(context), "site"),
        ) + localDirs
        for (root in roots) {
            val files = root.listFiles { f -> f.isFile && f.name.endsWith(".jar", true) }
                ?: continue
            for (f in files) {
                val ok = looksLikeJarFile(f)
                if (!ok) {
                    TbLog.w("清理非法 jar 缓存：%s（%d 字节）", f.name, f.length())
                    FileUtil.deleteQuietly(f)
                    if (!f.exists()) removed += f.name
                }
            }
        }
        return removed
    }

    /** 判断下载内容是否是合法的 jar/dex（zip 魔数或裸 dex 魔数，含可选 BOM）。 */
    private fun looksLikeJar(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        var off = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) off = 3
        if (off + 4 > bytes.size) return false

        if (bytes[off] == 0x50.toByte() && bytes[off + 1] == 0x4B.toByte() &&
            bytes[off + 2] == 0x03.toByte() && bytes[off + 3] == 0x04.toByte()
        ) return true

        if (bytes[off] == 0x64.toByte() && bytes[off + 1] == 0x65.toByte() &&
            bytes[off + 2] == 0x78.toByte() && bytes[off + 3] == 0x0A.toByte()
        ) return true

        return false
    }

    /** 从文件头读 n 字节做魔数判定（不整包读入内存）。 */
    private fun looksLikeJarFile(f: File): Boolean {
        if (!f.isFile || f.length() < 4) return false
        val head = runCatching {
            f.inputStream().use { input ->
                val buf = ByteArray(7)
                val n = input.read(buf, 0, 7)
                if (n > 0) buf.copyOf(n) else null
            }
        }.getOrNull() ?: return false
        return looksLikeJar(head)
    }

    /** 公共 spider jar 是否可用（两处落点都要看：顶层与 local/<md5>/ 子目录）。 */
    private fun hasValidCommonJar(): Boolean {
        val root = FileUtil.spiderDir(context)
        val topLevel = root.listFiles { f -> f.isFile && f.name.endsWith(".jar", true) }
            ?.any { looksLikeJarFile(it) } == true
        if (topLevel) return true
        return File(root, "local").listFiles { f -> f.isDirectory }
            ?.any { d ->
                d.listFiles { f -> f.isFile && f.name.endsWith(".jar", true) }
                    ?.any { looksLikeJarFile(it) } == true
            } == true
    }

    /** 从地址猜一个配置名：本地取文件名，局域网取 host，http 保留中文域名原样。 */
    private fun guessName(url: String): String {
        when (val src = LocalSourceResolver.parse(url)) {
            is LocalSource.LocalFile -> return src.file.nameWithoutExtension
                .takeIf { it.isNotBlank() } ?: "本地配置"
            is LocalSource.RemoteClan -> return runCatching { java.net.URI(src.httpUrl).host }
                .getOrNull() ?: "局域网配置"
            is LocalSource.Content -> return "本地配置"
            null -> Unit
        }
        val host = url.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore('?')
            .substringAfterLast('@')
            .substringBefore(':')
            .removePrefix("www.")
            .takeIf { it.isNotBlank() }
        return host ?: runCatching {
            java.net.URI(url).host?.removePrefix("www.") ?: "配置"
        }.getOrDefault("配置")
    }

    companion object {
        private const val TAG = "ConfigRepo"
    }
}


/**
 * 配置缓存的序列化器（纯函数，供 JVM round-trip 单测锁字段集）。
 *
 * 缓存正文带 `_cacheVersion` 标记：旧缓存版本不符即视为过期，强制按原地址重取。
 * 给缓存正文增删字段必须 [VERSION] +1 —— 这是"缓存静默丢字段"坑（方案 §10 已知）的唯一防线。
 */
object TvboxConfigCacheCodec {
    /** 缓存格式版本号：增删缓存字段必须 +1。 */
    const val VERSION = 3

    /** 从缓存正文头部提取版本号（写在 encode 最前，正则一次命中） */
    val VERSION_REGEX = Regex("\"_cacheVersion\"\\s*:\\s*(\\d+)")

    fun encode(config: TvBoxConfig): String = buildString {
        append("{\"_cacheVersion\":").append(VERSION).append(',')
        config.spider?.let { append("\"spider\":").append(quote(it)).append(',') }
        config.wallpaper?.let { append("\"wallpaper\":").append(quote(it)).append(',') }
        append("\"sites\":[")
        config.sites.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append(siteToJson(s))
        }
        append("]")
        if (config.flags.isNotEmpty()) {
            append(",\"flags\":[")
            config.flags.forEachIndexed { i, f ->
                if (i > 0) append(',')
                append(quote(f))
            }
            append(']')
        }
        if (config.rules.isNotEmpty()) {
            append(",\"rules\":[")
            config.rules.forEachIndexed { i, r ->
                if (i > 0) append(',')
                append("{\"name\":").append(quote(r.name))
                    .append(",\"hosts\":").append(quote(r.hosts))
                    .append(",\"rule\":[")
                r.rule.forEachIndexed { j, rule ->
                    if (j > 0) append(',')
                    append(quote(rule))
                }
                append(']')
                if (r.ads.isNotEmpty()) {
                    append(",\"ads\":[")
                    r.ads.forEachIndexed { j, ad ->
                        if (j > 0) append(',')
                        append(quote(ad))
                    }
                    append(']')
                }
                append('}')
            }
            append(']')
        }
        if (config.ads.isNotEmpty()) {
            append(",\"ads\":[")
            config.ads.forEachIndexed { i, ad ->
                if (i > 0) append(',')
                append(quote(ad))
            }
            append(']')
        }
        if (config.proxy.isNotEmpty()) {
            append(",\"proxy\":[")
            config.proxy.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append("{\"name\":").append(quote(p.name)).append(",\"hosts\":[")
                p.hosts.forEachIndexed { j, h ->
                    if (j > 0) append(',')
                    append(quote(h))
                }
                append("],\"urls\":[")
                p.urls.forEachIndexed { j, u ->
                    if (j > 0) append(',')
                    append(quote(u))
                }
                append("]}")
            }
            append(']')
        }
        if (config.parses.isNotEmpty()) {
            append(",\"parses\":[")
            config.parses.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append("{\"name\":").append(quote(p.name))
                    .append(",\"type\":").append(p.type)
                    .append(",\"url\":").append(quote(p.url))
                    .append('}')
            }
            append(']')
        }
        if (config.lives.isNotEmpty()) {
            append(",\"lives\":[")
            config.lives.forEachIndexed { i, l ->
                if (i > 0) append(',')
                append("{\"name\":").append(quote(l.name))
                    .append(",\"type\":").append(l.type)
                    .append(",\"api\":").append(quote(l.api))
                    .append(",\"url\":").append(quote(l.api))
                l.jar?.let { append(",\"jar\":").append(quote(it)) }
                l.ext?.let { append(",\"ext\":").append(quote(it)) }
                l.userAgent?.let { append(",\"userAgent\":").append(quote(it)) }
                l.epg?.let { append(",\"epg\":").append(quote(it)) }
                l.logo?.let { append(",\"logo\":").append(quote(it)) }
                l.playerType?.let { append(",\"playerType\":").append(it) }
                l.timeout?.let { append(",\"timeout\":").append(it) }
                l.group?.let { append(",\"group\":").append(quote(it)) }
                l.catchup?.let { c ->
                    append(",\"catchup\":{\"type\":").append(quote(c.type))
                        .append(",\"source\":").append(quote(c.source))
                        .append(",\"replace\":").append(quote(c.replace))
                        .append(",\"regex\":").append(quote(c.regex))
                        .append('}')
                }
                append('}')
            }
            append(']')
        }
        append('}')
    }

    private fun siteToJson(s: SiteConfig): String = buildString {
        append("{\"key\":").append(quote(s.key))
        append(",\"name\":").append(quote(s.name))
        append(",\"type\":").append(s.type)
        append(",\"api\":").append(quote(s.api))
        append(",\"searchable\":").append(s.searchable)
        append(",\"quickSearch\":").append(s.quickSearch)
        append(",\"filterable\":").append(s.filterable)
        s.ext?.let { append(",\"ext\":").append(quote(it)) }
        s.jar?.let { append(",\"jar\":").append(quote(it)) }
        s.userAgent?.let { append(",\"userAgent\":").append(quote(it)) }
        s.playUrl?.let { append(",\"playUrl\":").append(quote(it)) }
        s.timeout?.let { append(",\"timeout\":").append(it) }
        s.header?.let { h ->
            append(",\"header\":{")
            h.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append(quote(k)).append(':').append(quote(v.toString().trim('"')))
            }
            append('}')
        }
        if (s.categories.isNotEmpty()) {
            append(",\"categories\":[")
            s.categories.forEachIndexed { i, c ->
                if (i > 0) append(',')
                append(quote(c))
            }
            append(']')
        }
        append('}')
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        s.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

}
