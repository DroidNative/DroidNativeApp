package app.droidnative.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import app.droidnative.apps.Shortcuts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.droidnative.apps.DesktopEntry
import app.droidnative.apps.LinuxApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droidnative.R
import app.droidnative.env.EnvController
import app.droidnative.env.EnvController.State
import kotlinx.coroutines.delay

/** Overview tab: container status and the installed applications. */
@Composable
fun OverviewScreen() {
    val context = LocalContext.current.applicationContext
    val status by EnvController.status.collectAsStateWithLifecycle()

    // The service has no change callbacks yet; poll while the tab is shown
    LaunchedEffect(Unit) {
        while (true) {
            EnvController.refresh(context)
            delay(1500)
        }
    }

    val installed = status.state == State.STOPPED || status.state == State.RUNNING
    val apps = remember { LinuxApps(context) }
    var entries by remember { mutableStateOf<List<AppItem>?>(null) }
    // Re-read when the environment was (re)installed or the tab comes back
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }
    LaunchedEffect(installed, resumed) {
        if (!installed) {
            entries = null
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val list = apps.list()
            entries = list.map { AppItem(it, apps.icon(it)?.asImageBitmap()) }
            // SVG-only icons need one rendering pass in the guest
            if (apps.convertIcons(list))
                entries = list.map { AppItem(it, apps.icon(it)?.asImageBitmap()) }
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    // Long-press menu of this application, null when closed
    var sheet by remember { mutableStateOf<AppItem?>(null) }
    var settingsFor by remember { mutableStateOf<DesktopEntry?>(null) }
    settingsFor?.let { AppSettingsDialog(it) { settingsFor = null } }
    sheet?.let { item -> AppSheet(item, onDismiss = { sheet = null }, onOpen = {
        sheet = null
        launch(context, apps, item.entry)
    }, onSettings = {
        sheet = null
        settingsFor = item.entry
    }) }
    // Collapsed menu sections, by category name
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { StatusCard(status, context) }
        val list = entries
        if (list != null) {
            item {
                OutlinedTextField(query, { query = it },
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                    placeholder = { Text(stringResource(R.string.apps_search)) },
                    singleLine = true,
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton({ query = "" }) {
                            Icon(painterResource(R.drawable.ic_clear), contentDescription = null)
                        }
                    })
            }
            val shown = list.filter { it.entry.matches(query) }
            if (list.isEmpty() || shown.isEmpty()) item {
                Text(stringResource(if (list.isEmpty()) R.string.apps_empty else R.string.apps_no_match),
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for ((category, group) in shown.groupBy { it.entry.category }.toSortedMap()) {
                // While searching, every match is shown
                val open = query.isNotBlank() || category.name !in collapsed
                item(key = "cat-${category.name}") {
                    CategoryHeader(stringResource(category.label), group.size, open) {
                        collapsed = if (category.name in collapsed) collapsed - category.name
                                    else collapsed + category.name
                    }
                }
                if (open) items(group, key = { it.entry.id }) { item ->
                    AppRow(item, onLongClick = { sheet = item }) { launch(context, apps, item.entry) }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(label: String, count: Int, open: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Text("$count  " + if (open) "\u2212" else "+",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private class AppItem(val entry: DesktopEntry, val icon: ImageBitmap?)

private fun launch(context: Context, apps: LinuxApps, entry: DesktopEntry) {
    try {
        apps.launch(entry)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.apps_start_failed, entry.name),
            Toast.LENGTH_LONG).show()
    }
}

/** Long-press menu: "Add to home screen" first, then per-app settings and "Open". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(item: AppItem, onDismiss: () -> Unit, onOpen: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppIcon(item, 48.dp)
            Column {
                Text(item.entry.name, style = MaterialTheme.typography.titleLarge)
                val sub = item.entry.genericName.ifEmpty { item.entry.comment }
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ListItem(
            headlineContent = { Text(stringResource(R.string.app_add_home)) },
            supportingContent = { Text(stringResource(R.string.app_add_home_summary)) },
            leadingContent = { Icon(painterResource(R.drawable.ic_add_home), contentDescription = null) },
            modifier = Modifier.clickable {
                val bitmap = LinuxApps(context).icon(item.entry)
                val ok = Shortcuts.pin(context, item.entry, bitmap)
                Toast.makeText(context,
                    if (ok) context.getString(R.string.app_add_home_requested, item.entry.name)
                    else context.getString(R.string.app_add_home_unsupported),
                    Toast.LENGTH_SHORT).show()
                onDismiss()
            })
        ListItem(
            headlineContent = { Text(stringResource(R.string.app_settings)) },
            supportingContent = { Text(stringResource(R.string.app_settings_summary)) },
            leadingContent = { Icon(painterResource(R.drawable.ic_tab_settings), contentDescription = null) },
            modifier = Modifier.clickable(onClick = onSettings))
        ListItem(
            headlineContent = { Text(stringResource(R.string.app_open)) },
            leadingContent = { Icon(painterResource(R.drawable.ic_open), contentDescription = null) },
            modifier = Modifier.clickable(onClick = onOpen))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AppIcon(item: AppItem, size: Dp) {
    if (item.icon != null) {
        Image(item.icon, contentDescription = null, Modifier.size(size))
    } else {
        // No usable icon: first letter on a tinted square
        Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer,
                RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text(item.entry.name.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(item: AppItem, onLongClick: () -> Unit, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(item, 40.dp)
        Text(item.entry.name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatusCard(status: EnvController.Status, context: Context) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.status_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (status.state == State.INSTALLING)
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(if (status.detail == EnvController.NEEDS_SETUP) R.string.status_needs_update
                                    else stateLabel(status.state)),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (status.state == State.ERROR) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface)
            }
            val detail = when {
                status.detail == EnvController.NEEDS_SETUP -> stringResource(R.string.status_needs_setup)
                status.detail.isNotEmpty() -> status.detail
                status.state == State.NOT_INSTALLED -> stringResource(R.string.status_install_hint)
                else -> ""
            }
            if (detail.isNotEmpty())
                Text(detail, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                when (status.state) {
                    State.NOT_INSTALLED -> Button({ EnvController.install(context) }) {
                        Text(stringResource(if (status.detail == EnvController.NEEDS_SETUP)
                            R.string.action_update else R.string.action_install))
                    }
                    State.ERROR -> Button({ EnvController.install(context) }) {
                        Text(stringResource(R.string.action_retry))
                    }
                    State.STOPPED -> Button({ EnvController.start(context) }) {
                        Text(stringResource(R.string.action_start))
                    }
                    State.RUNNING -> OutlinedButton({ EnvController.stop(context) }) {
                        Text(stringResource(R.string.action_stop))
                    }
                    State.INSTALLING -> {}
                }
            }
        }
    }
}

private fun stateLabel(state: State): Int = when (state) {
    State.NOT_INSTALLED -> R.string.status_not_installed
    State.INSTALLING -> R.string.status_installing
    State.STOPPED -> R.string.status_stopped
    State.RUNNING -> R.string.status_running
    State.ERROR -> R.string.status_error
}
