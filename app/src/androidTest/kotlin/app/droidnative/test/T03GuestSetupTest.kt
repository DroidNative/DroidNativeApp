package app.droidnative.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class T03GuestSetupTest {

    /** Installs the package set (network, can take a while on first run). */
    @Test
    fun guestSetup() {
        val t0 = System.currentTimeMillis()
        TestEnv.ensureSetup()
        TestEnv.report("setup", "done in ${(System.currentTimeMillis() - t0) / 1000}s")
        val root = TestEnv.paths.rootfs
        assertTrue(File(root, "usr/bin/vulkaninfo").isFile)
        assertTrue(File(root, "usr/bin/dn-wltest").isFile)
        assertTrue("keymap missing", TestEnv.paths.keymap.isFile)
    }

    /** bubblewrap inside proot: the sandbox layouts Flatpak relies on */
    @Test
    fun bubblewrapRuns() {
        TestEnv.ensureSetup()
        val cases = mapOf(
            "bind-root" to "--bind / /",
            "dev-proc" to "--ro-bind / / --proc /proc --dev /dev --unshare-all",
            "built-root" to "--ro-bind /usr /usr --symlink usr/bin /bin --symlink usr/lib /lib " +
                "--symlink usr/lib /lib64 --proc /proc --dev /dev --tmpfs /tmp",
        )
        val failed = ArrayList<String>()
        for ((name, args) in cases) {
            val r = TestEnv.proot.sh("bwrap $args /bin/sh -c 'echo bwrap-ok; ls / | wc -l'")
            TestEnv.saveOutput("bwrap-$name", r)
            TestEnv.report("setup", "bwrap $name: exit=${r.exitCode} ${r.output.trim().replace('\n', ' ').take(160)}")
            if (!r.ok || !r.output.contains("bwrap-ok")) failed += name
        }
        assertTrue("failed bwrap layouts: $failed", failed.isEmpty())
    }
}
