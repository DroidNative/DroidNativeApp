package app.droidnative.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class T02ProotTest {

    @Before
    fun setUp() = TestEnv.ensureRootfs()

    private fun sh(name: String, script: String, timeoutMs: Long = 60_000) =
        TestEnv.proot.sh(script, timeoutMs = timeoutMs).also { TestEnv.saveOutput("proot-$name", it) }

    @Test
    fun unameMatchesArch() {
        val r = sh("uname", "uname -m")
        assertTrue(r.output, r.ok)
        val expected = when (TestEnv.arch.voidName) {
            "armv7l" -> listOf("armv7l", "armv8l")
            "i686" -> listOf("i686", "x86_64")
            else -> listOf(TestEnv.arch.voidName)
        }
        assertTrue("uname: ${r.output}", expected.contains(r.output.trim()))
    }

    @Test
    fun isVoid() {
        val r = sh("os-release", "cat /etc/os-release")
        assertTrue(r.output, r.ok && r.output.contains("void", ignoreCase = true))
    }

    @Test
    fun fakeRootAndXbps() {
        // Package management runs as fake root, everything else as the user
        val r = TestEnv.proot.sh("id -u && xbps-query -V", root = true)
            .also { TestEnv.saveOutput("proot-xbps", it) }
        assertTrue(r.output, r.ok)
        assertEquals("0", r.output.lineSequence().first().trim())
        val u = sh("user", "id -un; id -u; echo \$HOME")
        assertEquals(listOf("user", "1000", "/home/user"), u.output.lines().take(3).map { it.trim() })
    }

    @Test
    fun forkAndPipes() {
        val t0 = System.currentTimeMillis()
        val r = sh("fork", "i=0; while [ \$i -lt 200 ]; do /bin/true; i=\$((i+1)); done; seq 1 20000 | sort -rn | head -1")
        val ms = System.currentTimeMillis() - t0
        TestEnv.report("proot", "200 execs + pipeline took ${ms}ms")
        assertTrue(r.output, r.ok)
        assertEquals("20000", r.output.trim())
    }

    @Test
    fun hardLinkEmulation() {
        val r = sh("hardlink", "cd /tmp && rm -f dn-a dn-b && echo dn > dn-a && ln dn-a dn-b && cat dn-b && rm -f dn-a dn-b")
        assertTrue(r.output, r.ok && r.output.trim() == "dn")
    }

    @Test
    fun androidStorageVisible() {
        val r = sh("storage", "ls -d /media/sdcard && ls /media/sdcard | head -5")
        TestEnv.report("proot", "android storage: exit=${r.exitCode} ${r.output.lines().take(3)}")
        // Needs the all-files permission; only reported, granted in run.sh
    }
}
