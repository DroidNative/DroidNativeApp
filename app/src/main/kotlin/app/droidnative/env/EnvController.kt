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

    data class Status(val state: State, val detail: String = "")

    private val _status = MutableStateFlow(Status(State.NOT_INSTALLED))
    val status: StateFlow<Status> = _status

    /** Detail of NOT_INSTALLED when only the package setup is outstanding. */
    const val NEEDS_SETUP = "needs-setup"

    @Volatile private var busy = false

    /** Re-reads the state from disk and from the service. */
    fun refresh(context: Context) {
        if (busy) return
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
        _status.value = Status(State.INSTALLING, "starting")
        Thread({
            try {
                val paths = EnvPaths(app)
                val proot = Proot(paths)
                val installer = RootfsInstaller(app, paths)
                if (!installer.isInstalled) {
                    installer.install { stage, done, total ->
                        val pct = if (total > 0) " ${done * 100 / total}%" else ""
                        _status.value = Status(State.INSTALLING, "$stage$pct")
                    }
                }
                val setup = GuestSetup(app, paths, proot)
                if (!setup.isDone) {
                    val r = setup.run { line ->
                        // Progress lines of setup.sh start with "[setup]"
                        if (line.startsWith("[setup]"))
                            _status.value = Status(State.INSTALLING, line.removePrefix("[setup]").trim())
                    }
                    if (!r.ok) throw IllegalStateException("package setup failed (exit ${r.exitCode})")
                }
                busy = false
                start(app)
            } catch (e: Exception) {
                busy = false
                _status.value = Status(State.ERROR, e.message ?: e.javaClass.simpleName)
            }
        }, "dn-install").start()
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
