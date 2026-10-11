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
    private lateinit var view: WindowView
    // Buffer pixels per view pixel (below 1 when a lower resolution is set)
    private var renderScale = 1f
    private lateinit var settings: WindowSettings
    private lateinit var touch: TouchInput

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toplevelId = intent.getIntExtra(EXTRA_TOPLEVEL, 0)
        compositor = EnvService.compositor
        val tl = compositor?.toplevels?.get(toplevelId)
        if (tl == null) {
            finishAndRemoveTask()
            return
        }
        updateTaskDescription(tl)
        compositor?.addListener(this)

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
        if (compositor?.textInputToplevel == toplevelId) showKeyboard(true)
    }

    private fun showKeyboard(show: Boolean) {
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
        compositor?.focus(toplevelId, hasFocus)
    }

    /* Compositor events */

    override fun onToplevelChanged(toplevel: Compositor.Toplevel) {
        if (toplevel.id == toplevelId) runOnUiThread { updateTaskDescription(toplevel) }
    }

    override fun onToplevelClosed(id: Int) {
        if (id == toplevelId) runOnUiThread { finishAndRemoveTask() }
    }

    override fun onTextInput(id: Int, active: Boolean) {
        if (id == toplevelId) runOnUiThread { showKeyboard(active) }
    }

    /* Text from the on-screen keyboard */

    /** Surface that accepts an input method. */
    private inner class WindowView(context: Context) : SurfaceView(context) {
        override fun onCheckIsTextEditor() = true

        // Here, not in the activity: view coordinates are surface
        // coordinates, window coordinates include the status bar
        override fun onTouchEvent(event: MotionEvent) = handleTouch(event)
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
        if (event.actionMasked == MotionEvent.ACTION_UP)
            view.postDelayed({ if (c.textInputToplevel == toplevelId) showKeyboard(true) }, TAP_SHOW_DELAY_MS)
        return touch.onTouch(event)
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
                // Android: +1 per notch up; Wayland: 10 units per notch, down positive
                c.pointerAxis(toplevelId, -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 10f,
                    -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 10f, t)
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
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.device?.isVirtual != false)
            return super.dispatchKeyEvent(event)   // system back gesture
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
        )

        fun intent(context: Context, toplevelId: Int): Intent =
            Intent(context, WindowActivity::class.java)
                .setData(Uri.parse("droidnative://toplevel/$toplevelId"))
                .putExtra(EXTRA_TOPLEVEL, toplevelId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
    }
}
