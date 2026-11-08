import io

P = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\app\src\main\java\tv\own\owntv\mobile\tvbox\repository\TvboxConfigRepository.kt'
s = io.open(P, encoding='utf-8').read()

# 1. replace the in-class encodeConfig body (from its kdoc down to the end of quote())
start = s.find('    /** 序列化配置回缓存（保留未知字段由模型覆盖范围决定，此处只存核心字段） */')
end_marker = '    // ---------------- spider jar 预下载 ----------------'
end = s.find(end_marker)
assert start > 0 and end > start, (start, end)
moved = s[start:end]

s = s[:start] + (
    '    /** 序列化配置回缓存：见 [TvboxConfigCacheCodec.encode]（字段集由 round-trip 单测锁定）。 */\n'
    '    private fun encodeConfig(config: TvBoxConfig): String = TvboxConfigCacheCodec.encode(config)\n\n'
) + s[end:]

# 2. loadFromCache consts → codec consts
old_cache = '''        val version = CACHE_VERSION_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (version != CACHE_VERSION && !allowStale) {
            TbLog.i(
                "配置缓存版本过期（缓存=%s，当前=%d），将按原地址重新拉取：%s",
                version?.toString() ?: "无标记", CACHE_VERSION, entity.name,
            )
            return null
        }'''
new_cache = '''        val version = TvboxConfigCacheCodec.VERSION_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (version != TvboxConfigCacheCodec.VERSION && !allowStale) {
            TbLog.i(
                "配置缓存版本过期（缓存=%s，当前=%d），将按原地址重新拉取：%s",
                version?.toString() ?: "无标记", TvboxConfigCacheCodec.VERSION, entity.name,
            )
            return null
        }'''
assert old_cache in s, 'cache block'
s = s.replace(old_cache, new_cache)

# 3. companion: drop the moved consts
old_comp = '''    companion object {
        private const val TAG = "ConfigRepo"

        /** 缓存格式版本号：增删缓存字段必须 +1。 */
        private const val CACHE_VERSION = 3

        private val CACHE_VERSION_REGEX = Regex("_cacheVersion")'''
if old_comp not in s:
    # regex literal differs; find generically
    import re
    m = re.search(r'    companion object \{\n        private const val TAG = "ConfigRepo"\n\n.*?\n    \}\n\}\n$', s, re.S)
    assert m, 'companion not found'
    comp = m.group(0)
    new_comp = '    companion object {\n        private const val TAG = "ConfigRepo"\n    }\n}\n'
    s = s.replace(comp, new_comp)
else:
    s = s.replace(old_comp, '    companion object {\n        private const val TAG = "ConfigRepo"\n')

# 4. append the codec object with the moved functions
codec = '''

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
    val VERSION_REGEX = Regex("\"_cacheVersion\"\\\\s*:\\\\s*(\\\\d+)")

''' + moved.replace(
    '    /** 序列化配置回缓存（保留未知字段由模型覆盖范围决定，此处只存核心字段） */\n    private fun encodeConfig(config: TvBoxConfig): String = buildString {',
    '    fun encode(config: TvBoxConfig): String = buildString {',
) + '}\n'

s = s.rstrip() + '\n' + codec
io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('codec extracted; file lines:', s.count('\n'))
