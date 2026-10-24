package app.droidnative.display

import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import app.droidnative.apps.WindowSettings
import app.droidnative.overlay.OverlayView

/**
 * Input of one toplevel shown in [view] (a SurfaceView in [WindowActivity],
 * a TextureView in a desktop window): touch in the app's mouse mode, mouse,
 * captured pointer, hardware keys and text from the on-screen keyboard.
 * Coordinates are view pixels; [renderScale] converts them to buffer pixels.
 *
 * The host view forwards its events here and returns [createInputConnection]
 * from onCreateInputConnection. [showKeyboard] opens or closes the keyboard
 * for the view.
 */
class ToplevelInput(
    private val compositor: Compositor,
    val id: Int,
    private val view: View,
    cursor: View?,
    val settings: WindowSettings,
    private val renderScale: () -> Float,
    private val showKeyboard: (Boolean) -> Unit,
) {
    val touch = TouchInput(compositor, id, view, cursor, settings.mouseMode, renderScale)
    /** On-screen buttons over the view, which get their fingers first. */
    var overlay: OverlayView? = null

    private var lastButtons = 0
    private var composing = ""

    /* Pointer */

    fun onTouch(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(event)
        // A tap while a text field is active brings back a keyboard the
        // user dismissed (the client reports no new state then)
        // Fingers on on-screen buttons stay with the buttons
        val routed = overlay?.let { it.route(event) ?: return true } ?: event
        if (routed.actionMasked == MotionEvent.ACTION_UP)
            view.postDelayed({
                if (compositor.textInputToplevel == id && settings.autoKeyboard) showKeyboard(true)
            }, TAP_SHOW_DELAY_MS)
        val handled = touch.onTouch(routed)
        if (routed !== event) routed.recycle()
        return handled
    }

    // Captured mouse: event x/y are movement deltas
    fun onCapturedPointer(event: MotionEvent): Boolean {
        val t = event.eventTime.toInt()
        val s = renderScale()
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                var dx = event.x
                var dy = event.y
                for (h in 0 until event.historySize) {
                    dx += event.getHistoricalX(h)
                    dy += event.getHistoricalY(h)
                }
                compositor.pointerRelative(id, dx * s, dy * s, t)
            }
            MotionEvent.ACTION_SCROLL -> compositor.pointerWheel(id,
                -event.getAxisValue(MotionEvent.AXIS_HSCROLL).toInt(),
                -event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt(), t)
        }
        sendButtons(event, t)
        return true
    }

    fun onGenericMotion(event: MotionEvent): Boolean =
        (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) && onMouse(event)

    private fun onMouse(event: MotionEvent): Boolean {
        val t = event.eventTime.toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                // Android: +1 per notch up. A wheel sends whole notches (discrete
                // steps, as X11 programs need); touchpads fractions (smooth)
                val v = -event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = -event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val notches = v == v.toInt().toFloat() && h == h.toInt().toFloat()
                if (notches && event.isFromSource(InputDevice.SOURCE_MOUSE)) compositor.pointerWheel(id, h.toInt(), v.toInt(), t)
                else compositor.pointerAxis(id, h * 10f, v * 10f, t)
                return true
            }
            else -> compositor.pointerMotion(id, event.x * renderScale(), event.y * renderScale(), t)
        }
        sendButtons(event, t)
        return true
    }

    private fun sendButtons(event: MotionEvent, t: Int) {
        val buttons = event.buttonState
        val changed = buttons xor lastButtons
        for ((android, evdev) in BUTTONS) {
            if (changed and android != 0)
                compositor.pointerButton(id, evdev, buttons and android != 0, t)
        }
        lastButtons = buttons
    }

    /* Keys */

    /**
     * Sends a key to the toplevel. Returns false for keys the host handles
     * itself (Back, volume) or that have no Linux code.
     */
    fun key(event: KeyEvent): Boolean {
        // A mouse's side buttons also arrive as Back/Forward keys: they are
        // already sent as mouse buttons (onMouse)
        if ((event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_FORWARD) &&
            event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.device?.isVirtual != false) return false
        // Volume keys change Android's volume (Linux audio plays through it)
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) return false
        val code = KeyMap.toEvdev(event)
        if (code == 0) return false
        compositor.key(id, code, event.action == KeyEvent.ACTION_DOWN,
            KeyMap.modsDepressed(event.metaState), KeyMap.modsLocked(event.metaState),
            event.eventTime.toInt())
        return true
    }

    /* Text from the on-screen keyboard */

    fun createInputConnection(outAttrs: EditorInfo): InputConnection {
        // No suggestions or autocorrect: the keyboard has no view of the
        // text in the Linux application
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return TextConnection()
    }

    private inner class TextConnection : BaseInputConnection(view, false) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            sendText(text.toString())
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            compositor.textPreedit(id, text.toString())
            composing = text.toString()
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) sendText(composing)
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { tapKey(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength) { tapKey(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            tapKey(KeyEvent.KEYCODE_ENTER)
            return true
        }
    }

    private fun tapKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        key(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        key(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun sendText(text: String) {
        val hadPreedit = composing.isNotEmpty()
        composing = ""
        // Control characters are keys, not text
        if (text == "\n") return tapKey(KeyEvent.KEYCODE_ENTER)
        if (text == "\t") return tapKey(KeyEvent.KEYCODE_TAB)
        if (compositor.textInputToplevel == id) {
            // X11 programs (dn-notifyd's input method) get keys where the
            // keymap has them: Chromium (Steam) drops input method text in
            // password fields and only takes keys there
            if (compositor.isInputMethodToplevel(id)) {
                val events = keyEvents(text)
                if (events != null) {
                    if (hadPreedit) compositor.textPreedit(id, "")
                    for (e in events) key(e)
                    return
                }
            }
            compositor.textCommit(id, text)
            return
        }
        // The client has no text field active (keyboard opened by hand, or
        // a toolkit without text-input): type what the us keymap can express
        val events = keyEvents(text) ?: return
        for (e in events) key(e)
    }

    private fun keyEvents(text: String): Array<KeyEvent>? =
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())

    companion object {
        // Lets a tap that moves focus away from a text field disable it first
        private const val TAP_SHOW_DELAY_MS = 150L

        private val BUTTONS = listOf(
            MotionEvent.BUTTON_PRIMARY to 0x110,    // BTN_LEFT
            MotionEvent.BUTTON_SECONDARY to 0x111,  // BTN_RIGHT
            MotionEvent.BUTTON_TERTIARY to 0x112,   // BTN_MIDDLE
            MotionEvent.BUTTON_BACK to 0x113,       // BTN_SIDE
            MotionEvent.BUTTON_FORWARD to 0x114,    // BTN_EXTRA
        )
    }
}
