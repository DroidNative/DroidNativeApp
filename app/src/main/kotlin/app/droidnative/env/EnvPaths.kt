package app.droidnative.env

import android.content.Context
import java.io.File

/**
 * Host-side layout of the environment (all inside the app's private storage).
 * Unix socket paths must stay below 108 bytes, so runtime dirs are kept short.
 */
class EnvPaths(context: Context) {
    val appContext: Context = context.applicationContext
    val filesDir: File = context.filesDir
    val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)

    /** Extracted Void rootfs, the guest's "/". */
    val rootfs = File(filesDir, "rootfs")
    /** Marker written when extraction and first-boot setup completed. */
    val rootfsReady = File(filesDir, "rootfs.ready")
    val setupDone = File(filesDir, "setup.done")

    /** Bound into the guest at /run/droidnative (sockets, keymap). */
    val runDir = File(filesDir, "run")
    val waylandSocket = File(runDir, "wayland-0")
    val keymap = File(runDir, "keymap.xkb")
    /** Written by the guest's PulseAudio (module-pipe-sink), read by AudioOutput. */
    val audioFifo = File(runDir, "audio.fifo")

    /** Fake /proc entries Android hides from apps. */
    val sysdataDir = File(filesDir, "sysdata")

    val prootTmp = File(context.cacheDir, "proot")
    val downloads = File(context.cacheDir, "downloads")
    val logDir = File(filesDir, "logs")

    val proot = File(nativeLibDir, "libproot.so")
    val prootLoader = File(nativeLibDir, "libproot-loader.so")
    val prootLoader32 = File(nativeLibDir, "libproot-loader32.so")
    /** virglrenderer's vtest server (env/Virgl.kt) and its socket in [runDir]. */
    val virglServer = File(nativeLibDir, "libvirgl-server.so")
    val virglSocket = File(runDir, "virgl.sock")

    init {
        synchronized(EnvPaths) {
            if (!archChecked) {
                archChecked = true
                matchArch()
            }
        }
    }

    /**
     * The app can run as another ABI than the rootfs was made for (the APK
     * reinstalled with --abi, a 32-bit Android on a 64-bit phone restored
     * from a backup). An environment of another architecture is parked in
     * parked-<arch>/ instead of being run, and a parked one of this
     * architecture comes back.
     */
    private fun matchArch() {
        val arch = VoidArch.current().voidName
        val marker = rootfsReady.takeIf { it.isFile }?.readText()?.trim() ?: ""
        val own = Regex("^void-([a-z0-9_]+)-ROOTFS").find(marker)?.groupValues?.get(1)
        if (own != null && own != arch) {
            val park = File(filesDir, "parked-$own")
            if (!park.exists() && park.mkdirs()) {
                for (f in listOf(rootfs, rootfsReady, setupDone))
                    if (f.exists()) f.renameTo(File(park, f.name))
            }
        }
        val parked = File(filesDir, "parked-$arch")
        if (parked.isDirectory && !rootfsReady.exists() && !rootfs.exists()) {
            for (f in listOf(rootfs, rootfsReady, setupDone))
                File(parked, f.name).takeIf { it.exists() }?.renameTo(f)
            parked.delete()
        }
    }

    fun ensureDirs() {
        for (d in listOf(runDir, sysdataDir, prootTmp, downloads, logDir))
            d.mkdirs()
    }

    companion object {
        private var archChecked = false

        /** Guest-side mount point of [runDir]. */
        const val GUEST_RUN_DIR = "/run/droidnative"
        const val GUEST_WAYLAND_DISPLAY = "wayland-0"
        /** Unprivileged guest account everything except package management runs as. */
        const val GUEST_USER = "user"
        const val GUEST_UID = 1000
        const val GUEST_HOME = "/home/user"
    }
}
