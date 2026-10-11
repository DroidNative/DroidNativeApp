package app.droidnative.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Compositor with the protocol-level test client (CPU-rendered buffers). */
@RunWith(AndroidJUnit4::class)
class T05CompositorTest {

    @Before
    fun setUp() = TestEnv.ensureSetup()

    private fun wltest(mode: String, frames: Int, window: Boolean) = ClientRunner.run(
        "wltest-$mode${if (window) "" else "-hidden"}",
        listOf("dn-wltest", "--mode", mode, "--frames", "$frames", "--timeout-ms", "60000"),
        openWindow = window,
    )

    @Test
    fun shmInWindow() {
        val r = wltest("shm", 300, true)
        val s = r.stats
        assertTrue(r.summary(), r.exitCode == 0 && s != null)
        // Stats are sampled every 200 ms, the last frames may be missed
        assertTrue(r.summary(), s!!.shmCommits >= 250 && s.framesPresented >= 250)
        assertTrue("buffer content is black: ${r.summary()}", s.nonBlackSamples > 32)
        assertTrue("screen stayed black: ${r.summary()}", r.screenshotNonBlack > 16)
    }

    @Test
    fun dmabufProtocolInWindow() {
        val r = wltest("dmabuf", 300, true)
        val s = r.stats
        assertTrue(r.summary(), r.exitCode == 0 && s != null)
        assertTrue(r.summary(), s!!.dmabufCommits >= 250 && s.dmabufMapFailures == 0L)
        assertTrue(r.summary(), s.nonBlackSamples > 32)
    }

    /** Without a window frames are throttled (10/s) but must not stall. */
    @Test
    fun hiddenToplevelKeepsRunning() {
        val r = wltest("shm", 30, false)
        assertTrue(r.summary(), r.exitCode == 0)
    }
}
