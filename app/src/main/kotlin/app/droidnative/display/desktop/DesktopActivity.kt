package app.droidnative.display.desktop

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import app.droidnative.MainActivity
import app.droidnative.R
import app.droidnative.apps.DesktopEntry
import app.droidnative.apps.LinuxApps
import app.droidnative.apps.Shortcuts
import app.droidnative.apps.WindowSettings
import app.droidnative.display.Compositor
import app.droidnative.env.EnvService
import kotlin.math.roundToInt

/**
 * Desktop emulation ([DesktopMode]): one Android window for every Linux
 * toplevel, managed like a desktop. Windows ([DesktopWindowView]) can be
 * moved by their title bar, resized by the grip, minimised, maximised and
 * closed; the task bar has the application menu, a button per window, the
 * keyboard button, a clock and a button that collapses it to a small handle.
 *
 * Closing the desktop (Back, or removing it from recents) leaves the
 * programs running; [DesktopState] keeps the layout and the environment
 * notification brings the desktop back.
 */
class DesktopActivity : Activity(), Compositor.Listener {

    private val compositor = EnvService.compositor
    private val windows = HashMap<Int, DesktopWindowView>()
    private val taskButtons = HashMap<Int, View>()
    private lateinit var wallpaper: WallpaperView
    private lateinit var area: FrameLayout
    private lateinit var taskbar: LinearLayout
    private lateinit var tasks: LinearLayout
    private lateinit var handle: ImageButton
    private lateinit var outline: View
    private var wallpaperStamp = -1L
    private var hasFocus = false
    private var keyboardShown = false
    private var menu: PopupWindow? = null
    private val density get() = resources.displayMetrics.density

    // App list and icons, loaded in the background
    @Volatile private var entries: List<DesktopEntry> = emptyList()
    private val icons = HashMap<String, Bitmap?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DesktopMode.enabled(this)) {
            finish()
            return
        }
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        // Predictive back (API 33+) bypasses key events
        if (Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { moveTaskToBack(true) }
        drawBehindSystemBars()
        buildViews()
        DesktopState.host = this
        compositor.addListener(this)
        compositor.serverDecorations = true
        loadApps()
        // Every toplevel belongs to the desktop now, in the stacking order it had
        val ordered = DesktopState.windows.keys.mapNotNull { compositor.toplevels[it] } +
            compositor.toplevels.values.filter { it.id !in DesktopState.windows }.sortedBy { it.id }
        for (tl in ordered) addWindow(tl, focus = false)
        focus(windows[DesktopState.focused] ?: windows[DesktopState.topVisible()])
    }

    override fun onResume() {
        super.onResume()
        reloadWallpaper()
    }

    override fun onDestroy() {
        if (DesktopState.host === this) DesktopState.host = null
        compositor.removeListener(this)
        menu?.dismiss()
        // The programs keep running; their surfaces detach with the views
        for (id in windows.keys) compositor.hosted.remove(id)
        super.onDestroy()
    }

    /* Layout */

    @Suppress("DEPRECATION")
    private fun drawBehindSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // The wallpaper also fills the strip beside a camera cutout
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun buildViews() {
        val root = FrameLayout(this)
        wallpaper = WallpaperView(this)
        root.addView(wallpaper, FrameLayout.LayoutParams(MATCH, MATCH))

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        area = FrameLayout(this)
        area.clipChildren = true
        area.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            // A new width means rotation (or a resized Android window): bring
            // every window fully into view. Height alone is the keyboard or
            // the task bar: windows stay where they are, title bars reachable
            if (r - l != orr - ol) area.post { fitWindows(full = true) }
            else if (b - t != ob - ot) area.post { fitWindows(full = false) }
        }
        column.addView(area, LinearLayout.LayoutParams(MATCH, 0, 1f))
        taskbar = buildTaskbar()
        column.addView(taskbar, LinearLayout.LayoutParams(MATCH, dp(TASKBAR_DP)))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        outline = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0x333D7BD9)
                setStroke(dp(2), 0xFF3D7BD9.toInt())
            }
            visibility = View.GONE
        }
        area.addView(outline, FrameLayout.LayoutParams(1, 1))

        handle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_desk_expand)
            contentDescription = getString(R.string.desktop_expand)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC181B20.toInt())
            }
            setOnClickListener { setTaskbarCollapsed(false) }
        }
        root.addView(handle, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(8), dp(8))
        })

        // Status bar, navigation bar, cutout and keyboard stay clear
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                column.setPadding(i.left, i.top, i.right, i.bottom)
                (handle.layoutParams as FrameLayout.LayoutParams).setMargins(0, 0, i.right + dp(8), i.bottom + dp(8))
                keyboardShown = insets.isVisible(WindowInsets.Type.ime())
            } else {
                @Suppress("DEPRECATION")
                column.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            handle.requestLayout()
            WindowInsets.CONSUMED
        }
        setContentView(root)
        setTaskbarCollapsed(DesktopState.taskbarCollapsed)
    }

    private fun buildTaskbar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0xE6181B20.toInt())
        bar.addView(barButton(R.drawable.ic_desk_apps, R.string.desktop_apps) { showMenu(it) })
        tasks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tasks, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, MATCH))
        }
        bar.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f))
        bar.addView(barButton(R.drawable.ic_desk_keyboard, R.string.desktop_keyboard) { toggleKeyboard() })
        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm a"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
        }
        bar.addView(clock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, MATCH))
        bar.addView(barButton(R.drawable.ic_desk_collapse, R.string.desktop_collapse) { setTaskbarCollapsed(true) })
        return bar
    }

    private fun barButton(icon: Int, label: Int, action: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = getString(label)
        setBackgroundResource(android.R.drawable.list_selector_background)
        layoutParams = LinearLayout.LayoutParams(dp(TASKBAR_DP), MATCH)
        setOnClickListener { action(it) }
    }

    private fun setTaskbarCollapsed(collapsed: Boolean) {
        DesktopState.taskbarCollapsed = collapsed
        taskbar.visibility = if (collapsed) View.GONE else View.VISIBLE
        handle.visibility = if (collapsed) View.VISIBLE else View.GONE
    }

    private fun reloadWallpaper() {
        val f = Wallpaper.file(this)
        val stamp = if (f.isFile) f.lastModified() else 0L
        if (stamp == wallpaperStamp) return
        wallpaperStamp = stamp
        val dm = resources.displayMetrics
        val side = maxOf(dm.widthPixels, dm.heightPixels)
        Thread {
            val img = Wallpaper.load(applicationContext, side, side)
            runOnUiThread { if (!isDestroyed) wallpaper.show(img) else img?.bitmap?.recycle() }
        }.start()
    }

    /* Windows */

    /** Shows [tl] on the desktop (from WindowLauncher.open or at start). */
    fun addWindow(tl: Compositor.Toplevel, focus: Boolean = true) {
        if (windows.containsKey(tl.id) || !compositor.toplevels.containsKey(tl.id)) return
        val state = DesktopState.window(tl.id)
        val w = DesktopWindowView(this, tl, state, compositor)
        windows[tl.id] = w
        compositor.hosted.add(tl.id)
        area.addView(w, FrameLayout.LayoutParams(1, 1))
        if (!state.placed) place(w)
        applyGeometry(w)
        w.visibility = if (state.minimised) View.GONE else View.VISIBLE
        w.setIcon(iconFor(tl))
        addTaskButton(w)
        outline.bringToFront()
        if (focus && !state.minimised) focus(w)
    }

    private fun removeWindow(id: Int) {
        val w = windows.remove(id) ?: return
        area.removeView(w)
        compositor.hosted.remove(id)
        taskButtons.remove(id)?.let { tasks.removeView(it) }
        val wasFocused = DesktopState.focused == id
        DesktopState.remove(id)
        if (wasFocused) focus(windows[DesktopState.topVisible()])
    }

    // New windows: the size the program drew its first frame at, centred,
    // each one a little further down and right
    private fun place(w: DesktopWindowView) {
        val s = w.state
        val aw = area.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val ah = area.height.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels - dp(TASKBAR_DP))
        val tl = w.toplevel
        var cw: Int
        var ch: Int
        if (tl.width > 0 && tl.height > 0) {
            cw = (tl.width / w.renderScale).roundToInt()
            ch = (tl.height / w.renderScale).roundToInt()
        } else {
            cw = aw * 3 / 4
            ch = ah * 3 / 4
        }
        s.width = (cw + 2 * w.border).coerceIn(minWidth(), aw)
        s.height = (ch + w.titleHeight + 2 * w.border).coerceIn(minHeight(w), ah)
        val step = dp(CASCADE_DP) * (DesktopState.cascade % CASCADE_STEPS)
        DesktopState.cascade++
        s.x = ((aw - s.width) / 2 + step).coerceIn(0, (aw - s.width).coerceAtLeast(0))
        s.y = ((ah - s.height) / 3 + step).coerceIn(0, (ah - s.height).coerceAtLeast(0))
        s.placed = true
    }

    private fun applyGeometry(w: DesktopWindowView) {
        val s = w.state
        val lp = w.layoutParams as FrameLayout.LayoutParams
        if (s.maximised) {
            lp.width = MATCH
            lp.height = MATCH
            w.x = 0f
            w.y = 0f
        } else {
            lp.width = s.width
            lp.height = s.height
            w.x = s.x.toFloat()
            w.y = s.y.toFloat()
        }
        w.layoutParams = lp
        w.setMaximisedLook(s.maximised)
    }

    /**
     * The desktop changed size. [full]: windows larger than the desktop
     * shrink to it and all move fully inside; otherwise they only keep
     * their title bars reachable.
     */
    private fun fitWindows(full: Boolean) {
        menu?.dismiss()
        val aw = area.width
        val ah = area.height
        if (aw <= 0 || ah <= 0) return
        for (w in windows.values) {
            val s = w.state
            if (s.maximised) continue
            if (full) {
                val resized = s.width > aw || s.height > ah
                s.width = s.width.coerceAtMost(aw)
                s.height = s.height.coerceAtMost(ah)
                s.x = s.x.coerceIn(0, aw - s.width)
                s.y = s.y.coerceIn(0, ah - s.height)
                if (resized) applyGeometry(w)
            } else {
                clampPosition(w, s.x.toFloat(), s.y.toFloat())
            }
            w.x = s.x.toFloat()
            w.y = s.y.toFloat()
        }
    }

    private fun clampPosition(w: DesktopWindowView, x: Float, y: Float) {
        val s = w.state
        val keep = dp(KEEP_VISIBLE_DP)
        s.x = x.roundToInt().coerceIn(keep - s.width, (area.width - keep).coerceAtLeast(0))
        s.y = y.roundToInt().coerceIn(0, (area.height - w.titleHeight).coerceAtLeast(0))
    }

    private fun minWidth() = dp(MIN_WIDTH_DP)
    private fun minHeight(w: DesktopWindowView) = w.titleHeight + dp(MIN_CONTENT_DP)

    fun focus(w: DesktopWindowView?) {
        val old = DesktopState.focused
        if (w == null) {
            if (old != 0) compositor.focus(old, false)
            DesktopState.focused = 0
            updateTaskButtons()
            return
        }
        if (w.state.minimised) {
            w.state.minimised = false
            w.visibility = View.VISIBLE
        }
        DesktopState.raise(w.tid)
        w.bringToFront()
        outline.bringToFront()
        if (old != w.tid) {
            windows[old]?.setFocusedLook(false)
            if (old != 0) compositor.focus(old, false)
        }
        DesktopState.focused = w.tid
        w.setFocusedLook(true)
        if (hasFocus) compositor.focus(w.tid, true)
        w.content.requestFocus()
        if (hasFocus && compositor.pointerLocked.contains(w.tid)) w.content.requestPointerCapture()
        // The keyboard follows the focused window's text field
        if (old != w.tid) {
            val active = compositor.textInputToplevel == w.tid
            if (active && w.settings.autoKeyboard) showKeyboard(w, true)
            else if (!active) showKeyboard(w, false)
        }
        updateTaskButtons()
    }

    fun minimise(w: DesktopWindowView) {
        w.state.minimised = true
        w.visibility = View.GONE
        if (DesktopState.focused == w.tid) focus(windows[DesktopState.topVisible(except = w.tid)])
        else updateTaskButtons()
    }

    fun toggleMaximise(w: DesktopWindowView) {
        w.state.maximised = !w.state.maximised
        applyGeometry(w)
        focus(w)
    }

    fun beginMove(w: DesktopWindowView) {
        // Dragging a maximised window restores it under the finger
        if (w.state.maximised) {
            w.state.maximised = false
            applyGeometry(w)
        }
    }

    fun moveTo(w: DesktopWindowView, x: Float, y: Float) {
        clampPosition(w, x, y)
        w.x = w.state.x.toFloat()
        w.y = w.state.y.toFloat()
    }

    fun resizeOutline(w: DesktopWindowView, dx: Float, dy: Float) {
        val s = w.state
        val p = outline.layoutParams as FrameLayout.LayoutParams
        p.width = newWidth(w, dx)
        p.height = newHeight(w, dy)
        outline.layoutParams = p
        outline.x = s.x.toFloat()
        outline.y = s.y.toFloat()
        outline.visibility = View.VISIBLE
        outline.bringToFront()
    }

    fun resizeEnd(w: DesktopWindowView, dx: Float, dy: Float) {
        outline.visibility = View.GONE
        w.state.width = newWidth(w, dx)
        w.state.height = newHeight(w, dy)
        applyGeometry(w)
    }

    private fun newWidth(w: DesktopWindowView, dx: Float) =
        (w.state.width + dx).roundToInt().coerceIn(minWidth(), (area.width - w.state.x).coerceAtLeast(minWidth()))

    private fun newHeight(w: DesktopWindowView, dy: Float) =
        (w.state.height + dy).roundToInt().coerceIn(minHeight(w), (area.height - w.state.y).coerceAtLeast(minHeight(w)))

    /* Task bar */

    private fun addTaskButton(w: DesktopWindowView) {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(10), 0)
            setOnClickListener {
                // Tap: bring it up; tap on the focused window: minimise it
                if (DesktopState.focused == w.tid && !w.state.minimised) minimise(w) else focus(w)
            }
        }
        val icon = ImageView(this).apply {
            tag = ICON_TAG
            val bmp = iconFor(w.toplevel)
            if (bmp != null) setImageBitmap(bmp) else setImageResource(R.drawable.ic_penguin)
        }
        b.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
        val label = TextView(this).apply {
            tag = LABEL_TAG
            setTextColor(Color.WHITE)
            textSize = 13f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(TASK_MAX_DP)
            setPadding(dp(6), 0, 0, 0)
        }
        b.addView(label)
        tasks.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, MATCH).apply {
            marginEnd = dp(2)
        })
        taskButtons[w.tid] = b
        updateTaskButtons()
    }

    private fun updateTaskButtons() {
        for ((id, b) in taskButtons) {
            val w = windows[id] ?: continue
            b.findViewWithTag<TextView>(LABEL_TAG)?.text =
                w.toplevel.title.ifEmpty { w.toplevel.appId.ifEmpty { getString(R.string.window_unnamed) } }
            b.background = if (id == DesktopState.focused && !w.state.minimised) ColorDrawable(0x803D7BD9.toInt()) else null
            b.alpha = if (w.state.minimised) 0.6f else 1f
        }
    }

    /* Application menu */

    private fun loadApps() {
        Thread {
            val list = runCatching { LinuxApps(applicationContext).list() }.getOrDefault(emptyList())
            entries = list
            val apps = LinuxApps(applicationContext)
            val loaded = HashMap<String, Bitmap?>()
            for (e in list) loaded[e.id] = runCatching { apps.icon(e) }.getOrNull()
            runOnUiThread {
                icons.putAll(loaded)
                for (w in windows.values) {
                    val bmp = iconFor(w.toplevel)
                    w.setIcon(bmp)
                    taskButtons[w.tid]?.findViewWithTag<ImageView>(ICON_TAG)?.let {
                        if (bmp != null) it.setImageBitmap(bmp)
                    }
                }
            }
        }.start()
    }

    private val appOfToplevel = HashMap<Int, String?>()

    // Not cached while unknown: the app list may still be loading
    private fun iconFor(tl: Compositor.Toplevel): Bitmap? {
        val id = appOfToplevel[tl.id] ?: (WindowSettings.resolveApp(this, tl.pid)
            ?: entries.firstOrNull { it.id.equals(tl.appId, ignoreCase = true) }?.id)
            ?.also { appOfToplevel[tl.id] = it }
            ?: return null
        return icons[id]
    }

    private fun showMenu(anchor: View) {
        menu?.dismiss()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(text: String, icon: Bitmap?, iconRes: Int, action: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(16), dp(8))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { menu?.dismiss(); action() }
            }
            val iv = ImageView(this)
            if (icon != null) iv.setImageBitmap(icon) else if (iconRes != 0) iv.setImageResource(iconRes)
            r.addView(iv, LinearLayout.LayoutParams(dp(28), dp(28)))
            r.addView(TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(dp(12), 0, 0, 0)
            })
            list.addView(r)
        }
        val apps = entries
        if (apps.isEmpty()) {
            list.addView(TextView(this).apply {
                setText(R.string.desktop_no_apps)
                setTextColor(0xB3FFFFFF.toInt())
                setPadding(dp(16), dp(12), dp(16), dp(12))
            })
        }
        for (e in apps) row(e.name, icons[e.id], R.drawable.ic_penguin) { launch(e) }
        list.addView(View(this).apply { setBackgroundColor(0x33FFFFFF) },
            LinearLayout.LayoutParams(MATCH, dp(1)).apply { setMargins(0, dp(4), 0, dp(4)) })
        row(getString(R.string.desktop_wallpaper_choose), null, 0) { pickWallpaper() }
        if (Wallpaper.isCustom(this))
            row(getString(R.string.desktop_wallpaper_reset), null, 0) {
                Wallpaper.reset(this)
                reloadWallpaper()
            }
        row(getString(R.string.desktop_settings), null, R.drawable.ic_tab_settings) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        row(getString(R.string.desktop_leave), null, 0) { moveTaskToBack(true) }

        val scroll = ScrollView(this).apply {
            addView(list)
            setPadding(0, dp(4), 0, dp(4))
            background = GradientDrawable().apply {
                setColor(0xF2202329.toInt())
                cornerRadius = dp(8).toFloat()
            }
            clipToOutline = true
        }
        // Placed inside the desktop area by hand: a drop-down that does not
        // fit is moved by Android within the whole (edge-to-edge) window,
        // over the status bar and the task bar
        val margin = dp(MENU_MARGIN_DP)
        val width = minOf(dp(MENU_WIDTH_DP), area.width - 2 * margin)
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = minOf(scroll.measuredHeight, area.height - 2 * margin)
        val pw = PopupWindow(scroll, width, height, true)
        pw.elevation = dp(8).toFloat()
        menu = pw
        val loc = IntArray(2)
        area.getLocationInWindow(loc)
        val anchorLoc = IntArray(2)
        anchor.getLocationInWindow(anchorLoc)
        val x = anchorLoc[0].coerceIn(loc[0] + margin, loc[0] + area.width - margin - width)
        pw.showAtLocation(area, Gravity.NO_GRAVITY, x, loc[1] + area.height - margin - height)
    }

    private fun launch(e: DesktopEntry) {
        // LaunchActivity starts it and reports a failed start with its log;
        // the window then comes here (WindowLauncher.open)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Shortcuts.uri(e)).setPackage(packageName))
        } catch (ex: Exception) {
            Toast.makeText(this, getString(R.string.apps_start_failed, e.name) + ": " + ex.message, Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun pickWallpaper() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try {
            startActivityForResult(i, PICK_WALLPAPER)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.desktop_wallpaper_failed, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Activity result API of plain activities")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_WALLPAPER || resultCode != RESULT_OK || uri == null) return
        Thread {
            val ok = Wallpaper.set(applicationContext, uri)
            runOnUiThread {
                if (ok) reloadWallpaper()
                else Toast.makeText(this, R.string.desktop_wallpaper_failed, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /* Keyboard */

    fun showKeyboard(w: DesktopWindowView, show: Boolean) {
        if (DesktopState.focused != w.tid) return
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) {
            w.content.requestFocus()
            imm.showSoftInput(w.content, 0)
        } else {
            imm.hideSoftInputFromWindow(w.content.windowToken, 0)
        }
        if (Build.VERSION.SDK_INT < 30) keyboardShown = show
    }

    fun toggleKeyboard() {
        val w = windows[DesktopState.focused] ?: return
        showKeyboard(w, !keyboardShown)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.device?.isVirtual != false &&
            !event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            // Back leaves the desktop running, like Android's own apps
            if (event.action == KeyEvent.ACTION_UP) moveTaskToBack(true)
            return true
        }
        val w = windows[DesktopState.focused]
        if (w != null && menu?.isShowing != true && w.input.key(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        this.hasFocus = hasFocus
        val id = DesktopState.focused
        if (id == 0) return
        compositor.focus(id, hasFocus)
        if (hasFocus && compositor.pointerLocked.contains(id)) windows[id]?.content?.requestPointerCapture()
    }

    /* Compositor events (compositor thread) */

    override fun onToplevelChanged(toplevel: Compositor.Toplevel) {
        runOnUiThread {
            windows[toplevel.id]?.updateTitle()
            updateTaskButtons()
        }
    }

    override fun onToplevelClosed(id: Int) {
        runOnUiThread { removeWindow(id) }
    }

    override fun onTextInput(id: Int, active: Boolean) {
        runOnUiThread {
            val w = windows[id] ?: return@runOnUiThread
            // Hiding always follows the app; showing only if wanted for this app
            if (!active || w.settings.autoKeyboard) showKeyboard(w, active)
        }
    }

    override fun onPointerLock(id: Int, locked: Boolean) {
        runOnUiThread {
            val w = windows[id] ?: return@runOnUiThread
            w.input.touch.locked = locked
            if (locked && hasFocus && DesktopState.focused == id) w.content.requestPointerCapture()
            else if (!locked) w.content.releasePointerCapture()
        }
    }

    override fun onDesktopEmulation(enabled: Boolean) {
        if (!enabled) runOnUiThread { finishAndRemoveTask() }
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val TASKBAR_DP = 44
        private const val TASK_MAX_DP = 140
        private const val MENU_WIDTH_DP = 280
        private const val MENU_MARGIN_DP = 6
        private const val MIN_WIDTH_DP = 160
        private const val MIN_CONTENT_DP = 80
        private const val KEEP_VISIBLE_DP = 64
        private const val CASCADE_DP = 28
        private const val CASCADE_STEPS = 6
        private const val PICK_WALLPAPER = 1
        private const val ICON_TAG = "icon"
        private const val LABEL_TAG = "label"
    }
}
