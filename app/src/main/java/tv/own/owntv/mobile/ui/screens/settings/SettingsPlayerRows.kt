package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.live.DEFAULT_MULTIVIEW_TILES
import tv.own.owntv.core.live.MAX_MULTIVIEW_TILES
import tv.own.owntv.core.live.MIN_MULTIVIEW_TILES
import tv.own.owntv.core.player.SurroundMode
import tv.own.owntv.core.recording.RecordingSchedule
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.timeshift.TimeshiftRules
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.screens.recordings.RecordingsViewModel
import tv.own.owntv.mobile.ui.stage.StagePlaylistMark
import tv.own.owntv.player.ZoomMode

/*
 * The five groups that were the Video player level: Player, Picture, Sound & subtitles, Live TV,
 * Watching & recording. The stored values are core's, under the TV's keys, so a backup means the same thing on
 * both apps and a pin made on one is the same row on the other.
 */

// ----------------------------------------------------------------------------------------------- Player

@Composable
internal fun playerSections(vm: SettingsViewModel): List<SettingSection> {
    val g = SettingsGroup.PLAYER
    val s = vm.settings
    val reconnects = s.vodReconnects.pref(1)
    val externalOn = s.externalPlayerMovies.pref(false) || s.externalPlayerSeries.pref(false)
    return listOf(
        SettingSection(
            stringResource(R.string.settings_vp_cat_player),
            listOf(
                pageItem(
                    "vp_external", MobileIcons.OpenInNew, stringResource(R.string.settings_external_player), stringResource(R.string.settings_line_external),
                    stringResource(if (externalOn) R.string.common_on else R.string.common_off), SettingsLeaf.EXTERNAL_PLAYER.route,
                ),
            ),
        ),
        SettingSection(
            stringResource(R.string.settings_vp_cat_streaming),
            listOf(
                choiceItem(
                    "vp_vod_reconnects", MobileIcons.Refresh, g, stringResource(R.string.settings_vod_reconnects),
                    stringResource(R.string.settings_line_vod_reconnects_mobile), stringResource(R.string.settings_vod_reconnects_description),
                    s.vodReconnectChoices.map { SettingsChoice(it, stringResource(R.string.settings_vod_reconnects_value, it)) },
                    reconnects, 1, { v -> vm.edit { setVodReconnects(v) } },
                ),
            ),
        ),
    )
}

/** "19 saved · Reset": what the player remembered per title or channel; a tap asks before forgetting it. */
@Composable
private fun savedItem(key: String, titleRes: Int, lineRes: Int, count: Int, confirmRes: Int, confirmTextRes: Int, onReset: () -> Unit): SettingItem {
    val confirm = stringResource(confirmRes)
    val text = stringResource(confirmTextRes)
    val reset = stringResource(R.string.common_reset)
    return SettingItem(
        key, MobileIcons.Refresh, stringResource(titleRes), stringResource(lineRes), SettingValue.Saved(count),
        // Nothing saved, nothing to forget: the row only says so.
        sheet = if (count > 0) ({ dismiss -> StageConfirmSheet(confirm, text, reset, onReset, dismiss) }) else null,
    )
}

// ---------------------------------------------------------------------------------------------- Picture

@Composable
internal fun pictureSections(vm: SettingsViewModel): List<SettingSection> {
    val g = SettingsGroup.PICTURE
    val s = vm.settings
    val savedZoom by vm.savedZoomCount.collectAsStateWithLifecycle()
    val zoomName = s.defaultZoom.pref(ZoomMode.FIT.name)
    val zoom = runCatching { ZoomMode.valueOf(zoomName) }.getOrDefault(ZoomMode.FIT)
    return listOf(
        SettingSection(
            stringResource(R.string.settings_vp_cat_picture),
            listOf(
                quickSwitch(vm, "vp_hw", MobileIcons.AutoAwesome, R.string.settings_hardware_decoding, R.string.settings_line_hw_mobile),
                quickSwitch(vm, "vp_hdr", MobileIcons.WbSunny, R.string.settings_quick_prefer_sdr, R.string.settings_line_prefer_sdr),
                choiceItem(
                    "vp_zoom", MobileIcons.OpenInFull, g, stringResource(R.string.settings_default_zoom),
                    stringResource(R.string.settings_line_zoom), stringResource(R.string.settings_help_zoom_mode),
                    ZoomMode.entries.map { SettingsChoice(it, stringResource(it.labelRes)) }, zoom, ZoomMode.FIT,
                    { z -> vm.edit { setDefaultZoom(z.name) } },
                ),
                savedItem(
                    "vp_reset_zoom", R.string.settings_title_saved_zoom, R.string.settings_line_saved_zoom, savedZoom,
                    R.string.settings_reset_saved_zoom_confirm, R.string.settings_reset_saved_zoom_confirm_description,
                ) { vm.clearSavedZoom() },
            ),
        ),
        )
}

// ------------------------------------------------------------------------------------------------ Sound

@Composable
internal fun soundSections(vm: SettingsViewModel): List<SettingSection> {
    val g = SettingsGroup.SOUND
    val s = vm.settings
    val surround = s.surroundMode.pref(SurroundMode.AUTO)
    val subStyle = s.subtitleStyleEnabled.pref(false)
    return listOf(
        SettingSection(
            null,
            listOf(
                choiceItem(
                    "vp_surround", MobileIcons.Headphones, g, stringResource(R.string.settings_surround_sound),
                    stringResource(R.string.settings_line_surround_mobile), stringResource(R.string.settings_surround_description_mobile),
                    SurroundMode.entries.map { SettingsChoice(it, stringResource(it.labelRes()), stringResource(it.descriptionRes())) },
                    surround, SurroundMode.AUTO, { m -> vm.edit { setSurroundMode(m) } },
                ),
                pageItem(
                    "vp_sub_style", MobileIcons.Subtitles, stringResource(R.string.settings_subtitle_appearance),
                    stringResource(R.string.settings_line_sub_style_mobile), stringResource(if (subStyle) R.string.common_on else R.string.common_off),
                    SettingsLeaf.SUBTITLE_APPEARANCE.route,
                ),
            ),
        ),
    )
}

// ---------------------------------------------------------------------------------------------- Live TV

@Composable
internal fun liveSections(vm: SettingsViewModel): List<SettingSection> {
    val g = SettingsGroup.LIVE
    val s = vm.settings
    val timeshift = s.timeshiftEnabled.pref(false)
    val window = s.timeshiftWindowMinutes.pref(TimeshiftRules.DEFAULT_WINDOW_MINUTES)
    val resume = s.timeshiftResumeMode.pref(SettingsRepository.ResumeMode.ASK)
    return listOf(
        SettingSection(
            stringResource(R.string.settings_live_tv),
            listOfNotNull(
                switchItem(
                    "vp_timeshift", MobileIcons.Pause, stringResource(R.string.settings_timeshift),
                    stringResource(R.string.settings_line_timeshift), timeshift,
                ) { vm.edit { setTimeshiftEnabled(it) } },
                // The length and the comeback question mean nothing while saving is off, so they come with the switch.
                if (timeshift) choiceItem(
                    "vp_timeshift_window", MobileIcons.Catchup, g, stringResource(R.string.settings_timeshift_window),
                    stringResource(R.string.settings_line_timeshift_window), stringResource(R.string.settings_help_timeshift_window),
                    TimeshiftRules.WINDOW_CHOICES_MINUTES.map { SettingsChoice(it, stringResource(R.string.player_duration_minutes, it)) },
                    window, TimeshiftRules.DEFAULT_WINDOW_MINUTES, { m -> vm.edit { setTimeshiftWindowMinutes(m) } },
                ) else null,
                if (timeshift) choiceItem(
                    "vp_timeshift_resume", MobileIcons.History, g, stringResource(R.string.settings_timeshift_resume),
                    stringResource(R.string.settings_timeshift_resume_description), null,
                    SettingsRepository.ResumeMode.entries.map { SettingsChoice(it, stringResource(it.resumeLabel())) },
                    resume, SettingsRepository.ResumeMode.ASK, { m -> vm.edit { setTimeshiftResumeMode(m) } },
                ) else null,
            ),
        ),
    )
}

// ----------------------------------------------------------------------------------- Watching & recording

@Composable
internal fun watchingSections(vm: SettingsViewModel): List<SettingSection> {
    val g = SettingsGroup.WATCHING
    val s = vm.settings
    // ASK: what core stores when nothing has been chosen.
    val resume = s.resumeMode.pref(SettingsRepository.ResumeMode.ASK)
    val multiview = s.multiviewEnabled.pref(false)
    val tiles = s.multiviewTiles.pref(DEFAULT_MULTIVIEW_TILES)
    // A value stored before "off" was withdrawn reads as docked, so the row never names a choice the sheet lacks.
    val mini = s.miniPlayerStyle.pref(SettingsRepository.MiniPlayerStyle.FLOATING)
        .takeIf { it != SettingsRepository.MiniPlayerStyle.OFF } ?: SettingsRepository.MiniPlayerStyle.DOCKED
    val pipSize = s.pipSize.pref(SettingsRepository.PipSize.MEDIUM)
    val preRoll = s.recordingPreRollMinutes.pref(RecordingSchedule.DEFAULT_PRE_ROLL_MINUTES)
    val postRoll = s.recordingPostRollMinutes.pref(RecordingSchedule.DEFAULT_POST_ROLL_MINUTES)
    val recordWatching = s.recordWhatImWatching.pref(false)
    val recordingsVm: RecordingsViewModel = koinViewModel()

    // The player's record button costs the one spare connection, so turning it on says so first.
    var warnRecordWatching by remember { mutableStateOf(false) }
    if (warnRecordWatching) {
        StageConfirmSheet(
            title = stringResource(R.string.settings_record_watching_warning_title),
            text = stringResource(R.string.settings_record_watching_warning_description),
            confirm = stringResource(R.string.settings_record_watching_turn_on),
            cancel = stringResource(R.string.settings_record_watching_keep_off),
            onConfirm = { vm.edit { setRecordWhatImWatching(true) } },
            onDismiss = { warnRecordWatching = false },
        )
    }
    val minutes = @Composable { m: Int -> pluralStringResource(R.plurals.recording_minutes, m, m) }
    return listOf(
        SettingSection(
            stringResource(R.string.settings_vp_cat_controls_resume),
            listOf(
                choiceItem(
                    "vp_resume", MobileIcons.History, g, stringResource(R.string.settings_resume_playback),
                    stringResource(R.string.settings_line_resume), stringResource(R.string.settings_help_resume),
                    SettingsRepository.ResumeMode.entries.map { SettingsChoice(it, stringResource(it.resumeLabel())) },
                    resume, SettingsRepository.ResumeMode.ASK, { m -> vm.edit { setResumeMode(m) } },
                ),
                quickSwitch(vm, "vp_autoplay", MobileIcons.SkipNext, R.string.settings_autoplay_next, R.string.settings_line_autoplay),
            ),
        ),
        SettingSection(
            stringResource(R.string.settings_vp_cat_multiview),
            listOfNotNull(
                switchItem(
                    "vp_multiview", MobileIcons.Multiview, stringResource(R.string.settings_multiview),
                    stringResource(R.string.settings_vp_cat_multiview_summary_mobile), multiview,
                ) { vm.edit { setMultiviewEnabled(it) } },
                // "Max 4", not "4": the number is a ceiling — the grid opens with two and grows when asked.
                if (multiview) choiceItem(
                    "vp_multiview_tiles", MobileIcons.GridView, g, stringResource(R.string.settings_multiview_tiles_max),
                    stringResource(R.string.settings_line_tiles), stringResource(R.string.settings_multiview_description),
                    (MIN_MULTIVIEW_TILES..MAX_MULTIVIEW_TILES).map { SettingsChoice(it, stringResource(R.string.settings_multiview_tiles_max_value, it)) },
                    tiles, DEFAULT_MULTIVIEW_TILES, { n -> vm.edit { setMultiviewTiles(n) } },
                ) else null,
                // Docked or floating, and no third answer: "off" would leave a stream nowhere to go.
                choiceItem(
                    "vp_mini", MobileIcons.Phone, g, stringResource(R.string.settings_mini_player),
                    stringResource(R.string.settings_mini_player_style_description), null,
                    SettingsRepository.MiniPlayerStyle.entries.filter { it != SettingsRepository.MiniPlayerStyle.OFF }
                        .map { SettingsChoice(it, stringResource(it.miniLabel())) },
                    mini, SettingsRepository.MiniPlayerStyle.FLOATING, { m -> vm.edit { setMiniPlayerStyle(m) } },
                ),
                quickSwitch(vm, "pip_enabled", MobileIcons.Phone, R.string.settings_pip, R.string.settings_line_pip_mobile),
                choiceItem(
                    "pip_size", MobileIcons.OpenInFull, g, stringResource(R.string.settings_pip_size),
                    stringResource(R.string.settings_line_pip_size_mobile), null,
                    SettingsRepository.PipSize.entries.map { SettingsChoice(it, stringResource(it.pipLabel())) },
                    pipSize, SettingsRepository.PipSize.MEDIUM, { v -> vm.edit { setPipSize(v) } },
                ),
                quickSwitch(vm, "pip_snap", MobileIcons.Move, R.string.settings_pip_snap, R.string.settings_line_pip_snap_mobile),
            ),
        ),
        SettingSection(
            stringResource(R.string.content_downloads_group_on_phone),
            listOf(
                quickSwitch(vm, "background_playback", MobileIcons.Headphones, R.string.settings_background_playback, R.string.settings_background_playback_description),
                quickSwitch(vm, "audio_on_screen_off", MobileIcons.Bedtime, R.string.settings_audio_on_screen_off, R.string.settings_line_audio_screen_off_mobile),
                quickSwitch(vm, "audio_on_mobile_data", MobileIcons.Translate, R.string.settings_audio_on_mobile_data, R.string.settings_audio_on_mobile_data_description),
                quickSwitch(vm, "audio_per_channel", MobileIcons.VolumeUp, R.string.settings_audio_per_channel, R.string.settings_line_audio_per_channel_mobile),
            ),
        ),
        SettingSection(
            stringResource(R.string.recording_settings_group),
            note = stringResource(R.string.settings_recording_timers_inexact).takeUnless { recordingsVm.timersAreExact },
            items = listOf(
                switchItem(
                    "rec_reserve", MobileIcons.Record, stringResource(R.string.settings_recording_reserve),
                    stringResource(R.string.settings_line_rec_reserve),
                    s.recordingReserveConnection.pref(true),
                ) { vm.edit { setRecordingReserveConnection(it) } },
                switchItem(
                    "rec_mobile_data", MobileIcons.Translate, stringResource(R.string.settings_recording_mobile_data),
                    stringResource(R.string.settings_line_rec_mobile_data_mobile), s.recordingOverMobileData.pref(true),
                ) { vm.edit { setRecordingOverMobileData(it) } },
                switchItem(
                    "rec_watching", MobileIcons.Record, stringResource(R.string.settings_record_watching),
                    stringResource(R.string.settings_line_rec_watching_mobile), recordWatching,
                ) { on -> if (on) warnRecordWatching = true else vm.edit { setRecordWhatImWatching(false) } },
                choiceItem(
                    "rec_pre_roll", MobileIcons.Schedule, g, stringResource(R.string.settings_recording_pre_roll),
                    stringResource(R.string.settings_line_rec_pre_roll_mobile), stringResource(R.string.settings_recording_pre_roll_description),
                    ROLL_CHOICES.map { SettingsChoice(it, minutes(it)) }, preRoll, RecordingSchedule.DEFAULT_PRE_ROLL_MINUTES,
                    { m -> vm.edit { setRecordingPreRollMinutes(m) } },
                ),
                choiceItem(
                    "rec_post_roll", MobileIcons.Schedule, g, stringResource(R.string.settings_recording_post_roll),
                    stringResource(R.string.settings_line_rec_post_roll_mobile), stringResource(R.string.settings_recording_post_roll_description),
                    ROLL_CHOICES.map { SettingsChoice(it, minutes(it)) }, postRoll, RecordingSchedule.DEFAULT_POST_ROLL_MINUTES,
                    { m -> vm.edit { setRecordingPostRollMinutes(m) } },
                ),
            ),
        ),
    )
}

/** The paddings worth offering, all inside core's own 0..30 clamp. */
private val ROLL_CHOICES = listOf(0, 1, 2, 3, 5, 10, 15, 30)

// ---------------------------------------------------------------------------- Per-playlist pages

/**
 * One per-playlist override: each playlist with what it is set to; a tap opens the choices for that
 * playlist — Follow the global setting first. Only Catch-up's time zone is still per playlist here.
 */
@Composable
fun PerPlaylistPage(leaf: SettingsLeaf, modifier: Modifier = Modifier, vm: SettingsViewModel = koinViewModel()) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val follow = stringResource(R.string.settings_live_preroll_follow)
    val eyebrow = stringResource(leaf.titleRes)
    var editing by remember { mutableStateOf<Long?>(null) }

    SettingsStagePage(modifier) {
        sources.forEachIndexed { index, src ->
            item(key = src.id) {
                StageSettingRowFor(src, index, sourceValue(leaf, src, follow)) { editing = src.id }
            }
        }
    }

    val src = sources.firstOrNull { it.id == editing }
    if (src != null) {
        val close = { editing = null }
        when (leaf) {
            SettingsLeaf.CATCHUP_SOURCES -> {
                val manual = SettingsRepository.CatchupTimezone.MANUAL.name
                StageChoiceSheet(
                    eyebrow, src.name, stringResource(R.string.settings_catchup_timezone_per_playlist_description),
                    listOf(
                        SettingsChoice<Pair<String?, Int?>>(null to null, follow),
                        SettingsChoice<Pair<String?, Int?>>(SettingsRepository.CatchupTimezone.DEVICE.name to null, stringResource(R.string.settings_catchup_timezone_device)),
                    ) + vm.settings.catchupOffsetChoicesMinutes.map { SettingsChoice<Pair<String?, Int?>>(manual to it, utcOffsetLabel(it)) },
                    src.catchupTimezone to src.catchupOffsetMin.takeIf { src.catchupTimezone == manual },
                    { (mode, offset) -> vm.setSourceCatchupTimezone(src.id, mode, offset) }, close,
                )
            }
            else -> Unit
        }
    }
}

/** A playlist row of a per-playlist page: its mark, its name, its value with ▾. */
@Composable
private fun StageSettingRowFor(src: SourceEntity, index: Int, value: String, onClick: () -> Unit) {
    tv.own.owntv.mobile.ui.stage.StageSettingRow(
        title = src.name,
        leading = { StagePlaylistMark(tv.own.owntv.core.ui.stage.PlaylistMark.of(src.name, index)) },
        onClick = onClick,
    ) { tv.own.owntv.mobile.ui.stage.StageRowValue(value, tv.own.owntv.mobile.ui.stage.StageChevron.DOWN) }
}

@Composable
private fun sourceValue(leaf: SettingsLeaf, src: SourceEntity, follow: String): String = when (leaf) {
    SettingsLeaf.CATCHUP_SOURCES -> when (src.catchupTimezone) {
        null -> follow
        SettingsRepository.CatchupTimezone.MANUAL.name -> utcOffsetLabel(src.catchupOffsetMin ?: 0)
        else -> stringResource(R.string.settings_catchup_timezone_device)
    }
    else -> ""
}

/** Settings › Player › External player: which sections open in another app, one switch each. */
@Composable
fun ExternalPlayerPage(modifier: Modifier = Modifier, vm: SettingsViewModel = koinViewModel()) {
    val s = vm.settings
    val rows = listOf(
        Triple(SettingsRepository.ExternalPlayerSection.MOVIES, R.string.common_nav_movies, s.externalPlayerMovies.pref(false)),
        Triple(SettingsRepository.ExternalPlayerSection.SERIES, R.string.common_nav_series, s.externalPlayerSeries.pref(false)),
    )
    val note = stringResource(R.string.settings_external_player_description)
    SettingsStagePage(modifier) {
        item(key = "note") { PageNote(note) }
        rows.forEach { (section, titleRes, on) ->
            item(key = section.name) {
                SettingItemRow(
                    switchItem(section.name, MobileIcons.OpenInNew, stringResource(titleRes), null, on) { vm.edit { setExternalPlayer(section, it) } },
                    onOpenSheet = {}, onLongClick = null,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- labels

private fun SurroundMode.labelRes() = when (this) {
    SurroundMode.AUTO -> R.string.settings_auto
    SurroundMode.STEREO -> R.string.settings_surround_stereo
    SurroundMode.SURROUND -> R.string.settings_surround_sound
}

private fun SurroundMode.descriptionRes() = when (this) {
    SurroundMode.AUTO -> R.string.settings_surround_auto_description_mobile
    SurroundMode.STEREO -> R.string.settings_surround_stereo_description_mobile
    SurroundMode.SURROUND -> R.string.settings_surround_forced_description
}

private fun SettingsRepository.ResumeMode.resumeLabel() = when (this) {
    SettingsRepository.ResumeMode.AUTO -> R.string.settings_resume_always
    SettingsRepository.ResumeMode.ASK -> R.string.settings_resume_ask
    SettingsRepository.ResumeMode.NEVER -> R.string.settings_resume_never
}

private fun SettingsRepository.MiniPlayerStyle.miniLabel(): Int = when (this) {
    SettingsRepository.MiniPlayerStyle.FLOATING -> R.string.settings_mini_player_style_floating
    SettingsRepository.MiniPlayerStyle.DOCKED -> R.string.settings_mini_player_style_docked
    SettingsRepository.MiniPlayerStyle.OFF -> R.string.common_off
}

private fun SettingsRepository.PipSize.pipLabel(): Int = when (this) {
    SettingsRepository.PipSize.SMALL -> R.string.settings_pip_size_small
    SettingsRepository.PipSize.MEDIUM -> R.string.settings_pip_size_medium
    SettingsRepository.PipSize.LARGE -> R.string.settings_pip_size_large
}
