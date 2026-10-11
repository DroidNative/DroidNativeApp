package app.droidnative.display

import android.util.Log
import android.view.Surface
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

    /** [pid]: the Wayland client process (host pid), 0 if unknown. */
    class Toplevel(val id: Int, @Volatile var appId: String, @Volatile var title: String, val pid: Int = 0)

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
        /** A text field of the toplevel wants (or no longer wants) text entry. */
        fun onTextInput(id: Int, active: Boolean) {}
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

    fun attach(id: Int, surface: Surface, width: Int, height: Int) =
        withHandle { nativeAttach(it, id, surface, width, height) }
    fun detach(id: Int) = withHandle { nativeDetach(it, id) }
    fun resize(id: Int, width: Int, height: Int) = withHandle { nativeResize(it, id, width, height) }
    fun focus(id: Int, focused: Boolean) = withHandle { nativeFocus(it, id, focused) }
    fun close(id: Int) = withHandle { nativeClose(it, id) }

    fun touch(id: Int, action: Int, slot: Int, x: Float, y: Float, time: Int) =
        withHandle { nativeTouch(it, id, action, slot, x, y, time) }
    fun pointerMotion(id: Int, x: Float, y: Float, time: Int) =
        withHandle { nativePointerMotion(it, id, x, y, time) }
    fun pointerButton(id: Int, button: Int, pressed: Boolean, time: Int) =
        withHandle { nativePointerButton(it, id, button, pressed, time) }
    fun pointerAxis(id: Int, dx: Float, dy: Float, time: Int) =
        withHandle { nativePointerAxis(it, id, dx, dy, time) }
    fun key(id: Int, key: Int, pressed: Boolean, modsDepressed: Int, modsLocked: Int, time: Int) =
        withHandle { nativeKey(it, id, key, pressed, modsDepressed, modsLocked, time) }

    /** Toplevel whose client has an active text field, 0 if none. */
    @Volatile var textInputToplevel = 0
        private set

    /** Text from the input method for the active text field. */
    fun textCommit(id: Int, text: String) =
        withHandle { nativeText(it, id, text.toByteArray(Charsets.UTF_8), false) }
    /** Text still being composed; empty clears it. */
    fun textPreedit(id: Int, text: String) =
        withHandle { nativeText(it, id, text.toByteArray(Charsets.UTF_8), true) }

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

    @Suppress("unused")
    private fun onToplevelClosed(id: Int) {
        toplevels.remove(id)
        for (l in listeners) runCatching { l.onToplevelClosed(id) }.onFailure { Log.e(TAG, "listener", it) }
    }

    @Suppress("unused")
    private fun onTextInput(id: Int, active: Boolean) {
        textInputToplevel = if (active) id else 0
        for (l in listeners) runCatching { l.onTextInput(id, active) }.onFailure { Log.e(TAG, "listener", it) }
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
    private external fun nativeTouch(h: Long, id: Int, action: Int, slot: Int, x: Float, y: Float, time: Int)
    private external fun nativePointerMotion(h: Long, id: Int, x: Float, y: Float, time: Int)
    private external fun nativePointerButton(h: Long, id: Int, button: Int, pressed: Boolean, time: Int)
    private external fun nativePointerAxis(h: Long, id: Int, dx: Float, dy: Float, time: Int)
    private external fun nativeKey(h: Long, id: Int, key: Int, pressed: Boolean, modsDepressed: Int, modsLocked: Int, time: Int)
    private external fun nativeStats(h: Long, id: Int, out: LongArray): Boolean

    companion object {
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
