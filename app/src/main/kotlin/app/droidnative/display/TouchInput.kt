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
 * MOUSE: the finger is the pointer. Tap = left click, drag = left drag,
 *   long press = right click, two fingers = scroll.
 * TOUCHPAD: relative movement of a drawn [cursor]. Tap = left click,
 *   two-finger tap = right click, tap then hold and move = drag,
 *   two fingers = scroll.
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

    // Shared state of the gesture in progress
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var leftDown = false
    private var consumed = false          // long press already handled
    private var scrolling = false
    private var maxPointers = 0

    // Touchpad cursor and tap-drag
    private var cx = -1f
    private var cy = -1f
    private var lastTapUp = 0L

    private val longPressAction = Runnable {
        if (mode == MouseMode.MOUSE && !moved && !scrolling && !leftDown) {
            consumed = true
            click(BTN_RIGHT)
        }
    }

    fun onTouch(e: MotionEvent): Boolean {
        if (mode == MouseMode.TOUCH) return native(e)
        val t = e.eventTime.toInt()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                downTime = e.eventTime
                moved = false; consumed = false; scrolling = false; maxPointers = 1
                if (mode == MouseMode.MOUSE) {
                    motion(e.x, e.y, t)
                    view.postDelayed(longPressAction, longPress)
                } else {
                    ensureCursor()
                    // Second touch right after a tap: drag
                    if (e.eventTime - lastTapUp < DOUBLE_TAP_MS) press(true, t)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                view.removeCallbacks(longPressAction)
                if (leftDown) press(false, t)
                scrolling = true
                lastX = avgX(e); lastY = avgY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling) {
                    val x = avgX(e); val y = avgY(e)
                    // Content follows the fingers, as on a touchscreen
                    compositor.pointerAxis(id, (lastX - x) * scale(), (lastY - y) * scale(), t)
                    if (hypot(x - downX, y - downY) > slop) moved = true
                    lastX = x; lastY = y
                    return true
                }
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                    moved = true
                    view.removeCallbacks(longPressAction)
                    if (mode == MouseMode.MOUSE && !consumed) press(true, t)   // start the drag where it began
                }
                if (mode == MouseMode.MOUSE) {
                    if (moved && !consumed) motion(e.x, e.y, t)
                } else if (moved) {
                    moveCursor((e.x - lastX) * POINTER_SPEED, (e.y - lastY) * POINTER_SPEED, t)
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Remaining finger continues the scroll from its position
                val rest = (0 until e.pointerCount).filter { it != e.actionIndex }
                lastX = rest.map { e.getX(it) }.average().toFloat()
                lastY = rest.map { e.getY(it) }.average().toFloat()
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(longPressAction)
                val quick = e.eventTime - downTime < TAP_MS
                when {
                    leftDown -> press(false, t)
                    consumed -> {}
                    scrolling -> if (!moved && quick && maxPointers == 2) click(BTN_RIGHT)
                    !moved && mode == MouseMode.MOUSE -> click(BTN_LEFT)
                    !moved && quick -> { click(BTN_LEFT); lastTapUp = e.eventTime }
                }
                scrolling = false
            }
            MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPressAction)
                if (leftDown) press(false, t)
                scrolling = false
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

    private fun ensureCursor() {
        if (cx >= 0) return
        cx = view.width / 2f
        cy = view.height / 2f
        cursor?.visibility = View.VISIBLE
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
        if (mode == MouseMode.TOUCHPAD) motion(cx, cy, t)
        compositor.pointerButton(id, button, true, t)
        compositor.pointerButton(id, button, false, t + 1)
    }

    private fun avgX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun avgY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()
    private fun now() = SystemClock.uptimeMillis().toInt()

    companion object {
        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111
        private const val TAP_MS = 250L
        private const val DOUBLE_TAP_MS = 300L
        private const val POINTER_SPEED = 1.5f
    }
}
