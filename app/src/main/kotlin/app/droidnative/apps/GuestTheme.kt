package app.droidnative.apps

import android.content.Context
import android.content.res.Configuration
import app.droidnative.env.EnvPaths
import app.droidnative.env.Prefs
import app.droidnative.env.ThemeMode
import java.io.File

/**
 * Hands DroidNative's light or dark mode to Linux programs, which normally
 * get it from the desktop's settings service (none runs here):
 * GTK 3 and 4 read gtk-application-prefer-dark-theme from settings.ini,
 * libadwaita reads ADW_DEBUG_COLOR_SCHEME when no settings portal exists.
 * Applies to programs started afterwards.
 */
object GuestTheme {
    fun isDark(context: Context): Boolean = when (Prefs(context).themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    /** Updates the GTK settings files; returns the variables for a program. */
    fun prepare(context: Context): Map<String, String> {
        val dark = isDark(context)
        val home = File(EnvPaths(context).rootfs, EnvPaths.GUEST_HOME.trimStart('/'))
        for (gtk in listOf("gtk-3.0", "gtk-4.0"))
            setKey(File(home, ".config/$gtk/settings.ini"), "gtk-application-prefer-dark-theme", if (dark) "1" else "0")
        return mapOf("ADW_DEBUG_COLOR_SCHEME" to if (dark) "prefer-dark" else "prefer-light")
    }

    // key=value in the [Settings] group, keeping everything else of the file
    private fun setKey(file: File, key: String, value: String) {
        val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
        var section = lines.indexOfFirst { it.trim() == "[Settings]" }
        if (section < 0) {
            lines += "[Settings]"
            section = lines.size - 1
        }
        var end = section + 1
        while (end < lines.size && !lines[end].trim().startsWith("[")) end++
        val existing = (section + 1 until end).firstOrNull { lines[it].substringBefore('=').trim() == key }
        val line = "$key=$value"
        if (existing != null) {
            if (lines[existing].trim() == line) return
            lines[existing] = line
        } else {
            lines.add(end, line)
        }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n") + "\n")
    }
}
