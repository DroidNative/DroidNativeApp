package app.droidnative.display

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import app.droidnative.bridge.Bridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Kotlin side of the native Wayland compositor (app/src/main/cpp/compositor).
 *
 * The native server lives on its own thread: create, run and destroy all
 * happen there because the EGL context is bound to it. Everything else may
 * be called from any thread.
 */
class Compositor {

    data class Config(
        val socketPath: String,
        val keymapPath: String?,
        val tmpDir: String,
        val scale: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val physWidthMm: Int,
        val physHeightMm: Int,
        val refreshMhz: Int,
        val touchMode: TouchMode = TouchMode.POINTER,
    )

    enum class TouchMode { POINTER, NATIVE }

    /**
     * [pid]: the Wayland client process (host pid), 0 if unknown.
     * [width]/[height]: size of the first frame the program drew before it
     * had a window (buffer pixels), 0 until known (see [Listener.onToplevelSize]).
     */
    class Toplevel(val id: Int, @Volatile var appId: String, @Volatile var title: String, val pid: Int = 0) {
        @Volatile var width = 0
        @Volatile var height = 0
    }

    data class Stats(
        val commits: Long, val shmCommits: Long, val dmabufCommits: Long,
        val dmabufMapFailures: Long, val framesPresented: Long,
        val width: Int, val height: Int, val format: Int,
        val bufferKind: Int, val nonBlackSamples: Int, val hasWindow: Boolean,
    )

    interface Listener {
        fun onToplevelNew(toplevel: Toplevel) {}
        fun onToplevelChanged(toplevel: Toplevel) {}
        fun onToplevelClosed(id: Int) {}
        /** The program drew its first frame at its own size, [Toplevel.width] x [Toplevel.height]. */
        fun onToplevelSize(toplevel: Toplevel) {}
        /** A text field of the toplevel wants (or no longer wants) text entry. */
        fun onTextInput(id: Int, active: Boolean) {}
        /** A program captured (locked) or released the pointer in the toplevel. */
        fun onPointerLock(id: Int, locked: Boolean) {}
        /**
         * Desktop emulation was switched on or off: windows move into the
         * desktop or out of it into their own tasks (desktop/DesktopMode.kt).
         */
        fun onDesktopEmulation(enabled: Boolean) {}
    }

    val toplevels = ConcurrentHashMap<Int, Toplevel>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var handle = 0L
    private var thread: Thread? = null

    val isRunning get() = handle != 0L

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    /** Starts the server; returns false if it could not be created. */
    @Synchronized
    fun start(config: Config): Boolean {
        if (thread != null) return isRunning
        val ready = CountDownLatch(1)
        val t = Thread({
            val h = nativeCreate(
                config.socketPath, config.keymapPath, config.tmpDir, config.scale,
                config.displayWidth, config.displayHeight, config.physWidthMm,
                config.physHeightMm, config.refreshMhz, config.touchMode.ordinal,
            )
            handle = h
            ready.countDown()
            if (h == 0L) {
                Log.e(TAG, "compositor creation failed")
                return@Thread
            }
            if (serverDecorations) nativeSetServerDecorations(h, true)
            val rc = nativeRun(h)
            Log.i(TAG, "compositor loop ended ($rc)")
            handle = 0L
            nativeDestroy(h)
            toplevels.clear()
        }, "dn-compositor")
        thread = t
        t.start()
        ready.await(10, TimeUnit.SECONDS)
        if (!isRunning) {
            thread = null
            return false
        }
        return true
    }

    @Synchronized
    fun stop() {
        val h = handle
        if (h != 0L) nativeStop(h)
        thread?.join(5_000)
        thread = null
    }

    // Host that attached the toplevel's window last: a host going away (a
    // window moving into the desktop or out of it) must not detach the
    // window the next host already attached
    private val owners = ConcurrentHashMap<Int, Any>()

    fun attach(id: Int, surface: Surface, width: Int, height: Int, owner: Any) {
        owners[id] = owner
        withHandle { nativeAttach(it, id, surface, width, height) }
    }
    fun detach(id: Int, owner: Any) {
        if (!owners.remove(id, owner)) return
        withHandle { nativeDetach(it, id) }
    }
    /** True while some host shows the toplevel. */
    fun isAttached(id: Int) = owners.containsKey(id)
    fun resize(id: Int, width: Int, height: Int) = withHandle { nativeResize(it, id, width, height) }

    /**
     * GTK 3 windows created from now on leave the title bar to Android
     * (freeform and desktop windows have one) instead of drawing their own.
     */
    @Volatile var serverDecorations = false
        set(v) {
            if (field == v) return
            field = v
            withHandle { nativeSetServerDecorations(it, v) }
        }
    fun focus(id: Int, focused: Boolean) {
        if (focused) focusedToplevel = id
        else if (focusedToplevel == id) focusedToplevel = 0
        withHandle { nativeFocus(it, id, focused) }
    }

    /** Toplevel whose Android window has focus, 0 if none. */
    @Volatile private var focusedToplevel = 0
    fun close(id: Int) = withHandle { nativeClose(it, id) }
    /** Replaces the keyboard layout (compiled xkb keymap file) for all clients. */
    fun setKeymap(path: String) = withHandle { nativeSetKeymap(it, path) }

    /** Ends the client of [id] (Wayland: disconnect, X11: kill). */
    fun kill(id: Int) = withHandle { nativeKill(it, id) }

    /** Toplevels that currently have an Android window (WindowActivity). */
    val hosted: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** When the user last closed a window from recents (elapsedRealtime). */
    @Volatile var lastHostClose = 0L
        private set
    private val main = Handler(Looper.getMainLooper())

    /**
     * The user removed the window from recents: ask the application to
     * close, and end it after [CLOSE_GRACE_MS] unless it did, or another
     * of its windows (a "save changes?" dialog, say) is open on screen.
     */
    fun closeFromHost(id: Int) {
        lastHostClose = SystemClock.elapsedRealtime()
        val pid = toplevels[id]?.pid ?: 0
        close(id)
        main.postDelayed({
            // The app may answer with new windows (dialogs, a restored
            // session) that Android does not let it show from the background:
            // what counts is whether the process still has a visible window
            val remaining = if (pid != 0) toplevels.values.filter { it.pid == pid }
                            else listOfNotNull(toplevels[id])
            if (remaining.isNotEmpty() && remaining.none { it.id in hosted }) {
                Log.i(TAG, "toplevel $id (pid $pid) still has hidden windows, ending its client")
                kill(remaining.first().id)
            }
        }, CLOSE_GRACE_MS)
    }

    fun touch(id: Int, action: Int, slot: Int, x: Float, y: Float, time: Int) =
        withHandle { nativeTouch(it, id, action, slot, x, y, time) }
    fun pointerMotion(id: Int, x: Float, y: Float, time: Int) =
        withHandle { nativePointerMotion(it, id, x, y, time) }
    fun pointerButton(id: Int, button: Int, pressed: Boolean, time: Int) =
        withHandle { nativePointerButton(it, id, button, pressed, time) }
    fun pointerAxis(id: Int, dx: Float, dy: Float, time: Int) =
        withHandle { nativePointerAxis(it, id, dx, dy, time) }
    /** Pointer movement while the pointer is locked; window (buffer) pixels. */
    fun pointerRelative(id: Int, dx: Float, dy: Float, time: Int) =
        withHandle { nativePointerRelative(it, id, dx, dy, time) }

    /** Mouse wheel notches; positive scrolls down/right. */
    fun pointerWheel(id: Int, stepsX: Int, stepsY: Int, time: Int) =
        withHandle { nativePointerWheel(it, id, stepsX, stepsY, time) }

    fun key(id: Int, key: Int, pressed: Boolean, modsDepressed: Int, modsLocked: Int, time: Int) =
        withHandle { nativeKey(it, id, key, pressed, modsDepressed, modsLocked, time) }

    /** Toplevel whose client has an active text field, 0 if none. */
    @Volatile var textInputToplevel = 0
        private set

    /** Text from the input method for the active text field. */
    fun textCommit(id: Int, text: String) {
        if (id == imToplevel) Bridge.send("im-commit", "text" to text)
        else withHandle { nativeText(it, id, text.toByteArray(Charsets.UTF_8), false) }
    }
    /** Text still being composed; empty clears it. */
    fun textPreedit(id: Int, text: String) {
        if (id == imToplevel) Bridge.send("im-preedit", "text" to text)
        else withHandle { nativeText(it, id, text.toByteArray(Charsets.UTF_8), true) }
    }

    /**
     * Toplevel with a text field reported by dn-notifyd's input method
     * (X11 programs, which have no text-input protocol), 0 if none. Its
     * keyboard text goes back over the bridge.
     */
    @Volatile private var imToplevel = 0

    /** True when [id]'s text field comes from dn-notifyd's input method. */
    fun isInputMethodToplevel(id: Int) = id != 0 && id == imToplevel

    /**
     * A text field of an X11 program became active or inactive. The
     * input method does not know the window; it is the focused one, since
     * X11 focus follows the Android window.
     */
    fun inputMethodFocus(active: Boolean) {
        if (active) {
            val id = focusedToplevel
            if (id == 0) return
            imToplevel = id
            onTextInput(id, true)
        } else {
            val id = imToplevel
            if (id == 0) return
            imToplevel = 0
            if (textInputToplevel == id) onTextInput(id, false)
        }
    }

    fun stats(id: Int): Stats? {
        val h = handle
        if (h == 0L) return null
        val v = LongArray(11)
        if (!nativeStats(h, id, v)) return null
        return Stats(v[0], v[1], v[2], v[3], v[4], v[5].toInt(), v[6].toInt(),
            v[7].toInt(), v[8].toInt(), v[9].toInt(), v[10] != 0L)
    }

    private inline fun withHandle(block: (Long) -> Unit) {
        val h = handle
        if (h != 0L) block(h)
    }

    /* Called from native code on the compositor thread */

    @Suppress("unused")
    private fun onToplevelNew(id: Int, appId: String, title: String, pid: Int) {
        val tl = Toplevel(id, appId, title, pid)
        Log.i(TAG, "toplevel $id new: app_id=$appId title=$title pid=$pid")
        toplevels[id] = tl
        for (l in listeners) runCatching { l.onToplevelNew(tl) }.onFailure { Log.e(TAG, "listener", it) }
    }

    @Suppress("unused")
    private fun onToplevelChanged(id: Int, appId: String, title: String) {
        val tl = toplevels[id] ?: return
        tl.appId = appId
        tl.title = title
        for (l in listeners) runCatching { l.onToplevelChanged(tl) }.onFailure { Log.e(TAG, "listener", it) }
    }

    /** Tells the window hosts that desktop emulation changed (main thread). */
    fun desktopEmulationChanged(enabled: Boolean) {
        for (l in listeners) runCatching { l.onDesktopEmulation(enabled) }.onFailure { Log.e(TAG, "listener", it) }
    }

    @Suppress("unused")
    private fun onToplevelSize(id: Int, width: Int, height: Int) {
        val tl = toplevels[id] ?: return
        tl.width = width
        tl.height = height
        Log.i(TAG, "toplevel $id first frame ${width}x$height")
        for (l in listeners) runCatching { l.onToplevelSize(tl) }.onFailure { Log.e(TAG, "listener", it) }
    }

    @Suppress("unused")
    private fun onToplevelClosed(id: Int) {
        Log.i(TAG, "toplevel $id closed")
        toplevels.remove(id)
        owners.remove(id)
        for (l in listeners) runCatching { l.onToplevelClosed(id) }.onFailure { Log.e(TAG, "listener", it) }
    }

    @Suppress("unused")
    private fun onTextInput(id: Int, active: Boolean) {
        textInputToplevel = if (active) id else 0
        for (l in listeners) runCatching { l.onTextInput(id, active) }.onFailure { Log.e(TAG, "listener", it) }
    }

    /** Toplevels whose program has locked the pointer. */
    val pointerLocked: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    @Suppress("unused")
    private fun onPointerLock(id: Int, locked: Boolean) {
        if (locked) pointerLocked += id else pointerLocked -= id
        Log.i(TAG, "toplevel $id pointer ${if (locked) "locked" else "released"}")
        for (l in listeners) runCatching { l.onPointerLock(id, locked) }.onFailure { Log.e(TAG, "listener", it) }
    }

    private external fun nativeText(h: Long, id: Int, utf8: ByteArray, preedit: Boolean)
    private external fun nativeCreate(
        socketPath: String, keymapPath: String?, tmpDir: String, scale: Int,
        dispW: Int, dispH: Int, physW: Int, physH: Int, refreshMhz: Int, touchMode: Int,
    ): Long
    private external fun nativeRun(h: Long): Int
    private external fun nativeStop(h: Long)
    private external fun nativeDestroy(h: Long)
    private external fun nativeAttach(h: Long, id: Int, surface: Surface, w: Int, hgt: Int)
    private external fun nativeDetach(h: Long, id: Int)
    private external fun nativeResize(h: Long, id: Int, w: Int, hgt: Int)
    private external fun nativeFocus(h: Long, id: Int, focused: Boolean)
    private external fun nativeClose(h: Long, id: Int)
    private external fun nativeKill(h: Long, id: Int)
    private external fun nativeSetKeymap(h: Long, path: String)
    private external fun nativeSetServerDecorations(h: Long, server: Boolean)
    private external fun nativeTouch(h: Long, id: Int, action: Int, slot: Int, x: Float, y: Float, time: Int)
    private external fun nativePointerMotion(h: Long, id: Int, x: Float, y: Float, time: Int)
    private external fun nativePointerButton(h: Long, id: Int, button: Int, pressed: Boolean, time: Int)
    private external fun nativePointerAxis(h: Long, id: Int, dx: Float, dy: Float, time: Int)
    private external fun nativePointerRelative(h: Long, id: Int, dx: Float, dy: Float, time: Int)
    private external fun nativePointerWheel(h: Long, id: Int, stepsX: Int, stepsY: Int, time: Int)
    private external fun nativeKey(h: Long, id: Int, key: Int, pressed: Boolean, modsDepressed: Int, modsLocked: Int, time: Int)
    private external fun nativeStats(h: Long, id: Int, out: LongArray): Boolean

    companion object {
        const val CLOSE_GRACE_MS = 3000L
        private const val TAG = "dn-compositor"

        // Must match enum dnc_touch_action
        const val TOUCH_DOWN = 0
        const val TOUCH_UP = 1
        const val TOUCH_MOTION = 2
        const val TOUCH_CANCEL = 3

        // Must match enum dnc_buffer_kind
        const val BUFFER_SHM = 1
        const val BUFFER_DMABUF = 2

        init {
            System.loadLibrary("droidnative")
        }
    }
}
