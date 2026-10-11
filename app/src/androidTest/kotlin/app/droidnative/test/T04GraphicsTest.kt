package app.droidnative.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class T04GraphicsTest {

    @Before
    fun setUp() = TestEnv.ensureSetup()

    @Test
    fun gpuNodesFromGuest() {
        val r = TestEnv.proot.sh("for n in /dev/kgsl-3d0 /dev/dma_heap/system /dev/ion; do " +
            "if [ -e \$n ]; then printf '%s r=%s w=%s\\n' \$n \$(test -r \$n && echo y || echo n) " +
            "\$(test -w \$n && echo y || echo n); else echo \"\$n missing\"; fi; done")
        TestEnv.saveOutput("gpu-nodes", r)
        TestEnv.report("graphics", r.output.trim().replace('\n', ';'))
        if (TestEnv.hasKgsl) assertTrue(r.output, r.output.contains("/dev/kgsl-3d0 r=y w=y"))
    }

    @Test
    fun vulkanInfo() {
        val r = TestEnv.proot.sh("vulkaninfo --summary 2>&1")
        TestEnv.saveOutput("vulkaninfo", r)
        val devices = Regex("deviceName\\s*=\\s*(.+)").findAll(r.output).map { it.groupValues[1].trim() }.toList()
        TestEnv.report("graphics", "vulkan devices: $devices")
        assertTrue(r.output, r.ok && devices.isNotEmpty())
        if (TestEnv.hasKgsl && TestEnv.arch.hasTurnipPackage)
            assertTrue("Turnip not listed: $devices", devices.any { it.contains("Adreno") || it.contains("Turnip") })
    }

    @Test
    fun zinkSurfaceless() {
        assumeTrue("no hardware Vulkan (KGSL) on this device", TestEnv.hasKgsl)
        // TU_DEBUG=noconform comes from Proot.guestEnv on KGSL devices
        val r = TestEnv.proot.run(listOf("dn-gl", "zink", "eglinfo", "-B", "-p", "surfaceless"),
            timeoutMs = 60_000)
        TestEnv.saveOutput("zink-surfaceless", r)
        val renderer = Regex("OpenGL (?:ES )?(?:core )?profile renderer: (.+)").find(r.output)?.groupValues?.get(1)
        TestEnv.report("graphics", "zink surfaceless: exit=${r.exitCode} renderer=$renderer")
        assertTrue("renderer: $renderer", renderer?.contains("zink", ignoreCase = true) == true)
    }
}
