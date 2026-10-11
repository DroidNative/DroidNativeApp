package app.droidnative.devices

import android.content.Context
import android.util.Log
import app.droidnative.env.EnvPaths
import app.droidnative.env.Passthrough
import java.io.File

/**
 * Device passthrough: which host files the guest gets as device nodes and
 * the bridges that feed them while the environment runs. Interfaces are
 * described in docs/device-passthrough.md.
 */
object Devices {
    private const val TAG = "dn-devices"
    const val GUEST_IIO = "/sys/bus/iio/devices"
    const val GUEST_GPS = "/dev/gps0"

    private var cameras: List<CameraBridge>? = null
    private var sensors: SensorBridge? = null
    private var location: LocationBridge? = null
    private var mic: MicBridge? = null

    fun dir(paths: EnvPaths) = File(paths.filesDir, "dev")
    private fun iioDir(paths: EnvPaths) = File(dir(paths), "iio")

    @Synchronized
    private fun cameras(context: Context, paths: EnvPaths): List<CameraBridge> =
        cameras ?: CameraBridge.discover(context.applicationContext, dir(paths)).also { cameras = it }

    /**
     * Binds for a new PRoot process; creates the host side of every enabled
     * device first (PRoot's V4L2 extension scans the camera FIFOs at start).
     */
    fun binds(paths: EnvPaths): List<Pair<String, String>> {
        val context = paths.appContext
        val out = ArrayList<Pair<String, String>>()
        try {
            if (Passthrough.CAMERA.isActive(context)) {
                for (cam in cameras(context, paths)) {
                    cam.prepare()
                    out += File(dir(paths), "video${cam.index}").path to "/dev/video${cam.index}"
                    // Tools identify V4L2 nodes through sysfs (v4l2-ctl reads
                    // uevent; Android answers EACCES for the real /sys/dev)
                    out += videoSysfs(paths, cam).path to "/sys/dev/char/81:${cam.index}"
                    writeUdevDb(paths, cam)
                }
                // Whole class directory, so its listing shows the cameras
                if (cameras(context, paths).isNotEmpty()) {
                    out += File(dir(paths), "sys/video4linux").path to "/sys/class/video4linux"
                    // udev (eudev) lists devices from /sys/bus and /sys/class,
                    // which Android does not let apps list; it prefers the
                    // /sys/subsystem layout when present, which Android lacks
                    out += subsystemTree(paths, cameras(context, paths)).path to "/sys/subsystem"
                }
            }
            if (Passthrough.SENSORS.isActive(context)) {
                SensorBridge(context, iioDir(paths)).prepare()
                out += iioDir(paths).path to GUEST_IIO
            }
            if (Passthrough.LOCATION.isActive(context)) {
                LocationBridge(context, dir(paths)).prepare()
                out += File(dir(paths), "gps0").path to GUEST_GPS
            }
        } catch (e: Exception) {
            Log.w(TAG, "device setup", e)
        }
        return out
    }

    private fun videoSysfs(paths: EnvPaths, cam: CameraBridge): File {
        val d = File(dir(paths), "sys/video4linux/video${cam.index}")
        d.mkdirs()
        // libudev takes the subsystem from the name of this link's target
        val link = File(d, "subsystem")
        if (!link.exists()) runCatching { android.system.Os.symlink("/sys/class/video4linux", link.path) }
        File(d, "uevent").writeText("MAJOR=81\nMINOR=${cam.index}\nDEVNAME=video${cam.index}\n")
        File(d, "dev").writeText("81:${cam.index}\n")
        File(d, "name").writeText(cam.label + "\n")
        File(d, "index").writeText("0\n")
        return d
    }

    // udev database entry as udevd's v4l rules would write it: without one a
    // device counts as not initialised and GStreamer's provider skips it
    private fun writeUdevDb(paths: EnvPaths, cam: CameraBridge) {
        val dbDir = File(paths.rootfs, "run/udev/data")
        dbDir.mkdirs()
        File(dbDir, "c81:${cam.index}").writeText(
            "I:1\n" +
            "E:ID_V4L_VERSION=2\n" +
            "E:ID_V4L_PRODUCT=${cam.label}\n" +
            "E:ID_V4L_CAPABILITIES=:capture:\n" +
            "E:ID_PATH=platform-droidnative-${cam.index}\n" +
            "G:uaccess\n")
    }

    // /sys/subsystem/video4linux/devices/videoN -> /sys/class/video4linux/videoN
    private fun subsystemTree(paths: EnvPaths, cams: List<CameraBridge>): File {
        val root = File(dir(paths), "sys/subsystem")
        val devices = File(root, "video4linux/devices")
        devices.mkdirs()
        for (cam in cams) {
            // Relative like the kernel's links: eudev appends the target to
            // the link's directory
            val link = File(devices, "video${cam.index}")
            val target = "../../../class/video4linux/video${cam.index}"
            if (runCatching { android.system.Os.readlink(link.path) }.getOrNull() != target) {
                link.delete()
                runCatching { android.system.Os.symlink(target, link.path) }
            }
        }
        return root
    }

    /** Host environment for PRoot itself (not the guest). */
    fun prootEnv(paths: EnvPaths): Map<String, String> =
        if (Passthrough.CAMERA.isActive(paths.appContext)) mapOf("PROOT_DN_VIDEO_DIR" to dir(paths).path)
        else emptyMap()

    /** Guest environment for the session daemons (session.sh). */
    fun sessionEnv(context: Context): Map<String, String> =
        if (Passthrough.MICROPHONE.isActive(context)) mapOf("DN_MIC" to "1") else emptyMap()

    /** Starts the bridges of the enabled devices; called by EnvService. */
    @Synchronized
    fun start(context: Context) {
        val paths = EnvPaths(context)
        stop()
        if (Passthrough.CAMERA.isActive(context))
            cameras(context, paths).forEach { runCatching { it.start() }.onFailure { e -> Log.w(TAG, "camera", e) } }
        if (Passthrough.SENSORS.isActive(context))
            sensors = SensorBridge(context, iioDir(paths)).also { it.start() }
        if (Passthrough.LOCATION.isActive(context))
            location = LocationBridge(context, dir(paths)).also { it.start() }
        if (Passthrough.MICROPHONE.isActive(context))
            mic = MicBridge(paths.runDir).also { it.start() }
    }

    @Synchronized
    fun stop() {
        cameras?.forEach { it.stop() }
        cameras = null
        sensors?.stop(); sensors = null
        location?.stop(); location = null
        mic?.stop(); mic = null
    }
}
