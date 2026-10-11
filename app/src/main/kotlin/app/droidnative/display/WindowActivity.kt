package app.droidnative.display

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.util.DisplayMetrics
import android.view.KeyCharacterMap
import android.view.WindowInsets
import android.widget.FrameLayout
import app.droidnative.env.EnvService
import app.droidnative.R
import app.droidnative.overlay.OverlayStore
import app.droidnative.overlay.OverlayView
import app.droidnative.apps.Rotation
import app.droidnative.apps.WindowSettings
import android.content.pm.ActivityInfo
import android.widget.ImageView
import kotlin.math.roundToInt

/**
 * Hosts one Wayland toplevel in its own Android task (document mode), so each
 * Linux app shows up as a separate entry in recents.
 *
 * Extras: [EXTRA_TOPLEVEL] (compositor id). The data URI keeps one task per
 * toplevel ("intoExisting").
 */
class WindowActivity : Activity(), SurfaceHolder.Callback, Compositor.Listener {

    private var toplevelId = 0
    private var compositor: Compositor? = null
    private var attached = false
    @Volatile private var clientClosed = false
    private lateinit var view: WindowView
    // Buffer pixels per view pixel (below 1 when a lower resolution is set)
    private var renderScale = 1f
    private lateinit var settings: WindowSettings
    private lateinit var touch: TouchInput
    private var overlay: OverlayView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Volume keys set the media volume, which Linux audio plays at
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        toplevelId = intent.getIntExtra(EXTRA_TOPLEVEL, 0)
        compositor = EnvService.compositor
        val tl = compositor?.toplevels?.get(toplevelId)
        if (tl == null) {
            finishAndRemoveTask()
            return
        }
        updateTaskDescription(tl)
        compositor?.addListener(this)
        compositor?.hosted?.add(toplevelId)
        getSystemService(android.app.NotificationManager::class.java).cancel(1000 + toplevelId)
        // Predictive back (API 33+) bypasses key events
        if (Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { moveTaskToBack(true) }

        settings = WindowSettings.forClient(this, tl.pid)
        renderScale = settings.renderFactor
        requestedOrientation = when (settings.rotation) {
            Rotation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Rotation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Rotation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        view = WindowView(this)
        view.holder.addCallback(this)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        // Render at the configured resolution; Android scales to the view
        view.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = ((r - l) * renderScale).roundToInt().coerceAtLeast(1)
            val h = ((b - t) * renderScale).roundToInt().coerceAtLeast(1)
            if (renderScale != 1f) view.holder.setFixedSize(w, h)
            if (::touch.isInitialized) touch.clampCursor()
        }
        val frame = FrameLayout(this)
        frame.addView(view)
        // Pointer of the touchpad mode (Android draws none for us)
        val cursor = ImageView(this).apply {
            setImageResource(R.drawable.ic_cursor)
            visibility = View.GONE
        }
        frame.addView(cursor, FrameLayout.LayoutParams(
            (20 * resources.displayMetrics.density).roundToInt(),
            (20 * resources.displayMetrics.density).roundToInt()))
        touch = TouchInput(compositor!!, toplevelId, view, cursor, settings.mouseMode) { renderScale }
        touch.locked = compositor?.pointerLocked?.contains(toplevelId) == true
        // On-screen buttons chosen for this app, drawn over the surface
        settings.overlay?.let { OverlayStore(this).get(it) }?.let { layout ->
            overlay = OverlayView(this, layout, compositor!!, toplevelId) { toggleKeyboard() }
            frame.addView(overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        // Android 15 draws apps edge to edge and no longer resizes the
        // window for the keyboard: keep the surface clear of both
        if (Build.VERSION.SDK_INT >= 35) {
            frame.setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(frame)
        view.requestFocus()
        if (compositor?.textInputToplevel == toplevelId && settings.autoKeyboard) showKeyboard(true)
    }

    // Overlay keyboard button: the user may have closed it in between
    private fun toggleKeyboard() {
        val shown = if (Build.VERSION.SDK_INT >= 30)
            window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        else keyboardShown
        showKeyboard(!shown)
    }

    private var keyboardShown = false

    private fun showKeyboard(show: Boolean) {
        keyboardShown = show
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) {
            view.requestFocus()
            imm.showSoftInput(view, 0)
        } else {
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    override fun onDestroy() {
        compositor?.removeListener(this)
        compositor?.hosted?.remove(toplevelId)
        // Finishing without the app having closed: the task was removed from
        // recents (back only moves it to the background), so the app ends too
        if (isFinishing && !clientClosed && toplevelId != 0)
            compositor?.closeFromHost(toplevelId)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun updateTaskDescription(tl: Compositor.Toplevel) {
        val label = tl.title.ifEmpty { tl.appId.ifEmpty { "Linux app" } }
        setTaskDescription(ActivityManager.TaskDescription(label))
    }

    /* Surface lifecycle */

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val c = compositor ?: return
        if (!attached) {
            c.attach(toplevelId, holder.surface, width, height)
            attached = true
        } else {
            c.resize(toplevelId, width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        compositor?.detach(toplevelId)
        attached = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Pointer capture ends with focus; take it again when coming back
        if (hasFocus && compositor?.pointerLocked?.contains(toplevelId) == true) view.requestPointerCapture()
        compositor?.focus(toplevelId, hasFocus)
    }

    /* Compositor events */

    override fun onToplevelChanged(toplevel: Compositor.Toplevel) {
        if (toplevel.id == toplevelId) runOnUiThread { updateTaskDescription(toplevel) }
    }

    override fun onToplevelClosed(id: Int) {
        if (id != toplevelId) return
        clientClosed = true
        runOnUiThread { finishAndRemoveTask() }
    }

    override fun onPointerLock(id: Int, locked: Boolean) {
        if (id != toplevelId) return
        runOnUiThread { applyPointerLock(locked) }
    }

    // Touch gestures turn relative; a real mouse is captured by Android so
    // it delivers raw movement (onCapturedPointerEvent) instead of a position
    private fun applyPointerLock(locked: Boolean) {
        if (::touch.isInitialized) touch.locked = locked
        if (locked && hasWindowFocus()) view.requestPointerCapture()
        else if (!locked) view.releasePointerCapture()
    }

    override fun onTextInput(id: Int, active: Boolean) {
        // Hiding always follows the app; showing only if wanted for this app
        if (id == toplevelId && (!active || settings.autoKeyboard)) runOnUiThread { showKeyboard(active) }
    }

    /* Text from the on-screen keyboard */

    /** Surface that accepts an input method. */
    private inner class WindowView(context: Context) : SurfaceView(context) {
        override fun onCheckIsTextEditor() = true

        // Here, not in the activity: view coordinates are surface
        // coordinates, window coordinates include the status bar
        override fun onTouchEvent(event: MotionEvent) = handleTouch(event)
        override fun onCapturedPointerEvent(event: MotionEvent) = handleCapturedPointer(event)
        override fun onGenericMotionEvent(event: MotionEvent) =
            handleGenericMotion(event) || super.onGenericMotionEvent(event)

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            // No suggestions or autocorrect: the keyboard has no view of the
            // text in the Linux application
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
            return TextConnection(this)
        }
    }

    private inner class TextConnection(view: View) : BaseInputConnection(view, false) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            sendText(text.toString())
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            compositor?.textPreedit(toplevelId, text.toString())
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

    private var composing = ""

    private fun tapKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun sendText(text: String) {
        val c = compositor ?: return
        composing = ""
        // Control characters are keys, not text
        if (text == "\n") return tapKey(KeyEvent.KEYCODE_ENTER)
        if (text == "\t") return tapKey(KeyEvent.KEYCODE_TAB)
        if (c.textInputToplevel == toplevelId) {
            c.textCommit(toplevelId, text)
            return
        }
        // The client has no text field active (keyboard opened by hand, or
        // a toolkit without text-input): type what the us keymap can express
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(text.toCharArray()) ?: return
        for (e in events) dispatchKeyEvent(e)
    }

    /* Input */

    private fun handleTouch(event: MotionEvent): Boolean {
        val c = compositor ?: return false
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(event)
        // A tap while a text field is active brings back a keyboard the
        // user dismissed (the client reports no new state then)
        // Fingers on on-screen buttons stay with the buttons
        val routed = overlay?.let { it.route(event) ?: return true } ?: event
        if (routed.actionMasked == MotionEvent.ACTION_UP)
            view.postDelayed({
                if (c.textInputToplevel == toplevelId && settings.autoKeyboard) showKeyboard(true)
            }, TAP_SHOW_DELAY_MS)
        val handled = touch.onTouch(routed)
        if (routed !== event) routed.recycle()
        return handled
    }

    // Captured mouse: event x/y are movement deltas
    private fun handleCapturedPointer(event: MotionEvent): Boolean {
        val c = compositor ?: return false
        val t = event.eventTime.toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                var dx = event.x
                var dy = event.y
                for (h in 0 until event.historySize) {
                    dx += event.getHistoricalX(h)
                    dy += event.getHistoricalY(h)
                }
                c.pointerRelative(toplevelId, dx * renderScale, dy * renderScale, t)
            }
            MotionEvent.ACTION_SCROLL -> c.pointerWheel(toplevelId,
                -event.getAxisValue(MotionEvent.AXIS_HSCROLL).toInt(),
                -event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt(), t)
        }
        val buttons = event.buttonState
        val changed = buttons xor lastButtons
        for ((android, evdev) in BUTTONS) {
            if (changed and android != 0)
                c.pointerButton(toplevelId, evdev, buttons and android != 0, t)
        }
        lastButtons = buttons
        return true
    }

    private fun handleGenericMotion(event: MotionEvent): Boolean =
        (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) && onMouse(event)

    private var lastButtons = 0

    private fun onMouse(event: MotionEvent): Boolean {
        val c = compositor ?: return false
        val t = event.eventTime.toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                // Android: +1 per notch up. A wheel sends whole notches (discrete
                // steps, as X11 programs need); touchpads fractions (smooth)
                val v = -event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = -event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val notches = v == v.toInt().toFloat() && h == h.toInt().toFloat()
                if (notches && event.isFromSource(InputDevice.SOURCE_MOUSE)) c.pointerWheel(toplevelId, h.toInt(), v.toInt(), t)
                else c.pointerAxis(toplevelId, h * 10f, v * 10f, t)
                return true
            }
            else -> c.pointerMotion(toplevelId, event.x * renderScale, event.y * renderScale, t)
        }
        val buttons = event.buttonState
        val changed = buttons xor lastButtons
        for ((android, evdev) in BUTTONS) {
            if (changed and android != 0)
                c.pointerButton(toplevelId, evdev, buttons and android != 0, t)
        }
        lastButtons = buttons
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = compositor ?: return super.dispatchKeyEvent(event)
        // A mouse's side buttons also arrive as Back/Forward keys: they are
        // already sent as mouse buttons (onMouse)
        if ((event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_FORWARD) &&
            event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.device?.isVirtual != false) {
            // System back: leave the app running, like Android's own apps
            if (event.action == KeyEvent.ACTION_UP) moveTaskToBack(true)
            return true
        }
        // Volume keys change Android's volume (Linux audio plays through it)
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(event)
        val code = KeyMap.toEvdev(event)
        if (code == 0)
            return super.dispatchKeyEvent(event)
        c.key(toplevelId, code, event.action == KeyEvent.ACTION_DOWN,
            KeyMap.modsDepressed(event.metaState), KeyMap.modsLocked(event.metaState),
            event.eventTime.toInt())
        return true
    }

    companion object {
        const val EXTRA_TOPLEVEL = "app.droidnative.extra.TOPLEVEL"
        // Lets a tap that moves focus away from a text field disable it first
        private const val TAP_SHOW_DELAY_MS = 150L

        private val BUTTONS = listOf(
            MotionEvent.BUTTON_PRIMARY to 0x110,    // BTN_LEFT
            MotionEvent.BUTTON_SECONDARY to 0x111,  // BTN_RIGHT
            MotionEvent.BUTTON_TERTIARY to 0x112,   // BTN_MIDDLE
            MotionEvent.BUTTON_BACK to 0x113,       // BTN_SIDE
            MotionEvent.BUTTON_FORWARD to 0x114,    // BTN_EXTRA
        )

        fun intent(context: Context, toplevelId: Int): Intent =
            Intent(context, WindowActivity::class.java)
                .setData(Uri.parse("droidnative://toplevel/$toplevelId"))
                .putExtra(EXTRA_TOPLEVEL, toplevelId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
    }
}
