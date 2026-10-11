package app.droidnative.test

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.droidnative.MainActivity
import app.droidnative.devices.Devices
import app.droidnative.env.Passthrough
import app.droidnative.env.Prefs
import app.droidnative.env.Session
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Device passthrough (docs/device-passthrough.md): cameras as V4L2 devices,
 * sensors as IIO sysfs, location as an NMEA device, microphone as a
 * PulseAudio source. Permissions come from tests/device/run.sh (pm grant);
 * a device without the hardware or the permission skips that test.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class T11DevicesTest {

    @Before
    fun setUp() {
        TestEnv.ensureSetup()
        val prefs = Prefs(TestEnv.context)
        for (p in Passthrough.entries) prefs.setEnabled(p, p.hasPermission(TestEnv.context))
        // Android gives camera and microphone only to apps in the foreground
        TestEnv.context.startActivity(Intent(TestEnv.context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(1500)
        Devices.start(TestEnv.context)
    }

    @After
    fun tearDown() = Devices.stop()

    private fun sh(name: String, script: String, timeoutMs: Long = 60_000) =
        TestEnv.proot.sh(script, timeoutMs = timeoutMs).also { TestEnv.saveOutput("devices-$name", it) }

    @Test
    fun t1_camera() {
        assumeTrue("no camera permission", Passthrough.CAMERA.isActive(TestEnv.context))
        val info = sh("camera-info", "stat -c '%F %t:%T' /dev/video0; v4l2-ctl -d /dev/video0 --info --list-formats-ext 2>&1 | head -30")
        TestEnv.report("devices", "camera: ${info.output.lines().take(8).joinToString(" | ")}")
        assertTrue(info.output, info.output.contains("character special file 51:0"))
        assertTrue(info.output, info.output.contains("droidnative"))
        // 30 frames of 640x480 YUYV
        val r = sh("camera-stream", "v4l2-ctl -d /dev/video0 --set-fmt-video=width=640,height=480,pixelformat=YUYV " +
            "--stream-mmap --stream-count=30 --stream-to=/tmp/dn-cam.raw 2>&1 | tail -3; " +
            "stat -c %s /tmp/dn-cam.raw; od -An -tu1 -j 400000 -N16 /tmp/dn-cam.raw", 60_000)
        TestEnv.report("devices", "camera stream: ${r.output.lines().joinToString(" | ")}")
        val size = r.output.lines().map { it.trim() }.firstNotNullOfOrNull { it.toLongOrNull() } ?: 0
        assertTrue("expected 30 frames, got $size bytes", size >= 30L * 640 * 480 * 2)
    }

    @Test
    fun t2_sensors() {
        val r = sh("sensors", "cd /sys/bus/iio/devices && for d in iio:device*; do " +
            "echo \"\$d \$(cat \$d/name) \$(cat \$d/in_*_raw \$d/in_*_input 2>/dev/null | tr -s ' \\n' ' ')\"; done; " +
            "sleep 1; cat iio:device0/in_accel_z_raw")
        TestEnv.report("devices", "sensors: ${r.output.lines().joinToString(" | ")}")
        assertTrue(r.output, r.output.contains("accel_3d"))
    }

    @Test
    fun t3_location() {
        assumeTrue("no location permission", Passthrough.LOCATION.isActive(TestEnv.context))
        // Last known fix comes at once; a new one may take long indoors
        val r = sh("location", "timeout 20 head -n 2 /dev/gps0", 40_000)
        TestEnv.report("devices", "gps: ${r.output.lines().joinToString(" | ")}")
        assumeTrue("no fix within 20 s (indoors?)", r.output.contains("\$GPGGA"))
    }

    @Test
    fun t4_microphone() {
        assumeTrue("no microphone permission", Passthrough.MICROPHONE.isActive(TestEnv.context))
        Session.stop()
        assertTrue(Session.start(TestEnv.context))
        Devices.start(TestEnv.context)
        val r = sh("microphone", "for i in \$(seq 50); do pactl list short sources 2>/dev/null | grep -q android_mic && break; sleep 0.2; done; " +
            "pactl list short sources; timeout 3 parecord -d android_mic --raw --format=s16le --rate=48000 --channels=1 /tmp/dn-mic.raw; " +
            "stat -c %s /tmp/dn-mic.raw; cat /run/droidnative/mic.active", 60_000)
        TestEnv.report("devices", "mic: ${r.output.lines().joinToString(" | ")}")
        val size = r.output.lines().map { it.trim() }.firstNotNullOfOrNull { it.toLongOrNull()?.takeIf { v -> v > 1 } } ?: 0
        // 3 s of 48 kHz mono s16 is 288000 bytes; allow for start-up
        assertTrue("recorded $size bytes", size > 100_000)
    }
}
