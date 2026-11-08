package tv.own.owntv.mobile.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/** What the finger turned out to be doing. Decided once per gesture and never revised. */
private enum class Mode { UNDECIDED, SCRUB, LEFT_COLUMN, RIGHT_COLUMN, MIDDLE_COLUMN, PINCH, HOLD, LONG_PRESS }

/**
 * How far up or down the middle of the screen a finger has to travel before it counts as a swipe.
 *
 * A seventh of the screen — far enough that it cannot happen by accident, short enough to be one
 * comfortable thumb movement. Brightness and volume are deliberately NOT held to this: they are
 * continuous, they show what they are doing as they do it, and a small adjustment is the normal use.
 */
private const val SWIPE_FRACTION = 0.15f

/**
 * Every touch gesture the player understands, in one pass over the pointer stream.
 *
 * One loop rather than a stack of `detectXGestures` modifiers, because those all consume the same
 * events and the last one registered wins: a pinch handler and a drag handler layered together give
 * a player where the volume slide sometimes zooms. Here the gesture is classified once — by how many
 * fingers are down, where they landed and which way they moved — and then keeps that meaning until
 * the finger lifts.
 *
 * The screen is read in vertical thirds: the outer two adjust brightness and volume, the middle one
 * is where a swipe down docks the player and a swipe up opens the channel list. **No gesture is the
 * only way to reach anything** — every one of these has a button on the controls as well.
 */
fun Modifier.playerGestures(
    onTap: () -> Unit,
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit,
    onScrub: (deltaFraction: Float) -> Unit,
    onScrubEnd: () -> Unit,
    onBrightness: (deltaFraction: Float) -> Unit,
    onVolume: (deltaFraction: Float) -> Unit,
    onPinch: (zoomIn: Boolean) -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeUp: () -> Unit,
    onSpeedHold: (held: Boolean) -> Unit,
    onTwoFingerTap: () -> Unit,
    sensitivity: Float = 1f,
    /**
     * A tap in the left third, when it means something other than the screen's own tap. Live TV
     * uses it to bring out the categories/channels panel; everywhere else it is null and a tap is
     * just a tap.
     */
    onTapLeft: (() -> Unit)? = null,
    /**
     * A hold that never moved, begun in the right half — the live channel's settings panel.
     *
     * The gesture is already classified here, so this is a branch of [onSpeedHold]'s own timeout
     * rather than a second `combinedClickable` on top: a second detector would have taken the taps
     * it covered with it, and tap-to-show-controls would have stopped working on that half.
     */
    onLongPressRight: (() -> Unit)? = null,
): Modifier = pointerInput(sensitivity, onTapLeft != null, onLongPressRight != null) {
    val slop = viewConfiguration.touchSlop
    val longPress = viewConfiguration.longPressTimeoutMillis
    val doubleTap = viewConfiguration.doubleTapTimeoutMillis
    var lastTapUpMs = 0L
    var lastTapX = 0f

    awaitEachGesture {
        val down = awaitFirstDown()
        val startX = down.position.x
        var mode = Mode.UNDECIDED
        var travel = Offset.Zero
        var pinchStart = 0f
        var twoFingers = false

        // A finger that neither moves nor lifts within the long-press timeout is holding for 2× speed —
        // or, on the right half of a live channel, asking for the settings panel.
        val firstEvent = withTimeoutOrNull(longPress) { awaitPointerEvent() }
        if (firstEvent == null) {
            if (onLongPressRight != null && startX > size.width / 2f) {
                mode = Mode.LONG_PRESS
                onLongPressRight()
            } else {
                mode = Mode.HOLD
                onSpeedHold(true)
            }
        } else {
            travel += firstEvent.changes.firstOrNull()?.positionChange() ?: Offset.Zero
        }

        var event = firstEvent
        while (true) {
            if (event != null) {
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    twoFingers = true
                    val distance = (pressed[0].position - pressed[1].position).getDistance()
                    if (pinchStart == 0f) {
                        pinchStart = distance
                    } else if (mode != Mode.PINCH && abs(distance - pinchStart) > slop * 2) {
                        mode = Mode.PINCH
                        onPinch(distance > pinchStart)
                    }
                } else if (mode != Mode.HOLD && mode != Mode.PINCH && mode != Mode.LONG_PRESS) {
                    val change: PointerInputChange = pressed[0]
                    val delta = change.positionChange()
                    travel += delta
                    if (mode == Mode.UNDECIDED && travel.getDistance() > slop) {
                        mode = if (abs(travel.x) > abs(travel.y)) {
                            Mode.SCRUB
                        } else {
                            when {
                                startX < size.width / 3f -> Mode.LEFT_COLUMN
                                startX > size.width * 2f / 3f -> Mode.RIGHT_COLUMN
                                else -> Mode.MIDDLE_COLUMN
                            }
                        }
                    }
                    when (mode) {
                        // Sensitivity scales how far the value moves, not how far the finger has to
                        // travel to be recognised: the slop above stays the system's, so a drag is
                        // still a drag at 50% and the setting only changes what it is worth.
                        Mode.SCRUB -> onScrub(delta.x / size.width * sensitivity)
                        // Up is more, which is why the sign flips: dragging toward the top of the
                        // screen raises brightness and volume, as it does everywhere else on a phone.
                        Mode.LEFT_COLUMN -> onBrightness(-delta.y / size.height * sensitivity)
                        Mode.RIGHT_COLUMN -> onVolume(-delta.y / size.height * sensitivity)
                        else -> Unit
                    }
                    if (mode != Mode.UNDECIDED) change.consume()
                }
            }
            event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
        }

        when (mode) {
            Mode.HOLD -> onSpeedHold(false)
            // The panel is already up; letting go is the end of the hold and nothing else.
            Mode.LONG_PRESS -> Unit
            Mode.SCRUB -> onScrubEnd()
            // A swipe has to be a real one. What classified this gesture was the system's touch slop
            // — about three millimetres — so *any* slip past it counted: reaching for the pause
            // button with a thumb that slid on the way shrank the player into the mini window, or
            // threw the channel list up over the picture. Both are whole-screen actions and neither
            // is what that finger meant.
            Mode.MIDDLE_COLUMN -> if (abs(travel.y) > size.height * SWIPE_FRACTION) {
                if (travel.y > 0) onSwipeDown() else onSwipeUp()
            }
            Mode.PINCH -> Unit
            Mode.LEFT_COLUMN, Mode.RIGHT_COLUMN -> Unit
            Mode.UNDECIDED -> {
                // Nothing moved far enough to be a drag, so it was a tap of some kind.
                val now = System.currentTimeMillis()
                when {
                    twoFingers -> onTwoFingerTap()
                    now - lastTapUpMs < doubleTap && abs(startX - lastTapX) < size.width / 4f -> {
                        lastTapUpMs = 0L // a third tap starts a new pair rather than skipping again
                        if (startX < size.width / 2f) onDoubleTapLeft() else onDoubleTapRight()
                    }
                    else -> {
                        // A left tap that has its own meaning is not half of a double tap: adding
                        // the panel and then skipping would answer the second tap with a gesture
                        // nobody made.
                        val leftTap = onTapLeft?.takeIf { startX < size.width / 3f }
                        lastTapUpMs = if (leftTap != null) 0L else now
                        lastTapX = startX
                        if (leftTap != null) leftTap() else onTap()
                    }
                }
            }
        }
    }
}
