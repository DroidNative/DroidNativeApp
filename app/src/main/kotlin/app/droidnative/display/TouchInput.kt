package app.droidnative.display

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.droidnative.apps.MouseMode
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Touchscreen input for one window in the chosen [MouseMode]. Coordinates
 * are view pixels; [scale] converts them to buffer pixels for the compositor.
 *
 * MOUSE (emulated mouse clicks): the finger is the pointer. Tap = left
 *   click, drag = left drag, long press = right click.
 * TOUCHPAD: relative movement of a drawn [cursor]. Tap = left click (sent
 *   after a short wait), tap then touch again and move = held left button,
 *   two-finger tap = right click, three-finger tap = middle click.
 * Both: two fingers moving = mouse wheel (content follows the fingers).
 * While a program has locked the pointer ([locked], games and 3D views),
 * movement is sent as relative motion only and the touchpad cursor is
 * hidden; clicks stay where the pointer is.
 */
class TouchInput(
    private val compositor: Compositor,
    private val id: Int,
    private val view: View,
    private val cursor: View?,
    private val mode: MouseMode,
    private val scale: () -> Float,
) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val longPress = ViewConfiguration.getLongPressTimeout().toLong()
    // Finger travel per wheel notch
    private val notch = WHEEL_NOTCH_DP * view.resources.displayMetrics.density

    // Gesture in progress
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var leftDown = false
    private var consumed = false          // long press already handled
    private var multi = false             // more than one finger was down
    private var maxPointers = 0
    private var wheelX = 0f               // finger travel not yet sent as notches
    private var wheelY = 0f

    // Touchpad cursor and tap handling
    private var cx = -1f
    private var cy = -1f
    private var tapPending = false        // a tap waits: click, or the start of a drag
    private var dragging = false          // tap-drag holds the left button

    /** A program has captured the pointer: relative motion only. */
    var locked = false
        set(value) {
            field = value
            cursor?.visibility = if (value || cx < 0) View.GONE else View.VISIBLE
        }

    private val longPressAction = Runnable {
        if (mode == MouseMode.MOUSE && !moved && !multi && !leftDown) {
            consumed = true
            click(BTN_RIGHT)
        }
    }

    // No second touch followed the tap: it was a click
    private val tapClick = Runnable {
        if (tapPending) {
            tapPending = false
            click(BTN_LEFT)
        }
    }

    fun onTouch(e: MotionEvent): Boolean {
        if (mode == MouseMode.TOUCH) return native(e)
        val t = e.eventTime.toInt()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                downTime = e.eventTime
                moved = false; consumed = false; multi = false; maxPointers = 1
                wheelX = 0f; wheelY = 0f
                if (mode == MouseMode.MOUSE) {
                    if (!locked) motion(e.x, e.y, t)
                    view.postDelayed(longPressAction, longPress)
                } else {
                    ensureCursor()
                    if (tapPending) {
                        // Touch again right after a tap: hold the button
                        view.removeCallbacks(tapClick)
                        tapPending = false
                        dragging = true
                        press(true, t)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                multi = true
                view.removeCallbacks(longPressAction)
                if (leftDown) press(false, t)
                dragging = false
                lastX = avgX(e); lastY = avgY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    val x = avgX(e); val y = avgY(e)
                    if (hypot(x - downX, y - downY) > slop) moved = true
                    if (e.pointerCount == 2) wheel(lastX - x, lastY - y, t)
                    lastX = x; lastY = y
                    return true
                }
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                    moved = true
                    view.removeCallbacks(longPressAction)
                    if (mode == MouseMode.MOUSE && !consumed) press(true, t)   // drag from where it began
                }
                if (locked) {
                    if (moved && !consumed) {
                        val speed = if (mode == MouseMode.TOUCHPAD) POINTER_SPEED else 1f
                        compositor.pointerRelative(id, (e.x - lastX) * speed * scale(), (e.y - lastY) * speed * scale(), t)
                    }
                } else if (mode == MouseMode.MOUSE) {
                    if (moved && !consumed) motion(e.x, e.y, t)
                } else if (moved) {
                    moveCursor((e.x - lastX) * POINTER_SPEED, (e.y - lastY) * POINTER_SPEED, t)
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // The remaining fingers continue the gesture from where they are
                val rest = (0 until e.pointerCount).filter { it != e.actionIndex }
                lastX = rest.map { e.getX(it) }.average().toFloat()
                lastY = rest.map { e.getY(it) }.average().toFloat()
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(longPressAction)
                val tap = !moved && e.eventTime - downTime < TAP_MS
                when {
                    dragging || leftDown -> { press(false, t); dragging = false }
                    consumed -> {}
                    multi -> if (tap) when (maxPointers) {
                        2 -> click(BTN_RIGHT)
                        3 -> click(BTN_MIDDLE)
                    }
                    mode == MouseMode.MOUSE -> if (!moved) click(BTN_LEFT)
                    tap -> {
                        tapPending = true
                        view.postDelayed(tapClick, DOUBLE_TAP_MS)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPressAction)
                if (leftDown) press(false, t)
                dragging = false
            }
        }
        return true
    }

    /** Window size changed: keep the touchpad cursor inside. */
    fun clampCursor() {
        if (cx < 0) return
        cx = cx.coerceIn(0f, (view.width - 1).toFloat().coerceAtLeast(0f))
        cy = cy.coerceIn(0f, (view.height - 1).toFloat().coerceAtLeast(0f))
        placeCursor()
    }

    private fun native(e: MotionEvent): Boolean {
        val t = e.eventTime.toInt()
        val s = scale()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                compositor.touch(id, Compositor.TOUCH_DOWN, e.getPointerId(i), e.getX(i) * s, e.getY(i) * s, t)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount)
                compositor.touch(id, Compositor.TOUCH_MOTION, e.getPointerId(i), e.getX(i) * s, e.getY(i) * s, t)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                compositor.touch(id, Compositor.TOUCH_UP, e.getPointerId(i), e.getX(i) * s, e.getY(i) * s, t)
            }
            MotionEvent.ACTION_CANCEL -> compositor.touch(id, Compositor.TOUCH_CANCEL, 0, 0f, 0f, t)
        }
        return true
    }

    // Finger travel into wheel notches; dx/dy positive scroll right/down
    private fun wheel(dx: Float, dy: Float, t: Int) {
        wheelX += dx
        wheelY += dy
        val sx = (wheelX / notch).toInt()
        val sy = (wheelY / notch).toInt()
        if (sx == 0 && sy == 0) return
        wheelX -= sx * notch
        wheelY -= sy * notch
        // One axis at a time: diagonal jitter would scroll sideways too
        if (abs(sy) >= abs(sx)) compositor.pointerWheel(id, 0, sy, t)
        else compositor.pointerWheel(id, sx, 0, t)
    }

    private fun ensureCursor() {
        if (cx >= 0) return
        cx = view.width / 2f
        cy = view.height / 2f
        if (!locked) cursor?.visibility = View.VISIBLE
        placeCursor()
        motion(cx, cy, now())
    }

    private fun moveCursor(dx: Float, dy: Float, t: Int) {
        if (abs(dx) < 0.01f && abs(dy) < 0.01f) return
        cx = (cx + dx).coerceIn(0f, (view.width - 1).toFloat())
        cy = (cy + dy).coerceIn(0f, (view.height - 1).toFloat())
        placeCursor()
        motion(cx, cy, t)
    }

    private fun placeCursor() {
        val c = cursor ?: return
        c.translationX = view.left + cx
        c.translationY = view.top + cy
    }

    private fun motion(x: Float, y: Float, t: Int) {
        val s = scale()
        compositor.pointerMotion(id, x * s, y * s, t)
    }

    private fun press(down: Boolean, t: Int) {
        leftDown = down
        compositor.pointerButton(id, BTN_LEFT, down, t)
    }

    private fun click(button: Int) {
        val t = now()
        if (mode == MouseMode.TOUCHPAD && !locked) motion(cx, cy, t)
        compositor.pointerButton(id, button, true, t)
        compositor.pointerButton(id, button, false, t + 1)
    }

    private fun avgX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun avgY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()
    private fun now() = SystemClock.uptimeMillis().toInt()

    companion object {
        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111
        private const val BTN_MIDDLE = 0x112
        private const val TAP_MS = 250L
        private const val DOUBLE_TAP_MS = 250L
        private const val POINTER_SPEED = 1.5f
        private const val WHEEL_NOTCH_DP = 24f
    }
}
