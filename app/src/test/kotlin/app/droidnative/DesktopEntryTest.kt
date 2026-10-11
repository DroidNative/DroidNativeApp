package app.droidnative

import app.droidnative.apps.DesktopEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopEntryTest {
    private fun entry(extra: String = "") = """
        [Desktop Entry]
        Name=Thunar File Manager
        Name[de]=Thunar Dateiverwaltung
        Exec=thunar %U
        Icon=org.xfce.thunar
        Type=Application
        $extra

        [Desktop Action home]
        Name=Home
        Exec=thunar %U --home
    """.trimIndent()

    @Test fun parsesMainGroupOnly() {
        val e = DesktopEntry.parse("thunar", entry())!!
        assertEquals("Thunar File Manager", e.name)
        assertEquals("thunar", e.commandLine())
        assertEquals("org.xfce.thunar", e.icon)
    }

    @Test fun hiddenEntriesAreDropped() {
        assertNull(DesktopEntry.parse("a", entry("NoDisplay=true")))
        assertNull(DesktopEntry.parse("a", entry("Hidden=true")))
        assertNull(DesktopEntry.parse("a", entry("OnlyShowIn=XFCE;")))
        assertNull(DesktopEntry.parse("a", entry("NotShowIn=GNOME;DroidNative;")))
        assertNotNull(DesktopEntry.parse("a", entry("NotShowIn=GNOME;")))
        assertNotNull(DesktopEntry.parse("a", entry("OnlyShowIn=XFCE;DroidNative;")))
    }

    @Test fun x11OnlyVariablesAreDropped() {
        val e = DesktopEntry("audacity", "Audacity", "env GDK_BACKEND=x11 UBUNTU_MENUPROXY=0 audacity %F", "", false)
        assertEquals("env UBUNTU_MENUPROXY=0 audacity", e.waylandCommandLine())
        assertEquals("audacity", e.program)
        val f = DesktopEntry("x", "X", "env GDK_BACKEND=x11 foo", "", false)
        assertEquals("foo", f.waylandCommandLine())
    }

    @Test fun fieldCodesAreRemoved() {
        val e = DesktopEntry("x", "X", "sh -c \"echo 100%% %f\" --name=%c %F", "", false)
        assertEquals("sh -c \"echo 100% \" --name=", e.commandLine())
    }
}
