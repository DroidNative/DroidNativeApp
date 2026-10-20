package app.droidnative.ui

import app.droidnative.env.EnvPaths
import android.util.Log
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droidnative.LaunchActivity
import app.droidnative.R
import app.droidnative.apps.Flatpak
import app.droidnative.apps.LinuxApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Manage Flatpaks" from the Install tab: the catalogue of the configured
 * sources (search, install), installed apps (open, update, remove) and the
 * sources themselves. Backend: apps/Flatpak.kt.
 *
 * Also opens .flatpakref links and files (the "Install" buttons of Flathub
 * and other sites: flatpak+https://...), with that app's install sheet.
 */
class FlatpakActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Pending until handled, also across a rotation
        incoming.value = if (savedInstanceState == null) intent?.data
                         else savedInstanceState.getString(KEY_INCOMING)?.let(Uri::parse)
        if (!File(EnvPaths(this).rootfs, "usr/bin/flatpak").exists() && incoming.value != null) {
            Toast.makeText(this, R.string.flatpak_ref_no_env, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContent {
            DroidNativeTheme {
                FlatpakScreen(onBack = ::finish, incoming = incoming.value, onIncomingHandled = { incoming.value = null })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        incoming.value?.let { outState.putString(KEY_INCOMING, it.toString()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.value = intent.data
    }

    companion object {
        private const val KEY_INCOMING = "incoming"

        fun intent(context: Context) = Intent(context, FlatpakActivity::class.java)
    }
}

/** What the screens show, read in the background after every change. */
private class FlatpakState(
    val remotes: List<Flatpak.Remote>,
    val installed: List<Flatpak.Installed>,
    val catalogue: List<Flatpak.App>,
    val catalogueMissing: Boolean,
)

/** Detail sheet subject: a catalogue entry, an installed app, or both. */
private class Subject(val app: Flatpak.App?, val installed: Flatpak.Installed?) {
    val id get() = app?.id ?: installed!!.id
    val name get() = app?.name ?: installed!!.name
}

private val icons = LruCache<String, ImageBitmap>(200)

// A change is running: buttons that start another one are disabled
private val LocalBusy = compositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlatpakScreen(onBack: () -> Unit, incoming: Uri? = null, onIncomingHandled: () -> Unit = {}) {
    val context = LocalContext.current
    val job by Flatpak.job.collectAsStateWithLifecycle()
    val changes by Flatpak.changes.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<FlatpakState?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var subject by remember { mutableStateOf<Subject?>(null) }
    // null: not checked yet in this screen
    var updates by remember { mutableStateOf<Set<String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(changes) {
        state = withContext(Dispatchers.IO) {
            val remotes = Flatpak.remotes(context)
            FlatpakState(remotes, Flatpak.installed(context), Flatpak.catalogue(context, remotes),
                Flatpak.catalogueMissing(context, remotes))
        }
        // An update or install changes what is outdated
        if (updates != null && changes > 0) updates = null
    }
    // A source without a catalogue yet: fetch it once
    LaunchedEffect(state?.catalogueMissing) {
        if (state?.catalogueMissing == true && !Flatpak.busy) Flatpak.refreshCatalogue(context)
    }
    // A .flatpakref from a link or file: its install sheet, or a prompt when
    // no configured source has it
    var refPrompt by remember { mutableStateOf<Flatpak.Ref?>(null) }
    LaunchedEffect(incoming, state != null) {
        val uri = incoming ?: return@LaunchedEffect
        val s = state ?: return@LaunchedEffect
        val ref = withContext(Dispatchers.IO) {
            runCatching { Flatpak.readRef(context, uri) }
                .onFailure { Log.w("dn-flatpak", "cannot read $uri", it) }.getOrNull()
        }
        onIncomingHandled()
        if (ref == null) {
            Toast.makeText(context, R.string.flatpak_ref_bad, Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }
        val remote = Flatpak.remoteFor(s.remotes, ref)
        val app = s.catalogue.firstOrNull { it.id == ref.name && it.remote == remote?.name }
        val installed = s.installed.firstOrNull { it.id == ref.name }
        if (app != null || installed != null) subject = Subject(app, installed)
        else refPrompt = ref
    }

    fun checkUpdates() {
        checking = true
        scope.launch {
            updates = withContext(Dispatchers.IO) { Flatpak.updates(context).toSet() }
            checking = false
        }
    }

    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text(stringResource(R.string.install_flatpak_title)) },
                navigationIcon = {
                    IconButton(onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
                    }
                })
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(R.string.flatpak_tab_browse, R.string.flatpak_tab_installed, R.string.flatpak_tab_sources)
                    .forEachIndexed { i, label ->
                        Tab(tab == i, { tab = i }, text = { Text(stringResource(label)) })
                    }
            }
        }
    }) { padding ->
        CompositionLocalProvider(LocalBusy provides (job?.running == true)) {
        Column(Modifier.fillMaxSize().padding(padding)) {
            job?.let { JobCard(it) }
            val s = state
            if (s == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            val installedIds = s.installed.map { it.id }.toSet()
            when (tab) {
                0 -> BrowseTab(s, installedIds) { subject = Subject(it, s.installed.firstOrNull { i -> i.id == it.id }) }
                1 -> InstalledTab(s, updates, checking, ::checkUpdates) { i ->
                    subject = Subject(s.catalogue.firstOrNull { it.id == i.id }, i)
                }
                else -> SourcesTab(s)
            }
        }
        }
    }

    refPrompt?.let { ref ->
        AlertDialog(
            onDismissRequest = { refPrompt = null },
            title = { Text(stringResource(R.string.flatpak_ref_title, ref.title)) },
            text = { Text(stringResource(R.string.flatpak_ref_text, ref.name, ref.url)) },
            confirmButton = {
                TextButton({ Flatpak.installRef(context, ref); refPrompt = null }, enabled = job?.running != true) {
                    Text(stringResource(R.string.flatpak_install))
                }
            },
            dismissButton = { TextButton({ refPrompt = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    subject?.let { sub ->
        val installed = state?.installed?.firstOrNull { it.id == sub.id }
        CompositionLocalProvider(LocalBusy provides (job?.running == true)) {
            AppSheet(Subject(sub.app, installed), updates?.contains(sub.id) == true) { subject = null }
        }
    }
}

@Composable
private fun JobCard(job: Flatpak.Job) {
    OutlinedCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (job.running) {
                Text(job.title)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (job.line.isNotEmpty()) Text(job.line, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val failed = job.error != null
                        Text(stringResource(if (failed) R.string.flatpak_job_failed else R.string.flatpak_job_done, job.title),
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        (job.error ?: job.note)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(Flatpak::dismiss) { Icon(painterResource(R.drawable.ic_clear), contentDescription = null) }
                }
            }
        }
    }
}

/* Browse */

@Composable
private fun BrowseTab(s: FlatpakState, installed: Set<String>, onOpen: (Flatpak.App) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    if (s.remotes.isEmpty()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.flatpak_no_remotes))
            Button({ Flatpak.addRemote(context, Flatpak.FLATHUB_NAME, Flatpak.FLATHUB_URL) }, enabled = !LocalBusy.current) {
                Text(stringResource(R.string.flatpak_add_flathub))
            }
        }
        return
    }
    val shown = remember(s.catalogue, query) { s.catalogue.filter { it.matches(query) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            SearchField(query, R.string.flatpak_search) { query = it }
        }
        item {
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton({ Flatpak.refreshCatalogue(context) }, enabled = !LocalBusy.current) {
                    Text(stringResource(R.string.flatpak_refresh_catalogue))
                }
            }
        }
        if (s.catalogue.isEmpty()) item {
            Text(stringResource(if (s.catalogueMissing) R.string.flatpak_catalogue_empty else R.string.flatpak_no_match),
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (shown.isEmpty()) item {
            Text(stringResource(R.string.flatpak_no_match), Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(shown, key = { it.id }) { app ->
            AppRow(app.id, app.name, app.summary, app.icon,
                label = if (app.id in installed) stringResource(R.string.flatpak_installed_label) else null) { onOpen(app) }
        }
    }
}

@Composable
internal fun SearchField(query: String, placeholder: Int, onChange: (String) -> Unit) {
    OutlinedTextField(query, onChange,
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        placeholder = { Text(stringResource(placeholder)) },
        singleLine = true,
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton({ onChange("") }) {
                Icon(painterResource(R.drawable.ic_clear), contentDescription = null)
            }
        })
}

@Composable
private fun AppRow(id: String, name: String, line: String, icon: File?, label: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppIcon(id, name, icon, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        label?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// Catalogue icon, else the installed app's exported icon, else its initial
@Composable
private fun AppIcon(id: String, name: String, file: File?, size: Dp) {
    val context = LocalContext.current
    val bitmap by produceState(icons.get(id), id, file) {
        if (value == null) value = withContext(Dispatchers.IO) {
            (file?.let { BitmapFactory.decodeFile(it.path) } ?: LinuxApps(context).icon(id))
                ?.asImageBitmap()?.also { icons.put(id, it) }
        }
    }
    val b = bitmap
    if (b != null) Image(b, contentDescription = null, Modifier.size(size))
    else Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.titleMedium)
    }
}

/* Installed */

@Composable
private fun InstalledTab(s: FlatpakState, updates: Set<String>?, checking: Boolean, onCheck: () -> Unit,
                         onOpen: (Flatpak.Installed) -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onCheck, enabled = !checking) { Text(stringResource(R.string.flatpak_check_updates)) }
                    if (!updates.isNullOrEmpty()) Button({ Flatpak.update(context, null, null) }, enabled = !LocalBusy.current) {
                        Text(stringResource(R.string.flatpak_update_all))
                    }
                }
                when {
                    checking -> Text(stringResource(R.string.flatpak_checking), style = MaterialTheme.typography.bodySmall)
                    updates == null -> {}
                    updates.isEmpty() -> Text(stringResource(R.string.flatpak_no_updates), style = MaterialTheme.typography.bodySmall)
                    else -> Text(stringResource(R.string.flatpak_updates_count, updates.size),
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ Flatpak.removeUnused(context) }, enabled = !LocalBusy.current) {
                    Text(stringResource(R.string.flatpak_remove_unused))
                }
            }
        }
        if (s.installed.isEmpty()) item {
            Text(stringResource(R.string.flatpak_none_installed), Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(s.installed, key = { it.id }) { i ->
            val app = s.catalogue.firstOrNull { it.id == i.id }
            AppRow(i.id, i.name, listOf(i.version, i.origin, i.size).filter { it.isNotEmpty() }.joinToString("  "),
                app?.icon, label = if (updates?.contains(i.id) == true) stringResource(R.string.flatpak_update_label) else null) {
                onOpen(i)
            }
        }
    }
}

/* Sources */

@Composable
private fun SourcesTab(s: FlatpakState) {
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Flatpak.Remote?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ adding = true }, enabled = !LocalBusy.current) { Text(stringResource(R.string.flatpak_add_source)) }
                if (s.remotes.none { it.name == Flatpak.FLATHUB_NAME })
                    OutlinedButton({ Flatpak.addRemote(context, Flatpak.FLATHUB_NAME, Flatpak.FLATHUB_URL) },
                        enabled = !LocalBusy.current) { Text(stringResource(R.string.flatpak_add_flathub)) }
            }
        }
        items(s.remotes, key = { it.name }) { r ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.title + if (r.disabled) " (" + stringResource(R.string.flatpak_disabled) + ")" else "",
                        style = MaterialTheme.typography.bodyLarge)
                    Text("${r.name}  ${r.url}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ removing = r }, enabled = !LocalBusy.current) {
                    Icon(painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.flatpak_remove))
                }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        val valid = Flatpak.validName(name) && url.startsWith("http")
        AlertDialog(onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.flatpak_add_source)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.trim().take(40) }, singleLine = true,
                        label = { Text(stringResource(R.string.flatpak_source_name)) },
                        isError = name.isNotEmpty() && !Flatpak.validName(name),
                        supportingText = { if (name.isNotEmpty() && !Flatpak.validName(name))
                            Text(stringResource(R.string.flatpak_bad_name)) })
                    OutlinedTextField(url, { url = it.trim() }, singleLine = true,
                        label = { Text(stringResource(R.string.flatpak_source_url)) })
                }
            },
            confirmButton = {
                TextButton({ adding = false; Flatpak.addRemote(context, name, url) }, enabled = valid) {
                    Text(stringResource(R.string.flatpak_add))
                }
            },
            dismissButton = { TextButton({ adding = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
    removing?.let { r ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.flatpak_remove_source_title, r.title)) },
            text = { Text(stringResource(R.string.flatpak_remove_source_text)) },
            confirmButton = {
                TextButton({ removing = null; Flatpak.removeRemote(context, r.name) }) {
                    Text(stringResource(R.string.flatpak_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ removing = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}

/* Details */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(sub: Subject, hasUpdate: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = sub.app
    val installed = sub.installed
    var confirmRemove by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppIcon(sub.id, sub.name, app?.icon, 56.dp)
                Column {
                    Text(sub.name, style = MaterialTheme.typography.titleLarge)
                    val by = app?.developer?.takeIf { it.isNotEmpty() } ?: sub.id
                    Text(by, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            app?.summary?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed == null) {
                    Button({ app?.let { Flatpak.install(context, it) }; onDismiss() },
                        enabled = app != null && !Flatpak.busy) { Text(stringResource(R.string.flatpak_install)) }
                } else {
                    Button({
                        val entry = LinuxApps(context).let { it.find(app?.desktopId ?: sub.id) ?: it.find(sub.id) }
                        if (entry != null) LaunchActivity.start(context, entry)
                        else Toast.makeText(context, R.string.flatpak_open_missing, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.flatpak_open)) }
                    if (hasUpdate) OutlinedButton({ Flatpak.update(context, sub.id, sub.name); onDismiss() },
                        enabled = !LocalBusy.current) { Text(stringResource(R.string.flatpak_update)) }
                    TextButton({ confirmRemove = true }, enabled = !LocalBusy.current) {
                        Text(stringResource(R.string.flatpak_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            HorizontalDivider()
            app?.description?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val facts = listOfNotNull(
                (app?.remote ?: installed?.origin)?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.flatpak_from_source, it) },
                app?.license?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.flatpak_licence, it) },
                installed?.let { listOf(it.version, it.branch, it.size).filter { v -> v.isNotEmpty() }.joinToString("  ") }
                    ?.takeIf { it.isNotEmpty() },
                sub.id,
            )
            facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            app?.homepage?.takeIf { it.startsWith("http") }?.let { url ->
                TextButton({
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }) { Text(stringResource(R.string.flatpak_homepage)) }
            }
            Spacer(Modifier.height(24.dp).navigationBarsPadding())
        }
    }
    if (confirmRemove) {
        var deleteData by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.flatpak_remove_title, sub.name)) },
            text = {
                Column {
                    Text(stringResource(R.string.flatpak_remove_text))
                    Row(Modifier.clickable { deleteData = !deleteData }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteData, { deleteData = it })
                        Text(stringResource(R.string.flatpak_delete_data))
                    }
                }
            },
            confirmButton = {
                TextButton({
                    confirmRemove = false
                    Flatpak.uninstall(context, sub.id, sub.name, deleteData)
                    onDismiss()
                }) { Text(stringResource(R.string.flatpak_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
