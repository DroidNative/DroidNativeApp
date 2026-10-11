package app.droidnative.test

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.droidnative.display.Compositor
import app.droidnative.display.WindowActivity
import app.droidnative.env.EnvService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

/**
 * Runs a Wayland client inside the guest, shows its toplevel in a real
 * WindowActivity and collects compositor statistics plus a screenshot.
 */
object ClientRunner {

    data class Run(
        val exitCode: Int,
        val timedOut: Boolean,
        val output: String,
        val stats: Compositor.Stats?,
        val screenshotNonBlack: Int,   // of 64 sampled screen pixels
        val wallMs: Long,
    ) {
        val fps get() = if (wallMs > 0 && stats != null) stats.framesPresented * 1000.0 / wallMs else 0.0
        fun summary() = "exit=$exitCode timedOut=$timedOut wall=${wallMs}ms " +
            "presented=${stats?.framesPresented} shm=${stats?.shmCommits} " +
            "dmabuf=${stats?.dmabufCommits} mapFail=${stats?.dmabufMapFailures} " +
            "size=${stats?.width}x${stats?.height} nonblack=${stats?.nonBlackSamples}/64 " +
            "screen=$screenshotNonBlack/64 fps=${"%.1f".format(fps)}"
    }

    fun startCompositor(): Compositor {
        EnvService.autoOpenWindows = false
        assertTrue("compositor failed to start", EnvService.startCompositor(TestEnv.context))
        return EnvService.compositor
    }

    fun run(
        name: String,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 120_000,
        openWindow: Boolean = true,
        screenshotAfterMs: Long = 3_000,
    ): Run {
        val compositor = startCompositor()
        val newToplevel = CountDownLatch(1)
        var toplevelId = -1
        val listener = object : Compositor.Listener {
            override fun onToplevelNew(toplevel: Compositor.Toplevel) {
                if (toplevelId < 0) {
                    toplevelId = toplevel.id
                    newToplevel.countDown()
                }
            }
        }
        compositor.addListener(listener)
        val t0 = SystemClock.elapsedRealtime()
        val proc = TestEnv.proot.start(argv, env)
        proc.outputStream.close()
        val out = StringBuilder()
        val reader = Thread {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (l in lines) if (out.length < 128 * 1024) out.append(l).append('\n')
            }
        }.apply { start() }

        var stats: Compositor.Stats? = null
        var screenNonBlack = -1
        try {
            // Wait for the window, but stop early if the client already died
            var appeared = false
            val waitUntil = SystemClock.elapsedRealtime() + 60_000
            while (!appeared && proc.isAlive && SystemClock.elapsedRealtime() < waitUntil)
                appeared = newToplevel.await(200, TimeUnit.MILLISECONDS)
            if (appeared && openWindow) {
                val intent = WindowActivity.intent(TestEnv.context, toplevelId)
                InstrumentationRegistry.getInstrumentation().context.startActivity(intent)
            }
            val deadline = t0 + timeoutMs
            var shotTaken = false
            while (SystemClock.elapsedRealtime() < deadline) {
                if (toplevelId >= 0)
                    compositor.stats(toplevelId)?.let { stats = it }
                if (openWindow && !shotTaken && toplevelId >= 0 &&
                    SystemClock.elapsedRealtime() - t0 > screenshotAfterMs) {
                    screenNonBlack = screenshotNonBlack()
                    shotTaken = true
                }
                if (proc.waitFor(200, TimeUnit.MILLISECONDS)) break
            }
        } finally {
            compositor.removeListener(listener)
        }
        val finished = !proc.isAlive
        if (!finished) proc.destroyForcibly()
        reader.join(5_000)
        val run = Run(if (finished) proc.exitValue() else -1, !finished, out.toString(),
            stats, screenNonBlack, SystemClock.elapsedRealtime() - t0)
        TestEnv.report(name, run.summary())
        TestEnv.saveOutput(name, app.droidnative.env.Proot.Result(run.exitCode, run.output, run.timedOut))
        SystemClock.sleep(500)   // let the window close before the next test
        return run
    }

    private fun screenshotNonBlack(): Int {
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation
            .takeScreenshot() ?: return -1
        var n = 0
        for (gy in 0 until 8) for (gx in 0 until 8) {
            // Central 60% of the screen, away from system bars
            val x = (bmp.width * (0.2 + 0.6 * (gx + 0.5) / 8)).toInt()
            val y = (bmp.height * (0.2 + 0.6 * (gy + 0.5) / 8)).toInt()
            val c = bmp.getPixel(x, y)
            if ((c shr 16 and 0xff) + (c shr 8 and 0xff) + (c and 0xff) > 24) n++
        }
        bmp.recycle()
        return n
    }
}
