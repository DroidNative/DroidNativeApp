package app.droidnative.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.droidnative.env.Virgl
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
        // No display: vulkaninfo crashes probing X11 surfaces when DISPLAY
        // names an X server that is not running (the suite starts none)
        val r = TestEnv.proot.sh("env -u DISPLAY -u WAYLAND_DISPLAY vulkaninfo --summary 2>&1")
        TestEnv.saveOutput("vulkaninfo", r)
        val devices = Regex("deviceName\\s*=\\s*(.+)").findAll(r.output).map { it.groupValues[1].trim() }.toList()
        TestEnv.report("graphics", "vulkan devices: $devices")
        assertTrue(r.output, r.ok && devices.isNotEmpty())
        TestEnv.report("graphics", "android renderer: ${TestEnv.androidRenderer}")
        if (TestEnv.hasTurnipGpu)
            assertTrue("Turnip not listed: $devices", devices.any { it.contains("Adreno") || it.contains("Turnip") })
    }

    @Test
    fun zinkSurfaceless() {
        assumeTrue("no Turnip GPU on this device", TestEnv.hasTurnipGpu)
        // TU_DEBUG=noconform comes from Proot.guestEnv on KGSL devices
        val r = TestEnv.proot.run(listOf("dn-gl", "zink", "eglinfo", "-B", "-p", "surfaceless"),
            timeoutMs = 60_000)
        TestEnv.saveOutput("zink-surfaceless", r)
        val renderer = Regex("OpenGL (?:ES )?(?:core )?profile renderer: (.+)").find(r.output)?.groupValues?.get(1)
        TestEnv.report("graphics", "zink surfaceless: exit=${r.exitCode} renderer=$renderer")
        assertTrue("renderer: $renderer", renderer?.contains("zink", ignoreCase = true) == true)
    }

    /** Hardware GL on every GPU: Mesa virpipe -> app's virgl server -> Android GLES. */
    @Test
    fun virglSurfaceless() {
        assumeTrue("no virgl server in this build", TestEnv.paths.virglServer.exists())
        Virgl.start(TestEnv.context)
        try {
            var r = TestEnv.proot.sh("for i in 1 2 3 4 5 6 7 8 9 10; do [ -S \$VTEST_SOCKET_NAME ] && break; sleep 0.5; done; " +
                "dn-gl virgl eglinfo -B -p surfaceless 2>&1")
            TestEnv.saveOutput("virgl-surfaceless", r)
            val renderer = Regex("OpenGL (?:ES )?(?:core )?profile renderer: (.+)").find(r.output)?.groupValues?.get(1)
            TestEnv.report("graphics", "virgl surfaceless: exit=${r.exitCode} renderer=$renderer")
            assertTrue("renderer: $renderer", renderer?.contains("virgl", ignoreCase = true) == true)
            // gl4es on top of it: desktop GL 2.1 through GLES on the Android driver
            r = TestEnv.proot.sh("dn-gl gl4es env | grep -E '^(GALLIUM_DRIVER|LD_LIBRARY_PATH)='")
            TestEnv.report("graphics", "gl4es backend env: ${r.output.trim().replace('\n', ';')}")
            if (!TestEnv.hasTurnipGpu) assertTrue(r.output, r.output.contains("GALLIUM_DRIVER=virpipe"))
        } finally {
            Virgl.stop()
        }
    }
}
