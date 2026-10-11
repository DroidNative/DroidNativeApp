package app.droidnative.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.droidnative.R
import app.droidnative.overlay.OverlayEditorActivity
import app.droidnative.overlay.OverlayLayout
import app.droidnative.overlay.OverlayStore
import app.droidnative.overlay.OverlayXml

/**
 * Buttons tab: on-screen button layouts. Create, edit, rename, delete,
 * export and import (XML files). Apps pick a layout in their per-app
 * settings.
 */
@Composable
fun ButtonsScreen() {
    val context = LocalContext.current
    val store = remember { OverlayStore(context) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose {}
    }
    val layouts = remember(refresh) { store.list() }
    var renaming by remember { mutableStateOf<OverlayLayout?>(null) }
    var deleting by remember { mutableStateOf<OverlayLayout?>(null) }
    var exporting by remember { mutableStateOf<OverlayLayout?>(null) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri ->
        val l = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { OverlayXml.write(l, it) } != null
        }.getOrDefault(false)
        Toast.makeText(context, if (ok) R.string.buttons_exported else R.string.buttons_export_failed,
            Toast.LENGTH_SHORT).show()
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val l = context.contentResolver.openInputStream(uri)?.use { store.import(it) }
            if (l != null) Toast.makeText(context, context.getString(R.string.buttons_imported, l.name),
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.buttons_import_failed, e.message ?: ""),
                Toast.LENGTH_LONG).show()
        }
        refresh++
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.buttons_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    val l = OverlayLayout(name = context.getString(R.string.buttons_new_name, layouts.size + 1))
                    store.save(l)
                    context.startActivity(OverlayEditorActivity.intent(context, l.id))
                }) { Text(stringResource(R.string.buttons_new)) }
                OutlinedButton({ import.launch(arrayOf("text/xml", "application/xml", "*/*")) }) {
                    Text(stringResource(R.string.buttons_import))
                }
            }
        }
        if (layouts.isEmpty()) item {
            Text(stringResource(R.string.buttons_empty), Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(layouts, key = { it.id }) { l ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(l.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.buttons_summary, l.buttons.count { !it.isJoystick },
                        l.buttons.count { it.isJoystick }, l.groups.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        TextButton({ context.startActivity(OverlayEditorActivity.intent(context, l.id)) }) {
                            Text(stringResource(R.string.buttons_edit))
                        }
                        TextButton({ exporting = l; export.launch(fileName(l)) }) {
                            Text(stringResource(R.string.buttons_export))
                        }
                        TextButton({ renaming = l }) { Text(stringResource(R.string.buttons_rename)) }
                        TextButton({ deleting = l }) {
                            Text(stringResource(R.string.buttons_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    renaming?.let { l ->
        var name by remember(l.id) { mutableStateOf(l.name) }
        AlertDialog(onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.buttons_rename)) },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true) },
            confirmButton = {
                TextButton({
                    store.save(l.copy(name = name.ifBlank { l.name }))
                    renaming = null
                    refresh++
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton({ renaming = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
    deleting?.let { l ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.buttons_delete_title, l.name)) },
            text = { Text(stringResource(R.string.buttons_delete_text)) },
            confirmButton = {
                TextButton({
                    store.delete(l.id)
                    deleting = null
                    refresh++
                }) { Text(stringResource(R.string.buttons_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}

private fun fileName(l: OverlayLayout) =
    l.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "buttons" } + ".xml"
