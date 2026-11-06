package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.profiles.PIN_MAX
import tv.own.owntv.mobile.ui.profiles.PIN_MIN
import tv.own.owntv.mobile.ui.profiles.ProfilePinSheet
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageField
import tv.own.owntv.mobile.ui.stage.StageGroupLabel
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * Settings › App › App lock: the PIN this app asks for before it shows anything.
 *
 * It is the profile's own PIN — the same one that opens the locked categories and answers the
 * Settings PIN gate — so this page edits that row rather than keeping a second secret in step with
 * it. Setting it asks for two matching digits; changing or removing it asks for the current ones
 * first, because the screen that can turn a lock off is not a screen that should be able to.
 *
 * Saving does not lock the app now. This session has already passed the gate, and dropping a PIN
 * prompt in the middle of somebody's film would make the setting something to be afraid of. It
 * takes effect on the next launch.
 */
@Composable
fun SettingsAppLockPage(vm: SettingsViewModel = koinViewModel(), modifier: Modifier = Modifier) {
    val profile by vm.activeProfile.collectAsStateWithLifecycle()
    val palette = LocalStagePalette.current
    // Which sheet is up, if any: a new PIN, a check of the current one before changing it, or the same
    // check before removing it. Three flags that can each be true on their own would let two open.
    var form by remember { mutableStateOf(false) }
    var verifyFor by remember { mutableStateOf(VerifyFor.NONE) }
    val locked = profile?.pinHash != null
    val name = profile?.name.orEmpty()

    SettingsPage(modifier) {
        item(key = "about") {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
                Text(
                    text = stringResource(R.string.mobile_app_lock_description),
                    style = stageText(13f, 600),
                    color = palette.text,
                )
                Text(
                    text = stringResource(R.string.mobile_app_lock_warning),
                    style = stageText(12f, 500),
                    color = palette.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        settingsGroup(key = "app-lock") {
            StageGroupLabel(stringResource(R.string.mobile_app_lock))
            if (!locked) {
                StageMenuItem(
                    label = stringResource(R.string.mobile_app_lock_set),
                    onClick = { form = true },
                    icon = MobileIcons.Lock,
                    height = 48.dp,
                    chevron = true,
                )
            } else {
                StageMenuItem(
                    label = stringResource(R.string.mobile_app_lock_change),
                    onClick = { verifyFor = VerifyFor.CHANGE },
                    icon = MobileIcons.Lock,
                    height = 48.dp,
                    chevron = true,
                )
                StageMenuItem(
                    label = stringResource(R.string.mobile_app_lock_remove),
                    onClick = { verifyFor = VerifyFor.REMOVE },
                    icon = MobileIcons.Close,
                    height = 48.dp,
                    danger = true,
                )
            }
        }
    }

    val current = profile
    // Room has not answered yet, so there is no row to verify a PIN against — a sheet that cannot
    // check the digits it is asking for is worse than the frame it waits for.
    if (current != null) {
        if (form) {
            AppLockPinSheet(
                title = stringResource(if (locked) R.string.mobile_app_lock_change else R.string.mobile_app_lock_set),
                onConfirm = { vm.setAppLockPin(it) },
                onDismiss = { form = false },
            )
        }
        when (verifyFor) {
            VerifyFor.NONE -> Unit
            VerifyFor.CHANGE -> ProfilePinSheet(
                profileName = name,
                onSubmit = { pin ->
                    vm.verifyPin(current, pin).also { if (it) { verifyFor = VerifyFor.NONE; form = true } }
                },
                onDismiss = { verifyFor = VerifyFor.NONE },
            )
            VerifyFor.REMOVE -> ProfilePinSheet(
                profileName = name,
                onSubmit = { pin ->
                    vm.verifyPin(current, pin).also { if (it) { vm.setAppLockPin(""); verifyFor = VerifyFor.NONE } }
                },
                onDismiss = { verifyFor = VerifyFor.NONE },
            )
        }
    }
}

/** What the current-PIN check is being asked for: to replace the digits, or to take the lock off. */
private enum class VerifyFor { NONE, CHANGE, REMOVE }

/**
 * New digits, typed twice: a PIN nobody can read back is a lock with no key, so the form does not
 * confirm until the two agree. The check is on length as well — four to six digits, the same range
 * the lock screen accepts.
 */
@Composable
private fun AppLockPinSheet(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val mismatch = again.isNotEmpty() && again != pin
    val ready = pin.length in PIN_MIN..PIN_MAX && again == pin
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title)
        StageField(
            value = pin,
            onValueChange = { if (it.length <= PIN_MAX && it.all(Char::isDigit)) pin = it },
            label = stringResource(R.string.mobile_app_lock_new),
            placeholder = stringResource(R.string.profiles_pin_digits),
            password = true,
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Next,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        StageField(
            value = again,
            onValueChange = { if (it.length <= PIN_MAX && it.all(Char::isDigit)) again = it },
            label = stringResource(R.string.mobile_app_lock_confirm),
            placeholder = stringResource(R.string.profiles_pin_digits),
            password = true,
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
            isError = mismatch,
            supportingText = if (mismatch) stringResource(R.string.settings_customize_pin_mismatch) else null,
            onImeDone = { if (ready) { onConfirm(pin); onDismiss() } },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        StageMenuItem(
            stringResource(R.string.common_ok),
            onClick = { onConfirm(pin); onDismiss() },
            icon = MobileIcons.Check,
            filled = ready,
            enabled = ready,
            modifier = Modifier.padding(top = 6.dp),
        )
        StageMenuItem(stringResource(R.string.common_cancel), onClick = onDismiss, icon = MobileIcons.Close)
    }
}
