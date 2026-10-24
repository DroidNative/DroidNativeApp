package app.droidnative.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OpenGL on Wayland through the dn-gl backends (zink, gl4es, llvmpipe).
 * GLES: es2gears_wayland. Desktop GL: glmark2-wayland (scene subset).
 */
@RunWith(AndroidJUnit4::class)
class T07ZinkWaylandTest {

    @Before
    fun setUp() = TestEnv.ensureSetup()

    private fun renderer(backend: String): String? {
        ClientRunner.startCompositor()
        val probe = TestEnv.proot.run(listOf("dn-gl", backend, "eglinfo", "-B", "-p", "wayland"),
            timeoutMs = 60_000)
        TestEnv.saveOutput("egl-probe-$backend", probe)
        val r = Regex("OpenGL ES profile renderer: (.+)").find(probe.output)?.groupValues?.get(1)
        TestEnv.report("gl", "$backend: wayland GLES renderer=$r")
        return r
    }

    private fun es2gears(backend: String) = ClientRunner.run(
        "es2gears-$backend",
        listOf("dn-gl", backend, "timeout", "-s", "INT", "12", "es2gears_wayland"),
        timeoutMs = 60_000,
    )

    private fun glmark2(backend: String) = ClientRunner.run(
        "glmark2-$backend",
        listOf("dn-gl", backend, "glmark2-wayland", "-b", "build:duration=4",
            "-b", "texture:duration=4", "-b", "shading:duration=4"),
        timeoutMs = 120_000,
    ).also { r ->
        Regex("glmark2 Score: (\\d+)").find(r.output)?.let {
            TestEnv.report("gl", "glmark2 $backend score=${it.groupValues[1]}")
        }
    }

    @Test
    fun zinkGles() {
        assumeTrue("no Turnip GPU on this device", TestEnv.hasTurnipGpu)
        val renderer = renderer("zink")
        assertTrue("renderer: $renderer", renderer?.contains("zink", ignoreCase = true) == true)
        val r = es2gears("zink")
        assertTrue(r.summary(), (r.stats?.framesPresented ?: 0) >= 100 && (r.stats?.nonBlackSamples ?: 0) > 0)
    }

    @Test
    fun zinkDesktopGl() {
        assumeTrue("no Turnip GPU on this device", TestEnv.hasTurnipGpu)
        val r = glmark2("zink")
        assertTrue(r.summary(), r.exitCode == 0 && (r.stats?.framesPresented ?: 0) >= 100)
    }

    @Test
    fun gl4esDesktopGl() {
        val r = glmark2("gl4es")
        assertTrue(r.summary(), r.exitCode == 0 && (r.stats?.framesPresented ?: 0) >= 30)
    }

    /** Reference: software GL through the same compositor */
    @Test
    fun llvmpipeGles() {
        val r = es2gears("llvmpipe")
        assertTrue(r.summary(), (r.stats?.framesPresented ?: 0) >= 30)
    }
}
