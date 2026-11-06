package tv.own.owntv.mobile.ui.screens.settings.customize

import tv.own.owntv.mobile.ui.components.MobileIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.own.owntv.mobile.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.screens.settings.SettingsChoice
import tv.own.owntv.mobile.ui.screens.settings.SheetText
import tv.own.owntv.mobile.ui.screens.settings.StageChoiceSheet
import tv.own.owntv.mobile.ui.screens.settings.StageConfirmSheet
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageRadio
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageSwitch
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * One destination row in the "Move to…" dialog: a user-created custom category plus how many items
 * it currently holds.
 */
data class MoveTarget(val id: String, val displayName: String, val count: Int)

/**
 * Ask for one line of text — rename a category, name a new one, type a rule's value — as a Stage sheet: the
 * title, an optional [description], the field with its [hint] under it, then the confirm and Cancel.
 *
 * [allowBlank] is what makes a rename able to undo itself: clearing the field and saving restores the
 * provider's own name, which is otherwise unreachable once a custom one is set.
 */
@Composable
fun TextPromptDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    description: String? = null,
    hint: String? = null,
    allowBlank: Boolean = true,
    confirmLabel: String = stringResource(R.string.common_save),
    /** A PIN: masked on screen, digits on the keyboard, and no autofill suggestions. */
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    extraButton: (@Composable () -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    val enabled = allowBlank || text.isNotBlank()
    MobileBottomSheet(onDismissRequest = onDismiss) {
        StageSheetHeader(title)
        SheetText(description)
        StageSearchField(
            value = text,
            onValueChange = { text = it },
            placeholder = title,
            icon = null,
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
            password = password,
            onSearch = { if (enabled) onConfirm(text) },
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        hint?.let { Text(it, style = stageText(11.5f, 500), color = LocalStagePalette.current.muted, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp)) }
        Box(Modifier.size(1.dp, 6.dp))
        StageMenuItem(confirmLabel, onClick = { onConfirm(text) }, icon = MobileIcons.Check, filled = enabled, enabled = enabled)
        extraButton?.let { Row(Modifier.padding(horizontal = 4.dp)) { it() } }
        StageMenuItem(stringResource(R.string.common_cancel), onClick = onDismiss, icon = MobileIcons.Close)
    }
}

/** A short confirm — delete a category, restore original names, refuse an oversized rename. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The callers close it themselves in onConfirm, so the sheet's own dismiss after confirming is a no-op for them.
    StageConfirmSheet(title = title, text = message, confirm = confirmLabel, onConfirm = onConfirm, onDismiss = onDismiss)
}

/** A one-of-these picker, for the pickers that open on top of another sheet. */
@Composable
fun <T> OptionsDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    StageChoiceSheet(
        eyebrow = null,
        title = title,
        text = null,
        choices = options.map { (value, label) -> SettingsChoice<T?>(value, label) },
        selected = selected,
        onSelect = { it?.let(onSelect) },
        onDismiss = onDismiss,
    )
}

/**
 * Pick a custom category to move an item into, and decide whether the item also stays where it is.
 *
 * Moving out of a provider folder never deletes anything: the item keeps its place in All, in search and
 * in recents — it simply stops appearing in the folder it came from.
 */
@Composable
fun MoveToCategoryDialog(
    moveTargets: List<MoveTarget>,
    originName: String,
    onNewCategory: () -> Unit,
    onMove: (targetId: String, keepInOrigin: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTarget by remember { mutableStateOf<String?>(null) }
    var keepInOrigin by remember { mutableStateOf(false) }
    MobileBottomSheet(onDismissRequest = onDismiss) {
        StageSheetHeader(stringResource(R.string.settings_move_category_title))
        SheetText(stringResource(R.string.settings_move_category_description, originName))
        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            StageMenuItem(stringResource(R.string.settings_move_category_new), onClick = onNewCategory, icon = MobileIcons.Add)
            moveTargets.forEach { target ->
                StageMenuItem(
                    target.displayName,
                    onClick = { selectedTarget = target.id },
                    selected = selectedTarget == target.id,
                    leading = { StageRadio(selectedTarget == target.id) },
                    count = stringResource(R.string.common_number_grouped, target.count),
                    role = Role.RadioButton,
                )
            }
        }
        StageSettingRow(
            title = stringResource(R.string.settings_move_category_keep, originName),
            minHeight = 48.dp,
            onClick = { keepInOrigin = !keepInOrigin },
        ) { StageSwitch(keepInOrigin) }
        val enabled = selectedTarget != null
        Box(Modifier.size(1.dp, 6.dp))
        StageMenuItem(
            stringResource(R.string.settings_move_category_action),
            onClick = { selectedTarget?.let { onMove(it, keepInOrigin) } },
            icon = MobileIcons.Check,
            filled = enabled,
            enabled = enabled,
        )
        StageMenuItem(stringResource(R.string.common_cancel), onClick = onDismiss, icon = MobileIcons.Close)
    }
}
