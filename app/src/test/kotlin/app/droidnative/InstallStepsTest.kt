package app.droidnative

import app.droidnative.env.EnvController.Progress
import app.droidnative.env.EnvController.ScriptSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InstallStepsTest {
    private fun ScriptSteps.feed(vararg lines: String) = lines.forEach { parse(it) }

    @Test
    fun countsBothScripts() {
        val s = ScriptSteps(3)
        assertFalse(s.parse("Downloading foo-1.0..."))
        assertEquals(Progress(3, 0), s.progress())
        // setup.sh: two steps; one step reserved for setup-user.sh
        s.feed("[setup-steps] 2", "[setup] updating xbps")
        assertEquals(Progress(3, 6), s.progress())
        assertEquals("updating xbps", s.label)
        s.feed("[setup] installing x")
        assertEquals(Progress(4, 6), s.progress())
        s.feed("[setup-end]")
        assertEquals(Progress(5, 6), s.progress())
        s.feed("[setup-steps] 1", "[setup] configuring flatpak", "[setup-end]")
        assertEquals(Progress(6, 6), s.progress())
    }

    @Test
    fun userScriptWithoutSteps() {
        val s = ScriptSteps(0)
        s.feed("[setup-steps] 1", "[setup] a", "[setup-end]")
        assertEquals(Progress(1, 2), s.progress())
        s.feed("[setup-steps] 0", "[setup-end]")
        assertEquals(Progress(1, 1), s.progress())
    }
}
