package app.droidnative.env

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The guest's long-running session daemons (D-Bus session bus, PulseAudio;
 * assets/guest/session.sh) as one proot process, plus the Android side of
 * the audio path. One instance per app process.
 */
object Session {
    private const val TAG = "dn-session"

    private var process: Process? = null
    var audio: AudioOutput? = null
        private set

    val isRunning: Boolean
        @Synchronized get() = process?.isAlive == true

    /** Starts the daemons if the guest is installed; safe to call again. */
    @Synchronized
    fun start(context: Context): Boolean {
        if (isRunning) return true
        val paths = EnvPaths(context)
        if (!paths.rootfsReady.isFile) return false
        val proot = Proot(paths)
        GuestSetup(context, paths, proot).writeHostFiles()

        // A FIFO left by a killed PulseAudio is reused; anything else is not
        val log = File(paths.logDir, "session.log")
        log.parentFile?.mkdirs()
        val proc = proot.processBuilder(listOf("/usr/libexec/droidnative/session.sh"),
                app.droidnative.devices.Devices.sessionEnv(context))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(log))
            .start()
        proc.outputStream.close()
        process = proc
        Log.i(TAG, "session started")

        if (audio == null) audio = AudioOutput(paths.audioFifo, Prefs(context).audioBufferMs).also { it.start() }
        return true
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
        audio?.stop()
        audio = null
    }
}
