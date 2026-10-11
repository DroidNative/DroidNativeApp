package app.droidnative.env

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * State of the Linux container as shown in the UI, and the operations that
 * change it (install, start, stop). Long operations run on a worker thread.
 */
object EnvController {

    enum class State { NOT_INSTALLED, INSTALLING, STOPPED, RUNNING, ERROR }

    /**
     * Install progress: [done] of [total] steps (total 0 while not known
     * yet), [fraction] of the running step or -1.
     */
    data class Progress(val done: Int, val total: Int, val fraction: Float = -1f)

    data class Status(val state: State, val detail: String = "", val progress: Progress? = null)

    private val _status = MutableStateFlow(Status(State.NOT_INSTALLED))
    val status: StateFlow<Status> = _status

    /** Output of the running (or failed) install, newest last. */
    private val _installLog = MutableStateFlow<List<String>>(emptyList())
    val installLog: StateFlow<List<String>> = _installLog
    private val logLines = ArrayDeque<String>()

    private fun log(line: String) = synchronized(logLines) {
        logLines.addLast(line)
        if (logLines.size > LOG_LINES) logLines.removeFirst()
        _installLog.value = logLines.toList()
    }

    /** Detail of NOT_INSTALLED when only the package setup is outstanding. */
    const val NEEDS_SETUP = "needs-setup"

    private const val LOG_LINES = 2000
    // Download (or local tarball), verify, extract (RootfsInstaller stages)
    private const val ROOTFS_STEPS = 3
    private const val USER_STEPS = 1

    @Volatile private var busy = false

    /** Re-reads the state from disk and from the service. */
    fun refresh(context: Context) {
        if (busy) return
        // A failed install stays shown with its log until Retry
        if (_status.value.state == State.ERROR && !EnvService.isRunning) return
        val paths = EnvPaths(context)
        _status.value = when {
            !paths.rootfsReady.isFile -> Status(State.NOT_INSTALLED)
            // An app update shipped a different setup.sh or package repo
            !EnvService.isRunning && !GuestSetup(context, paths, Proot(paths)).isDone ->
                Status(State.NOT_INSTALLED, NEEDS_SETUP)
            EnvService.isRunning -> Status(State.RUNNING, runningDetail())
            else -> Status(State.STOPPED)
        }
    }

    private fun runningDetail(): String {
        val parts = ArrayList<String>()
        parts += if (EnvService.compositor.isRunning) "display" else "no display"
        parts += if (Session.isRunning) "session" else "no session"
        return parts.joinToString(", ")
    }

    /** Downloads and unpacks the rootfs, installs the packages, then starts. */
    fun install(context: Context) {
        if (busy) return
        busy = true
        val app = context.applicationContext
        synchronized(logLines) { logLines.clear(); _installLog.value = emptyList() }
        _status.value = Status(State.INSTALLING, "starting", Progress(0, 0))
        Thread({
            try {
                val paths = EnvPaths(app)
                val proot = Proot(paths)
                val installer = RootfsInstaller(app, paths)
                val rootfsSteps = if (installer.isInstalled) 0 else ROOTFS_STEPS
                if (rootfsSteps > 0) {
                    var last = ""
                    installer.install { stage, done, total ->
                        if (stage != last) log(stage)
                        last = stage
                        val step = when (stage) { "verifying" -> 1; "extracting" -> 2; else -> 0 }
                        val fraction = if (total > 0) done.toFloat() / total else -1f
                        val pct = if (total > 0) " ${done * 100 / total}%" else ""
                        // The scripts announce their steps only once the rootfs is there
                        _status.value = Status(State.INSTALLING, "$stage$pct", Progress(step, 0, fraction))
                    }
                }
                val setup = GuestSetup(app, paths, proot)
                if (!setup.isDone) {
                    val steps = ScriptSteps(rootfsSteps)
                    val r = setup.run { line ->
                        if (steps.parse(line)) {
                            _status.value = Status(State.INSTALLING, steps.label, steps.progress())
                            if (line.startsWith("[setup]")) log(line)
                        } else log(line)
                    }
                    if (!r.ok) throw IllegalStateException("package setup failed (exit ${r.exitCode})")
                }
                busy = false
                start(app)
            } catch (e: Exception) {
                busy = false
                val message = e.message ?: e.javaClass.simpleName
                log("error: $message")
                _status.value = Status(State.ERROR, message)
            }
        }, "dn-install").start()
    }

    /**
     * Steps of setup.sh and setup-user.sh, from their markers: "[setup-steps] N"
     * announces N steps, "[setup] text" starts one, "[setup-end]" ends a
     * script's last one. Until setup-user.sh has announced, [USER_STEPS] are
     * reserved for it so the bar does not jump back.
     */
    internal class ScriptSteps(private val base: Int) {
        private var announced = 0
        private var announcements = 0
        private var finished = 0
        private var open = false
        var label = ""

        /** True for a marker line. */
        fun parse(line: String): Boolean {
            when {
                line.startsWith("[setup-steps]") -> {
                    announced += line.substringAfter(']').trim().toIntOrNull() ?: 0
                    announcements++
                }
                line.startsWith("[setup-end]") -> { if (open) finished++; open = false }
                line.startsWith("[setup]") -> {
                    if (open) finished++
                    open = true
                    label = line.removePrefix("[setup]").trim()
                }
                else -> return false
            }
            return true
        }

        fun progress(): Progress {
            if (announcements == 0) return Progress(base, 0)
            val total = base + announced + if (announcements < 2) USER_STEPS else 0
            return Progress(minOf(base + finished, total), total)
        }
    }

    fun start(context: Context) {
        EnvService.start(context)
        // The service flips isRunning in onCreate; reflect the intent now
        _status.value = Status(State.RUNNING, "starting")
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, EnvService::class.java))
        _status.value = Status(State.STOPPED)
    }
}
