package app.droidnative.test

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.droidnative.env.AudioOutput
import app.droidnative.env.Session
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Audio: guest PulseAudio -> FIFO -> AudioOutput -> AudioTrack. Plays a
 * 440 Hz tone (audible on the phone) and checks that the samples reached the
 * Android side; also the D-Bus session bus started by the same session.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class T10AudioTest {

    @Before
    fun setUp() {
        TestEnv.ensureSetup()
        assertTrue("session did not start", Session.start(TestEnv.context))
    }

    private fun waitForPulse(): String {
        var last = ""
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val r = TestEnv.proot.sh("pactl info 2>&1", timeoutMs = 20_000)
            last = r.output
            if (r.ok && last.contains("Default Sink")) return last
            SystemClock.sleep(1000)
        }
        return last
    }

    // 2 s, 440 Hz, s16 stereo 48 kHz, amplitude 12000
    private fun writeTone(file: File) {
        val frames = 2 * AudioOutput.RATE
        val pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (12000 * sin(2 * PI * 440 * i / AudioOutput.RATE)).toInt().toShort()
            pcm.putShort(v).putShort(v)
        }
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray())
        h.putInt(16).putShort(1).putShort(2).putInt(AudioOutput.RATE)
        h.putInt(AudioOutput.RATE * 4).putShort(4).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.capacity())
        out.write(h.array()); out.write(pcm.array())
        file.writeBytes(out.toByteArray())
    }

    @Test
    fun a_pulseaudioRuns() {
        val info = waitForPulse()
        TestEnv.report("audio", info.lines().filter { it.startsWith("Server Name") || it.startsWith("Default Sink") }.toString())
        assertTrue(info, info.contains("Default Sink: android"))
    }

    @Test
    fun b_toneReachesAndroid() {
        waitForPulse()
        val audio = Session.audio ?: error("no audio output")
        writeTone(File(TestEnv.paths.rootfs, "tmp/dn-tone.wav"))
        audio.resetPeak()
        val before = audio.stats
        val r = TestEnv.proot.sh("paplay /tmp/dn-tone.wav 2>&1", timeoutMs = 60_000)
        SystemClock.sleep(1500)
        val after = audio.stats
        val bytes = after.bytesPlayed - before.bytesPlayed
        TestEnv.saveOutput("audio-paplay", r)
        TestEnv.report("audio", "paplay exit=${r.exitCode} bytes=$bytes peak=${after.peak} " +
            "trackFrames=${after.trackFrames - before.trackFrames}")
        assertTrue(r.output, r.ok)
        // 2 s of s16 stereo at 48 kHz are 384000 bytes
        assertTrue("too little audio arrived: $bytes bytes", bytes >= 300_000)
        assertTrue("signal too quiet: peak ${after.peak}", after.peak in 9000..15000)
        assertTrue("AudioTrack did not advance", after.trackFrames > before.trackFrames)
    }

    @Test
    fun c_sessionBus() {
        val r = TestEnv.proot.sh("gdbus call --session --dest org.freedesktop.DBus " +
            "--object-path /org/freedesktop/DBus --method org.freedesktop.DBus.ListNames 2>&1", timeoutMs = 60_000)
        TestEnv.saveOutput("audio-dbus", r)
        TestEnv.report("audio", "session bus: ${r.output.trim().take(160)}")
        assertTrue(r.output, r.ok && r.output.contains("org.freedesktop.DBus"))
    }

    /** Sound from inside a Flatpak sandbox (PulseAudio socket passed in) */
    @Test
    fun d_flatpakAudio() {
        org.junit.Assume.assumeTrue(TestEnv.arch.supportsFlatpak)
        val app = "org.freedesktop.Platform.GlxInfo"
        val installed = TestEnv.proot.sh("flatpak --user list --columns=application 2>/dev/null")
        org.junit.Assume.assumeTrue("test app not installed (run T09 first)", installed.output.contains(app))
        waitForPulse()
        val audio = Session.audio ?: error("no audio output")
        // Not /tmp: Flatpak never shares the host's /tmp with a sandbox
        writeTone(File(TestEnv.paths.rootfs, "home/user/dn-tone.wav"))
        audio.resetPeak()
        val before = audio.stats
        val r = TestEnv.proot.sh("flatpak --user run --filesystem=/home/user/dn-tone.wav:ro --command=sh $app -c " +
            "'if command -v paplay >/dev/null; then paplay /home/user/dn-tone.wav; else echo no-paplay-in-runtime; fi' 2>&1",
            timeoutMs = 120_000)
        SystemClock.sleep(1500)
        val bytes = audio.stats.bytesPlayed - before.bytesPlayed
        TestEnv.saveOutput("audio-flatpak", r)
        TestEnv.report("audio", "flatpak paplay: exit=${r.exitCode} bytes=$bytes peak=${audio.stats.peak} ${r.output.trim().take(120)}")
        org.junit.Assume.assumeTrue("runtime has no paplay", !r.output.contains("no-paplay-in-runtime"))
        assertTrue(r.output, bytes >= 300_000 && audio.stats.peak >= 9000)
    }
}
