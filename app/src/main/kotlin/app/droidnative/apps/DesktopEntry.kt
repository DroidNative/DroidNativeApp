package app.droidnative.apps

import java.io.File

/**
 * One launchable application from a freedesktop.org desktop entry.
 * [id] is the file name without ".desktop".
 */
data class DesktopEntry(
    val id: String,
    val name: String,
    val exec: String,
    val icon: String,
    val terminal: Boolean,
    val genericName: String = "",
    val comment: String = "",
    val categories: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
) {
    /**
     * [commandLine] without variable assignments that force X11 (Audacity's
     * entry sets GDK_BACKEND=x11): there is no X server until Xwayland.
     */
    fun waylandCommandLine(): String {
        val words = commandLine().split(' ').filter { it.isNotEmpty() }
        val kept = words.filter { it !in X11_ONLY }
        // "env" with nothing left to assign is just noise
        return (if (kept.firstOrNull() == "env" && kept.getOrNull(1)?.contains('=') != true) kept.drop(1) else kept)
            .joinToString(" ")
    }

    /**
     * Program the entry runs, as a basename ("mousepad"), skipping "env"
     * and variable assignments; matches the processes it starts.
     */
    val program: String
        get() = commandLine().split(Regex("\\s+")).dropWhile { it == "env" || (it.contains('=') && !it.startsWith("/")) }
            .firstOrNull()?.trim('"', '\'')?.substringAfterLast('/') ?: ""

    /** Menu section, as Linux desktops group their application menus. */
    val category: Category get() = Category.of(categories)

    /** Search over the same fields a desktop menu searches. */
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val fields = listOf(name, genericName, comment, id, exec.substringBefore(' ')) + keywords
        return q.split(Regex("\\s+")).all { word -> fields.any { it.contains(word, ignoreCase = true) } }
    }

    /**
     * Command line for /bin/sh: the Exec value without its field codes
     * (%f, %U, ...), since nothing is passed when started from the overview.
     */
    fun commandLine(): String {
        val out = StringBuilder()
        var i = 0
        while (i < exec.length) {
            val c = exec[i]
            if (c == '%' && i + 1 < exec.length) {
                if (exec[i + 1] == '%') out.append('%')
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString().trim()
    }

    companion object {
        private val X11_ONLY = setOf("GDK_BACKEND=x11", "QT_QPA_PLATFORM=xcb", "SDL_VIDEODRIVER=x11",
            "CLUTTER_BACKEND=x11", "WINIT_UNIX_BACKEND=x11", "MOZ_ENABLE_WAYLAND=0")

        /** Value of XDG_CURRENT_DESKTOP in the guest; for OnlyShowIn/NotShowIn. */
        const val DESKTOP_NAME = "DroidNative"

        /**
         * Parses the "[Desktop Entry]" group. Returns null for entries that
         * must not be listed: not an application, NoDisplay, Hidden, or
         * restricted to other desktops.
         */
        fun parse(id: String, text: String): DesktopEntry? {
            val keys = HashMap<String, String>()
            var inGroup = false
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("[")) {
                    inGroup = line == "[Desktop Entry]"
                    continue
                }
                if (!inGroup) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                // Localised keys (Name[de]) are skipped: the UI is English
                val key = line.substring(0, eq).trim()
                if (key !in keys) keys[key] = line.substring(eq + 1).trim()
            }
            if (keys["Type"] != "Application") return null
            if (keys["NoDisplay"] == "true" || keys["Hidden"] == "true") return null
            val only = keys["OnlyShowIn"]
            if (only != null && DESKTOP_NAME !in only.split(';')) return null
            if (DESKTOP_NAME in (keys["NotShowIn"] ?: "").split(';')) return null
            val name = keys["Name"] ?: return null
            val exec = keys["Exec"] ?: return null
            fun list(key: String) = (keys[key] ?: "").split(';').map { it.trim() }.filter { it.isNotEmpty() }
            return DesktopEntry(id, name, exec, keys["Icon"] ?: "", keys["Terminal"] == "true",
                genericName = keys["GenericName"] ?: "", comment = keys["Comment"] ?: "",
                categories = list("Categories"), keywords = list("Keywords"))
        }

        fun parse(file: File): DesktopEntry? = try {
            parse(file.name.removeSuffix(".desktop"), file.readText())
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Main categories of the freedesktop.org menu specification, in the order
 * desktop menus show them. [label] is a string resource.
 */
enum class Category(val keys: List<String>, val label: Int) {
    ACCESSORIES(listOf("Utility"), app.droidnative.R.string.cat_accessories),
    DEVELOPMENT(listOf("Development"), app.droidnative.R.string.cat_development),
    EDUCATION(listOf("Education", "Science"), app.droidnative.R.string.cat_education),
    GAMES(listOf("Game"), app.droidnative.R.string.cat_games),
    GRAPHICS(listOf("Graphics"), app.droidnative.R.string.cat_graphics),
    INTERNET(listOf("Network"), app.droidnative.R.string.cat_internet),
    MULTIMEDIA(listOf("AudioVideo", "Audio", "Video"), app.droidnative.R.string.cat_multimedia),
    OFFICE(listOf("Office"), app.droidnative.R.string.cat_office),
    SETTINGS(listOf("Settings"), app.droidnative.R.string.cat_settings),
    SYSTEM(listOf("System"), app.droidnative.R.string.cat_system),
    OTHER(emptyList(), app.droidnative.R.string.cat_other);

    companion object {
        // Settings before System (settings dialogs carry both), System before
        // Utility (terminals are "System;TerminalEmulator;Utility" in some entries)
        private val PRIORITY = listOf(SETTINGS, GAMES, DEVELOPMENT, EDUCATION, GRAPHICS,
            INTERNET, MULTIMEDIA, OFFICE, SYSTEM, ACCESSORIES)

        fun of(categories: List<String>): Category =
            PRIORITY.firstOrNull { c -> c.keys.any { it in categories } } ?: OTHER
    }
}
