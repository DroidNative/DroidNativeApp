package app.droidnative.test

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.droidnative.env.EnvPaths
import app.droidnative.env.GuestSetup
import app.droidnative.env.Proot
import app.droidnative.env.RootfsInstaller
import app.droidnative.env.VoidArch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared fixtures and the report written for tests/device/run.sh. */
object TestEnv {
    const val TAG = "dn-test"

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val paths by lazy { EnvPaths(context) }
    val proot by lazy { Proot(paths) }
    val installer by lazy { RootfsInstaller(context, paths) }
    val setup by lazy { GuestSetup(context, paths, proot) }
    val arch get() = VoidArch.current()

    val reportDir get() = File(paths.filesDir, "test-reports").apply { mkdirs() }

    val hasKgsl get() = File("/dev/kgsl-3d0").exists()

    /** Appends one line to the report and logcat. */
    fun report(test: String, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        File(reportDir, "report.txt").appendText("$ts [$test] $message\n")
        Log.i(TAG, "[$test] $message")
    }

    /** Stores a command's full output next to the report. */
    fun saveOutput(name: String, result: Proot.Result) {
        File(reportDir, "$name.log").writeText(
            "exit=${result.exitCode} timedOut=${result.timedOut}\n${result.output}")
    }

    fun ensureRootfs() {
        // run.sh grants all-files access; the user-facing switch is separate
        app.droidnative.env.Prefs(context).storageAccess = true
        if (!installer.isInstalled) {
            installer.install { stage, done, total ->
                Log.i(TAG, "rootfs: $stage $done/$total")
            }
        }
        setup.writeHostFiles()
    }

    fun ensureSetup() {
        ensureRootfs()
        if (!setup.isDone) {
            val r = setup.run { Log.i(TAG, "setup: $it") }
            saveOutput("setup", r)
            check(r.ok) { "guest setup failed (exit ${r.exitCode}), see setup.log" }
        }
    }
}
