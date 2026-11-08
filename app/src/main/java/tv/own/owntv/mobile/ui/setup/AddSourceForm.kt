package tv.own.owntv.mobile.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.setup.MAG_USER_AGENTS
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.sync.SyncScopeChoice
import tv.own.owntv.mobile.R
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.dp
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.screens.settings.PageNote
import tv.own.owntv.mobile.ui.screens.settings.SettingsChoice
import tv.own.owntv.mobile.ui.screens.settings.StageChoiceSheet
import tv.own.owntv.mobile.ui.stage.StageTextRow
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageSectionHead
import tv.own.owntv.mobile.ui.stage.StageSegmented
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.stageText
import tv.own.owntv.mobile.ui.stage.StageSwitch
import tv.own.owntv.mobile.ui.stage.StageRowValue
import tv.own.owntv.mobile.ui.stage.StageChevron
import androidx.compose.ui.semantics.Role

/** Which kind of provider the form is asking about. */
enum class SourceKind { XTREAM, M3U, STALKER, TVBOX }

/**
 * Everything the form holds, so editing an existing playlist can fill it and take it back whole.
 * Only the edit path uses it; adding still calls the three start callbacks, one per kind.
 */
data class SourceFormValues(
    val kind: SourceKind,
    val name: String = "",
    val urlOrServer: String = "",
    val username: String = "",
    val password: String = "",
    val mac: String = "",
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val userAgent: String = "",
    val httpReferer: String = "",
    val autoRefresh: PlaylistRefresh = PlaylistRefresh(),
    val live: SyncScopeChoice = SyncScopeChoice.Now,
    val movies: SyncScopeChoice = SyncScopeChoice.Now,
    val series: SyncScopeChoice = SyncScopeChoice.Now,
    val preferHls: Boolean = false,
)

/**
 * One playlist's details, as one scrolling page.
 *
 * The television asks the same questions across several popups because a remote control cannot
 * scroll a long form comfortably. A phone is the opposite: a popup would put the keyboard over the
 * field being typed into, so everything — including the optional half — is on this page, and the
 * page scrolls under the keyboard.
 *
 * Passing [initial] and [onSave] turns the same page into the edit form for a playlist that already
 * exists: the kind chips go away, because a playlist cannot change what kind of provider it is, and
 * a blank password means "keep the stored one" rather than "no password".
 */
@Composable
fun AddSourceForm(
    onStartXtream: (
        name: String, server: String, username: String, password: String, userAgent: String,
        httpReferer: String, autoRefresh: PlaylistRefresh, live: SyncScopeChoice, movies: SyncScopeChoice,
        series: SyncScopeChoice, preferHls: Boolean,
    ) -> Unit,
    onStartM3u: (name: String, url: String, userAgent: String, httpReferer: String, autoRefresh: PlaylistRefresh) -> Unit,
    onStartStalker: (
        name: String, portalUrl: String, mac: String, serialNumber: String, deviceId: String,
        deviceId2: String, signature: String, userAgent: String, httpReferer: String, autoRefresh: PlaylistRefresh,
        live: SyncScopeChoice, movies: SyncScopeChoice, series: SyncScopeChoice, login: String, password: String,
    ) -> Unit,
    modifier: Modifier = Modifier,
    initial: SourceFormValues? = null,
    onSave: ((SourceFormValues) -> Unit)? = null,
) {
    val editing = onSave != null
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: SourceKind.XTREAM) }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var server by rememberSaveable {
        mutableStateOf(if (initial?.kind == SourceKind.XTREAM) initial.urlOrServer else "")
    }
    var username by rememberSaveable { mutableStateOf(initial?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(initial?.password.orEmpty()) }
    var m3uUrl by rememberSaveable {
        mutableStateOf(if (initial?.kind == SourceKind.M3U || initial?.kind == SourceKind.TVBOX) initial.urlOrServer else "")
    }
    var portalUrl by rememberSaveable {
        mutableStateOf(if (initial?.kind == SourceKind.STALKER) initial.urlOrServer else "")
    }
    var mac by rememberSaveable { mutableStateOf(initial?.mac.orEmpty()) }
    var serialNumber by rememberSaveable { mutableStateOf(initial?.serialNumber.orEmpty()) }
    var deviceId by rememberSaveable { mutableStateOf(initial?.deviceId.orEmpty()) }
    var deviceId2 by rememberSaveable { mutableStateOf(initial?.deviceId2.orEmpty()) }
    var signature by rememberSaveable { mutableStateOf(initial?.signature.orEmpty()) }
    var userAgent by rememberSaveable { mutableStateOf(initial?.userAgent.orEmpty()) }
    var httpReferer by rememberSaveable { mutableStateOf(initial?.httpReferer.orEmpty()) }
    var preferHls by rememberSaveable { mutableStateOf(initial?.preferHls ?: false) }
    var refreshMode by rememberSaveable { mutableStateOf(initial?.autoRefresh?.mode ?: PlaylistAutoRefresh.OFF) }
    var manualDays by rememberSaveable {
        mutableStateOf((initial?.autoRefresh ?: PlaylistRefresh()).manualDays.toString())
    }
    // Stalker has no bulk catalogue endpoint, so its films and series are fetched later by default.
    var syncLive by rememberSaveable { mutableStateOf(initial?.live ?: SyncScopeChoice.Now) }
    var syncMovies by rememberSaveable { mutableStateOf(initial?.movies ?: SyncScopeChoice.Now) }
    var syncSeries by rememberSaveable { mutableStateOf(initial?.series ?: SyncScopeChoice.Now) }
    var showRefreshSheet by remember { mutableStateOf(false) }
    var showDeviceSheet by remember { mutableStateOf(false) }
    var tvboxFileRejected by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickPlaylist = rememberPlaylistFilePicker { uri ->
        scope.launch {
            copyPickedFile(context, uri, java.io.File(context.filesDir, PLAYLIST_DIR))?.let { file ->
                m3uUrl = file.absolutePath
                if (name.isBlank()) name = file.nameWithoutExtension
            }
        }
    }
    val pickTvboxJson = rememberJsonFilePicker(
        onPicked = { uri ->
            scope.launch {
                copyPickedFile(context, uri, java.io.File(context.filesDir, PLAYLIST_DIR))?.let { file ->
                    m3uUrl = file.absolutePath
                    tvboxFileRejected = false
                    if (name.isBlank()) name = file.nameWithoutExtension
                }
            }
        },
        onRejected = { tvboxFileRejected = true },
    )

    val macValid = StalkerClient.canonicalizeMac(mac) != null
    val autoRefresh = PlaylistRefresh(refreshMode, manualDays.toIntOrNull() ?: PlaylistRefresh().manualDays)
    val hasAnySection = syncLive != SyncScopeChoice.Off ||
        syncMovies != SyncScopeChoice.Off ||
        syncSeries != SyncScopeChoice.Off
    val canStart = when (kind) {
        SourceKind.XTREAM ->
            // On an edit the password field starts empty and left empty keeps the stored one.
            server.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || editing) && hasAnySection
        SourceKind.M3U -> m3uUrl.isNotBlank()
        // TVBox is a single JSON config: both the URL and a picked file have to end in .json for
        // core to pick the `lives` parser instead of the M3U one.
        SourceKind.TVBOX -> isJsonLocation(m3uUrl)
        SourceKind.STALKER -> StalkerClient.isValidPortalUrl(portalUrl) && macValid && hasAnySection
    }
    fun values() = SourceFormValues(
        kind = kind,
        name = name,
        urlOrServer = when (kind) {
            SourceKind.XTREAM -> server
            SourceKind.STALKER -> portalUrl
            else -> m3uUrl
        },
        username = username,
        password = password,
        mac = mac,
        serialNumber = serialNumber,
        deviceId = deviceId,
        deviceId2 = deviceId2,
        signature = signature,
        userAgent = userAgent,
        httpReferer = httpReferer,
        autoRefresh = autoRefresh,
        live = syncLive,
        movies = syncMovies,
        series = syncSeries,
        preferHls = preferHls,
    )

    // The type as a segmented control, the fields as rows (a tap opens the field), OPTIONAL, Start import.
    val palette = LocalStagePalette.current
    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 100.dp),
    ) {
        if (!editing) {
            item(key = "kind") {
                // Stalker portals answer one item at a time, so a full film catalogue on the spot is an hour of
                // requests; the television makes the same choice for the same reason.
                StageSegmented(
                    options = listOf(
                        stringResource(R.string.setup_xtream),
                        stringResource(R.string.setup_m3u),
                        stringResource(R.string.setup_stalker_mac),
                        stringResource(R.string.setup_tvbox),
                    ),
                    selected = kind.ordinal,
                    onSelect = { index ->
                        kind = SourceKind.entries[index]
                        if (kind == SourceKind.STALKER) {
                            syncMovies = SyncScopeChoice.Later
                            syncSeries = SyncScopeChoice.Later
                        }
                    },
                    fill = true,
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 10.dp),
                )
            }
        }
        item(key = "name") {
            StageTextRow(MobileIcons.Edit, stringResource(R.string.setup_source_name_optional), name, { name = it }, placeholder = stringResource(R.string.setup_default_iptv))
        }
        when (kind) {
            SourceKind.XTREAM -> {
                item(key = "server") {
                    StageTextRow(
                        MobileIcons.Translate, stringResource(R.string.setup_server_url), server, { server = it },
                        placeholder = stringResource(R.string.setup_server_example), keyboardType = KeyboardType.Uri,
                    )
                }
                item(key = "user") { StageTextRow(MobileIcons.Person, stringResource(R.string.setup_username), username, { username = it }) }
                item(key = "pass") {
                    StageTextRow(
                        MobileIcons.Lock, stringResource(if (editing) R.string.setup_password_keep else R.string.setup_password),
                        password, { password = it }, password = true,
                    )
                }
                item(key = "hls") {
                    StageSettingRow(
                        icon = MobileIcons.LiveTv,
                        title = stringResource(R.string.setup_prefer_hls_live_tv),
                        subtitle = stringResource(R.string.setup_prefer_hls_description),
                        role = Role.Switch,
                        onClick = { preferHls = !preferHls },
                    ) { StageSwitch(preferHls) }
                }
            }
            SourceKind.M3U -> {
                item(key = "url") {
                    StageTextRow(
                        MobileIcons.Translate, stringResource(R.string.setup_playlist_url_local_file), m3uUrl, { m3uUrl = it },
                        placeholder = stringResource(R.string.setup_playlist_example), keyboardType = KeyboardType.Uri,
                    )
                }
                item(key = "file") {
                    StageSettingRow(
                        title = stringResource(R.string.setup_local_file_choose),
                        icon = MobileIcons.Folder,
                        subtitle = stringResource(R.string.setup_local_file_hint_mobile),
                        subtitleColor = palette.dim,
                        onClick = { pickPlaylist() },
                    ) { StageIcon(MobileIcons.KeyboardArrowRight, 14.dp, palette.muted) }
                }
            }
            SourceKind.TVBOX -> {
                item(key = "tvbox-url") {
                    StageTextRow(
                        MobileIcons.Translate, stringResource(R.string.setup_tvbox_url), m3uUrl,
                        { m3uUrl = it; tvboxFileRejected = false },
                        placeholder = stringResource(R.string.setup_tvbox_url_example), keyboardType = KeyboardType.Uri,
                    )
                }
                item(key = "tvbox-file") {
                    StageSettingRow(
                        title = stringResource(R.string.setup_tvbox_local_file),
                        icon = MobileIcons.Folder,
                        subtitle = stringResource(R.string.setup_tvbox_json_hint),
                        subtitleColor = palette.dim,
                        onClick = { pickTvboxJson() },
                    ) { StageIcon(MobileIcons.KeyboardArrowRight, 14.dp, palette.muted) }
                }
                if ((m3uUrl.isNotBlank() && !isJsonLocation(m3uUrl)) || tvboxFileRejected) {
                    item(key = "tvbox-bad") {
                        Text(stringResource(R.string.setup_tvbox_json_invalid), style = stageText(11.5f, 600), color = palette.danger, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
            SourceKind.STALKER -> {
                item(key = "portal") {
                    StageTextRow(
                        MobileIcons.Translate, stringResource(R.string.setup_portal_url), portalUrl, { portalUrl = it },
                        placeholder = stringResource(R.string.setup_portal_example), keyboardType = KeyboardType.Uri,
                    )
                }
                item(key = "mac") {
                    StageTextRow(MobileIcons.Edit, stringResource(R.string.setup_mac_address), mac, { mac = it }, placeholder = stringResource(R.string.setup_mac_example))
                }
                if (mac.isNotBlank() && !macValid) {
                    item(key = "mac-bad") {
                        Text(stringResource(R.string.setup_mac_invalid), style = stageText(11.5f, 600), color = palette.danger, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
                // Only for portals that ask for a login on top of the MAC; most do not.
                item(key = "portal-user") { StageTextRow(MobileIcons.Person, stringResource(R.string.settings_username_optional), username, { username = it }) }
                item(key = "portal-pass") {
                    StageTextRow(
                        MobileIcons.Lock, stringResource(if (editing) R.string.setup_password_keep else R.string.settings_password_optional),
                        password, { password = it }, password = true,
                    )
                }
                item(key = "identity") { StageSectionHead(stringResource(R.string.setup_stalker_advanced_identity)) }
                item(key = "serial") { StageTextRow(MobileIcons.Edit, stringResource(R.string.setup_stalker_serial_number_optional), serialNumber, { serialNumber = it }) }
                item(key = "dev1") { StageTextRow(MobileIcons.Edit, stringResource(R.string.setup_stalker_device_id_optional), deviceId, { deviceId = it }) }
                item(key = "dev2") { StageTextRow(MobileIcons.Edit, stringResource(R.string.setup_stalker_device_id2_optional), deviceId2, { deviceId2 = it }) }
                item(key = "sig") { StageTextRow(MobileIcons.Edit, stringResource(R.string.setup_stalker_signature_optional), signature, { signature = it }) }
                item(key = "model") {
                    StageSettingRow(
                        icon = MobileIcons.Phone,
                        title = stringResource(R.string.setup_device_model_preset),
                        onClick = { showDeviceSheet = true },
                    ) { StageRowValue(stringResource(MAG_USER_AGENTS.firstOrNull { it.userAgent == userAgent }?.labelRes ?: MAG_USER_AGENTS.first().labelRes), StageChevron.DOWN) }
                }
            }
        }
        // TVBox only needs a name, a .json URL or file, and the import button; per-channel headers
        // in the config already carry the UA/referer, so the optional section would be misleading.
        if (kind != SourceKind.TVBOX) {
            item(key = "optional") { StageSectionHead(stringResource(R.string.settings_form_optional)) }
            item(key = "ua") {
                StageTextRow(MobileIcons.Edit, stringResource(R.string.setup_user_agent_optional), userAgent, { userAgent = it }, placeholder = stringResource(R.string.setup_user_agent_example))
            }
            item(key = "referer") {
                StageTextRow(MobileIcons.Edit, stringResource(R.string.setup_referer_optional), httpReferer, { httpReferer = it }, placeholder = stringResource(R.string.setup_referer_example))
            }
            item(key = "refresh") {
                StageSettingRow(
                    icon = MobileIcons.Schedule,
                    title = stringResource(R.string.setup_auto_refresh),
                    onClick = { showRefreshSheet = true },
                ) { StageRowValue(stringResource(refreshMode.labelRes()), StageChevron.DOWN) }
            }
            if (refreshMode == PlaylistAutoRefresh.MANUAL) {
                item(key = "days") {
                    StageTextRow(
                        MobileIcons.CalendarMonth, stringResource(R.string.settings_sources_refresh_days_title), manualDays,
                        { manualDays = it.filter(Char::isDigit).take(3) },
                        placeholder = stringResource(R.string.settings_sources_refresh_days_hint), keyboardType = KeyboardType.Number,
                    )
                }
            }
        }
        if (kind != SourceKind.M3U && kind != SourceKind.TVBOX) {
            item(key = "sync-head") { StageSectionHead(stringResource(R.string.setup_what_to_sync)) }
            item(key = "sync-note") { PageNote(stringResource(R.string.setup_sync_choices)) }
            item(key = "sync-live") { ScopeRow(MobileIcons.LiveTv, stringResource(R.string.setup_live_tv), stringResource(R.string.setup_channels_categories), syncLive) { syncLive = it } }
            item(key = "sync-movies") { ScopeRow(MobileIcons.Movie, stringResource(R.string.setup_movies), stringResource(R.string.setup_vod_movie_catalog), syncMovies) { syncMovies = it } }
            item(key = "sync-series") { ScopeRow(MobileIcons.Series, stringResource(R.string.setup_series), stringResource(R.string.setup_tv_series_catalog), syncSeries) { syncSeries = it } }
        }
        item(key = "start") {
            StageButton(
                onClick = {
                    onSave?.let { save -> save(values()); return@StageButton }
                    when (kind) {
                        SourceKind.XTREAM -> onStartXtream(
                            name, server, username, password, userAgent, httpReferer, autoRefresh,
                            syncLive, syncMovies, syncSeries, preferHls,
                        )
                        // A TVBox config is a playlist too: it is stored as an M3U source and core
                        // switches to the `lives` parser based on the .json location.
                        SourceKind.M3U, SourceKind.TVBOX -> onStartM3u(name, m3uUrl, userAgent, httpReferer, autoRefresh)
                        SourceKind.STALKER -> onStartStalker(
                            name, portalUrl, mac, serialNumber, deviceId, deviceId2, signature,
                            userAgent, httpReferer, autoRefresh, syncLive, syncMovies, syncSeries, username, password,
                        )
                    }
                },
                text = stringResource(if (editing) R.string.common_save else R.string.setup_start_import),
                icon = if (editing) MobileIcons.Check else MobileIcons.Download,
                style = StageButtonStyle.PRIMARY,
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
            )
        }
    }

    if (showRefreshSheet) {
        StageChoiceSheet(
            eyebrow = null,
            title = stringResource(R.string.setup_auto_refresh_title),
            text = stringResource(R.string.setup_auto_refresh_description),
            choices = PlaylistAutoRefresh.entries.map { SettingsChoice(it, stringResource(it.labelRes())) },
            selected = refreshMode,
            onSelect = { refreshMode = it },
            onDismiss = { showRefreshSheet = false },
        )
    }
    if (showDeviceSheet) {
        StageChoiceSheet(
            eyebrow = null,
            title = stringResource(R.string.setup_device_model_preset_title),
            text = null,
            choices = MAG_USER_AGENTS.map { SettingsChoice(it.userAgent, stringResource(it.labelRes)) },
            selected = userAgent,
            onSelect = { userAgent = it },
            onDismiss = { showDeviceSheet = false },
        )
    }
}

/** Now, Later or not at all — one row per catalogue, cycled by tapping it. */
@Composable
private fun ScopeRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, description: String, value: SyncScopeChoice, onChange: (SyncScopeChoice) -> Unit) {
    val options = listOf(SyncScopeChoice.Now, SyncScopeChoice.Later, SyncScopeChoice.Off)
    StageSettingRow(
        icon = icon,
        title = label,
        subtitle = description,
        onClick = { onChange(options[(options.indexOf(value) + 1) % options.size]) },
    ) { StageRowValue(stringResource(
            when (value) {
                SyncScopeChoice.Now -> R.string.setup_now
                SyncScopeChoice.Later -> R.string.setup_later
                SyncScopeChoice.Off -> R.string.setup_off
            },
        ), StageChevron.DOWN) }
}

internal fun PlaylistAutoRefresh.labelRes(): Int = when (this) {
    PlaylistAutoRefresh.OFF -> R.string.settings_sources_refresh_off
    PlaylistAutoRefresh.STARTUP -> R.string.settings_sources_refresh_startup
    PlaylistAutoRefresh.HOURS_6 -> R.string.settings_sources_refresh_6h
    PlaylistAutoRefresh.HOURS_12 -> R.string.settings_sources_refresh_12h
    PlaylistAutoRefresh.MANUAL -> R.string.settings_sources_refresh_manual
}

/** Where a playlist picked from the phone's storage is kept. */
private const val PLAYLIST_DIR = "playlists"

/** A TVBox config is either an http(s) URL or an absolute local path, both ending in .json. */
internal fun isJsonLocation(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false
    val path = trimmed.substringBefore('#').substringBefore('?')
    return path.endsWith(".json", ignoreCase = true) &&
        (trimmed.contains("://") || trimmed.startsWith("/"))
}
