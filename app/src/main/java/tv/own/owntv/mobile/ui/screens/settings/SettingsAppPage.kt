package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.own.owntv.mobile.ui.stage.StageTag
import tv.own.owntv.mobile.ui.stage.StageTagTone
import tv.own.owntv.mobile.ui.stage.StageTool
import tv.own.owntv.mobile.ui.stage.stageClickable
import tv.own.owntv.mobile.ui.stage.drawInnerRing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.em
import androidx.compose.ui.platform.LocalConfiguration
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageRow
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageText
import tv.own.owntv.mobile.ui.components.MobileIcons
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.mobile.i18n.AppLocales
import tv.own.owntv.mobile.BuildConfig
import tv.own.owntv.mobile.R
import tv.own.owntv.player.PlaybackErrorLog
import java.text.DateFormat
import java.util.Date

/**
 * The version, the licence, where the source lives and how to reach the people who use it.
 *
 * A More page rather than a block inside Settings → App: it is a page of facts, and no part of it
 * is a preference. The content itself is untouched — only its door moved.
 */
@Composable
fun AboutPage(onOpenLanguage: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = LocalStagePalette.current
    val settings: tv.own.owntv.core.settings.SettingsRepository = org.koin.compose.koinInject()
    val updates: tv.own.owntv.core.update.UpdateManager = org.koin.compose.koinInject()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val checkedAt by settings.lastUpdateCheckAt.collectAsStateWithLifecycle(initialValue = null)
    var updateSheet by remember { mutableStateOf(false) }
    val sep = stringResource(R.string.content_epg_bits_separator)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 35.dp, bottom = 24.dp)) {
        // The app icon on its dark tile (radius 24 %), the wordmark, the version with what the last
        // check found, and when that was.
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color(0xFF0B1216))
                    .drawBehind { drawInnerRing(Color.White.copy(alpha = 0.08f), 1.dp.toPx(), 15.dp.toPx()) },
                contentAlignment = Alignment.Center,
            ) {
                tv.own.owntv.mobile.ui.components.BrandMark(tv.own.owntv.mobile.ui.components.rememberAppliedIcon(), 52.dp)
            }
            Column(Modifier.weight(1f)) {
                tv.own.owntv.mobile.ui.components.Wordmark(150.dp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME), style = stageText(13f, 700), color = palette.text)
                    when (updateState) {
                        tv.own.owntv.core.update.UpdateManager.State.UpToDate ->
                            StageTag(stringResource(R.string.more_about_up_to_date).uppercase(LocalConfiguration.current.locales[0]), tone = StageTagTone.ACCENT)
                        is tv.own.owntv.core.update.UpdateManager.State.Available ->
                            StageTag(stringResource(R.string.update_available).uppercase(LocalConfiguration.current.locales[0]), tone = StageTagTone.WARN)
                        else -> Unit
                    }
                }
                Text(
                    listOfNotNull(
                        checkedAt?.let { stringResource(R.string.more_about_checked, loggedWhen(it).replaceFirstChar { c -> c.lowercase(LocalConfiguration.current.locales[0]) }) },
                        stringResource(R.string.more_about_license),
                    ).joinToString(sep),
                    style = stageText(11f, 500),
                    color = palette.muted,
                )
            }
        }
        Text(
            stringResource(R.string.more_about_description_mobile),
            style = stageText(12.5f, 500, lineHeight = 1.5f),
            color = palette.body,
            modifier = Modifier.padding(top = 16.dp),
        )
        // The Telegram card: the QR drawn from the address (never shipped as a picture, so the two cannot
        // drift), the group, the address in the accent, and the whole card opens it.
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(palette.ink.copy(alpha = 0.045f))
                .stageClickable(interaction, { openLink(context, "https://$TELEGRAM_LINK") }, null)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            remember { CompanionLink.renderQr("https://$TELEGRAM_LINK") }?.let { qr ->
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = stringResource(R.string.settings_telegram_qr),
                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(6.dp),
                )
            }
            Column {
                Text(stringResource(R.string.settings_join_telegram), style = stageText(14f, 800), color = palette.text)
                Text(TELEGRAM_LINK, style = stageText(11f, 500), color = stageAccent.accent)
                Text(stringResource(R.string.more_about_scan_mobile), style = stageText(11f, 500), color = palette.muted)
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StageTool(onClick = { openLink(context, "https://$GITHUB_REPO") }, label = GITHUB_NAME, icon = MobileIcons.Translate)
            val languages = AppLocales.rows.size
            StageTool(onClick = onOpenLanguage, label = pluralStringResource(R.plurals.more_about_languages, languages, languages), icon = MobileIcons.Translate)
            StageTool(onClick = { updateSheet = true }, label = stringResource(R.string.settings_check_updates), icon = MobileIcons.Refresh)
        }
    }
    if (updateSheet) UpdateSheet(onDismiss = { updateSheet = false }, checkOnOpen = true)
}

/**
 * More › Error log: what it is, Export · Clear log, then the entries newest first — the kind as a tag
 * (EVENT ink, ERROR danger, REPORT warn), what happened (13.5 / 700), the engine · Live or VOD · stream line
 * and when. A tap selects an entry (`.fx`) and shows the provider's own words and the device. Its own page,
 * not a dialog: this is the screen a user is asked to screenshot when they report a problem.
 */
@Composable
fun SettingsErrorLogPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<PlaybackErrorLog.Entry>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var exportPath by remember { mutableStateOf<String?>(null) }
    var exportFailed by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val palette = LocalStagePalette.current
    LaunchedEffect(reload) {
        entries = withContext(Dispatchers.IO) { PlaybackErrorLog.read(context) }
    }
    val list = entries
    val sep = stringResource(R.string.content_epg_bits_separator)

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp)) {
        item(key = "log-head") {
            Column {
                Text(stringResource(R.string.settings_playback_error_description_full), style = stageText(12f, 500, lineHeight = 1.4f), color = palette.muted)
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Export stays available on an empty list: the live diagnostics ring goes into the
                    // file too, and a handoff can leave useful detail without logging an entry.
                    StageButton(
                        onClick = {
                            scope.launch {
                                val path = withContext(Dispatchers.IO) { PlaybackErrorLog.export(context) }
                                exportPath = path
                                exportFailed = path == null
                            }
                        },
                        text = stringResource(R.string.settings_export),
                        icon = MobileIcons.OpenInNew,
                        small = true,
                    )
                    if (!list.isNullOrEmpty()) {
                        StageButton(
                            onClick = {
                                PlaybackErrorLog.clear(context)
                                exportPath = null
                                exportFailed = false
                                picked = null
                                reload++
                            },
                            text = stringResource(R.string.settings_clear_log),
                            icon = MobileIcons.Delete,
                            style = StageButtonStyle.DANGER,
                            small = true,
                        )
                    }
                }
                exportPath?.let { Text(stringResource(R.string.settings_backup_saved_to, it), style = stageText(11.5f, 600), color = stageAccent.accent, modifier = Modifier.padding(bottom = 8.dp)) }
                if (exportFailed) Text(stringResource(R.string.settings_backup_export_error), style = stageText(11.5f, 600), color = palette.danger, modifier = Modifier.padding(bottom = 8.dp))
                when {
                    list == null -> Text(stringResource(R.string.settings_loading), style = stageText(12.5f, 500), color = palette.muted)
                    list.isEmpty() -> Text(stringResource(R.string.settings_no_playback_errors), style = stageText(12.5f, 500), color = palette.muted)
                }
            }
        }
        if (!list.isNullOrEmpty()) {
            items(list.size, key = { list[it].atMs.toString() + it }) { index ->
                val e = list[index]
                LogEntry(
                    entry = e,
                    line = listOfNotNull(
                        e.engine.engineName(),
                        stringResource(if (e.live) R.string.settings_live else R.string.settings_vod),
                        e.spec,
                    ).joinToString(sep),
                    selected = picked == index,
                    onClick = { picked = if (picked == index) null else index },
                )
            }
        }
    }
}

/** `.srow` aligned to the top: the kind tag, then what happened, its line and when (dim). */
@Composable
private fun LogEntry(entry: PlaybackErrorLog.Entry, line: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val locale = LocalConfiguration.current.locales[0]
    val (kind, tone) = when (entry.kind) {
        PlaybackErrorLog.Kind.ERROR -> stringResource(R.string.settings_playback_kind_error) to palette.danger
        PlaybackErrorLog.Kind.EVENT -> stringResource(R.string.settings_playback_kind_event) to null
        PlaybackErrorLog.Kind.REPORT -> stringResource(R.string.settings_playback_kind_report) to palette.warn
    }
    StageRow(onClick = onClick, current = selected, minHeight = 60.dp, gap = 12.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    kind.uppercase(locale),
                    style = stageText(9.5f, 800, 0.05.em),
                    color = tone ?: palette.tagText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(tone?.copy(alpha = 0.16f) ?: palette.ink.copy(alpha = 0.08f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Column(Modifier.weight(1f)) {
                    val reason = entry.reason?.let { stringResource(it.messageRes) } ?: entry.legacyReason ?: entry.engine.engineName()
                    Text(reason, style = stageText(13.5f, 700), color = palette.text)
                    Text(line, style = stageText(11.5f, 500), color = palette.muted)
                    Text(loggedWhen(entry.atMs), style = stageText(11.5f, 500), color = palette.dim)
                    if (selected) {
                        entry.raw?.let { Text(it, style = stageText(11.5f, 500), color = palette.dim, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
                        Text(stringResource(R.string.settings_device_details, entry.model, entry.android), style = stageText(11.5f, 500), color = palette.dim, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

/** "Synced today 06:10": [loggedWhen] inside a sentence — only Today / Yesterday lose their capital, never a month (#6). */
@Composable
internal fun syncedWhen(atMs: Long): String {
    val text = loggedWhen(atMs)
    val relative = android.text.format.DateUtils.isToday(atMs) ||
        android.text.format.DateUtils.isToday(atMs + android.text.format.DateUtils.DAY_IN_MILLIS)
    return if (relative) text.replaceFirstChar { it.lowercase(LocalConfiguration.current.locales[0]) } else text
}

/** "Today 20:15", "Yesterday 22:41", "28 Sep 18:02" — when the entry was written. */
@Composable
internal fun loggedWhen(atMs: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val time = android.icu.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "jm"), locale).format(Date(atMs))
    return when {
        android.text.format.DateUtils.isToday(atMs) -> stringResource(R.string.content_epg_today) + " " + time
        android.text.format.DateUtils.isToday(atMs + android.text.format.DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.content_epg_yesterday) + " " + time
        else -> android.icu.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMjm"), locale).format(Date(atMs))
    }
}

private const val GITHUB_REPO = "github.com/ahXN00/OwnTV"
private const val GITHUB_NAME = "GitHub"
private const val TELEGRAM_LINK = "t.me/owntvplayer"


private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun PlaybackErrorLog.Kind.labelRes() = when (this) {
    PlaybackErrorLog.Kind.ERROR -> R.string.settings_playback_kind_error
    PlaybackErrorLog.Kind.EVENT -> R.string.settings_playback_kind_event
    PlaybackErrorLog.Kind.REPORT -> R.string.settings_playback_kind_report
}

@Composable
private fun String.engineName(): String = when (trim().lowercase()) {
    "mpv" -> stringResource(R.string.settings_player_mpv)
    "exoplayer", "exo" -> stringResource(R.string.settings_player_exoplayer)
    else -> this
}

