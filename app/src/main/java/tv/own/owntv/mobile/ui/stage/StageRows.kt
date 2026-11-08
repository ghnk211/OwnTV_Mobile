package tv.own.owntv.mobile.ui.stage

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.StageConfirmItems
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.own.owntv.mobile.ui.components.MobileIcons

/**
 * Whether a row whose title does not fit scrolls it instead of cutting it short.
 *
 * Off everywhere a page has the whole width: a title that runs off the end and starts moving is
 * noise on a list that is already readable. The player's overlays turn it on — their columns are a
 * third of the screen, where nearly every channel and setting name is too long for the row.
 */
val LocalScrollLongTitles = staticCompositionLocalOf { false }

/** Tap and long-press without a ripple; the long press ticks like every other menu in the app. */@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.stageClickable(
    interaction: MutableInteractionSource,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    role: Role? = Role.Button,
): Modifier {
    if (onClick == null && onLongClick == null) return this
    val haptics = LocalHapticFeedback.current
    return combinedClickable(
        interactionSource = interaction,
        indication = null,
        role = role,
        onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
        onClick = onClick ?: {},
    )
}

/**
 * A Stage list row (16 dp corners, 12 dp sides): channel rows, setting rows, result rows. [current] is
 * the open / playing item (`.fx`), [selected] the left accent bar (`.selbar`). There is no filled
 * press on a row (`.f` is for menu items), so a pressed row takes the fx look while the finger is down.
 */
@Composable
fun StageRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    current: Boolean = false,
    selected: Boolean = false,
    height: Dp? = null,
    minHeight: Dp = 0.dp,
    gap: Dp = 11.dp,
    role: Role? = Role.Button,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(StageRadii.Row)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (height != null) Modifier.height(height) else Modifier.heightIn(min = minHeight))
            .then(if (current || pressed) Modifier.stageFx(StageRadii.Row) else Modifier)
            .then(if (selected) Modifier.stageSelectedBar() else Modifier)
            .clip(shape)
            .stageClickable(interaction, onClick, onLongClick, role)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * `.srow`: a settings row, at least 60 dp — the 34 dp icon square, the title (14.5 / 700) and its line
 * (11.5 muted), then [trailing]: a [StageSwitch], [StageRowValue], [StageStepper] or [StageSegmented].
 */
@Composable
fun StageSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    current: Boolean = false,
    role: Role? = Role.Button,
    /** 60 on a settings page; a sheet's switch rows are 48 (the Guide's SHOW). */
    minHeight: Dp = 60.dp,
    /** Drawn in the icon's 34 dp place instead (a profile's avatar, a playlist's mark). */
    leading: (@Composable () -> Unit)? = null,
    /** Above the title, 11.5 muted: a search result's path. */
    overline: String? = null,
    /** The title as styled text (a search result with its match in the accent). */
    styledTitle: androidx.compose.ui.text.AnnotatedString? = null,
    /** The line's colour when it is a value rather than a description (a form row's text, its dim example). */
    subtitleColor: Color? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalStagePalette.current
    StageRow(
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        current = current,
        minHeight = minHeight,
        gap = 12.dp,
        role = role,
    ) {
        if (leading != null) {
            // At least the icon's 34 dp; a wider picture (a playlist mark, "GOLD") keeps its own width.
            Box(Modifier.defaultMinSize(34.dp, 34.dp), contentAlignment = Alignment.Center) { leading() }
        } else if (icon != null) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(StageRadii.SettingIcon)).background(palette.controlFill),
                contentAlignment = Alignment.Center,
            ) { StageIcon(icon, 17.dp, palette.muted) }
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            val scrolling = LocalScrollLongTitles.current
            val marquee = if (scrolling) Modifier.basicMarquee() else Modifier
            overline?.let { Text(it, style = stageText(11.5f, 500, lineHeight = 1.3f), color = palette.muted, modifier = Modifier.padding(bottom = 2.dp)) }
            if (styledTitle != null) {
                Text(styledTitle, style = stageText(14.5f, 700), color = palette.text, maxLines = if (scrolling) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = marquee)
            } else {
                Text(
                    title,
                    style = stageText(14.5f, 700),
                    color = palette.text,
                    // A scrolling title is one line by definition: two lines of marquee is a wall.
                    maxLines = if (scrolling) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = marquee,
                )
            }
            subtitle?.let {
                Text(
                    it,
                    style = stageText(11.5f, 500, lineHeight = 1.3f),
                    color = subtitleColor ?: palette.muted,
                    maxLines = if (scrolling) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp).then(marquee),
                )
            }
        }
        trailing()
    }
}

enum class StageChevron { NONE, DOWN, FORWARD }

/**
 * `.srow .v`: a row's value (12.5 / 700). Accent by default (a choice); [plain] draws it in the text colour
 * (`.v.w`, an action). [accentPart] leads in the accent before " · " (`act` rows: "Never · Back up now").
 * The chevron says what a tap does: DOWN opens choices, FORWARD opens a page.
 */
@Composable
fun StageRowValue(
    value: String,
    chevron: StageChevron = StageChevron.NONE,
    plain: Boolean = false,
    accentPart: String? = null,
) {
    val palette = LocalStagePalette.current
    val accent = stageAccent.accent
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val text = buildAnnotatedString {
            if (accentPart != null) {
                withStyle(SpanStyle(color = accent)) { append(accentPart) }
                append(" · ")
            }
            append(value)
        }
        // A row measures its value first; uncapped, a long one ("Plus Jakarta Sans · 100%") at a large zoom and
        // font left the title so little room that it broke mid-word. Measured in the zoomed density, so it holds.
        val maxWidth = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.width * 0.4f).toDp() }
        Text(
            text,
            style = stageText(12.5f, 700),
            color = if (plain || accentPart != null) palette.text else accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = maxWidth),
        )
        when (chevron) {
            StageChevron.NONE -> Unit
            StageChevron.DOWN -> StageIcon(MobileIcons.KeyboardArrowDown, 14.dp, palette.muted)
            StageChevron.FORWARD -> StageIcon(MobileIcons.KeyboardArrowRight, 14.dp, palette.muted)
        }
    }
}

/** `.shd`: a page's sub-heading with its row count, dim at 70% (`MOVIES 4`). */
@Composable
fun StageSectionHead(text: String, modifier: Modifier = Modifier, count: Int? = null) {
    val dim = LocalStagePalette.current.dim
    Row(modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StageLabel(text)
        count?.let { StageLabel(it.toString(), color = dim.copy(alpha = dim.alpha * 0.7f)) }
    }
}

/** `.grp`: a sheet's group label (WATCH, CHANNEL, CHOICES). */
@Composable
fun StageGroupLabel(text: String, modifier: Modifier = Modifier) =
    StageLabel(text, modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp))

/**
 * `.sheet .hd`: what a sheet is about — an optional [leading] picture (a logo plate 44 × 32 or poster
 * art 34 × 50), an optional [eyebrow] label above, the title (18 / 800), its line (12 muted) and an
 * optional [extra] at the end (12 / 700 dim, e.g. "Hold to reorder").
 */
@Composable
fun StageSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    eyebrow: String? = null,
    extra: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val palette = LocalStagePalette.current
    Row(
        modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            eyebrow?.let { StageLabel(it, Modifier.padding(bottom = 3.dp)) }
            Text(title, style = stageText(18f, 800), color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = stageText(12f, 500), color = palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
        }
        extra?.let { Text(it, style = stageText(12f, 700), color = palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * `.mi`: a 46 dp sheet menu item — icon (18, muted), label (14.5 / 600), then at the end a [value] (`em`),
 * a [count] (`.n`), [tags] or a ✓ when [checked]. [selected] is the accent text; [danger] the danger
 * colour; pressed fills it with the accent (`.mi.f`), as does [filled] for the chosen item of a picker
 * (the playlist sheet draws its current playlist so). [leading] replaces the icon (a radio in a choice
 * sheet, with a 10 dp gap). [mark] ends a category row (`.pm` after the count).
 */
@Composable
fun StageMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    count: String? = null,
    checked: Boolean = false,
    selected: Boolean = false,
    danger: Boolean = false,
    filled: Boolean = false,
    enabled: Boolean = true,
    role: Role = Role.Button,
    leading: (@Composable () -> Unit)? = null,
    tags: (@Composable RowScope.() -> Unit)? = null,
    mark: tv.own.owntv.core.ui.stage.PlaylistMark? = null,
    onLongClick: (() -> Unit)? = null,
    /** More's rows are 48 high and end in a dim 13 dp chevron. */
    height: Dp = 46.dp,
    chevron: Boolean = false,
    /**
     * Tighter padding, a smaller icon and a smaller label — for a column a third of a screen wide,
     * where the full row left the name two characters and a marquee.
     */
    compact: Boolean = false,
) {
    val palette = LocalStagePalette.current
    val accent = stageAccent
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState().value || filled
    val shape = RoundedCornerShape(StageRadii.MenuItem)
    val fg: Color
    val iconTint: Color
    when {
        pressed -> { fg = accent.onAccent; iconTint = accent.onAccent }
        danger -> { fg = palette.danger; iconTint = palette.danger }
        selected -> { fg = accent.accent; iconTint = accent.accent }
        else -> { fg = palette.item; iconTint = palette.muted }
    }
    val sideTint = if (pressed) accent.onAccent else palette.dim
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            // Not available here (no trailer, not in history): the whole row at 45%.
            .then(if (enabled) Modifier else Modifier.alpha(0.45f))
            .clip(shape)
            .then(if (pressed) Modifier.background(accent.accent) else Modifier)
            .stageClickable(interaction, onClick.takeIf { enabled }, onLongClick?.takeIf { enabled }, role)
            .padding(horizontal = if (compact) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(
            when {
                leading != null -> 10.dp
                compact -> 8.dp
                else -> 12.dp
            },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> leading()
            icon != null -> StageIcon(icon, if (compact) 15.dp else 18.dp, iconTint)
        }
        Text(
            label,
            style = stageText(if (compact) 13f else 14.5f, 600),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Takes the free width, so whatever follows sits at the end (`margin-left: auto`). With a [value]
            // (a hint like "The order your playlist uses") the label is measured first and the hint shortens.
            modifier = (if (value != null) Modifier else Modifier.weight(1f))
                .then(if (LocalScrollLongTitles.current) Modifier.basicMarquee() else Modifier),
        )
        if (value != null) Spacer(Modifier.weight(1f))
        // 6 dp between the trailing parts (`.mi .tag + .n`).
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            tags?.let { Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = it) }
            value?.let { Text(it, style = stageText(if (compact) 11.5f else 12f, 700), color = sideTint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            count?.let { Text(it, style = stageText(if (compact) 11.5f else 12f, 600), color = sideTint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (checked) StageIcon(MobileIcons.Check, 18.dp, if (pressed) accent.onAccent else accent.accent)
            // On a filled row the playlist's own colour would vanish into the accent: it takes the row's text colour.
            mark?.let { StagePlaylistMark(it, Modifier.padding(start = 2.dp), tint = if (pressed) accent.onAccent else null) }
            if (chevron) StageIcon(MobileIcons.KeyboardArrowRight, 13.dp, sideTint)
        }
    }
}

/**
 * A form field as a row: the field's name, under it what is typed (or the example, dim), and a tap opens
 * a sheet with the field — Save keeps it. [password] shows dots in the row and masks the field.
 */
@Composable
fun StageTextRow(
    icon: ImageVector,
    label: String,
    value: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    current: Boolean = false,
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    val palette = LocalStagePalette.current
    StageSettingRow(
        title = label,
        modifier = modifier,
        icon = icon,
        subtitle = when {
            value.isBlank() -> placeholder.ifBlank { null }
            password -> "•".repeat(value.length.coerceAtMost(12))
            else -> value
        },
        subtitleColor = if (value.isBlank()) palette.dim else palette.text,
        current = current,
        onClick = { editing = true },
        trailing = trailing,
    )
    if (editing) {
        var text by remember { mutableStateOf(value) }
        // The field takes the keyboard as the sheet opens: a sheet for one field is there to type in.
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(150)
            runCatching { focus.requestFocus() }
        }
        MobileBottomSheet(onDismissRequest = { editing = false }) {
            StageSheetHeader(label)
            StageSearchField(
                value = text,
                onValueChange = { text = it },
                placeholder = placeholder,
                icon = null,
                password = password,
                keyboardType = keyboardType,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                onSearch = { onSave(text.trim()); editing = false },
                modifier = Modifier.padding(horizontal = 6.dp).focusRequester(focus),
            )
            StageConfirmItems(
                confirm = stringResource(R.string.common_save),
                confirmEnabled = true,
                onConfirm = { onSave(text.trim()) },
                onDismiss = { editing = false },
            )
        }
    }
}
