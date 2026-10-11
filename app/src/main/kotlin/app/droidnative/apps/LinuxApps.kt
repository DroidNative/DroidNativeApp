package app.droidnative.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.util.Log
import app.droidnative.env.EnvPaths
import app.droidnative.env.EnvService
import app.droidnative.env.Proot
import app.droidnative.env.Prefs
import app.droidnative.env.StorageMounts
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Applications installed in the guest: reads the desktop entries and icons
 * straight from the rootfs (no proot process needed) and starts them.
 */
class LinuxApps(context: Context) {
    private val app = context.applicationContext
    private val paths = EnvPaths(app)
    private val root = paths.rootfs

    /** Entries of all sources, user entries overriding system ones, by name. */
    fun list(): List<DesktopEntry> {
        val byId = LinkedHashMap<String, DesktopEntry?>()
        for (dir in APPLICATION_DIRS) {
            val files = File(root, dir).listFiles() ?: continue
            for (f in files) {
                if (!f.name.endsWith(".desktop")) continue
                val id = f.name.removeSuffix(".desktop")
                // An entry that hides itself also hides the ones it overrides
                byId[id] = resolve(f)?.let { DesktopEntry.parse(id, it.readText()) }
            }
        }
        index = null   // the program index follows the list
        return byId.values.filterNotNull().sortedBy { it.name.lowercase() }
    }

    /**
     * Follows symlinks the way the guest sees them: absolute targets are
     * relative to the rootfs (Flatpak exports are such links).
     */
    private fun resolve(file: File): File? {
        var f = file
        repeat(8) {
            val target = try { Os.readlink(f.path) } catch (_: Exception) { return if (f.isFile) f else null }
            f = if (target.startsWith("/")) File(root, target.trimStart('/'))
                else File(f.parentFile, target)
        }
        return null
    }

    /** The listed entry with this id, or null. */
    fun find(id: String): DesktopEntry? = list().firstOrNull { it.id == id }

    /**
     * Entry for a name an application reports about itself (desktop-entry
     * hint, MPRIS DesktopEntry or Identity): the id, case-insensitively, a
     * reverse-DNS id ending in it, or the entry's name.
     */
    fun findLoose(name: String): DesktopEntry? {
        if (name.isEmpty()) return null
        val n = name.removeSuffix(".desktop")
        val all = list()
        return all.firstOrNull { it.id == n } ?: all.firstOrNull { it.id.equals(n, true) }
            ?: all.firstOrNull { it.id.endsWith(".$n", true) } ?: all.firstOrNull { it.name.equals(n, true) }
    }

    /** Icon as a bitmap, or null when the guest has none in a usable format. */
    fun icon(entry: DesktopEntry): Bitmap? = icon(entry.icon)

    /** Icon by theme name or guest path, like a desktop entry's Icon key. */
    fun icon(name: String): Bitmap? {
        val file = iconFile(name) ?: return null
        return BitmapFactory.decodeFile(file.path)
    }

    /**
     * The host file behind guest path [path] (the runtime dir, storage
     * binds, else the rootfs with its symlinks); null if it does not exist.
     */
    fun guestFile(path: String): File? {
        if (!path.startsWith("/")) return null
        val run = EnvPaths.GUEST_RUN_DIR + "/"
        if (path.startsWith(run)) return File(paths.runDir, path.removePrefix(run)).takeIf { it.isFile }
        for (m in StorageMounts.active(app))
            if (path.startsWith(m.guest + "/")) return File(m.host, path.removePrefix(m.guest + "/")).takeIf { it.isFile }
        return resolve(File(root, path.trimStart('/')))
    }

    private fun iconFile(icon: String): File? {
        if (icon.isEmpty()) return null
        if (icon.startsWith("/")) {
            val f = resolve(File(root, icon.trimStart('/')))
            return if (f != null && !f.name.endsWith(".svg")) f else f?.let { converted(icon) }
        }
        for (base in ICON_DIRS) {
            for (size in ICON_SIZES) {
                resolve(File(root, "$base/hicolor/$size/apps/$icon.png"))?.let { return it }
            }
        }
        resolve(File(root, "usr/share/pixmaps/$icon.png"))?.let { return it }
        return converted(icon)
    }

    // PNG rendered from an SVG by convertIcons()
    private fun converted(icon: String): File? =
        File(root, "$ICON_CACHE/${cacheName(icon)}.png").takeIf { it.isFile }

    /** Output of the last start of [entry]. */
    fun logFile(entry: DesktopEntry) = File(paths.logDir, "app-${cacheName(entry.id)}.log")

    private fun cacheName(icon: String) = icon.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * Renders the SVG-only icons of [entries] to PNG inside the guest
     * (rsvg-convert), once per icon. Blocking; returns true if any was made.
     */
    fun convertIcons(entries: List<DesktopEntry>): Boolean {
        val script = StringBuilder("mkdir -p /$ICON_CACHE\n")
        var jobs = 0
        for (e in entries) {
            if (e.icon.isEmpty() || iconFile(e.icon) != null) continue
            val svg = if (e.icon.startsWith("/")) e.icon.takeIf { it.endsWith(".svg") }
                      else ICON_DIRS.map { "/$it/hicolor/scalable/apps/${e.icon}.svg" }
                          .firstOrNull { resolve(File(root, it.trimStart('/'))) != null }
            svg ?: continue
            val out = "/$ICON_CACHE/${cacheName(e.icon)}.png"
            script.append("rsvg-convert -w 128 -h 128 '${svg.replace("'", "")}' -o '$out.tmp' && mv '$out.tmp' '$out'\n")
            jobs++
        }
        if (jobs == 0) return false
        val r = Proot(paths).sh(script.toString(), timeoutMs = 60_000)
        if (!r.ok) Log.w(TAG, "icon conversion: ${r.output.take(500)}")
        return true
    }

    /**
     * Starts [entry] in the guest with its per-app settings; its windows
     * open through the compositor. [override] replaces stored settings for
     * this start only (e.g. the Install tab's portrait OctoXBPS).
     */
    fun launch(entry: DesktopEntry, override: AppSettings? = null): Process {
        if (!EnvService.isRunning) EnvService.start(app)
        val settings = override ?: AppSettingsStore(app).get(entry.id)
        // Entries that force X11 (Audacity) get it when Xwayland is installed
        val hasX = File(paths.rootfs, "usr/bin/Xwayland").exists() && File(paths.rootfs, "usr/bin/dn-xwm").exists()
        var cmd = if (hasX) entry.commandLine() else entry.waylandCommandLine()
        if (entry.terminal) cmd = "xfce4-terminal -x $cmd"
        val backend = settings.backend ?: GlBackend.AUTO
        if (backend != GlBackend.AUTO) cmd = "dn-gl ${backend.arg} $cmd"
        val env = linkedMapOf(
            "XDG_CURRENT_DESKTOP" to DesktopEntry.DESKTOP_NAME,
            // No accessibility bus in the guest; GTK would wait for it
            "NO_AT_BRIDGE" to "1",
        )
        // Light or dark like DroidNative (GTK settings.ini, libadwaita)
        env.putAll(GuestTheme.prepare(app))
        val dpiVars = dpiEnv(settings.dpi)
        env.putAll(dpiVars)
        if (settings.children != ChildPolicy.ALL) {
            // libdn-appenv withdraws these from child programs (droidnative-tools)
            env["DN_APP_CHILDREN"] = settings.children.name.lowercase()
            env["DN_APP_VARS"] = dpiVars.keys.joinToString(" ")
            env["LD_PRELOAD"] = APPENV_LIB
        }
        writeAppList()
        AppLaunches.prune(paths)
        AppLaunches.dir(paths).mkdirs()
        val log = logFile(entry)
        log.parentFile?.mkdirs()
        // Login shell: /etc/profile.d/droidnative.sh sets the session
        // variables. The shell registers its pid, which the app keeps after
        // exec, so windows can be traced back to this launch.
        val id = entry.id.replace("'", "")
        val script = "echo '$id' > ${EnvPaths.GUEST_RUN_DIR}/apps/$$; export DN_APP_PID=$$; exec $cmd"
        val proc = Proot(paths).processBuilder(listOf("/bin/sh", "-lc", script), extraEnv = env)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(log))
            .start()
        proc.outputStream.close()
        Log.i(TAG, "started ${entry.id}: $cmd")
        return proc
    }

    /**
     * Toolkit variables for the part of [dpi] (null: global) the integer
     * wl_output scale does not cover. GTK scales text, Qt everything.
     */
    fun dpiEnv(dpi: Int?): Map<String, String> {
        val prefs = Prefs(app)
        val wanted = dpi ?: prefs.dpi ?: prefs.defaultDpi(app.resources.displayMetrics.density)
        val factor = wanted / (96f * EnvService.outputScale)
        if (abs(factor - 1f) < 0.01f) return emptyMap()
        val f = String.format(Locale.ROOT, "%.3f", factor)
        return mapOf("GDK_DPI_SCALE" to f, "QT_SCALE_FACTOR" to f)
    }

    /**
     * /run/droidnative/apps.list for libdn-appenv: listed programs with their
     * own backend and DPI variables, one per line, tab separated:
     * program, backend, GDK_DPI_SCALE, QT_SCALE_FACTOR ("-" when unset).
     */
    private fun writeAppList() {
        val store = AppSettingsStore(app)
        val sb = StringBuilder()
        for (e in list()) {
            val prog = e.program
            if (prog.isEmpty() || prog in AMBIGUOUS_PROGRAMS) continue
            val s = store.get(e.id)
            val dpi = dpiEnv(s.dpi)
            sb.append(prog).append('\t').append((s.backend ?: GlBackend.AUTO).arg).append('\t')
                .append(dpi["GDK_DPI_SCALE"] ?: "-").append('\t')
                .append(dpi["QT_SCALE_FACTOR"] ?: "-").append('\n')
        }
        val f = File(paths.runDir, "apps.list")
        val tmp = File(paths.runDir, "apps.list.tmp")
        tmp.writeText(sb.toString())
        tmp.renameTo(f)
    }

    companion object {
        private const val TAG = "dn-apps"
        const val APPENV_LIB = "/usr/lib/droidnative/libdn-appenv.so"
        // Programs many entries share: they say nothing about the application
        private val AMBIGUOUS_PROGRAMS = setOf("flatpak", "sh", "bash", "env", "python3", "dn-appimage", "dn-gl")

        @Volatile private var index: Map<String, String>? = null

        /** Program basename -> desktop id of the listed applications. */
        fun programIndex(context: Context): Map<String, String> = index ?: LinuxApps(context).list()
            .filter { it.program.isNotEmpty() && it.program !in AMBIGUOUS_PROGRAMS }
            .associate { it.program to it.id }.also { index = it }

        /** Call after installs and removals. */
        fun invalidate() { index = null }

        // Later directories override earlier ones (same order as XDG_DATA_DIRS reversed)
        private val APPLICATION_DIRS = listOf(
            "usr/share/applications",
            "usr/local/share/applications",
            "var/lib/flatpak/exports/share/applications",
            "home/user/.local/share/flatpak/exports/share/applications",
            "home/user/.local/share/applications",
        )
        private val ICON_DIRS = listOf(
            "usr/share/icons",
            "usr/local/share/icons",
            "home/user/.local/share/flatpak/exports/share/icons",
            "home/user/.local/share/icons",
        )
        private val ICON_SIZES = listOf("128x128", "96x96", "256x256", "64x64", "48x48", "512x512")
        private const val ICON_CACHE = "home/user/.cache/droidnative/icons"
    }
}
