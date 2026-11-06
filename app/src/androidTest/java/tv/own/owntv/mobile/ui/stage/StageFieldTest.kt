package tv.own.owntv.mobile.ui.stage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Rule
import org.junit.Test

/**
 * Keyboard + focus regression tests for the phone text entry.
 *
 * Covers the acceptance criteria that can be asserted without a physical IME:
 * tapping focuses, typed/pasted/deleted text updates, Next/Done/Search actions behave,
 * and a focused field inside a scrolling IME-safe container stays visible.
 */
class StageFieldTest {

    @get:Rule
    val rule = createComposeRule()

    // A field's caller modifiers land on the label-plus-input Column, so that wrapper never carries
    // the editable semantics (RequestFocus, PerformImeAction, Focused); select the text node.
    private fun field() = rule.onNode(hasSetTextAction())

    private fun fieldAt(index: Int) = rule.onAllNodes(hasSetTextAction())[index]

    @Test
    fun tappingFieldFocusesIt() {
        rule.setContent {
            MaterialTheme {
                StageField(
                    value = "",
                    onValueChange = {},
                    label = "Name",
                )
            }
        }
        field().performClick()
        field().assertIsFocused()
    }

    @Test
    fun typedPastedAndDeletedTextUpdates() {
        var text by mutableStateOf("")
        rule.setContent {
            MaterialTheme {
                StageField(
                    value = text,
                    onValueChange = { text = it },
                    label = "Name",
                )
            }
        }
        // Typed.
        field().performTextInput("Hello")
        rule.runOnIdle { assert(text == "Hello") }
        // Pasted (a multi-word replacement behaves like a paste).
        field().performTextClearance()
        field().performTextInput("Hello World pasted")
        rule.runOnIdle { assert(text == "Hello World pasted") }
        // Deleted.
        field().performTextClearance()
        rule.runOnIdle { assert(text.isEmpty()) }
    }

    @Test
    fun pinFieldKeepsDigitsOnly() {
        var pin by mutableStateOf("")
        rule.setContent {
            MaterialTheme {
                StageField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 6 && it.all(Char::isDigit)) pin = it
                    },
                    label = "PIN",
                    keyboardType = KeyboardType.NumberPassword,
                    password = true,
                    imeAction = ImeAction.Done,
                )
            }
        }
        // Digits are accepted, exactly as a numeric IME sends them.
        field().performTextInput("1234")
        rule.runOnIdle { assert(pin == "1234") }
        // A non-digit edit is rejected whole rather than partially applied.
        field().performTextClearance()
        field().performTextInput("12ab")
        rule.runOnIdle { assert(pin.isEmpty()) }
    }

    @Test
    fun searchImeActionCallsOnImeDone() {
        var searched = false
        rule.setContent {
            MaterialTheme {
                StageField(
                    value = "cnn",
                    onValueChange = {},
                    label = "Search",
                    imeAction = ImeAction.Search,
                    onImeDone = { searched = true },
                )
            }
        }
        field().performClick()
        field().performImeAction()
        rule.runOnIdle { assert(searched) }
    }

    @Test
    fun nextImeActionMovesFocusForward() {
        val first = FocusRequester()
        val second = FocusRequester()
        rule.setContent {
            MaterialTheme {
                Column {
                    StageField(
                        value = "",
                        onValueChange = {},
                        label = "First",
                        imeAction = ImeAction.Next,
                        modifier = Modifier.focusRequester(first),
                    )
                    StageField(
                        value = "",
                        onValueChange = {},
                        label = "Second",
                        imeAction = ImeAction.Done,
                        modifier = Modifier.focusRequester(second),
                    )
                }
            }
        }
        fieldAt(0).performClick()
        fieldAt(0).assertIsFocused()
        fieldAt(0).performImeAction()
        fieldAt(1).assertIsFocused()
    }

    @Test
    fun focusedFieldInScrollingImeSafeFormStaysVisible() {
        rule.setContent {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                ) {
                    repeat(20) {
                        StageField(
                            value = "",
                            onValueChange = {},
                            label = "Field $it",
                            imeAction = if (it < 19) ImeAction.Next else ImeAction.Done,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        // Scrolling forms keep every field reachable and focusable with the keyboard up.
        fieldAt(19).performScrollTo()
        fieldAt(19).performClick()
        fieldAt(19).assertIsFocused()
        fieldAt(19).assertIsDisplayed()
        rule.onNodeWithText("Field 19").assertIsDisplayed()
    }
}
