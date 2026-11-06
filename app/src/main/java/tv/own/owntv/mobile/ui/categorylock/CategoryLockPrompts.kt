package tv.own.owntv.mobile.ui.categorylock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.categorylock.CategoryLockPrompt
import tv.own.owntv.core.categorylock.CategoryLockUi
import tv.own.owntv.core.categorylock.PinAttempt
import tv.own.owntv.core.categorylock.PinAttemptLimiter
import tv.own.owntv.core.categorylock.SealedCategoryAction
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.util.Pin
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.screens.settings.customize.TextPromptDialog

/**
 * The category-lock PIN sheets of section [type]. [onClosed] hears every sheet that goes away, with
 * whether its PIN opened the category. A sheet asked for by a menu action on a sealed category instead
 * runs that action through [onAction] once the PIN opens the category.
 */
@Composable
internal fun CategoryLockPrompts(
    type: MediaType,
    onAction: (key: LiveKey, action: SealedCategoryAction) -> Unit,
    onClosed: (key: LiveKey, opened: Boolean) -> Unit = { _, _ -> },
    vm: CategoryLockViewModel = koinViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val latestOnAction by rememberUpdatedState(onAction)
    val latestOnClosed by rememberUpdatedState(onClosed)
    LaunchedEffect(vm, type) {
        vm.closed.collect {
            if (it.type != type) return@collect
            val then = it.then
            if (it.opened && then != null) latestOnAction(it.key, then)
            latestOnClosed(it.key, it.opened && then == null)
        }
    }
    val prompt = ui.prompt?.takeIf { it.type == type } ?: return
    key(ui.attempt) {
        PinEntrySheet(title = promptTitle(prompt, ui), onSubmit = vm::submit, onDismiss = vm::dismiss)
    }
}

@Composable
private fun promptTitle(prompt: CategoryLockPrompt, ui: CategoryLockUi): String = when {
    prompt is CategoryLockPrompt.CreatePin -> stringResource(
        when {
            prompt.firstPin == null -> R.string.settings_customize_new_pin
            prompt.mismatch -> R.string.settings_customize_pin_mismatch
            else -> R.string.settings_customize_confirm_pin
        },
    )
    else -> pinEntryTitle(wrong = ui.wrong, waitSeconds = ui.waitSeconds)
}

/** The title of a Customize PIN entry: the wait while entries are refused, else whether the last one was wrong. */
@Composable
internal fun pinEntryTitle(wrong: Boolean, waitSeconds: Int): String = when {
    waitSeconds > 0 -> pluralStringResource(R.plurals.content_category_pin_wait, waitSeconds, waitSeconds)
    wrong -> stringResource(R.string.settings_customize_wrong_pin)
    else -> stringResource(R.string.settings_customize_enter_pin)
}

/** One PIN entry sheet; a new [title] starts it again with an empty field. */
@Composable
internal fun PinEntrySheet(title: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit, description: String? = null) {
    TextPromptDialog(
        title = title,
        initial = "",
        description = description,
        allowBlank = false,
        confirmLabel = stringResource(R.string.common_ok),
        // A PIN is not a name: it is masked as it is typed, and the field asks for a number pad. Left
        // as a plain prompt, the category lock and the Settings door printed the digits on screen.
        password = true,
        keyboardType = KeyboardType.NumberPassword,
        onConfirm = onSubmit,
        onDismiss = onDismiss,
    )
}

/**
 * A door behind the profile's Customize PIN: Customize itself, Settings, Backup & Restore, Local sync.
 * [pin] is the stored value. This PIN also takes the category locks off, so wrong entries count against
 * the same [PinAttemptLimiter] as the locks: guessing at any door earns the same wait.
 */
@Composable
internal fun PinDoorSheet(pin: String, onUnlock: () -> Unit, onDismiss: () -> Unit, description: String? = null) {
    val limiter: PinAttemptLimiter = koinInject()
    var wrong by remember { mutableStateOf(false) }
    var waitSeconds by remember { mutableIntStateOf(limiter.waitSeconds()) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(waitSeconds > 0) {
        while (waitSeconds > 0) {
            delay(1_000)
            waitSeconds = limiter.waitSeconds()
        }
    }
    key(attempt) {
        PinEntrySheet(
            title = pinEntryTitle(wrong = wrong, waitSeconds = waitSeconds),
            description = description,
            onSubmit = { entered ->
                when (val result = limiter.attempt { Pin.verifyCustomize(entered, pin) }) {
                    PinAttempt.Accepted -> onUnlock()
                    PinAttempt.Wrong -> { wrong = true; attempt++ }
                    is PinAttempt.Wait -> { wrong = false; waitSeconds = result.seconds; attempt++ }
                }
            },
            onDismiss = onDismiss,
        )
    }
}
