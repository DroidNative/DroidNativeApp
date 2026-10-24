package app.droidnative.display

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import app.droidnative.apps.WindowSettings
import app.droidnative.display.desktop.DesktopMode
import app.droidnative.display.desktop.DesktopState
import app.droidnative.env.EnvService
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Opens the Android window ([WindowActivity]) of a Linux toplevel.
 *
 * Android opens a new task fullscreen unless it gets launch bounds, also
 * while the user works in freeform or desktop (DeX) windows. So while the
 * last used DroidNative window is such a window ([desktopMode], kept up to
 * date by [noteWindow]), a new window waits up to [SIZE_WAIT_MS] for the
 * size of the program's first frame ([Compositor.Listener.onToplevelSize])
 * and opens as a freeform window of that size: buffer pixels divided by the
 * render factor (per-app resolution), plus the caption bar Android draws
 * inside the bounds. It is centred on the last used window and started
 * from it: Android passes a source activity's freeform mode on to the new
 * task, while bounds alone are ignored where freeform is only switched on
 * in the developer options (seen on Android 13).
 *
 * The same mode switches GTK's own title bar off for windows created while
 * it lasts ([Compositor.serverDecorations]).
 *
 * With desktop emulation on (display/desktop/DesktopMode.kt) every window
 * goes into the desktop instead, also after waiting for its first frame,
 * whose size the desktop window takes.
 */
object WindowLauncher : Compositor.Listener {

    @Volatile var desktopMode = false
        private set
    @Volatile private var lastBounds: Rect? = null
    @Volatile private var lastWindow: WeakReference<Activity>? = null
    // Caption bar inside freeform bounds, measured in our windows; -1 unknown
    @Volatile private var captionPx = -1

    private val handler = Handler(Looper.getMainLooper())
    private val waiting = HashMap<Int, MutableList<() -> Unit>>()
    private var listening = false

    /**
     * True for a freeform or desktop window. Android has no public API for
     * the windowing mode; its configuration string names it, and Samsung
     * DeX has its own flag.
     */
    fun isDesktopWindow(activity: Activity): Boolean {
        val cfg = activity.resources.configuration
        if (samsungDesktop(cfg)) return true
        return activity.isInMultiWindowMode && "mWindowingMode=freeform" in cfg.toString()
    }

    private fun samsungDesktop(cfg: Configuration): Boolean = runCatching {
        val c = Configuration::class.java
        c.getField("semDesktopModeEnabled").getInt(cfg) == c.getField("SEM_DESKTOP_MODE_ENABLED").getInt(null)
    }.getOrDefault(false)

    /**
     * The user works in [activity] now (resumed, focused or resized).
     * [content]: its content view, whose position shows the caption bar.
     */
    fun noteWindow(activity: Activity, content: View? = null) {
        val desktop = isDesktopWindow(activity)
        desktopMode = desktop
        EnvService.compositor.serverDecorations = desktop || DesktopMode.enabled(activity)
        if (!desktop) return
        lastWindow = WeakReference(activity)
        val b = windowBounds(activity) ?: return
        lastBounds = b
        if (content != null && content.isLaidOut) {
            val pos = IntArray(2)
            content.getLocationOnScreen(pos)
            val caption = pos[1] - b.top
            if (caption >= 0 && caption < b.height() / 3) captionPx = caption
        }
    }

    /**
     * Opens the window of [tl] from [context], then runs [then] on the main
     * thread (also when the start failed).
     */
    fun open(context: Context, tl: Compositor.Toplevel, then: () -> Unit = {}) {
        val emulated = DesktopMode.enabled(context)
        val go = if (emulated) ({ openInDesktop(context, tl); then() }) else ({ start(context, tl); then() })
        if ((!desktopMode && !emulated) || tl.width > 0) {
            handler.post(go)
            return
        }
        synchronized(waiting) {
            if (!listening) {
                EnvService.compositor.addListener(this)
                listening = true
            }
            waiting.getOrPut(tl.id) { mutableListOf() } += go
        }
        handler.postDelayed({ release(tl.id) }, SIZE_WAIT_MS)
    }

    override fun onToplevelSize(toplevel: Compositor.Toplevel) {
        handler.post { release(toplevel.id) }
    }

    override fun onToplevelClosed(id: Int) {
        synchronized(waiting) { waiting.remove(id) }
    }

    private fun release(id: Int) {
        val run = synchronized(waiting) { waiting.remove(id) } ?: return
        for (r in run) r()
    }

    private fun openInDesktop(context: Context, tl: Compositor.Toplevel) {
        DesktopState.host?.addWindow(tl)
        DesktopMode.open(context)
    }

    private fun start(context: Context, tl: Compositor.Toplevel) {
        try {
            val bounds = if (desktopMode) launchBounds(context, tl) else null
            if (bounds != null) {
                val source = lastWindow?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: context
                Log.i(TAG, "toplevel ${tl.id}: freeform window $bounds (first frame ${tl.width}x${tl.height})")
                source.startActivity(WindowActivity.intent(source, tl.id),
                    ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle())
            } else {
                context.startActivity(WindowActivity.intent(context, tl.id))
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot open window for ${tl.id}", e)
        }
    }

    private fun launchBounds(context: Context, tl: Compositor.Toplevel): Rect? {
        val display = displayBounds(context)
        val last = lastBounds
        val density = context.resources.displayMetrics.density
        val caption = if (captionPx >= 0) captionPx else (DEFAULT_CAPTION_DP * density).roundToInt()
        var w: Int
        var h: Int
        if (tl.width > 0 && tl.height > 0) {
            val factor = WindowSettings.forClient(context, tl.pid).renderFactor.takeIf { it > 0f } ?: 1f
            w = (tl.width / factor).roundToInt()
            h = (tl.height / factor).roundToInt() + caption
        } else if (last != null) {
            w = last.width()
            h = last.height()
        } else {
            w = display.width() * 2 / 3
            h = display.height() * 2 / 3
        }
        w = w.coerceIn(1, display.width())
        h = h.coerceIn(1, display.height())
        val cx = last?.centerX() ?: display.centerX()
        val cy = last?.centerY() ?: display.centerY()
        val left = (cx - w / 2).coerceIn(display.left, display.right - w)
        val top = (cy - h / 2).coerceIn(display.top, display.bottom - h)
        return Rect(left, top, left + w, top + h)
    }

    private fun windowBounds(activity: Activity): Rect? =
        if (Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds
        else null

    // The service has no window; fall back to the display metrics there
    private fun displayBounds(context: Context): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds }
                .getOrNull()?.let { return it }
        }
        return context.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
    }

    private const val TAG = "dn-window"
    // A Wayland program draws its first frame right after the first configure
    private const val SIZE_WAIT_MS = 400L
    private const val DEFAULT_CAPTION_DP = 40
}
