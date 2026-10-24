package app.droidnative.display.desktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.droidnative.R
import app.droidnative.apps.WindowSettings
import app.droidnative.display.Compositor
import app.droidnative.display.ToplevelInput
import app.droidnative.overlay.OverlayStore
import app.droidnative.overlay.OverlayView
import kotlin.math.roundToInt

/**
 * One Linux toplevel on the desktop: a title bar (icon, title, minimise,
 * maximise, close), the content and a resize grip. The content is a
 * TextureView, unlike WindowActivity's SurfaceView, so windows can overlap
 * and stack in any order; the compositor renders into its SurfaceTexture.
 * Moving, resizing and focus are decided by [DesktopActivity].
 */
@SuppressLint("ViewConstructor")
class DesktopWindowView(
    private val desk: DesktopActivity,
    val toplevel: Compositor.Toplevel,
    val state: DesktopState.Window,
    private val compositor: Compositor,
) : FrameLayout(desk), TextureView.SurfaceTextureListener {

    /** Compositor id of the toplevel. */
    val tid = toplevel.id
    private val density = resources.displayMetrics.density
    val titleHeight = (TITLE_DP * density).roundToInt()
    val border = (BORDER_DP * density).roundToInt().coerceAtLeast(1)

    val settings = WindowSettings.forClient(desk, toplevel.pid)
    // Buffer pixels per view pixel (below 1 when a lower resolution is set)
    val renderScale = settings.renderFactor

    private val titleBar = LinearLayout(desk)
    private val icon = ImageView(desk)
    private val title = TextView(desk)
    private val maximiseButton = button(R.drawable.ic_win_maximise, R.string.window_maximise) { desk.toggleMaximise(this) }
    val content = ContentView(desk)
    private val cursor = ImageView(desk)
    private val grip = ImageView(desk)
    val input: ToplevelInput
    private var surface: Surface? = null
    private var bufferW = 0
    private var bufferH = 0
    private val main = Handler(Looper.getMainLooper())

    init {
        setBackgroundColor(COLOR_BORDER)
        setPadding(border, border, border, border)

        titleBar.orientation = LinearLayout.HORIZONTAL
        titleBar.gravity = Gravity.CENTER_VERTICAL
        titleBar.setBackgroundColor(COLOR_TITLE)
        val iconSize = (18 * density).roundToInt()
        titleBar.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize).apply {
            marginStart = (8 * density).roundToInt()
            marginEnd = (6 * density).roundToInt()
        })
        title.setTextColor(COLOR_TEXT)
        title.textSize = 13f
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        titleBar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(button(R.drawable.ic_win_minimise, R.string.window_minimise) { desk.minimise(this) })
        titleBar.addView(maximiseButton)
        titleBar.addView(button(R.drawable.ic_win_close, R.string.window_close) { compositor.close(tid) })
        titleBar.setOnTouchListener(TitleDrag())

        val body = FrameLayout(desk)
        content.surfaceTextureListener = this
        content.isFocusable = true
        content.isFocusableInTouchMode = true
        body.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Pointer of the touchpad mode (Android draws none for us)
        cursor.setImageResource(R.drawable.ic_cursor)
        cursor.visibility = View.GONE
        body.addView(cursor, LayoutParams((20 * density).roundToInt(), (20 * density).roundToInt()))

        val column = LinearLayout(desk)
        column.orientation = LinearLayout.VERTICAL
        column.addView(titleBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, titleHeight))
        column.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val gripSize = (GRIP_DP * density).roundToInt()
        grip.setImageResource(R.drawable.ic_win_grip)
        grip.setOnTouchListener(GripDrag())
        addView(grip, LayoutParams(gripSize, gripSize, Gravity.BOTTOM or Gravity.END))

        input = ToplevelInput(compositor, tid, content, cursor, settings, { renderScale }) { desk.showKeyboard(this, it) }
        input.touch.locked = compositor.pointerLocked.contains(tid)
        settings.overlay?.let { OverlayStore(desk).get(it) }?.let { layout ->
            val o = OverlayView(desk, layout, compositor, tid) { desk.toggleKeyboard() }
            input.overlay = o
            body.addView(o, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> input.touch.clampCursor() }
        updateTitle()
        setFocusedLook(false)
    }

    fun updateTitle() {
        title.text = toplevel.title.ifEmpty { toplevel.appId.ifEmpty { context.getString(R.string.window_unnamed) } }
    }

    fun setIcon(bitmap: Bitmap?) {
        if (bitmap != null) icon.setImageBitmap(bitmap) else icon.setImageResource(R.drawable.ic_penguin)
    }

    fun setFocusedLook(focused: Boolean) {
        titleBar.setBackgroundColor(if (focused) COLOR_TITLE_FOCUSED else COLOR_TITLE)
        title.alpha = if (focused) 1f else 0.7f
    }

    fun setMaximisedLook(maximised: Boolean) {
        maximiseButton.setImageResource(if (maximised) R.drawable.ic_win_restore else R.drawable.ic_win_maximise)
        grip.visibility = if (maximised) View.GONE else View.VISIBLE
    }

    private fun button(iconRes: Int, label: Int, action: () -> Unit) = ImageButton(desk).apply {
        setImageResource(iconRes)
        contentDescription = context.getString(label)
        setBackgroundResource(android.R.drawable.list_selector_background)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = (6 * density).roundToInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(titleHeight + pad, titleHeight)
        setOnClickListener { action() }
    }

    /* Surface */

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        setBufferSize(st, width, height)
        val s = Surface(st)
        surface = s
        compositor.attach(tid, s, bufferW, bufferH, this)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        setBufferSize(st, width, height)
        compositor.resize(tid, bufferW, bufferH)
    }

    // The compositor thread may still draw into it until the detach is
    // processed: release a moment later instead of right away
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        compositor.detach(tid, this)
        val s = surface
        surface = null
        main.postDelayed({
            s?.release()
            st.release()
        }, RELEASE_DELAY_MS)
        return false
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun setBufferSize(st: SurfaceTexture, width: Int, height: Int) {
        bufferW = (width * renderScale).roundToInt().coerceAtLeast(1)
        bufferH = (height * renderScale).roundToInt().coerceAtLeast(1)
        st.setDefaultBufferSize(bufferW, bufferH)
    }

    /** Content: forwards input to the toplevel and accepts an input method. */
    inner class ContentView(context: Context) : TextureView(context) {
        override fun onCheckIsTextEditor() = true

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) desk.focus(this@DesktopWindowView)
            return input.onTouch(event)
        }

        override fun onCapturedPointerEvent(event: MotionEvent) = input.onCapturedPointer(event)

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) desk.focus(this@DesktopWindowView)
            return input.onGenericMotion(event) || super.onGenericMotionEvent(event)
        }

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection =
            input.createInputConnection(outAttrs)
    }

    /* Moving and resizing */

    // Three fingers moving anywhere on the window move it. Only once they
    // moved: a three-finger tap still reaches the program (middle click)
    private val slop = ViewConfiguration.get(desk).scaledTouchSlop
    private var threeArmed = false
    private var threeMoving = false
    private var threeStartX = 0f
    private var threeStartY = 0f
    private var threeWinX = 0f
    private var threeWinY = 0f

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 3) {
                threeArmed = true
                threeStartX = rawAvgX(e, -1)
                threeStartY = rawAvgY(e, -1)
            }
            MotionEvent.ACTION_MOVE -> if (threeArmed && e.pointerCount >= 3 &&
                kotlin.math.hypot(rawAvgX(e, -1) - threeStartX, rawAvgY(e, -1) - threeStartY) > slop) {
                threeMoving = true
                desk.focus(this)
                desk.beginMove(this)
                startThreeMove(e, -1)
                return true   // the content gets ACTION_CANCEL
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> threeArmed = false
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!threeMoving) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> desk.moveTo(this,
                threeWinX + rawAvgX(e, -1) - threeStartX, threeWinY + rawAvgY(e, -1) - threeStartY)
            // A finger more or less: carry on from the new centre
            MotionEvent.ACTION_POINTER_DOWN -> startThreeMove(e, -1)
            MotionEvent.ACTION_POINTER_UP -> startThreeMove(e, e.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                threeMoving = false
                threeArmed = false
            }
        }
        return true
    }

    private fun startThreeMove(e: MotionEvent, skip: Int) {
        threeStartX = rawAvgX(e, skip)
        threeStartY = rawAvgY(e, skip)
        threeWinX = x
        threeWinY = y
    }

    // Screen position of the fingers' centre (getRawX(i) needs API 29);
    // [skip]: index of a finger going up, -1 for none
    private fun rawAvgX(e: MotionEvent, skip: Int): Float {
        val off = e.rawX - e.getX(0)
        var sum = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getX(i); n++ }
        return if (n == 0) e.rawX else sum / n + off
    }

    private fun rawAvgY(e: MotionEvent, skip: Int): Float {
        val off = e.rawY - e.getY(0)
        var sum = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getY(i); n++ }
        return if (n == 0) e.rawY else sum / n + off
    }

    // Drag moves the window, double tap maximises
    private inner class TitleDrag : OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var winX = 0f
        private var winY = 0f
        private var moving = false
        private var lastTap = 0L
        private val slop = ViewConfiguration.get(desk).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    desk.focus(this@DesktopWindowView)
                    startX = e.rawX; startY = e.rawY
                    winX = x; winY = y
                    moving = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (!moving && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        moving = true
                        desk.beginMove(this@DesktopWindowView)
                        winX = x; winY = y
                        startX = e.rawX; startY = e.rawY
                    }
                    if (moving) desk.moveTo(this@DesktopWindowView, winX + dx, winY + dy)
                }
                MotionEvent.ACTION_UP -> {
                    if (!moving) {
                        if (e.eventTime - lastTap < DOUBLE_TAP_MS) {
                            lastTap = 0L
                            desk.toggleMaximise(this@DesktopWindowView)
                        } else {
                            lastTap = e.eventTime
                        }
                    }
                }
            }
            return true
        }
    }

    // The grip shows an outline while dragging; the window takes its size
    // at the end, so the program is not reconfigured on every move
    private inner class GripDrag : OnTouchListener {
        private var startX = 0f
        private var startY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    desk.focus(this@DesktopWindowView)
                    startX = e.rawX; startY = e.rawY
                    desk.resizeOutline(this@DesktopWindowView, 0f, 0f)
                }
                MotionEvent.ACTION_MOVE -> desk.resizeOutline(this@DesktopWindowView, e.rawX - startX, e.rawY - startY)
                MotionEvent.ACTION_UP -> desk.resizeEnd(this@DesktopWindowView, e.rawX - startX, e.rawY - startY)
                MotionEvent.ACTION_CANCEL -> desk.resizeEnd(this@DesktopWindowView, 0f, 0f)
            }
            return true
        }
    }

    companion object {
        const val TITLE_DP = 34
        const val BORDER_DP = 1
        const val GRIP_DP = 28
        private const val DOUBLE_TAP_MS = 300L
        private const val RELEASE_DELAY_MS = 500L
        private const val COLOR_BORDER = 0xFF3A3F47.toInt()
        private const val COLOR_TITLE = 0xFF24272C.toInt()
        private const val COLOR_TITLE_FOCUSED = 0xFF2F4A6E.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
    }
}
