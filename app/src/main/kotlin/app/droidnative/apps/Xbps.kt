package app.droidnative.apps

import android.content.Context
import android.util.Log
import app.droidnative.R
import app.droidnative.env.EnvPaths
import app.droidnative.env.Proot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

/**
 * Native Void packages (xbps), for ui/XbpsActivity.kt: catalogue, search
 * by name, description or file (installed files, like OctoXBPS), details,
 * and changes (install, remove, sync, update) as background [Job]s with
 * progress parsed from xbps' output and the full output kept for the
 * terminal view. Package management runs as the emulated root. Installs
 * apply DroidNative presets ([Presets], source "xbps").
 */
object Xbps {
    private const val TAG = "dn-xbps"
    private const val MAX_LOG_LINES = 5000
    // xbps refuses a system update until it is updated itself
    private const val XBPS_FIRST = "xbps-install -u xbps"

    data class Pkg(val name: String, val version: String, val desc: String, val installed: Boolean,
                   val manual: Boolean = false)

    /** A file search hit: package and the matching path. */
    data class FileHit(val name: String, val version: String, val path: String)

    /** A pending update from a dry run: name, installed and new version. */
    data class Update(val name: String, val from: String, val to: String)

    /** A running or finished change; [fraction] < 0 while unknown. */
    data class Job(val title: String, val running: Boolean, val fraction: Float = -1f, val phase: String = "",
                   val error: String? = null, val note: String? = null)

    private val _job = MutableStateFlow<Job?>(null)
    val job: StateFlow<Job?> = _job.asStateFlow()

    /** Output of the current or last job, for the terminal view. */
    private val _output = MutableStateFlow<List<String>>(emptyList())
    val output: StateFlow<List<String>> = _output.asStateFlow()

    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    val busy get() = _job.value?.running == true

    fun dismiss() {
        if (_job.value?.running == false) _job.value = null
    }

    /** Packages DroidNative itself needs: not offered for removal. */
    fun isProtected(name: String) = name.startsWith("droidnative-") || name in PROTECTED

    private val PROTECTED = setOf("xbps", "base-system", "base-files", "glibc", "bash", "dash", "coreutils",
        "dbus", "pulseaudio", "flatpak", "bubblewrap", "xorg-server-xwayland", "mesa-turnip-kgsl", "gl4es",
        "ca-certificates", "sudo", "runit")

    /* Reading (as the user: queries need no root) */

    private fun query(context: Context, vararg args: String, timeoutMs: Long = 120_000): Proot.Result =
        Proot(EnvPaths(context)).run(listOf(*args), timeoutMs = timeoutMs, maxOutput = 8 shl 20)

    // "[*] name-1.0_1   description": installed marker, pkgver, text
    private val SEARCH_LINE = Regex("""^\[(.)]\s+(\S+)\s*(.*)$""")
    // "ii name-1.0_1   description"
    private val LIST_LINE = Regex("""^(\S\S)\s+(\S+)\s*(.*)$""")

    private fun splitPkgver(pkgver: String): Pair<String, String> {
        val dash = pkgver.lastIndexOf('-')
        return if (dash > 0) pkgver.substring(0, dash) to pkgver.substring(dash + 1) else pkgver to ""
    }

    /** Every package of the repositories plus installed ones, by name. */
    fun catalogue(context: Context): List<Pkg> {
        val byName = LinkedHashMap<String, Pkg>()
        val r = query(context, "xbps-query", "-Rs", "")
        for (line in r.output.lineSequence()) {
            val m = SEARCH_LINE.matchEntire(line.trim()) ?: continue
            val (name, version) = splitPkgver(m.groupValues[2])
            val pkg = Pkg(name, version, m.groupValues[3].trim(), m.groupValues[1] == "*")
            // The local DroidNative repository and Void's may both list it
            val old = byName[name]
            if (old == null || (pkg.installed && !old.installed)) byName[name] = pkg
        }
        for (p in installed(context)) if (p.name !in byName) byName[p.name] = p
        return byName.values.sortedBy { it.name }
    }

    /** Installed packages; [Pkg.manual]: installed on purpose, not as a dependency. */
    fun installed(context: Context): List<Pkg> {
        val manual = query(context, "xbps-query", "-m").output.lineSequence()
            .map { splitPkgver(it.trim()).first }.filter { it.isNotEmpty() }.toSet()
        return query(context, "xbps-query", "-l").output.lineSequence().mapNotNull { line ->
            val m = LIST_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val (name, version) = splitPkgver(m.groupValues[2])
            Pkg(name, version, m.groupValues[3].trim(), true, name in manual)
        }.sortedBy { it.name }.toList()
    }

    /** Installed packages owning a file whose path contains [term] (xbps-query -o). */
    fun searchFiles(context: Context, term: String): List<FileHit> {
        val t = term.trim().replace(Regex("[*?\\[\\]]"), "")
        if (t.length < 2) return emptyList()
        val r = query(context, "xbps-query", "-o", "*$t*", timeoutMs = 300_000)
        // "name-1.0_1: /usr/bin/x (regular file)"
        return r.output.lineSequence().mapNotNull { line ->
            val colon = line.indexOf(": ")
            if (colon <= 0) return@mapNotNull null
            val (name, version) = splitPkgver(line.substring(0, colon))
            FileHit(name, version, line.substring(colon + 2).substringBefore(" (").trim())
        }.toList()
    }

    /**
     * Properties of [name] (xbps-query [-R]): single values, and lists
     * (run_depends ...) as lines joined with newlines.
     */
    fun details(context: Context, name: String, installed: Boolean): Map<String, String> {
        val args = if (installed) arrayOf("xbps-query", name) else arrayOf("xbps-query", "-R", name)
        val out = LinkedHashMap<String, String>()
        var key: String? = null
        for (line in query(context, *args).output.lineSequence()) {
            if (line.startsWith("\t") || line.startsWith(" ")) {
                key?.let { k -> out[k] = (out[k].orEmpty() + "\n" + line.trim()).trim() }
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            key = line.substring(0, colon).trim()
            out[key] = line.substring(colon + 1).trim()
        }
        return out
    }

    /** Files of an installed package. */
    fun files(context: Context, name: String): List<String> =
        query(context, "xbps-query", "-f", name).output.lineSequence()
            .map { it.trim().substringBefore(" -> ") }.filter { it.startsWith("/") }.toList()

    /** Pending updates from a dry run against the last synced index. */
    fun updates(context: Context): List<Update> {
        val versions = installed(context).associate { it.name to it.version }
        // "name-2.0_1 update aarch64 https://... 1MB 300KB"
        return query(context, "xbps-install", "-un", timeoutMs = 300_000).output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2 || parts[1] != "update") return@mapNotNull null
            val (name, version) = splitPkgver(parts[0])
            Update(name, versions[name] ?: "", version)
        }.toList()
    }

    /* Changes (as the emulated root) */

    private fun start(context: Context, title: String, action: (Runner) -> String?) {
        if (busy) return
        _output.value = emptyList()
        _job.value = Job(title, running = true)
        thread(name = "xbps-job") {
            val result = try {
                Job(title, running = false, fraction = 1f, note = action(Runner(context, title)))
            } catch (e: Exception) {
                Log.w(TAG, "$title failed", e)
                Job(title, running = false, error = e.message ?: e.toString())
            }
            LinuxApps.invalidate()
            _job.value = result
            _changes.value++
        }
    }

    /** Runs xbps commands of one job, feeding the progress and the output. */
    private class Runner(val context: Context, val title: String) {
        fun run(vararg args: String, allowFailure: Boolean = false): Proot.Result {
            val progress = Progress()
            append("$ ${args.joinToString(" ")}")
            val r = Proot(EnvPaths(context)).run(listOf(*args), timeoutMs = 4 * 3600_000L, root = true,
                maxOutput = 1 shl 20, onLine = onLine@{ raw ->
                    // Download meters redraw with \r; keep the last state
                    val line = raw.substringAfterLast('\r').trimEnd()
                    if (line.isEmpty()) return@onLine
                    append(line)
                    progress.feed(line)
                    _job.value = _job.value?.copy(fraction = progress.fraction, phase = Progress.label(line))
                })
            if (!r.ok && !allowFailure) throw RuntimeException(errorOf(r))
            return r
        }

        private fun append(line: String) {
            val list = _output.value
            _output.value = if (list.size >= MAX_LOG_LINES) list.drop(list.size - MAX_LOG_LINES + 1) + line else list + line
        }
    }

    private fun errorOf(r: Proot.Result): String {
        if (r.timedOut) return "timed out"
        val lines = r.output.trim().lines().map { it.substringAfterLast('\r').trim() }.filter { it.isNotEmpty() }
        return lines.lastOrNull { it.startsWith("ERROR", true) || "error" in it.lowercase() || "failed" in it }
            ?: lines.takeLast(2).joinToString("\n").ifBlank { "xbps failed (exit ${r.exitCode})" }
    }

    /**
     * Turns xbps-install / xbps-remove output into a fraction: the
     * transaction summary ("2 packages will be installed:") gives the
     * package count, then each phase (download, unpack, configure, remove)
     * advances per package line.
     */
    private class Progress {
        private var total = 0
        private var phaseBase = 0f
        private var phaseSpan = 0f
        private var count = 0
        private var known = false

        val fraction get() = if (!known || total == 0) -1f else (phaseBase + phaseSpan * minOf(1f, count.toFloat() / total))

        fun feed(line: String) {
            SUMMARY.find(line)?.let { total += it.groupValues[1].toInt(); return }
            when {
                line.startsWith("[*] Downloading") -> phase(0f, 0.4f)
                line.startsWith("[*] Verifying") -> phase(0.4f, 0.1f)
                line.startsWith("[*] Collecting") -> phase(0.5f, 0.05f)
                line.startsWith("[*] Unpacking") -> phase(0.55f, 0.25f)
                line.startsWith("[*] Configuring") -> phase(0.8f, 0.2f)
                line.startsWith("[*] Removing") -> phase(0f, 1f)
                line.contains(".xbps: ") && !line.contains(".sig") -> count++
                line.contains(": verifying") || line.contains(": unpacking") || line.contains(": configuring") ||
                    line.contains("removed successfully") -> count++
            }
        }

        private fun phase(base: Float, span: Float) {
            known = true
            phaseBase = base
            phaseSpan = span
            count = 0
        }

        companion object {
            /** What the progress line under the bar says. */
            fun label(line: String) = if (line.startsWith("[*] ")) line.removePrefix("[*] ") else line.take(120)

            private val SUMMARY =
                Regex("""^(\d+) packages? will be (installed|updated|reinstalled|downgraded|removed|configured)""")
        }
    }

    /** Installs [names] with their dependencies; presets apply afterwards. */
    fun install(context: Context, names: List<String>, label: String) =
        start(context, context.getString(R.string.xbps_job_install, label)) { run ->
            Presets.refresh(context)
            for (n in names) if (!Presets.preinstall(context, Presets.Source.XBPS, n))
                throw RuntimeException(context.getString(R.string.xbps_preinstall_failed, n))
            installWithXbpsFirst(run, "xbps-install", "-y", *names.toTypedArray())
            presetNote(context)
        }

    /** Removes [name]; [recursive] also removes dependencies nothing else needs. */
    fun remove(context: Context, name: String, recursive: Boolean) =
        start(context, context.getString(R.string.xbps_job_remove, name)) { run ->
            if (recursive) run.run("xbps-remove", "-Ry", name) else run.run("xbps-remove", "-y", name)
            null
        }

    /** Downloads the repository indexes (xbps-install -S). */
    fun sync(context: Context) = start(context, context.getString(R.string.xbps_job_sync)) { run ->
        run.run("xbps-install", "-S")
        null
    }

    /** Syncs and updates every package, xbps itself first when it asks for that. */
    fun updateAll(context: Context) = start(context, context.getString(R.string.xbps_job_update_all)) { run ->
        installWithXbpsFirst(run, "xbps-install", "-Syu", "-y")
        presetNote(context)
    }

    fun removeOrphans(context: Context) = start(context, context.getString(R.string.xbps_job_orphans)) { run ->
        run.run("xbps-remove", "-oy")
        null
    }

    fun cleanCache(context: Context) = start(context, context.getString(R.string.xbps_job_cache)) { run ->
        run.run("xbps-remove", "-Oy")
        null
    }

    // xbps refuses transactions while a newer xbps is available: update it, then retry
    private fun installWithXbpsFirst(run: Runner, vararg args: String) {
        val r = run.run(*args, allowFailure = true)
        if (r.ok) return
        if (XBPS_FIRST !in r.output) throw RuntimeException(errorOf(r))
        run.run("xbps-install", "-Syu", "-y", "xbps")
        run.run(*args)
    }

    // Presets of every package that came with the transaction
    private fun presetNote(context: Context): String? {
        val applied = Presets.checkXbps(context)
        return if (applied.isEmpty()) null
               else context.getString(R.string.xbps_presets_applied, applied.joinToString(", "))
    }
}
