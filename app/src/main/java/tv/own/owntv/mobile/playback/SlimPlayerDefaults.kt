package tv.own.owntv.mobile.playback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.entity.FOLLOW_GLOBAL_LATENCY_SECS
import tv.own.owntv.core.database.entity.FOLLOW_GLOBAL_PREROLL
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.player.AudioOutputKind
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.player.FfmpegAudioSetting
import tv.own.owntv.core.player.OutputAudioDelays
import tv.own.owntv.core.player.PlaybackPrefsStore
import tv.own.owntv.core.settings.LiveBuffer
import tv.own.owntv.core.settings.LiveLatency
import tv.own.owntv.core.settings.SeekSteps
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.core.theme.AnimationLevel
import tv.own.owntv.core.theme.PopupSizeScale
import tv.own.owntv.core.theme.UiZoom
import tv.own.owntv.player.LiveLadder
import tv.own.owntv.player.LiveTuneController

private const val TAG = "SlimSettings"

/** 焦点高亮的出厂宽度（dp）。 */
private const val FOCUS_HIGHLIGHT_DEFAULT_DP = 2

/**
 * 包装 core 的 [LiveTuneController.Host]，把直播路由的两个“选择型”默认值固定在本 App 的策略上。
 *
 * 只影响路由的决策输入，不改 core、不写 DataStore：
 * - 全局偏好固定 ExoPlayer 优先，失败时由 LiveLadder 自动回退 mpv；
 * - 播放列表级引擎覆盖一律归零，旧列值留在 Room 里但不再参与路由。
 *
 * 其余能力（解析、pin、日志、预算、时移）通过接口委托原样透传。
 *
 * @param delegate core 的 CoreHost 实例。
 */
class SlimLiveHost(
    private val delegate: LiveTuneController.Host,
) : LiveTuneController.Host by delegate {

    /** 直播全局默认：ExoPlayer 优先。 */
    override suspend fun globalPreference(): EnginePreference = EnginePreference.EXO_FIRST

    /** 抹掉播放列表级覆盖；其余字段原样返回，解析与 UA 行为不变。 */
    override suspend fun sourceOf(sourceId: Long): SourceEntity? =
        delegate.sourceOf(sourceId)?.copy(
            liveEnginePreference = null,
            vodEnginePreference = null,
            // 直播延迟、预缓冲、放弃等待时间的“按播放列表设置”也已从设置页移除：
            // 一律回到“跟随全局”，旧列值留在库里但不再参与播放决策。
            liveLatencyMode = null,
            liveLatencyCustomSecs = FOLLOW_GLOBAL_LATENCY_SECS,
            livePrerollSecs = FOLLOW_GLOBAL_PREROLL,
            liveTuneTimeoutSecs = null,
        )
}

/**
 * 冷启动把“能力型”播放设置收敛到本次改版的固定策略。
 *
 * 幂等：只在与目标值不一致时写入；不删键、不设版本号，因此不是数据迁移。
 * 失败只记日志、不阻塞启动；下一次冷启动会重试。
 *
 * @param settings core 设置仓库。
 */
internal suspend fun applySlimPlaybackCaps(settings: SettingsRepository) = runCatching {
    // 0 = 自动 / 不限：缓冲交给内核，画质不设上限。
    if (settings.vodBufferSecs.first() != 0) settings.setVodBufferSecs(0)
    if (settings.maxVideoHeight.first() != 0) settings.setMaxVideoHeight(0)
    if (settings.mobileDataMaxVideoHeight.first() != 0) settings.setMobileDataMaxVideoHeight(0)
    // 流量节省是硬门禁，必须关闭，否则老用户会“不能播且无处可开”。
    if (settings.dataSaver.first()) settings.setDataSaver(false)
}.onFailure { Log.w(TAG, "applySlimPlaybackCaps failed", it) }

/**
 * 冷启动把直播电视页中已固定的默认值写回存储，保证“默认开启且不可关闭”的项不再受旧值影响。
 *
 * 频道编号、直播预览、预览声音回开启；预缓冲回关闭（0 = 跟随内核的起播阈值）。
 * 幂等：只在与目标值不一致时写入；失败只记日志，下一次冷启动会重试。
 *
 * @param settings core 设置仓库。
 */
internal suspend fun applySlimLiveDefaults(settings: SettingsRepository) = runCatching {
    if (!settings.directTune.first()) settings.setDirectTune(true)
    if (!settings.livePreviewEnabled.first()) settings.setLivePreviewEnabled(true)
    if (!settings.livePreviewAudio.first()) settings.setLivePreviewAudio(true)
    if (settings.livePrerollSecs.first() != 0) settings.setLivePrerollSecs(0)
}.onFailure { Log.w(TAG, "applySlimLiveDefaults failed", it) }

/**
 * 冷启动把外观组中已从设置页移除的“其他项”统一回出厂默认，不保留老用户值。
 *
 * 只重置被移除的项；主题、玻璃与背景、字体家族和字号仍由用户选择，不在收敛范围内。
 * 幂等：只在与目标值不一致时写入；失败只记日志，下一次冷启动会重试。
 *
 * @param settings core 设置仓库。
 */
internal suspend fun applySlimAppearanceDefaults(settings: SettingsRepository) = runCatching {
    // 强调色回品牌色 TEAL，自定义色一并清空（setAccent 本身也会清空）。
    if (settings.accent.first() != AccentColor.TEAL) settings.setAccent(AccentColor.TEAL)
    if (settings.customAccent.first().isNotBlank()) settings.setCustomAccent("")
    // 选中高亮：跟随强调色（空串），宽度回 2 dp。
    if (settings.focusHighlight.first().isNotBlank()) settings.setFocusHighlight("")
    if (settings.focusHighlightWidth.first() != FOCUS_HIGHLIGHT_DEFAULT_DP) {
        settings.setFocusHighlightWidth(FOCUS_HIGHLIGHT_DEFAULT_DP)
    }
    // 弹窗尺寸回 100%，只改这一个字段，不动字体家族与字号。
    val fonts = settings.fontCustomization.first()
    if (fonts.popupSizePercent != PopupSizeScale.DEFAULT) {
        settings.setFontCustomization(fonts.copy(popupSizePercent = PopupSizeScale.DEFAULT))
    }
    // UI 缩放回手机端既定默认 90%（引导页的缩放行已同步移除）。
    if (settings.uiZoomPercent.first() != UiZoom.DEFAULT) settings.setUiZoomPercent(UiZoom.DEFAULT)
    // 环境光关闭；脉动回存储默认“开”，环境光关时无效果。
    if (settings.ambientGlowEnabled.first()) settings.setAmbientGlowEnabled(false)
    if (!settings.ambientGlowPulse.first()) settings.setAmbientGlowPulse(true)
    // 动画回 FULL。
    if (settings.animationLevel.first() != AnimationLevel.FULL) settings.setAnimationLevel(AnimationLevel.FULL)
}.onFailure { Log.w(TAG, "applySlimAppearanceDefaults failed", it) }

/**
 * 冷启动把「声音和字幕」组中已从设置页移除的项统一回出厂默认，不保留老用户值。
 *
 * 只重置被移除的项；环绕声与字幕外观仍由用户选择，不在收敛范围内。
 * 幂等：只在与目标值不一致时写入；失败只记日志，下一次冷启动会重试。
 *
 * @param settings core 设置仓库（DataStore）。
 * @param playbackPrefs 条目级音量/同步记忆（Room），一并清空。
 * @param context FFmpeg 音频开关所在的独立 SharedPreferences。
 */
internal suspend fun applySlimSoundDefaults(
    settings: SettingsRepository,
    playbackPrefs: PlaybackPrefsStore,
    context: Context,
) = runCatching {
    // 默认音量 100、直通开：core 的出厂默认。
    if (settings.defaultVolume.first() != 100) settings.setDefaultVolume(100)
    if (!settings.audioPassthrough.first()) settings.setAudioPassthrough(true)
    // 夜间模式与音量均衡关闭后，直通不再被 core 的联动逻辑压掉。
    if (settings.nightMode.first()) settings.setNightMode(false)
    if (settings.volumeLevelling.first()) settings.setVolumeLevelling(false)
    // 音视频同步归零；按输出的延迟没有整表清空 API，逐类型写 0 等价于 NONE。
    if (settings.audioDelayMs.first() != 0) settings.setAudioDelayMs(0)
    if (settings.outputAudioDelays.first() != OutputAudioDelays.NONE) {
        AudioOutputKind.entries.forEach { settings.setOutputAudioDelay(it, 0) }
    }
    // 语言按 profile 存：写空串后 profile 键短路共享键的旧值；本 App 已锁定单 profile。
    if (settings.preferredAudioLang.first().isNotBlank()) settings.setPreferredAudioLang("")
    if (settings.preferredSubLang.first().isNotBlank()) settings.setPreferredSubLang("")
    // FFmpeg 音频在独立 SharedPreferences，进程级固定：写回 false，下次启动生效。
    if (FfmpegAudioSetting.saved(context)) FfmpegAudioSetting.save(context, false)
    // 条目级音量/同步记忆：每次冷启动清空，保证起播恒用默认音量与零延迟。
    playbackPrefs.clearVolume()
    playbackPrefs.clearAudioDelay()
}.onFailure { Log.w(TAG, "applySlimSoundDefaults failed", it) }

/**
 * 冷启动把已从设置页移除的「播放行为与诊断」类选项统一回出厂默认，不保留老用户值。
 *
 * 覆盖五个组的被删行：诊断（测量统计/详细日志/网络超时）、解码（隧道播放/1080p 以上软解/
 * 自动帧率）、直播调谐（延迟模式/自定义延迟/放弃等待）、控制（快进与回退步长/手势灵敏度）。
 * 幂等：只在与目标值不一致时写入；失败只记日志，下一次冷启动会重试。
 *
 * @param settings core 设置仓库（DataStore）。
 */
internal suspend fun applySlimPlaybackBehaviorDefaults(settings: SettingsRepository) = runCatching {
    // 诊断三项：测量统计回设备分级默认（非 lowSpec 设备开），日志与网络超时回关/自动。
    if (settings.measuredStreamStats.first() != settings.measuredStreamStatsDefault) {
        settings.setMeasuredStreamStats(settings.measuredStreamStatsDefault)
    }
    if (settings.detailedDiagnostics.first()) settings.setDetailedDiagnostics(false)
    if (settings.vodNetworkTimeoutSecs.first() != 0) settings.setVodNetworkTimeoutSecs(0)
    // 解码四键：隧道播放、1080p 以上软解、自动帧率（电影与直播）全部回关。
    if (settings.tunneledPlayback.first()) settings.setTunneledPlayback(false)
    if (settings.softwareDecodeAbove1080p.first()) settings.setSoftwareDecodeAbove1080p(false)
    if (settings.autoFrameRateFilms.first()) settings.setAutoFrameRateFilms(false)
    if (settings.autoFrameRateLive.first()) settings.setAutoFrameRateLive(false)
    // 直播调谐三键：延迟回 BALANCED，自定义值一并回出厂；放弃等待回 30 秒。
    if (settings.liveLatencyMode.first() != LiveLatency.BALANCED.name) settings.setLiveLatencyMode(LiveLatency.BALANCED.name)
    if (settings.liveLatencyCustomSecs.first() != LiveBuffer.CUSTOM_DEFAULT) settings.setLiveLatencyCustomSecs(LiveBuffer.CUSTOM_DEFAULT)
    if (settings.liveTuneTimeoutSecs.first() != LiveLadder.DEFAULT_BUDGET_SECS) settings.setLiveTuneTimeoutSecs(LiveLadder.DEFAULT_BUDGET_SECS)
    // 控制三键：快进 10 秒、直播回退 30 秒、手势灵敏度 100%。
    if (settings.seekStepSec.first() != SeekSteps.DEFAULT_SEEK_STEP_SEC) settings.setSeekStepSec(SeekSteps.DEFAULT_SEEK_STEP_SEC)
    if (settings.liveRewindStepSec.first() != SeekSteps.DEFAULT_LIVE_REWIND_STEP_SEC) settings.setLiveRewindStepSec(SeekSteps.DEFAULT_LIVE_REWIND_STEP_SEC)
    if (settings.gestureSensitivityPct.first() != 100) settings.setGestureSensitivityPct(100)
}.onFailure { Log.w(TAG, "applySlimPlaybackBehaviorDefaults failed", it) }
