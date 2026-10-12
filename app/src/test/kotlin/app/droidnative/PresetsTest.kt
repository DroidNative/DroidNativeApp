package app.droidnative

import app.droidnative.apps.ChildPolicy
import app.droidnative.apps.GlBackend
import app.droidnative.apps.MouseMode
import app.droidnative.apps.Presets
import app.droidnative.apps.Rotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetsTest {
    @Test
    fun parsesSectionsAndComments() {
        val ini = Presets.parseIni("""
            ; comment
            [org.luanti.luanti]
            backend = zink          ; inline comment
            Input = Touchpad
            # another comment
            [second]
            layout=layout.xml
        """.trimIndent())
        assertEquals(listOf("org.luanti.luanti", "second"), ini.keys.toList())
        assertEquals("zink", ini["org.luanti.luanti"]!!["backend"])
        assertEquals("Touchpad", ini["org.luanti.luanti"]!!["input"])
        assertEquals("layout.xml", ini["second"]!!["layout"])
    }

    @Test
    fun mapsKeysToSettings() {
        val s = Presets.toSettings(mapOf("backend" to "zink", "resolution" to "75%", "dpi" to "96",
            "input" to "touchpad", "rotation" to "landscape", "auto-keyboard" to "false", "children" to "unlisted"))
        assertEquals(GlBackend.ZINK, s.backend)
        assertEquals("75%", s.resolution)
        assertEquals(96, s.dpi)
        assertEquals(MouseMode.TOUCHPAD, s.mouseMode)
        assertEquals(Rotation.LANDSCAPE, s.rotation)
        assertEquals(false, s.autoKeyboard)
        assertEquals(ChildPolicy.UNLISTED, s.children)
    }

    @Test
    fun ignoresUnknownValues() {
        val s = Presets.toSettings(mapOf("backend" to "vulkan", "dpi" to "huge", "input" to "pen"))
        assertNull(s.backend)
        assertNull(s.dpi)
        assertNull(s.mouseMode)
        assertEquals(ChildPolicy.ALL, s.children)
    }

    @Test
    fun mapsSourcesToArchives() {
        assertEquals("https://codeload.github.com/DroidNative/DroidNativePresets/tar.gz/refs/heads/main",
            Presets.tarballUrl("https://github.com/DroidNative/DroidNativePresets"))
        assertEquals("https://codeload.github.com/demouser/presets/tar.gz/refs/heads/testing",
            Presets.tarballUrl("https://github.com/demouser/presets.git/tree/testing/"))
        assertEquals("https://example.invalid/p.tar.gz", Presets.tarballUrl(" https://example.invalid/p.tar.gz "))
    }
}
