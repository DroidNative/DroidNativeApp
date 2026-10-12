package app.droidnative.apps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import app.droidnative.R
import app.droidnative.env.AndroidFileOps
import app.droidnative.env.EnvPaths
import app.droidnative.env.Prefs
import app.droidnative.env.Proot
import app.droidnative.env.RootfsInstaller
import app.droidnative.env.TarExtractor
import app.droidnative.overlay.OverlayStore
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Presets from the DroidNativePresets repository: per package
 * (`<source>/<package>/`) a settings.ini with per-app settings by desktop
 * id, button layouts, helpers (copied to /etc/DroidNative/helpers/<source>/
 * <package>/) and preinstall.sh / postinstall.sh, run as the guest user.
 * The repository (Settings, Presets; a GitHub repository or a .tar.gz
 * address) is kept as an unpacked tarball in files/presets and re-checked
 * with its ETag when software is installed.
 *
 * Settings only fill in apps the user has not configured; nothing the
 * user chose is overwritten.
 */
object Presets {
    private const val TAG = "dn-presets"
    private val GITHUB = Regex("""^https?://github\.com/([^/]+)/([^/]+?)(?:\.git)?(?:/tree/([^/]+))?/?$""")
    // A check per install is cheap (304), but not for every package of a batch
    private const val RECHECK_MS = 10 * 60_000L
    private const val SCRIPT_TIMEOUT_MS = 10 * 60_000L
    private val PACKAGE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")

    enum class Source(val dir: String) { XBPS("xbps"), FLATPAK("flatpak"), APPIMAGE("appimage") }

    /** What [postinstall] did, for the caller to show. */
    data class Applied(val settings: List<String>, val layouts: Int, val scriptFailed: Boolean) {
        val any get() = settings.isNotEmpty() || layouts > 0
    }

    private fun cache(context: Context) = File(context.filesDir, "presets")

    /**
     * The archive behind a preset source: GitHub repositories (optionally
     * /tree/<branch>, else main) as their codeload tarball, anything else
     * as given (a .tar.gz address).
     */
    fun tarballUrl(source: String): String {
        val m = GITHUB.matchEntire(source.trim()) ?: return source.trim()
        val (owner, repo, branch) = m.destructured
        return "https://codeload.github.com/$owner/$repo/tar.gz/refs/heads/${branch.ifEmpty { "main" }}"
    }

    /**
     * Brings the cached repository up to date (conditional download);
     * offline the cache stays as it is. Blocking. Returns the error, or
     * null when the presets are current.
     */
    @Synchronized
    fun refresh(context: Context, force: Boolean = false): String? {
        val prefs = context.getSharedPreferences("presets", Context.MODE_PRIVATE)
        val dir = cache(context)
        val now = System.currentTimeMillis()
        val url = tarballUrl(Prefs(context).presetSource)
        // Another source: start over
        val sameSource = prefs.getString("url", null) == url
        if (!force && sameSource && dir.isDirectory && now - prefs.getLong("checked", 0L) < RECHECK_MS) return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val etag = prefs.getString("etag", null)
            if (etag != null && sameSource && dir.isDirectory) conn.setRequestProperty("If-None-Match", etag)
            try {
                when (conn.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> {}
                    HttpURLConnection.HTTP_OK -> {
                        val tmp = File(context.filesDir, "presets.new")
                        if (tmp.exists()) RootfsInstaller.deleteTree(tmp, context.filesDir)
                        conn.inputStream.use { TarExtractor(AndroidFileOps).extract(GZIPInputStream(it), tmp) }
                        // Archives usually hold one top directory (<repo>-<branch>)
                        val top = tmp.listFiles()?.singleOrNull()?.takeIf { it.isDirectory } ?: tmp
                        if (Source.entries.none { File(top, it.dir).isDirectory })
                            throw IOException("no xbps, flatpak or appimage folder in the archive")
                        if (dir.exists()) RootfsInstaller.deleteTree(dir, context.filesDir)
                        if (!top.renameTo(dir)) throw IOException("cannot move presets into place")
                        if (tmp.exists()) RootfsInstaller.deleteTree(tmp, context.filesDir)
                        prefs.edit().putString("etag", conn.getHeaderField("ETag")).putString("url", url).apply()
                        Log.i(TAG, "presets updated from $url")
                    }
                    else -> throw IOException("HTTP ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
            prefs.edit().putLong("checked", now).apply()
            null
        } catch (e: Exception) {
            Log.w(TAG, "presets not updated from $url: ${e.message}")
            e.message ?: e.toString()
        }
    }

    /** The preset directory of [pkg], or null when there is none. */
    fun find(context: Context, source: Source, pkg: String): File? {
        if (!PACKAGE_NAME.matches(pkg)) return null
        return File(cache(context), "${source.dir}/$pkg").takeIf { it.isDirectory }
    }

    /** Runs the preset's preinstall.sh, if any; false when it failed. */
    fun preinstall(context: Context, source: Source, pkg: String): Boolean {
        val dir = find(context, source, pkg) ?: return true
        return runScript(context, dir, "preinstall.sh", source, pkg)
    }

    /**
     * Applies the preset of [pkg] after its installation: helpers,
     * settings and layouts for unconfigured apps, then postinstall.sh.
     * [desktopId] maps a settings section to the installed desktop id
     * (AppImages get their own ids); null when nothing is there.
     */
    fun postinstall(context: Context, source: Source, pkg: String,
                    desktopId: (String) -> String = { it }): Applied? {
        val dir = find(context, source, pkg) ?: return null
        installHelpers(context, dir, source, pkg)
        val settings = ArrayList<String>()
        var layouts = 0
        File(dir, "settings.ini").takeIf { it.isFile }?.let { ini ->
            val store = AppSettingsStore(context)
            for ((section, keys) in parseIni(ini.readText())) {
                val id = desktopId(section)
                if (store.has(id)) continue
                var s = toSettings(keys)
                keys["layout"]?.let { name ->
                    val file = File(dir, name).takeIf { it.isFile && it.canonicalPath.startsWith(dir.canonicalPath + "/") }
                    if (file != null) runCatching {
                        val layout = file.inputStream().use { OverlayStore(context).import(it) }
                        s = s.copy(overlay = layout.id)
                        layouts++
                    }.onFailure { Log.w(TAG, "layout $name of $pkg: ${it.message}") }
                }
                store.put(id, s)
                settings += id
            }
        }
        val ok = runScript(context, dir, "postinstall.sh", source, pkg)
        Log.i(TAG, "${source.dir}/$pkg applied: settings for $settings, $layouts layouts, script ok: $ok")
        return Applied(settings, layouts, !ok)
    }

    private fun installHelpers(context: Context, dir: File, source: Source, pkg: String) {
        val helpers = File(dir, "helpers").takeIf { it.isDirectory } ?: return
        val etc = File(EnvPaths(context).rootfs, "etc/DroidNative/helpers/${source.dir}")
        val target = File(etc, pkg)
        if (target.exists()) RootfsInstaller.deleteTree(target, EnvPaths(context).rootfs)
        helpers.walkTopDown().forEach { f ->
            val out = File(target, f.relativeTo(helpers).path)
            if (f.isDirectory) out.mkdirs()
            else {
                out.parentFile?.mkdirs()
                f.copyTo(out, overwrite = true)
                if (f.canExecute()) out.setExecutable(true, false)
            }
        }
    }

    // Scripts see their preset folder at /run/droidnative-preset
    private fun runScript(context: Context, dir: File, name: String, source: Source, pkg: String): Boolean {
        val script = File(dir, name).takeIf { it.isFile } ?: return true
        val paths = EnvPaths(context)
        val log = File(paths.logDir, "preset-${source.dir}-$pkg.log")
        log.parentFile?.mkdirs()
        val r = Proot(paths).run(listOf("/bin/sh", "$GUEST_DIR/${script.name}"),
            extraEnv = mapOf(
                "DN_PRESET_DIR" to GUEST_DIR,
                "DN_HELPERS" to "/etc/DroidNative/helpers/${source.dir}/$pkg",
                "DN_PACKAGE" to pkg,
            ),
            timeoutMs = SCRIPT_TIMEOUT_MS, extraBinds = listOf(dir.path to GUEST_DIR))
        log.appendText("== $name (exit ${r.exitCode})\n${r.output}")
        if (!r.ok) Log.w(TAG, "$name of ${source.dir}/$pkg failed: ${r.output.takeLast(300)}")
        return r.ok
    }

    private const val GUEST_DIR = "/run/droidnative-preset"

    /** Sections of an ini file with their keys; ';' and '#' start comments. */
    fun parseIni(text: String): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
        var current: LinkedHashMap<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore(';').substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                current = out.getOrPut(line.substring(1, line.length - 1).trim()) { LinkedHashMap() }
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0 || current == null) continue
            current[line.substring(0, eq).trim().lowercase()] = line.substring(eq + 1).trim()
        }
        return out
    }

    /** settings.ini keys as [AppSettings]; unknown values are left at the default. */
    fun toSettings(k: Map<String, String>): AppSettings {
        fun bool(v: String?) = when (v?.lowercase()) { "true", "yes", "1" -> true; "false", "no", "0" -> false; else -> null }
        return AppSettings(
            backend = k["backend"]?.let { v -> GlBackend.entries.firstOrNull { it.arg == v.lowercase() } }
                ?.takeIf { it != GlBackend.AUTO },
            resolution = k["resolution"]?.takeIf { it.isNotEmpty() },
            dpi = k["dpi"]?.toIntOrNull()?.takeIf { it in 48..480 },
            mouseMode = k["input"]?.let { v -> MouseMode.entries.firstOrNull { it.name.equals(v, true) } },
            rotation = k["rotation"]?.let { v -> Rotation.entries.firstOrNull { it.name.equals(v, true) } },
            autoKeyboard = bool(k["auto-keyboard"]) ?: true,
            children = k["children"]?.let { v -> ChildPolicy.entries.firstOrNull { it.name.equals(v, true) } }
                ?: ChildPolicy.ALL,
        )
    }

    /**
     * Applies the presets of everything installed (xbps packages, Flatpak
     * apps, AppImages) again, for Settings: settings still only for
     * unconfigured apps, scripts run again. Returns the packages with a
     * preset. Blocking.
     */
    fun applyInstalled(context: Context): List<String> {
        refresh(context, force = true)
        val done = ArrayList<String>()
        val rootfs = EnvPaths(context).rootfs
        val xbps = Proot(EnvPaths(context)).run(listOf("xbps-query", "-l"), timeoutMs = 60_000)
        xbps.output.lineSequence().mapNotNull { it.split(' ').getOrNull(1)?.substringBeforeLast('-') }
            .filter { find(context, Source.XBPS, it) != null }
            .forEach { if (postinstall(context, Source.XBPS, it) != null) done += it }
        File(rootfs, "home/user/.local/share/flatpak/app").listFiles()?.map { it.name }
            ?.filter { find(context, Source.FLATPAK, it) != null }
            ?.forEach { if (postinstall(context, Source.FLATPAK, it) != null) done += it }
        File(rootfs, "home/user/.local/share/droidnative/appimages").listFiles()
            ?.filter { !it.name.startsWith(".") }?.forEach { appDir ->
                val own = appDir.listFiles { f -> f.name.endsWith(".desktop") }?.firstOrNull() ?: return@forEach
                val name = own.name.removeSuffix(".desktop")
                if (postinstall(context, Source.APPIMAGE, name) { "dn-appimage-${appDir.name}" } != null) done += name
            }
        LinuxApps.invalidate()
        return done
    }

    /* xbps: packages also come from a terminal, outside the app's screens */

    /** [checkXbps] on its own thread; tells the user what was applied. */
    fun checkXbpsInBackground(context: Context) {
        val app = context.applicationContext
        thread(name = "dn-presets") {
            val applied = try { checkXbps(app) } catch (e: Exception) { Log.w(TAG, "xbps check", e); emptyList() }
            if (applied.isNotEmpty()) notifyApplied(app, applied)
        }
    }

    private fun notifyApplied(context: Context, packages: List<String>) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(
            CHANNEL, context.getString(R.string.channel_presets), NotificationManager.IMPORTANCE_LOW))
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
                      else @Suppress("DEPRECATION") Notification.Builder(context)
        nm.notify(NOTIFICATION_ID, builder
            .setSmallIcon(R.drawable.ic_penguin)
            .setContentTitle(context.getString(R.string.presets_applied_title))
            .setContentText(context.getString(R.string.presets_applied_text, packages.joinToString(", ")))
            .setAutoCancel(true)
            .build())
    }

    private const val CHANNEL = "presets"
    private const val NOTIFICATION_ID = 0x4000

    /**
     * Applies presets of xbps packages installed since the last check (the
     * first check: all installed ones). Blocking.
     */
    @Synchronized
    fun checkXbps(context: Context): List<String> {
        val paths = EnvPaths(context)
        if (!File(paths.rootfs, "usr/bin/xbps-query").exists()) return emptyList()
        val r = Proot(paths).run(listOf("xbps-query", "-l"), timeoutMs = 60_000)
        if (!r.ok) return emptyList()
        // "ii luanti-5.17.0_1 description": the name is the pkgver without -version_revision
        val now = r.output.lineSequence().mapNotNull { line ->
            line.split(' ').getOrNull(1)?.substringBeforeLast('-')?.takeIf { it.isNotEmpty() }
        }.toSet()
        val prefs = context.getSharedPreferences("presets", Context.MODE_PRIVATE)
        // The first check covers everything installed so far
        val known = prefs.getStringSet("xbps", null) ?: emptySet()
        prefs.edit().putStringSet("xbps", now).apply()
        val added = now - known
        if (added.isEmpty()) return emptyList()
        refresh(context)
        return added.filter { postinstall(context, Source.XBPS, it)?.any == true }
            .also { if (it.isNotEmpty()) LinuxApps.invalidate() }
    }
}
