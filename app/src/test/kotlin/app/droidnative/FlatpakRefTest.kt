package app.droidnative

import app.droidnative.apps.Flatpak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FlatpakRefTest {
    private val flathub = """
        [Flatpak Ref]
        Name=org.gnome.Calculator
        Branch=stable
        Url=https://dl.flathub.org/repo/
        SuggestRemoteName=flathub
        Title=Calculator
        RuntimeRepo=https://dl.flathub.org/repo/flathub.flatpakrepo
        IsRuntime=false
    """.trimIndent()

    @Test fun parsesFlathubRef() {
        val ref = Flatpak.parseRef(flathub)!!
        assertEquals("org.gnome.Calculator", ref.name)
        assertEquals("stable", ref.branch)
        assertEquals("https://dl.flathub.org/repo/", ref.url)
        assertEquals("Calculator", ref.title)
        assertFalse(ref.isRuntime)
    }

    @Test fun matchesRemoteIgnoringSlash() {
        val ref = Flatpak.parseRef(flathub)!!
        val remotes = listOf(Flatpak.Remote("flathub", "Flathub", "https://dl.flathub.org/repo", false))
        assertEquals("flathub", Flatpak.remoteFor(remotes, ref)?.name)
    }

    @Test fun rejectsUnsafeRefs() {
        assertNull(Flatpak.parseRef(flathub.replace("https://dl.flathub.org/repo/", "http://example.com/")))
        assertNull(Flatpak.parseRef(flathub.replace("org.gnome.Calculator", "../evil")))
        assertNull(Flatpak.parseRef("[Flatpak Repo]\nUrl=https://example.com/"))
    }
}
