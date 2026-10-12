package app.droidnative.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.droidnative.env.VoidArch
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** AppImages without FUSE: dn-appimage unpacks and registers them. */
@RunWith(AndroidJUnit4::class)
class T08AppImageTest {

    @Before
    fun setUp() = TestEnv.ensureSetup()

    @Test
    fun installRunRemove() {
        // appimagetool is a small, official AppImage built for these arches
        val arch = when (TestEnv.arch) {
            VoidArch.AARCH64 -> "aarch64"
            VoidArch.X86_64 -> "x86_64"
            VoidArch.ARMV7L -> "armhf"
            VoidArch.I686 -> "i686"
        }
        val file = "appimagetool-$arch.AppImage"
        val url = "https://github.com/AppImage/appimagetool/releases/download/continuous/$file"
        val fetch = TestEnv.proot.sh("cd /home/user && { [ -s $file ] || xbps-fetch -o $file '$url'; } 2>&1 | tail -2",
            timeoutMs = 300_000)
        TestEnv.saveOutput("appimage-fetch", fetch)
        assumeTrue("could not download $file: ${fetch.output}", fetch.ok)

        val r = TestEnv.proot.sh(
            "cd /home/user && id=\$(dn-appimage install $file) && echo \"id=\$id\" && dn-appimage list && " +
                "cat ~/.local/share/applications/dn-appimage-\$id.desktop && " +
                "dn-appimage run \$id --version 2>&1 | tail -1 && " +
                // Own title, then back to the AppImage's
                "dn-appimage rename \$id 'Demo Tool' && grep -q '^Name=Demo Tool' " +
                "~/.local/share/applications/dn-appimage-\$id.desktop && dn-appimage rename \$id && " +
                "! grep -q '^Name=Demo Tool' ~/.local/share/applications/dn-appimage-\$id.desktop && echo rename-ok && " +
                "dn-appimage remove \$id && " +
                // Other AppImages the user installed stay listed
                "! dn-appimage list | cut -f1 | grep -qx \"\$id\" && echo removed-ok",
            timeoutMs = 300_000)
        TestEnv.saveOutput("appimage", r)
        TestEnv.report("appimage", "exit=${r.exitCode} ${r.output.lines().filter { it.startsWith("id=") || it.startsWith("appimagetool,") || it == "removed-ok" || it == "rename-ok" }}")
        assertTrue(r.output, r.ok && r.output.contains("removed-ok") && r.output.contains("rename-ok") &&
            r.output.contains("Exec=dn-appimage run") && r.output.contains("appimagetool,"))
    }
}
