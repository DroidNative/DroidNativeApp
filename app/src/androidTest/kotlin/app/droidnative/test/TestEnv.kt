package app.droidnative.test

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
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

    /** GL_RENDERER of Android's own driver, e.g. "Adreno (TM) 610". */
    val androidRenderer: String by lazy { glRenderer() }

    /**
     * Turnip drives Adreno 6xx and newer; older Adreno have KGSL too
     * (Galaxy S5: Adreno 330) but no Vulkan from Mesa.
     */
    val hasTurnipGpu: Boolean by lazy {
        val model = Regex("Adreno \\(TM\\) (\\d+)").find(androidRenderer)?.groupValues?.get(1)?.toIntOrNull()
        hasKgsl && arch.hasTurnipPackage && model != null && model >= 600
    }

    private fun glRenderer(): String {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return ""
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) return ""
            val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, surface, surface, ctx)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, ctx)
            return renderer
        } finally {
            EGL14.eglTerminate(display)
        }
    }

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
