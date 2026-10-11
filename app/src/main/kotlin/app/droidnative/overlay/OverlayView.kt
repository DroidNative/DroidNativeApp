package app.droidnative.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.droidnative.display.Compositor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The on-screen buttons of a layout over a Linux window. The view only
 * draws; the window passes its touch events through [route] first.
 *
 * A finger counts as a small circle, not a point: every visible button it
 * overlaps is pressed, so a finger between "run" and "jump" holds both, and
 * sliding over buttons presses and releases them like on a gamepad. A
 * finger that starts on a button belongs to the buttons until it lifts;
 * all other fingers go on to the window. Hidden groups are neither drawn
 * nor pressable.
 *
 * A finger that starts on a joystick controls only that stick until it
 * lifts, also outside its circle. Which directions it holds depends on the
 * angle from the stick's centre (see [stickDirections]).
 */
@SuppressLint("ViewConstructor")
class OverlayView(
    context: Context,
    private val layout: OverlayLayout,
    private val compositor: Compositor,
    private val toplevel: Int,
    /** Toggles the on-screen keyboard of the window. */
    private val toggleKeyboard: () -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val fingerRadius = FINGER_RADIUS_DP * density
    private val visibleGroups = layout.groups.filter { it.visible }.map { it.id }.toMutableSet()

    // Pointer id -> buttons it currently covers
    private val fingers = HashMap<Int, Set<OverlayButton>>()
    // Buttons held by at least one finger, with the number of fingers
    private val held = HashMap<OverlayButton, Int>()
    // Keys held by buttons, for the modifier state sent with every key
    private val heldKeys = HashMap<Int, Int>()

    private class StickFinger(val stick: OverlayButton) {
        var dx = 0f
        var dy = 0f
        // How far the stick is pushed, 0 to 1
        var deflection = 0f
        var active: Set<Int> = emptySet()
        var nextWheel = 0L
    }
    // Pointer id -> the joystick it controls
    private val sticks = HashMap<Int, StickFinger>()

    /**
     * Takes the fingers that belong to buttons and returns the event for
     * the window without them: the event itself if no finger is ours,
     * a new event (recycle it) with the other fingers, or null if none
     * remain for the window.
     */
    fun route(e: MotionEvent): MotionEvent? {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            val id = e.getPointerId(i)
            val stick = stickAt(e.getX(i), e.getY(i))
            if (stick != null) {
                sticks[id] = StickFinger(stick)
                moveStick(id, e.getX(i), e.getY(i))
            } else {
                val hit = hits(e.getX(i), e.getY(i))
                if (hit.isNotEmpty()) {
                    fingers[id] = emptySet()
                    update(id, hit)
                }
            }
        }
        if (fingers.isEmpty() && sticks.isEmpty()) return e
        // Decided before lifts are handled: a lifting button finger is still ours
        val ours = fingers.keys + sticks.keys

        when (action) {
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                if (id in fingers) update(id, hits(e.getX(i), e.getY(i)))
                if (id in sticks) moveStick(id, e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id in fingers) {
                    update(id, emptySet())
                    fingers.remove(id)
                }
                releaseStick(id)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (id in fingers.keys.toList()) update(id, emptySet())
                fingers.clear()
                for (id in sticks.keys.toList()) releaseStick(id)
            }
        }
        // The window gets the remaining fingers as a gesture of their own
        val others = (0 until e.pointerCount).filter { e.getPointerId(it) !in ours }
        return if (others.isEmpty()) null else subset(e, others)
    }

    /** Visible buttons a finger at (x, y) covers. */
    private fun hits(x: Float, y: Float): Set<OverlayButton> {
        val out = HashSet<OverlayButton>()
        for (b in layout.buttons) {
            if (!visible(b) || b.isJoystick) continue
            val r = rect(b)
            val dx = maxOf(r.left - x, 0f, x - r.right)
            val dy = maxOf(r.top - y, 0f, y - r.bottom)
            if (dx * dx + dy * dy <= fingerRadius * fingerRadius) out += b
        }
        return out
    }

    /** The visible joystick whose circle contains (x, y). */
    private fun stickAt(x: Float, y: Float): OverlayButton? = layout.buttons.lastOrNull { b ->
        if (!b.isJoystick || !visible(b)) return@lastOrNull false
        val r = rect(b)
        hypot(x - r.centerX(), y - r.centerY()) <= r.width() / 2
    }

    private fun moveStick(pointer: Int, x: Float, y: Float) {
        val f = sticks[pointer] ?: return
        val stick = f.stick.action as ButtonAction.Joystick
        val r = rect(f.stick)
        val radius = r.width() / 2
        f.dx = x - r.centerX()
        f.dy = y - r.centerY()
        f.deflection = (hypot(f.dx, f.dy) / radius).coerceAtMost(1f)
        val now = if (f.deflection < DEAD_ZONE) emptySet()
            else stickDirections(stick, Math.toDegrees(atan2(f.dx, -f.dy).toDouble()).toFloat())
        for (i in now - f.active) {
            when (val o = stick.outputs[i]) {
                is StickOutput.Key -> Keys.byName(o.name)?.let { key(it.code, true) }
                is StickOutput.Wheel -> f.nextWheel = 0L
                null -> {}
            }
        }
        for (i in f.active - now) {
            (stick.outputs[i] as? StickOutput.Key)?.let { o -> Keys.byName(o.name)?.let { key(it.code, false) } }
        }
        if ((now - f.active).any { stick.outputs[it] != null })
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        f.active = now
        if (now.any { stick.outputs[it] is StickOutput.Wheel }) {
            removeCallbacks(wheelLoop)
            post(wheelLoop)
        }
        invalidate()
    }

    private fun releaseStick(pointer: Int) {
        val f = sticks[pointer] ?: return
        val stick = f.stick.action as ButtonAction.Joystick
        for (i in f.active)
            (stick.outputs[i] as? StickOutput.Key)?.let { o -> Keys.byName(o.name)?.let { key(it.code, false) } }
        sticks.remove(pointer)
        invalidate()
    }

    // Wheel directions repeat: every 400 ms just past the dead zone, every 50 ms at the rim
    private val wheelLoop = object : Runnable {
        override fun run() {
            val t = SystemClock.uptimeMillis()
            var any = false
            for (f in sticks.values) {
                val stick = f.stick.action as ButtonAction.Joystick
                val wheels = f.active.mapNotNull { stick.outputs[it] as? StickOutput.Wheel }
                if (wheels.isEmpty()) continue
                any = true
                if (t < f.nextWheel) continue
                for (w in wheels) compositor.pointerWheel(toplevel, 0, if (w.up) -1 else 1, now())
                val push = ((f.deflection - DEAD_ZONE) / (1 - DEAD_ZONE)).coerceIn(0f, 1f)
                f.nextWheel = t + (400 - 350 * push).toLong()
            }
            if (any) postDelayed(this, 16)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(wheelLoop)
        super.onDetachedFromWindow()
    }

    private fun update(pointer: Int, now: Set<OverlayButton>) {
        val before = fingers[pointer] ?: emptySet()
        for (b in now - before) {
            val n = held[b] ?: 0
            held[b] = n + 1
            if (n == 0) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                press(b, true)
            }
        }
        for (b in before - now) {
            val n = held[b] ?: 0
            if (n <= 1) {
                held.remove(b)
                press(b, false)
            } else {
                held[b] = n - 1
            }
        }
        fingers[pointer] = now
        invalidate()
    }

    private fun visible(b: OverlayButton) = b.group == null || b.group in visibleGroups

    private fun rect(b: OverlayButton): RectF {
        val bw = b.width * density
        val bh = b.height * density
        val x = (b.x * width - bw / 2).coerceIn(0f, (width - bw).coerceAtLeast(0f))
        val y = (b.y * height - bh / 2).coerceIn(0f, (height - bh).coerceAtLeast(0f))
        return RectF(x, y, x + bw, y + bh)
    }

    /* Actions */

    private fun mods(): Int = heldKeys.keys.fold(0) { m, k -> m or Keys.modifierMask(k) }

    private fun key(code: Int, down: Boolean) {
        val n = heldKeys[code] ?: 0
        if (down) heldKeys[code] = n + 1 else if (n <= 1) heldKeys.remove(code) else heldKeys[code] = n - 1
        // Two buttons may share a key: only the first press and last release count
        if ((down && n == 0) || (!down && n == 1))
            compositor.key(toplevel, code, down, mods(), 0, now())
    }

    private fun press(b: OverlayButton, down: Boolean) {
        when (val a = b.action) {
            is ButtonAction.Keys -> {
                val codes = a.keys.mapNotNull { Keys.byName(it)?.code }
                // Modifiers first on press, last on release, like fingers do
                val ordered = codes.sortedByDescending { Keys.modifierMask(it) != 0 }
                if (down) ordered.forEach { key(it, true) } else ordered.reversed().forEach { key(it, false) }
            }
            is ButtonAction.Mouse -> compositor.pointerButton(toplevel, a.button.code, down, now())
            is ButtonAction.Wheel -> if (down) compositor.pointerWheel(toplevel, 0, if (a.up) -1 else 1, now())
            is ButtonAction.Keyboard -> if (down) toggleKeyboard()
            is ButtonAction.ToggleGroup -> if (down) {
                if (!visibleGroups.remove(a.group)) visibleGroups += a.group
                releaseHidden()
            }
            is ButtonAction.Joystick -> {} // moveStick
        }
    }

    // Buttons that just disappeared must not stay pressed
    private fun releaseHidden() {
        for ((id, buttons) in fingers.toMap()) update(id, buttons.filter { visible(it) }.toSet())
        for ((id, f) in sticks.toMap()) if (!visible(f.stick)) releaseStick(id)
        invalidate()
    }

    private fun now() = SystemClock.uptimeMillis().toInt()

    /* Drawing */

    // Dark fill, white outline over a dark one: readable on light and dark windows
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * density
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 4 * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = 14 * density
        setShadowLayer(3 * density, 0f, 0f, 0xFF000000.toInt())
    }

    private val small = Paint(text).apply { textSize = 11 * density }

    override fun onDraw(canvas: Canvas) {
        val alpha = (layout.opacity * 255).roundToInt()
        for (b in layout.buttons) {
            if (!visible(b)) continue
            if (b.isJoystick) {
                drawStick(canvas, b, alpha)
                continue
            }
            val r = rect(b)
            r.inset(edge.strokeWidth / 2, edge.strokeWidth / 2)
            val pressed = b in held
            fill.color = if (pressed) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            fill.alpha = if (pressed) (alpha * 0.7f).roundToInt() else alpha / 2
            stroke.alpha = alpha
            edge.alpha = alpha / 2
            text.alpha = (alpha * 2).coerceAtMost(255)
            val radius = minOf(r.width(), r.height()) * 0.3f
            canvas.drawRoundRect(r, radius, radius, fill)
            canvas.drawRoundRect(r, radius, radius, edge)
            canvas.drawRoundRect(r, radius, radius, stroke)
            if (b.label.isNotEmpty())
                canvas.drawText(b.label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun drawStick(canvas: Canvas, b: OverlayButton, alpha: Int) {
        val stick = b.action as ButtonAction.Joystick
        val r = rect(b)
        val radius = r.width() / 2 - edge.strokeWidth / 2
        val finger = sticks.values.firstOrNull { it.stick == b }
        fill.color = 0xFF000000.toInt()
        fill.alpha = alpha / 2
        stroke.alpha = alpha
        edge.alpha = alpha / 2
        canvas.drawCircle(r.centerX(), r.centerY(), radius, fill)
        canvas.drawCircle(r.centerX(), r.centerY(), radius, edge)
        canvas.drawCircle(r.centerX(), r.centerY(), radius, stroke)
        // Direction labels near the rim, bright while held
        val n = stick.outputs.size
        for ((i, o) in stick.outputs.withIndex()) {
            val label = when (o) {
                is StickOutput.Key -> Keys.byName(o.name)?.label ?: continue
                is StickOutput.Wheel -> if (o.up) "^" else "v"
                null -> continue
            }
            val a = Math.toRadians((stick.rotation + i * 360.0 / n))
            val lx = r.centerX() + (radius * 0.72f * sin(a)).toFloat()
            val ly = r.centerY() - (radius * 0.72f * cos(a)).toFloat()
            small.alpha = if (finger != null && i in finger.active) 255 else (alpha * 1.5f).roundToInt().coerceAtMost(255)
            canvas.drawText(label, lx, ly - (small.descent() + small.ascent()) / 2, small)
        }
        // The knob follows the finger, up to the rim
        val knob = radius * 0.38f
        var kx = 0f
        var ky = 0f
        if (finger != null) {
            val d = hypot(finger.dx, finger.dy)
            val max = radius - knob
            val k = if (d > max) max / d else 1f
            kx = finger.dx * k
            ky = finger.dy * k
        }
        fill.color = if (finger != null) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        fill.alpha = if (finger != null) (alpha * 0.7f).roundToInt() else alpha
        canvas.drawCircle(r.centerX() + kx, r.centerY() + ky, knob, fill)
        canvas.drawCircle(r.centerX() + kx, r.centerY() + ky, knob, edge)
        canvas.drawCircle(r.centerX() + kx, r.centerY() + ky, knob, stroke)
        if (b.label.isNotEmpty())
            canvas.drawText(b.label, r.centerX() + kx, r.centerY() + ky - (text.descent() + text.ascent()) / 2, text)
    }

    companion object {
        /** A fingertip as a circle: wide enough to cover two adjacent buttons. */
        const val FINGER_RADIUS_DP = 10f

        /** Joystick travel (fraction of its radius) that does nothing. */
        const val DEAD_ZONE = 0.3f

        /**
         * Directions of [stick] held at [angle] (degrees, 0 = up, clockwise).
         * One at a time: the nearest. Multi: every direction closer than
         * three quarters of the spacing (at most 67.5 degrees), so between
         * two neighbours both are held, as on an 8-way stick from 4 keys.
         */
        fun stickDirections(stick: ButtonAction.Joystick, angle: Float): Set<Int> {
            val n = stick.outputs.size
            val spacing = 360f / n
            fun distance(i: Int) = abs(((angle - stick.rotation - i * spacing) % 360f + 540f) % 360f - 180f)
            if (!stick.multi) return setOf((0 until n).minBy { distance(it) })
            val limit = minOf(spacing * 0.75f, 67.5f)
            return (0 until n).filter { distance(it) < limit }.toSet()
        }

        /** [e] reduced to the pointers at [indices], with the action renumbered. */
        fun subset(e: MotionEvent, indices: List<Int>): MotionEvent {
            val n = indices.size
            val props = Array(n) { MotionEvent.PointerProperties() }
            val coords = Array(n) { MotionEvent.PointerCoords() }
            for ((k, i) in indices.withIndex()) {
                e.getPointerProperties(i, props[k])
                e.getPointerCoords(i, coords[k])
            }
            val masked = e.actionMasked
            val actionPointer = indices.indexOf(e.actionIndex)
            val action = when (masked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                    if (actionPointer < 0) MotionEvent.ACTION_MOVE
                    else if (n == 1) MotionEvent.ACTION_DOWN
                    else MotionEvent.ACTION_POINTER_DOWN or (actionPointer shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                    if (actionPointer < 0) MotionEvent.ACTION_MOVE
                    else if (n == 1) MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_POINTER_UP or (actionPointer shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                else -> masked
            }
            return MotionEvent.obtain(e.downTime, e.eventTime, action, n, props, coords, e.metaState,
                e.buttonState, e.xPrecision, e.yPrecision, e.deviceId, e.edgeFlags, e.source, e.flags)
        }
    }
}
