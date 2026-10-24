package app.droidnative.display.desktop

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.droidnative.display.WindowLauncher
import app.droidnative.env.EnvService
import app.droidnative.env.Prefs

/**
 * Desktop emulation (Settings, "Desktop emulation mode"): for devices without
 * freeform windows, every Linux window opens inside one Android window,
 * [DesktopActivity], which acts as a window manager with a task bar.
 *
 * Switching it on moves open windows into the desktop (their WindowActivity
 * hands the toplevel over, see Compositor.Listener.onDesktopEmulation);
 * switching it off gives each window its own task again. The environment
 * notification then reads "Desktop environment is running" and reopens the
 * desktop (EnvService.updateNotification).
 */
object DesktopMode {

    private val handler = Handler(Looper.getMainLooper())

    fun enabled(context: Context) = Prefs(context).desktopEmulation

    /** Main thread. */
    fun set(context: Context, on: Boolean) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (prefs.desktopEmulation == on) return
        prefs.desktopEmulation = on
        val c = EnvService.compositor
        // The desktop draws the title bars
        c.serverDecorations = on || WindowLauncher.desktopMode
        if (EnvService.isRunning) EnvService.updateNotification(app)
        c.desktopEmulationChanged(on)
        if (on) {
            if (c.toplevels.isNotEmpty()) open(context)
        } else {
            // The desktop has finished (its listener); reopen its windows
            // once it let go of them
            handler.postDelayed({
                for (tl in c.toplevels.values)
                    if (!c.isAttached(tl.id)) WindowLauncher.open(app, tl)
            }, HANDOVER_MS)
        }
    }

    fun intent(context: Context): Intent =
        Intent(context, DesktopActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    /** Brings the desktop up (Android refuses this from the background). */
    fun open(context: Context) {
        try {
            context.startActivity(intent(context))
        } catch (e: Exception) {
            Log.w(TAG, "cannot open the desktop", e)
        }
    }

    private const val TAG = "dn-desktop"
    private const val HANDOVER_MS = 300L
}

/**
 * Window layout of the desktop, kept for the life of the process so a
 * closed desktop comes back as it was. Positions are pixels in the desktop
 * area (below the status bar, above the task bar).
 */
object DesktopState {

    class Window(val id: Int) {
        var x = 0
        var y = 0
        var width = 0                 // frame, title bar included
        var height = 0
        var minimised = false
        var maximised = false
        var placed = false            // got a position (DesktopActivity.place)
    }

    /** Stacking order, the topmost last. */
    val windows = LinkedHashMap<Int, Window>()
    var focused = 0
    var taskbarCollapsed = false
    // Next cascade offset for new windows, in steps
    var cascade = 0

    /** The desktop while it exists (main thread). */
    var host: DesktopActivity? = null

    fun window(id: Int): Window = windows.getOrPut(id) { Window(id) }

    fun raise(id: Int) {
        val w = windows.remove(id) ?: return
        windows[id] = w
    }

    fun remove(id: Int) {
        windows.remove(id)
        if (focused == id) focused = 0
    }

    /** Topmost window that is not minimised, 0 if none. */
    fun topVisible(except: Int = 0): Int =
        windows.values.lastOrNull { !it.minimised && it.id != except }?.id ?: 0
}
