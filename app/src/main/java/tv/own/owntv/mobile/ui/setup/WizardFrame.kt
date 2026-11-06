package tv.own.owntv.mobile.ui.setup

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.BrandMark
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.Wordmark
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageBackground
import tv.own.owntv.mobile.ui.stage.stageClickable
import tv.own.owntv.mobile.ui.stage.stageFx
import tv.own.owntv.mobile.ui.stage.stageGlass
import tv.own.owntv.mobile.ui.stage.stageText

/** The five steps the dots show, the TV's `WizardStep`. No profile step: this app has one, and the wizard makes it. */
internal enum class WizardStep { LANGUAGE, DISPLAY, CONTENT, PLAYLIST, READY }

/**
 * Every setup step's frame: the Stage page, the app's mark and wordmark top left, the step dots top
 * right, then one glass panel 16 dp in from the sides at y 120 — a 24 / 800 title, its 12.5 line, the step's rows
 * or cards, and the buttons: [onBack] as a plain "Back" on the left, [next] filled with › on the right. System
 * Back is [SetupFlow]'s, one table for every step (Importing ignores it: a stray swipe must not cancel a sync).
 */
@Composable
internal fun WizardFrame(
    step: WizardStep,
    title: String,
    sub: String?,
    next: String?,
    onNext: () -> Unit,
    onBack: (() -> Unit)?,
    nextEnabled: Boolean = true,
    backLabel: String = stringResource(R.string.common_back),
    /** More plain buttons beside Back (a password step's Skip). */
    extraLeft: List<Pair<String, () -> Unit>> = emptyList(),
    body: @Composable ColumnScope.() -> Unit = {},
) {
    val palette = LocalStagePalette.current
    val settings: SettingsRepository = koinInject()
    val icon by settings.appIcon.collectAsStateWithLifecycle(AppIcon.DEFAULT)
    Box(Modifier.fillMaxSize().stageBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(icon, 28.dp)
                Wordmark(80.dp)
                Spacer(Modifier.weight(1f))
                StepDots(step)
            }
            Column(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 44.dp)
                    .fillMaxWidth()
                    .stageGlass(RoundedCornerShape(26.dp))
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
            ) {
                Text(title, style = stageText(24f, 800, letterSpacing = (-0.5f / 24f).em), color = palette.text)
                if (!sub.isNullOrBlank()) {
                    Text(sub, style = stageText(12.5f, 500, lineHeight = 1.45f), color = palette.muted, modifier = Modifier.padding(top = 4.dp))
                }
                Column(Modifier.padding(top = 14.dp), content = body)
                if (onBack != null || next != null || extraLeft.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        onBack?.let { StageButton(onClick = it, text = backLabel, style = StageButtonStyle.PLAIN, small = true) }
                        extraLeft.forEach { (label, action) -> StageButton(onClick = action, text = label, style = StageButtonStyle.PLAIN, small = true) }
                        Spacer(Modifier.weight(1f))
                        if (next != null) {
                            StageButton(
                                onClick = onNext,
                                text = next,
                                style = StageButtonStyle.PRIMARY,
                                enabled = nextEnabled,
                                trailingIcon = MobileIcons.KeyboardArrowRight,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `.dots`: 6 dp dots 5 apart — reached ones in the accent, the current one an 18 dp accent bar. */
@Composable
private fun StepDots(current: WizardStep) {
    val accent = stageAccent.accent
    val idle = LocalStagePalette.current.ink.copy(alpha = 0.2f)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        WizardStep.entries.forEach { s ->
            Box(
                Modifier
                    .size(width = if (s == current) 18.dp else 6.dp, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (s.ordinal <= current.ordinal) accent else idle),
            )
        }
    }
}

/** A step's choice (`.card`): the icon, a 14 / 800 name and its 11 line; pressed takes the fx look. */
@Composable
internal fun WizardCard(icon: ImageVector, title: String, line: String, onClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .then(if (pressed) Modifier.stageFx(18.dp) else Modifier)
            .clip(shape)
            .background(palette.ink.copy(alpha = 0.045f))
            .stageClickable(interaction, onClick, null)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageIcon(icon, 20.dp, palette.text)
        Column(Modifier.weight(1f)) {
            Text(title, style = stageText(14f, 800), color = palette.text)
            Text(line, style = stageText(11f, 500, lineHeight = 1.3f), color = palette.muted)
        }
    }
}

/**
 * `.prog` while nobody knows the end (an import reports what arrived, never a total): a third of the bar sweeps
 * across, the accent's own sweep on dark. Never a made-up percentage.
 */
@Composable
internal fun WizardSweepBar(modifier: Modifier = Modifier) {
    val palette = LocalStagePalette.current
    val accent = stageAccent
    val t by androidx.compose.animation.core.rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = -0.35f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400)),
        label = "sweep",
    )
    Box(
        modifier
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(palette.ink.copy(alpha = 0.12f))
            .drawBehind {
                val x = size.width * t
                val w = size.width * 0.35f
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(accent.accent, accent.focus), startX = x, endX = x + w),
                    topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                    size = androidx.compose.ui.geometry.Size(w, size.height),
                )
            },
    )
}

/** A plain text link in the panel ("Skip for now": 12.5 / 700, muted). */
@Composable
internal fun WizardLink(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text,
        style = stageText(12.5f, 700),
        color = LocalStagePalette.current.muted,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .stageClickable(interaction, onClick, null)
            .padding(horizontal = 2.dp, vertical = 6.dp),
    )
}
