package app.droidnative.ui

import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.droidnative.R
import app.droidnative.apps.AppSettingsStore
import app.droidnative.apps.ChildPolicy
import app.droidnative.apps.DesktopEntry
import app.droidnative.apps.GlBackend
import app.droidnative.apps.MouseMode
import app.droidnative.apps.Rotation
import app.droidnative.apps.WindowSettings
import app.droidnative.env.Prefs

/**
 * Per-app settings of one application (long-press menu). Window settings
 * apply to windows opened afterwards, the others at the next start.
 */
@Composable
fun AppSettingsDialog(entry: DesktopEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AppSettingsStore(context) }
    val prefs = remember { Prefs(context) }
    var s by remember { mutableStateOf(store.get(entry.id)) }
    val (short, long) = remember {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(dm)
        minOf(dm.widthPixels, dm.heightPixels) to maxOf(dm.widthPixels, dm.heightPixels)
    }
    val global = stringResource(R.string.dpi_inherit)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_settings_title, entry.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Choice(stringResource(R.string.backend), s.backend, listOf<GlBackend?>(null) + GlBackend.entries.drop(1),
                    { if (it == null) global else backendLabel(it) }, { s = s.copy(backend = it) })
                // Key: a reset must rebuild the field with the stored text
                androidx.compose.runtime.key(s.resolution == null) {
                    ResolutionField(s.resolution, short, long, global) { s = s.copy(resolution = it) }
                }
                DpiChoice(s.dpi, global) { s = s.copy(dpi = it) }
                Choice(stringResource(R.string.mouse_mode), s.mouseMode, listOf<MouseMode?>(null) + MouseMode.entries,
                    { mouseModeLabel(it, "$global (${mouseModeLabel(prefs.mouseMode, "")})") },
                    { s = s.copy(mouseMode = it) })
                Choice(stringResource(R.string.rotation), s.rotation, listOf<Rotation?>(null) + Rotation.entries.drop(1),
                    { if (it == null) rotationLabel(null) else rotationLabel(it) }, { s = s.copy(rotation = it) })
                // Placeholder until on-screen buttons exist
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.button_overlays))
                        Text(stringResource(R.string.coming_later), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(false, null, enabled = false)
                }
                Text(stringResource(R.string.children_title), style = MaterialTheme.typography.titleSmall)
                for (p in ChildPolicy.entries) {
                    Row(Modifier.fillMaxWidth().selectable(s.children == p, role = Role.RadioButton) {
                            s = s.copy(children = p)
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.children == p, null)
                        Text(stringResource(when (p) {
                            ChildPolicy.ALL -> R.string.children_all
                            ChildPolicy.UNLISTED -> R.string.children_unlisted
                            ChildPolicy.NONE -> R.string.children_none
                        }), Modifier.padding(start = 8.dp))
                    }
                }
                Text(stringResource(R.string.app_settings_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton({
                store.put(entry.id, s)
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton({ s = app.droidnative.apps.AppSettings() }) { Text(stringResource(R.string.reset)) }
                TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}
