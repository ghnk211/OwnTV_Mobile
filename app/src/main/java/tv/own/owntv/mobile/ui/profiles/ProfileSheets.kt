package tv.own.owntv.mobile.ui.profiles

import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.StageField
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageSheetHeader

/** A PIN is four to six digits — long enough to be a lock, short enough to type one-handed. */
internal const val PIN_MIN = 4
internal const val PIN_MAX = 6

/**
 * Ask for a profile's PIN. [onSubmit] returns false when the digits were wrong, which keeps the
 * sheet open and says so rather than dropping the user back where they started with no explanation.
 *
 * A sheet, not a centred dialog: that is what every prompt in this app is, and it is the only form
 * that frosts the same wallpaper the rest of the app frosts — a dialog is its own window and cannot.
 * It also rises to the thumb instead of sitting where the keyboard will cover it.
 */
@Composable
fun ProfilePinSheet(
    profileName: String,
    onSubmit: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val autofill = LocalAutofillManager.current
    fun submit() {
        if (pin.length in PIN_MIN..PIN_MAX) {
            // A profile PIN is not a password to keep: ending the autofill session stops a password
            // manager from offering to save it.
            autofill?.cancel()
            if (!onSubmit(pin)) { wrong = true; pin = "" } else keyboard?.hide()
        }
    }
    val title = stringResource(R.string.profiles_enter_pin, profileName)
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title)
        StageField(
            value = pin,
            onValueChange = {
                if (it.length <= PIN_MAX && it.all(Char::isDigit)) {
                    pin = it
                    wrong = false
                }
            },
            label = stringResource(R.string.profiles_pin),
            placeholder = stringResource(R.string.profiles_pin_placeholder),
            password = true,
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
            isError = wrong,
            supportingText = if (wrong) stringResource(R.string.profiles_wrong_pin) else null,
            onImeDone = { submit() },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        SheetButtons(
            confirm = stringResource(R.string.common_ok),
            confirmEnabled = pin.length >= PIN_MIN,
            // Deliberately not dismissed here: a wrong PIN has to keep the sheet up to say so.
            onConfirm = { submit() },
            onDismiss = {
                keyboard?.hide()
                focusManager.clearFocus()
                onDismiss()
            },
        )
    }
}

/** Confirm (✓, filled once it can be used) and Cancel (✕) — how these sheets end. */
@Composable
private fun SheetButtons(
    confirm: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    StageMenuItem(
        confirm,
        onClick = onConfirm,
        icon = MobileIcons.Check,
        filled = confirmEnabled,
        enabled = confirmEnabled,
        modifier = Modifier.padding(top = 6.dp),
    )
    StageMenuItem(stringResource(R.string.common_cancel), onClick = onDismiss, icon = MobileIcons.Close)
}
