package tv.own.owntv.mobile.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.BrandMark
import tv.own.owntv.mobile.ui.components.Wordmark
import tv.own.owntv.mobile.ui.components.rememberAppliedIcon
import tv.own.owntv.mobile.ui.shell.ShellAvatar
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageField
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageBackground
import tv.own.owntv.mobile.ui.stage.stageText

/** The ring the chooser drew around the current profile, kept for the lock screen's avatar. */
private val AVATAR_SIZE = 104.dp

/** Five wrong digits and the button waits this long, so a 4–6 digit PIN is not a 10-second brute force. */
private const val FAILURE_LIMIT = 5
private const val COOLDOWN_SECONDS = 30

/**
 * The app lock: one profile, one PIN, and no way past it.
 *
 * This replaced the "Who's watching?" chooser when multi-profile management went. What it still is:
 * a screen shown *instead of* the app rather than over it, so there is no back gesture, no deep link
 * and no notification tap that can reveal a library behind a dialog. What it no longer is: a picker.
 * The name and the avatar are only here to say whose lock this is, and there is no "add profile",
 * no other face to tap, and no link to a forgotten PIN — the plan's one known gap (see §5.12.6).
 *
 * The PIN is verified off the main thread: it is a salted hash, and hashing inside composition would
 * put the frame the user is watching right where they typed.
 */
@Composable
fun ProfileGate(
    profile: ProfileEntity,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ProfilesViewModel,
) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var failures by remember { mutableIntStateOf(0) }
    var cooling by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val autofill = LocalAutofillManager.current
    val palette = LocalStagePalette.current
    val accent = stageAccent

    LaunchedEffect(cooling) {
        if (cooling > 0) {
            delay(1_000)
            cooling -= 1
        }
    }

    fun submit(entry: String) {
        if (entry.length !in PIN_MIN..PIN_MAX || cooling > 0) return
        scope.launch {
            val ok = withContext(Dispatchers.Default) { vm.verifyPin(profile, entry) }
            if (ok) {
                // A PIN is not a password to keep, and the digits must not outlive the screen.
                autofill?.cancel()
                pin = ""
                keyboard?.hide()
                focusManager.clearFocus()
                onUnlocked()
            } else {
                pin = ""
                wrong = true
                failures += 1
                if (failures >= FAILURE_LIMIT) {
                    cooling = COOLDOWN_SECONDS
                    failures = 0
                }
            }
        }
    }

    val heading = stringResource(R.string.profiles_enter_pin, profile.name)
    Column(
        modifier = modifier
            .fillMaxSize()
            .stageBackground()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 34.dp, end = 34.dp, top = 64.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(palette.ink),
                contentAlignment = Alignment.Center,
            ) { BrandMark(rememberAppliedIcon(), 28.dp) }
            Wordmark(110.dp)
        }
        Box(
            Modifier
                .padding(top = 56.dp)
                .drawBehind {
                    val r = size.minDimension / 2
                    drawCircle(accent.accent.copy(alpha = 0.3f), r + 7.dp.toPx())
                    drawCircle(accent.focus, r + 3.dp.toPx(), style = Stroke(2.dp.toPx()))
                },
        ) {
            ShellAvatar(profile, onClick = null, diameter = AVATAR_SIZE)
        }
        Text(
            text = profile.name,
            style = stageText(19f, 800),
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            text = heading,
            style = stageText(12.5f, 500),
            color = palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 26.dp),
        )
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
            supportingText = when {
                cooling > 0 -> pluralStringResource(R.plurals.mobile_unlock_too_many_attempts, cooling, cooling)
                wrong -> stringResource(R.string.profiles_wrong_pin)
                else -> null
            },
            onImeDone = { submit(pin) },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        StageButton(
            text = stringResource(R.string.mobile_unlock),
            onClick = { submit(pin) },
            enabled = cooling == 0 && pin.length >= PIN_MIN,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        )
    }
}
