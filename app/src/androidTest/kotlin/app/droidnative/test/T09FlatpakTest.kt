package app.droidnative.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.droidnative.env.Session
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Flatpak inside proot: Flathub remote, install (OSTree on emulated hard
 * links) and run (bubblewrap sandbox). Downloads a runtime of several
 * hundred MB on the first run.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class T09FlatpakTest {

    private val app = "org.freedesktop.Platform.GlxInfo"
    private val branch = "26.08"

    @Before
    fun setUp() {
        assumeTrue("Flathub has no builds for ${TestEnv.arch.voidName}", TestEnv.arch.supportsFlatpak)
        TestEnv.ensureSetup()
        // D-Bus session bus, as when the app runs normally
        Session.start(TestEnv.context)
    }

    @Test
    fun a_remote() {
        val r = TestEnv.proot.sh("flatpak --version && flatpak --user remote-add --if-not-exists flathub " +
            "https://dl.flathub.org/repo/flathub.flatpakrepo && flatpak --user remotes", timeoutMs = 300_000)
        TestEnv.saveOutput("flatpak-remote", r)
        assertTrue(r.output, r.ok && r.output.contains("flathub"))
    }

    @Test
    fun b_install() {
        val t0 = System.currentTimeMillis()
        val r = TestEnv.proot.sh("flatpak --user install -y --noninteractive flathub " +
            "app/$app/${TestEnv.arch.voidName}/$branch 2>&1 | tail -20; flatpak --user list --columns=ref",
            timeoutMs = 90 * 60_000L)
        TestEnv.saveOutput("flatpak-install", r)
        TestEnv.report("flatpak", "install took ${(System.currentTimeMillis() - t0) / 1000}s exit=${r.exitCode}")
        assertTrue(r.output, r.output.contains("$app/"))
    }

    @Test
    fun c_runInSandbox() {
        val r = TestEnv.proot.sh("flatpak --user run --command=sh $app -c " +
            "'echo inside-flatpak; head -1 /etc/os-release; ls /app | head -3' 2>&1 | tail -8", timeoutMs = 300_000)
        TestEnv.saveOutput("flatpak-run", r)
        TestEnv.report("flatpak", "run: ${r.output.trim().replace('\n', ' ').take(200)}")
        assertTrue(r.output, r.output.contains("inside-flatpak"))
    }

    /**
     * GLES app from the sandbox in a real window. On KGSL devices it must be
     * accelerated: Zink on Turnip from our GL extension (which also proves
     * Vulkan works inside the sandbox), presented through dmabuf.
     */
    @Test
    fun d_waylandWindow() {
        val r = ClientRunner.run("flatpak-es2gears",
            listOf("flatpak", "--user", "run", "--command=sh", app, "-c",
                "timeout -s INT 12 es2gears_wayland -info 2>&1 | grep -vE '^GL_EXTENSIONS'"),
            timeoutMs = 120_000)
        val renderer = Regex("GL_RENDERER\\s*=\\s*(.+)").find(r.output)?.groupValues?.get(1)
        TestEnv.report("flatpak", "es2gears renderer=$renderer")
        val s = r.stats
        assertTrue(r.summary() + "\n" + r.output.takeLast(600),
            s != null && s.framesPresented >= 100 && s.nonBlackSamples > 0)
        if (TestEnv.hasKgsl) {
            // Only Zink on Turnip (Vulkan WSI) hands the compositor dmabufs;
            // software GL would arrive as wl_shm. The renderer string is
            // informational, not every es2gears build prints it.
            assertTrue("expected dmabuf presentation: ${r.summary()}", s!!.dmabufCommits > 0)
        }
    }
}
