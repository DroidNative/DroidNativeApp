package app.droidnative.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droidnative.LaunchActivity
import app.droidnative.R
import app.droidnative.apps.LinuxApps
import app.droidnative.apps.Xbps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Manage Native (xbps) Packages" from the Install tab: search the Void
 * repositories by name, description or (installed) file, install and
 * remove packages, refresh the sources and update everything. Changes show
 * a progress bar; their full output opens as a terminal view. Backend:
 * apps/Xbps.kt.
 */
class XbpsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DroidNativeTheme { XbpsScreen(onBack = ::finish) } }
    }

    companion object {
        fun intent(context: Context) = Intent(context, XbpsActivity::class.java)
    }
}

private enum class SearchBy(val label: Int) { NAME(R.string.xbps_by_name), DESC(R.string.xbps_by_desc), FILE(R.string.xbps_by_file) }

private val LocalXbpsBusy = compositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XbpsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val job by Xbps.job.collectAsStateWithLifecycle()
    val changes by Xbps.changes.collectAsStateWithLifecycle()
    var catalogue by remember { mutableStateOf<List<Xbps.Pkg>?>(null) }
    var installed by remember { mutableStateOf<List<Xbps.Pkg>?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var details by remember { mutableStateOf<Xbps.Pkg?>(null) }
    var terminal by remember { mutableStateOf(false) }

    LaunchedEffect(changes) {
        withContext(Dispatchers.IO) {
            installed = Xbps.installed(context)
            catalogue = Xbps.catalogue(context)
        }
    }

    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text(stringResource(R.string.xbps_title)) },
                navigationIcon = {
                    IconButton(onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
                    }
                })
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(R.string.xbps_tab_browse, R.string.xbps_tab_installed, R.string.xbps_tab_updates)
                    .forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(stringResource(label)) }) }
            }
        }
    }) { padding ->
        CompositionLocalProvider(LocalXbpsBusy provides (job?.running == true)) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                job?.let { XbpsJobCard(it) { terminal = true } }
                val cat = catalogue
                val inst = installed
                if (cat == null || inst == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                when (tab) {
                    0 -> BrowseTab(cat) { details = it }
                    1 -> InstalledTab(inst) { details = it }
                    else -> UpdatesTab(changes)
                }
            }
            details?.let { p ->
                // The sheet follows installs and removals
                val current = installed?.firstOrNull { it.name == p.name } ?: p.copy(installed = false)
                PackageSheet(current) { details = null }
            }
        }
    }
    if (terminal) TerminalView { terminal = false }
}

@Composable
private fun XbpsJobCard(job: Xbps.Job, onOutput: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val failed = job.error != null
                    Text(when {
                        job.running -> job.title
                        failed -> stringResource(R.string.flatpak_job_failed, job.title)
                        else -> stringResource(R.string.flatpak_job_done, job.title)
                    }, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    (job.error ?: job.note)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!job.running) IconButton(Xbps::dismiss) {
                    Icon(painterResource(R.drawable.ic_clear), contentDescription = null)
                }
            }
            if (job.running) {
                if (job.fraction >= 0f) LinearProgressIndicator({ job.fraction }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (job.phase.isNotEmpty()) Text(job.phase, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onOutput) { Text(stringResource(R.string.xbps_output)) }
        }
    }
}

/** The job's complete output, following new lines like a terminal. */
@Composable
private fun TerminalView(onClose: () -> Unit) {
    val lines by Xbps.output.collectAsStateWithLifecycle()
    val job by Xbps.job.collectAsStateWithLifecycle()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(job?.title ?: "", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClose) { Text(stringResource(R.string.xbps_close)) }
                }
                val list = rememberLazyListState()
                LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
                LazyColumn(Modifier.fillMaxSize(), list) {
                    items(lines) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/* Browse */

@Composable
private fun BrowseTab(catalogue: List<Xbps.Pkg>, onOpen: (Xbps.Pkg) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var by by rememberSaveable { mutableStateOf(SearchBy.NAME) }
    var fileHits by remember { mutableStateOf<List<Xbps.FileHit>?>(null) }
    // File search runs xbps-query: wait until typing pauses
    LaunchedEffect(query, by) {
        fileHits = null
        if (by != SearchBy.FILE || query.trim().length < 2) return@LaunchedEffect
        delay(600)
        fileHits = withContext(Dispatchers.IO) { Xbps.searchFiles(context, query) }
    }
    val q = query.trim()
    val shown = remember(catalogue, q, by) {
        if (q.length < 2 || by == SearchBy.FILE) emptyList()
        else {
            val words = q.split(Regex("\\s+"))
            catalogue.filter { p ->
                val text = if (by == SearchBy.NAME) p.name else p.desc
                words.all { text.contains(it, ignoreCase = true) }
            }.sortedWith(compareBy({ !it.name.equals(q, true) }, { !it.name.startsWith(q, true) }, { it.name }))
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SearchField(query, R.string.xbps_search) { query = it } }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchBy.entries.forEach { mode ->
                    FilterChip(by == mode, { by = mode }, label = { Text(stringResource(mode.label)) })
                }
            }
        }
        when {
            q.length < 2 -> item {
                Text(if (by == SearchBy.FILE) stringResource(R.string.xbps_file_hint)
                     else stringResource(R.string.xbps_search_hint, catalogue.size),
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            by == SearchBy.FILE -> {
                val hits = fileHits
                if (hits == null) item {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.height(20.dp))
                        Text(stringResource(R.string.xbps_searching))
                    }
                } else if (hits.isEmpty()) item { NoMatch() }
                else items(hits, key = { it.name + it.path }) { h ->
                    PackageRow(h.name, h.version, h.path, installed = true) {
                        onOpen(catalogue.firstOrNull { it.name == h.name } ?: Xbps.Pkg(h.name, h.version, "", true))
                    }
                }
            }
            shown.isEmpty() -> item { NoMatch() }
            else -> items(shown, key = { it.name }) { p ->
                PackageRow(p.name, p.version, p.desc, p.installed) { onOpen(p) }
            }
        }
    }
}

@Composable
private fun NoMatch() {
    Text(stringResource(R.string.xbps_no_match), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PackageRow(name: String, version: String, line: String, installed: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Text(version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (installed) Text(stringResource(R.string.xbps_installed_label), Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/* Installed */

@Composable
private fun InstalledTab(installed: List<Xbps.Pkg>, onOpen: (Xbps.Pkg) -> Unit) {
    val context = LocalContext.current
    val busy = LocalXbpsBusy.current
    var filter by rememberSaveable { mutableStateOf("") }
    var manualOnly by rememberSaveable { mutableStateOf(true) }
    val shown = remember(installed, filter, manualOnly) {
        installed.filter { (!manualOnly || it.manual) && (filter.isBlank() || it.name.contains(filter.trim(), true) ||
            it.desc.contains(filter.trim(), true)) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SearchField(filter, R.string.xbps_filter) { filter = it } }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FilterChip(manualOnly, { manualOnly = !manualOnly }, label = { Text(stringResource(R.string.xbps_only_manual)) })
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton({ Xbps.removeOrphans(context) }, enabled = !busy) { Text(stringResource(R.string.xbps_remove_orphans)) }
                TextButton({ Xbps.cleanCache(context) }, enabled = !busy) { Text(stringResource(R.string.xbps_clean_cache)) }
            }
        }
        if (shown.isEmpty()) item { NoMatch() }
        items(shown, key = { it.name }) { p -> PackageRow(p.name, p.version, p.desc, installed = false) { onOpen(p) } }
    }
}

/* Updates */

@Composable
private fun UpdatesTab(changes: Int) {
    val context = LocalContext.current
    val busy = LocalXbpsBusy.current
    var updates by remember { mutableStateOf<List<Xbps.Update>?>(null) }
    // The dry run uses the last synced index: refresh after every job
    LaunchedEffect(changes) {
        updates = null
        updates = withContext(Dispatchers.IO) { Xbps.updates(context) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ Xbps.sync(context) }, enabled = !busy) { Text(stringResource(R.string.xbps_sync)) }
                    Button({ Xbps.updateAll(context) }, enabled = !busy) { Text(stringResource(R.string.xbps_update_all)) }
                }
                val u = updates
                Text(when {
                    u == null -> stringResource(R.string.xbps_checking)
                    u.isEmpty() -> stringResource(R.string.xbps_no_updates) + " " + stringResource(R.string.xbps_updates_hint)
                    else -> stringResource(R.string.xbps_updates_count, u.size)
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(updates.orEmpty(), key = { it.name }) { u ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(u.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("${u.from} -> ${u.to}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/* Details */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackageSheet(p: Xbps.Pkg, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val busy = LocalXbpsBusy.current
    val scope = rememberCoroutineScope()
    var props by remember(p.name, p.installed) { mutableStateOf<Map<String, String>?>(null) }
    var files by remember(p.name, p.installed) { mutableStateOf<List<String>?>(null) }
    var showFiles by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(p.name, p.installed) {
        withContext(Dispatchers.IO) {
            props = Xbps.details(context, p.name, p.installed)
            if (p.installed) files = Xbps.files(context, p.name)
        }
    }
    // Applications the package brings: its desktop entries
    val entries = remember(files) {
        val apps = LinuxApps(context)
        files.orEmpty().filter { it.startsWith("/usr/share/applications/") && it.endsWith(".desktop") }
            .mapNotNull { apps.find(it.substringAfterLast('/').removeSuffix(".desktop")) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleLarge)
            Text(props?.get("pkgver")?.substringAfterLast('-') ?: p.version, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            (props?.get("short_desc") ?: p.desc).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!p.installed) {
                    Button({ Xbps.install(context, listOf(p.name), p.name) }, enabled = !busy) {
                        Text(stringResource(R.string.xbps_install))
                    }
                } else {
                    entries.firstOrNull()?.let { e ->
                        Button({ LaunchActivity.start(context, e) }) { Text(stringResource(R.string.xbps_open)) }
                    }
                    TextButton({ confirmRemove = true }, enabled = !busy && !Xbps.isProtected(p.name)) {
                        Text(stringResource(R.string.xbps_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (p.installed && Xbps.isProtected(p.name)) Text(stringResource(R.string.xbps_protected),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            val pr = props
            if (pr == null) CircularProgressIndicator(Modifier.padding(8.dp))
            else {
                val facts = listOfNotNull(
                    pr["license"]?.let { stringResource(R.string.flatpak_licence, it) },
                    pr["maintainer"],
                    if (pr["installed_size"] != null || pr["filename-size"] != null)
                        stringResource(R.string.xbps_size, pr["installed_size"] ?: "-", pr["filename-size"] ?: "-") else null,
                    pr["repository"],
                )
                facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                pr["run_depends"]?.takeIf { it.isNotEmpty() }?.let { deps ->
                    Text(stringResource(R.string.xbps_depends), style = MaterialTheme.typography.titleSmall)
                    Text(deps.lines().joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
                pr["homepage"]?.takeIf { it.startsWith("http") }?.let { url ->
                    TextButton({ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) {
                        Text(stringResource(R.string.flatpak_homepage))
                    }
                }
            }
            files?.takeIf { it.isNotEmpty() }?.let { list ->
                TextButton({ showFiles = !showFiles }) { Text(stringResource(R.string.xbps_show_files) + " (${list.size})") }
                if (showFiles) Text(list.take(500).joinToString("\n"), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp).navigationBarsPadding())
        }
    }
    if (confirmRemove) {
        var recursive by remember { mutableStateOf(true) }
        AlertDialog(onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.xbps_remove_title, p.name)) },
            text = {
                Row(Modifier.clickable { recursive = !recursive }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(recursive, { recursive = it })
                    Text(stringResource(R.string.xbps_remove_deps))
                }
            },
            confirmButton = {
                TextButton({
                    confirmRemove = false
                    scope.launch { Xbps.remove(context, p.name, recursive) }
                }) { Text(stringResource(R.string.xbps_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
