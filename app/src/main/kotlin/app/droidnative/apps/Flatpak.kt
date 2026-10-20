package app.droidnative.apps

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Xml
import app.droidnative.R
import app.droidnative.env.EnvPaths
import app.droidnative.env.Proot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Flatpak apps of the guest's user installation (`flatpak --user`): the
 * catalogue from each remote's appstream data (read straight from the
 * rootfs), installed apps, updates and remotes. Changes run one at a time
 * in the background as [Job]s, through the guest's flatpak command; the
 * screen is ui/FlatpakActivity.kt. Installs apply DroidNative presets
 * ([Presets], source "flatpak").
 */
object Flatpak {
    private const val TAG = "dn-flatpak"
    const val FLATHUB_NAME = "flathub"
    const val FLATHUB_URL = "https://dl.flathub.org/repo/flathub.flatpakrepo"
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private const val APPSTREAM = "home/user/.local/share/flatpak/appstream"

    data class Remote(val name: String, val title: String, val url: String, val disabled: Boolean)

    data class Installed(val id: String, val name: String, val version: String, val branch: String,
                         val origin: String, val size: String)

    /** A catalogue entry from appstream. */
    data class App(
        val id: String, val name: String, val summary: String, val description: String,
        val developer: String, val license: String, val homepage: String,
        val icon: File?, val remote: String, val desktopId: String,
    ) {
        fun matches(query: String): Boolean {
            val q = query.trim()
            if (q.isEmpty()) return true
            return q.split(Regex("\\s+")).all { w ->
                name.contains(w, true) || summary.contains(w, true) || id.contains(w, true) || developer.contains(w, true)
            }
        }
    }

    /** A running or finished change. */
    data class Job(val title: String, val running: Boolean, val line: String = "",
                   val error: String? = null, val note: String? = null)

    private val _job = MutableStateFlow<Job?>(null)
    val job: StateFlow<Job?> = _job.asStateFlow()

    /** Bumped after every finished job, so screens re-read their lists. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    val busy get() = _job.value?.running == true

    fun dismiss() {
        if (_job.value?.running == false) _job.value = null
    }

    /* Reading */

    private fun flatpak(context: Context, vararg args: String, timeoutMs: Long = 120_000,
                        onLine: ((String) -> Unit)? = null): Proot.Result =
        Proot(EnvPaths(context)).run(listOf("flatpak") + args, timeoutMs = timeoutMs, onLine = onLine)

    private fun columns(r: Proot.Result): List<List<String>> =
        if (!r.ok) emptyList()
        else r.output.lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.toList()

    fun remotes(context: Context): List<Remote> =
        columns(flatpak(context, "remotes", "--user", "--columns=name,title,url,options")).map { c ->
            Remote(c[0], c.getOrElse(1) { "" }.ifEmpty { c[0] }, c.getOrElse(2) { "" },
                "disabled" in c.getOrElse(3) { "" })
        }

    fun installed(context: Context): List<Installed> =
        columns(flatpak(context, "list", "--user", "--app", "--columns=application,name,version,branch,origin,size"))
            .map { c -> Installed(c[0], c.getOrElse(1) { c[0] }, c.getOrElse(2) { "" }, c.getOrElse(3) { "" },
                c.getOrElse(4) { "" }, c.getOrElse(5) { "" }) }
            .sortedBy { it.name.lowercase() }

    /** Refs with an update on their remote (apps and runtimes); needs the network. */
    fun updates(context: Context): List<String> =
        columns(flatpak(context, "remote-ls", "--user", "--updates", "--columns=application", timeoutMs = 300_000))
            .map { it[0] }.filter { it.isNotEmpty() && it != "Application ID" }

    @Volatile private var catalogueCache: Pair<Long, List<App>>? = null

    /** Apps of all enabled remotes; cached until their appstream data changes. */
    fun catalogue(context: Context, remotes: List<Remote>): List<App> {
        val root = File(EnvPaths(context).rootfs, APPSTREAM)
        val arch = app.droidnative.env.VoidArch.current().voidName
        val files = remotes.filter { !it.disabled }.mapNotNull { r ->
            val dir = File(root, "${r.name}/$arch/active")
            val xml = File(dir, "appstream.xml.gz").takeIf { it.isFile } ?: File(dir, "appstream.xml").takeIf { it.isFile }
            xml?.let { Triple(r.name, dir, it) }
        }
        val stamp = files.sumOf { it.third.lastModified() xor it.third.length() } + files.size
        catalogueCache?.let { (s, list) -> if (s == stamp) return list }
        val all = LinkedHashMap<String, App>()
        for ((remote, dir, xml) in files) {
            try {
                xml.inputStream().use { raw ->
                    val input: InputStream = if (xml.name.endsWith(".gz")) GZIPInputStream(raw) else raw
                    parseAppstream(input, remote, dir).forEach { all.putIfAbsent(it.id, it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "appstream of $remote: ${e.message}")
            }
        }
        return all.values.sortedBy { it.name.lowercase() }.also { catalogueCache = stamp to it }
    }

    /** True when a remote has no catalogue yet (fresh remote: download it). */
    fun catalogueMissing(context: Context, remotes: List<Remote>): Boolean {
        val arch = app.droidnative.env.VoidArch.current().voidName
        return remotes.any { r -> !r.disabled && !File(EnvPaths(context).rootfs, "$APPSTREAM/${r.name}/$arch/active").exists() }
    }

    // Untranslated text only: elements with xml:lang are translations.
    // Depth: components 1, component 2, its fields 3.
    private fun parseAppstream(input: InputStream, remote: String, dir: File): List<App> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        val out = ArrayList<App>()
        var inApp = false
        var inDesc = false
        var inDeveloper = false
        val f = HashMap<String, String>()
        val desc = StringBuilder()
        var icon128 = ""
        var icon64 = ""
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val d = p.depth
                val lang = p.getAttributeValue(null, "xml:lang")
                val type = p.getAttributeValue(null, "type")
                when {
                    d == 2 && p.name == "component" -> {
                        inApp = type == "desktop-application" || type == "desktop"
                        inDesc = false; inDeveloper = false
                        f.clear(); desc.clear(); icon128 = ""; icon64 = ""
                    }
                    !inApp -> {}
                    d == 3 && p.name == "description" -> inDesc = lang == null
                    d == 3 && p.name == "developer" -> inDeveloper = true
                    inDesc && (p.name == "p" || p.name == "li") -> {
                        val text = allText(p).trim().replace(Regex("\\s+"), " ")
                        if (lang == null && text.isNotEmpty()) {
                            if (desc.isNotEmpty()) desc.append(if (p.name == "li") "\n" else "\n\n")
                            desc.append(if (p.name == "li") "- $text" else text)
                        }
                    }
                    inDeveloper && d == 4 && p.name == "name" && lang == null ->
                        f.putIfAbsent("developer", p.nextText().trim())
                    d == 3 && lang == null && p.name in TEXT_FIELDS -> f.putIfAbsent(p.name, p.nextText().trim())
                    d == 3 && p.name == "icon" && type == "cached" -> {
                        val h = p.getAttributeValue(null, "height")
                        val text = p.nextText().trim()
                        if (h == "128" && icon128.isEmpty()) icon128 = text
                        else if (h == "64" && icon64.isEmpty()) icon64 = text
                    }
                    d == 3 && p.name == "url" && type == "homepage" -> f.putIfAbsent("homepage", p.nextText().trim())
                    d == 3 && p.name == "launchable" && type == "desktop-id" ->
                        f.putIfAbsent("launchable", p.nextText().trim())
                }
            } else if (event == XmlPullParser.END_TAG && inApp) {
                when {
                    p.depth == 3 && p.name == "description" -> inDesc = false
                    p.depth == 3 && p.name == "developer" -> inDeveloper = false
                    p.depth == 2 && p.name == "component" -> {
                        f["id"]?.removeSuffix(".desktop")?.takeIf { it.isNotEmpty() }?.let { id ->
                            val icon = when {
                                icon128.isNotEmpty() -> File(dir, "icons/128x128/$icon128")
                                icon64.isNotEmpty() -> File(dir, "icons/64x64/$icon64")
                                else -> null
                            }
                            out += App(id, f["name"] ?: id, f["summary"] ?: "", desc.toString(),
                                f["developer"] ?: f["developer_name"] ?: "", f["project_license"] ?: "",
                                f["homepage"] ?: "", icon?.takeIf { it.isFile }, remote,
                                (f["launchable"] ?: "$id.desktop").removeSuffix(".desktop"))
                        }
                        inApp = false
                    }
                }
            }
            event = p.next()
        }
        return out
    }

    // Text of the current element including inline markup (<em>, <code>)
    private fun allText(p: XmlPullParser): String {
        val depth = p.depth
        val sb = StringBuilder()
        var e = p.next()
        while (e != XmlPullParser.END_DOCUMENT && !(e == XmlPullParser.END_TAG && p.depth == depth)) {
            if (e == XmlPullParser.TEXT) sb.append(p.text)
            e = p.next()
        }
        return sb.toString()
    }

    private val TEXT_FIELDS = setOf("id", "name", "summary", "project_license", "developer_name")

    /* Changes */

    private fun start(context: Context, title: String, action: (onLine: (String) -> Unit) -> String?) {
        if (busy) return
        _job.value = Job(title, running = true)
        thread(name = "flatpak-job") {
            val result = try {
                val note = action { line ->
                    val l = line.trim()
                    if (l.isNotEmpty()) _job.value = _job.value?.copy(line = l)
                }
                Job(title, running = false, note = note)
            } catch (e: Exception) {
                Log.w(TAG, "$title failed", e)
                Job(title, running = false, error = e.message ?: e.toString())
            }
            LinuxApps.invalidate()
            _job.value = result
            _changes.value++
        }
    }

    // Runs flatpak for a job; throws with flatpak's error message on failure
    private fun runOrThrow(context: Context, onLine: (String) -> Unit, vararg args: String) {
        val r = flatpak(context, *args, timeoutMs = 2 * 3600_000L, onLine = onLine)
        if (!r.ok) {
            val lines = r.output.trim().lines()
            throw RuntimeException(
                if (r.timedOut) "timed out"
                else lines.lastOrNull { it.startsWith("error:", true) }?.substringAfter(':')?.trim()
                    ?: lines.takeLast(3).joinToString("\n").ifBlank { "flatpak failed (exit ${r.exitCode})" })
        }
    }

    fun install(context: Context, app: App) = start(context, context.getString(R.string.flatpak_job_install, app.name)) { onLine ->
        Presets.refresh(context)
        if (!Presets.preinstall(context, Presets.Source.FLATPAK, app.id))
            throw RuntimeException(context.getString(R.string.flatpak_preinstall_failed))
        runOrThrow(context, onLine, "install", "--user", "-y", "--noninteractive", app.remote, app.id)
        val applied = Presets.postinstall(context, Presets.Source.FLATPAK, app.id)
        when {
            applied == null -> null
            applied.scriptFailed -> context.getString(R.string.flatpak_preset_script_failed)
            applied.any -> context.getString(R.string.flatpak_preset_applied)
            else -> null
        }
    }

    fun uninstall(context: Context, id: String, name: String, deleteData: Boolean) =
        start(context, context.getString(R.string.flatpak_job_uninstall, name)) { onLine ->
            runOrThrow(context, onLine, "uninstall", "--user", "-y", "--noninteractive", id)
            // flatpak's --delete-data also resets portal permissions over the
            // session bus, which only runs with the environment: delete here
            if (deleteData && validName(id)) {
                val rootfs = EnvPaths(context).rootfs
                val data = File(rootfs, "home/user/.var/app/$id")
                if (data.exists()) app.droidnative.env.RootfsInstaller.deleteTree(data, rootfs)
            }
            null
        }

    /** Updates [id], or everything (apps and runtimes) when null. */
    fun update(context: Context, id: String?, name: String?) =
        start(context, if (id == null) context.getString(R.string.flatpak_job_update_all)
                       else context.getString(R.string.flatpak_job_update, name ?: id)) { onLine ->
            val args = mutableListOf("update", "--user", "-y", "--noninteractive")
            if (id != null) args += id
            runOrThrow(context, onLine, *args.toTypedArray())
            null
        }

    fun removeUnused(context: Context) = start(context, context.getString(R.string.flatpak_job_unused)) { onLine ->
        runOrThrow(context, onLine, "uninstall", "--user", "--unused", "-y", "--noninteractive")
        null
    }

    fun refreshCatalogue(context: Context) = start(context, context.getString(R.string.flatpak_job_catalogue)) { onLine ->
        runOrThrow(context, onLine, "update", "--user", "--appstream")
        catalogueCache = null
        null
    }

    fun validName(name: String) = NAME.matches(name)

    /** Adds a remote; [url] is the repository or a .flatpakrepo file. */
    fun addRemote(context: Context, name: String, url: String) =
        start(context, context.getString(R.string.flatpak_job_add_remote, name)) { onLine ->
            require(validName(name)) { context.getString(R.string.flatpak_bad_name) }
            val args = mutableListOf("remote-add", "--user", "--if-not-exists")
            if (url.substringBefore('?').endsWith(".flatpakrepo")) args += "--from"
            args += listOf(name, url.trim())
            runOrThrow(context, onLine, *args.toTypedArray())
            // A new remote brings its catalogue
            runOrThrow(context, onLine, "update", "--user", "--appstream", name)
            catalogueCache = null
            null
        }

    /* .flatpakref (the "Install" buttons of Flathub and other sites) */

    /** An app or runtime and the repository it comes from. */
    data class Ref(val name: String, val branch: String, val url: String, val title: String,
                   val isRuntime: Boolean, val text: String)

    private const val REF_MAX = 64 * 1024

    /** Parses a .flatpakref file; null if it isn't a usable one. */
    fun parseRef(text: String): Ref? {
        val keys = HashMap<String, String>()
        var inGroup = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) { inGroup = line == "[Flatpak Ref]"; continue }
            if (!inGroup || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq > 0) keys[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        val name = keys["Name"] ?: return null
        val url = keys["Url"] ?: return null
        if (!validName(name) || !url.startsWith("https://")) return null
        return Ref(name, keys["Branch"] ?: "", url, keys["Title"]?.takeIf { it.isNotEmpty() } ?: name,
            keys["IsRuntime"].equals("true", true), text)
    }

    /**
     * Reads the .flatpakref behind [uri]: a flatpak+https:// link (what the
     * sites' buttons open), an https URL or a downloaded file.
     * Throws on errors.
     */
    fun readRef(context: Context, uri: Uri): Ref {
        val bytes = when (uri.scheme) {
            "flatpak+https", "https" -> {
                val url = URL("https:" + uri.toString().substringAfter(':'))
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                try {
                    if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
                    conn.inputStream.use { readCapped(it) }
                } finally { conn.disconnect() }
            }
            "content", "file" -> context.contentResolver.openInputStream(uri)?.use { readCapped(it) }
                ?: throw RuntimeException("cannot open $uri")
            else -> throw RuntimeException("unsupported link $uri")
        }
        return parseRef(String(bytes)) ?: throw RuntimeException("not a .flatpakref")
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > REF_MAX) throw RuntimeException("file too large")
        }
        return out.toByteArray()
    }

    /** The configured source that serves the repository of [ref], if any. */
    fun remoteFor(remotes: List<Remote>, ref: Ref): Remote? =
        remotes.firstOrNull { !it.disabled && it.url.trimEnd('/') == ref.url.trimEnd('/') }

    /** Installs [ref]; flatpak adds its source when there is none yet. */
    fun installRef(context: Context, ref: Ref) = start(context, context.getString(R.string.flatpak_job_install, ref.title)) { onLine ->
        Presets.refresh(context)
        if (!ref.isRuntime && !Presets.preinstall(context, Presets.Source.FLATPAK, ref.name))
            throw RuntimeException(context.getString(R.string.flatpak_preinstall_failed))
        val rootfs = EnvPaths(context).rootfs
        val file = File(rootfs, "tmp/dn-${ref.name}.flatpakref")
        file.parentFile?.mkdirs()
        file.writeText(ref.text)
        try {
            runOrThrow(context, onLine, "install", "--user", "-y", "--noninteractive", "--from", "/tmp/${file.name}")
        } finally {
            file.delete()
        }
        catalogueCache = null
        val applied = if (ref.isRuntime) null else Presets.postinstall(context, Presets.Source.FLATPAK, ref.name)
        when {
            applied == null -> null
            applied.scriptFailed -> context.getString(R.string.flatpak_preset_script_failed)
            applied.any -> context.getString(R.string.flatpak_preset_applied)
            else -> null
        }
    }

    fun removeRemote(context: Context, name: String) =
        start(context, context.getString(R.string.flatpak_job_remove_remote, name)) { onLine ->
            runOrThrow(context, onLine, "remote-delete", "--user", name)
            catalogueCache = null
            null
        }
}
