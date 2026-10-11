package app.droidnative.env

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The guest's runit services: runsvdir on /var/service (the service dir
 * setup.sh prepares) as emulated root, for as long as the environment runs.
 * Services are enabled in the guest with "sudo ln -s /etc/sv/<name> /var/service/".
 */
object Services {
    private const val TAG = "dn-services"
    private var process: Process? = null

    val isRunning: Boolean
        @Synchronized get() = process?.isAlive == true

    @Synchronized
    fun start(context: Context) {
        if (isRunning) return
        val paths = EnvPaths(context)
        if (!paths.rootfsReady.isFile || !File(paths.rootfs, "usr/sbin/runsvdir").exists()) return
        val log = File(paths.logDir, "services.log")
        log.parentFile?.mkdirs()
        // -P: every service in its own process group; the log argument is
        // runsvdir's rolling status line (ps shows recent service errors)
        val proc = Proot(paths).processBuilder(
            listOf("/usr/sbin/runsvdir", "-P", "/var/service", "log: " + ".".repeat(200)),
            root = true)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(log))
            .start()
        proc.outputStream.close()
        process = proc
        Log.i(TAG, "runsvdir started")
    }

    @Synchronized
    fun stop() {
        // proot ends every service with it (--kill-on-exit)
        process?.destroy()
        process = null
    }
}
