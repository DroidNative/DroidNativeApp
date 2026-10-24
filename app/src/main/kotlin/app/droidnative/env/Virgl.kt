package app.droidnative.env

import android.content.Context
import android.util.Log
import java.io.File

/**
 * virglrenderer's vtest server (native-host/build.sh, libvirgl-server.so):
 * renders the guest's OpenGL (Mesa's virpipe driver, `dn-gl virgl`) with
 * Android's own GLES driver. This is hardware OpenGL on GPUs Turnip cannot
 * drive (Mali, PowerVR, Adreno before 6xx). It listens on
 * [EnvPaths.virglSocket] (/run/droidnative/virgl.sock in the guest) and
 * forks one renderer per client, so an idle server costs nothing.
 */
object Virgl {
    private const val TAG = "dn-virgl"
    private var process: Process? = null

    val isRunning: Boolean
        @Synchronized get() = process?.isAlive == true

    @Synchronized
    fun start(context: Context) {
        if (isRunning) return
        val paths = EnvPaths(context)
        if (!paths.virglServer.exists()) return
        paths.ensureDirs()
        paths.virglSocket.delete()
        val log = File(paths.logDir, "virgl.log")
        process = try {
            ProcessBuilder(paths.virglServer.path, "--use-egl-surfaceless", "--use-gles",
                "--socket-path", paths.virglSocket.path)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log))
                .start()
                .also { it.outputStream.close() }
        } catch (e: Exception) {
            Log.w(TAG, "cannot start the virgl server", e)
            null
        }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
    }
}
