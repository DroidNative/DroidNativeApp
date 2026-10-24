package app.droidnative.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Vulkan clients through Mesa's Wayland WSI. */
@RunWith(AndroidJUnit4::class)
class T06VulkanWaylandTest {

    @Before
    fun setUp() = TestEnv.ensureSetup()

    private val vkcube = listOf("vkcube", "--wsi", "wayland", "--c", "600")

    /** Hardware path: dmabuf buffers (Turnip on Adreno) */
    @Test
    fun vkcubeDmabuf() {
        val r = ClientRunner.run("vkcube", vkcube)
        val s = r.stats
        assertTrue(r.summary(), r.exitCode == 0 && s != null)
        assertTrue(r.summary(), s!!.framesPresented >= 300)
        assertTrue("cube not visible: ${r.summary()}", s.nonBlackSamples > 0)
        if (TestEnv.hasTurnipGpu)
            assertTrue("expected dmabuf buffers on KGSL: ${r.summary()}", s.dmabufCommits > 0)
    }

    /** Fallback path used by Venus/software: WSI copies into wl_shm */
    @Test
    fun vkcubeShmFallback() {
        val r = ClientRunner.run("vkcube-sw-wsi", vkcube, mapOf("MESA_VK_WSI_DEBUG" to "sw"))
        val s = r.stats
        assertTrue(r.summary(), r.exitCode == 0 && s != null)
        assertTrue(r.summary(), s!!.shmCommits >= 300 && s.nonBlackSamples > 0)
    }

    /** Same as vkcubeDmabuf but without the CPU wait before present (tearing check) */
    @Test
    fun vkcubeWithoutPresentWait() {
        if (!TestEnv.hasTurnipGpu) return
        val r = ClientRunner.run("vkcube-nowait", vkcube, mapOf("DN_TU_NO_PRESENT_WAIT" to "1"))
        TestEnv.report("vkcube-nowait", "informational only, compare visually / fps with vkcube")
        assertTrue(r.summary(), r.exitCode == 0)
    }
}
