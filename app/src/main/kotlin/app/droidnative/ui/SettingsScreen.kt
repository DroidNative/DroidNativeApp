package app.droidnative.ui

import android.Manifest
import android.annotation.SuppressLint
import android.os.PowerManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.droidnative.R
import app.droidnative.apps.MouseMode
import app.droidnative.apps.WindowSettings
import app.droidnative.env.Passthrough
import app.droidnative.env.ThemeMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droidnative.env.Prefs
import app.droidnative.env.Session
import app.droidnative.env.StorageMounts

/** Settings tab: device passthrough and storage access. */
@Composable
fun SettingsScreen() {
    // Permissions can change while the app is in the background (system
    // settings); bumping this on resume re-reads them.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle(R.string.settings_devices)
        Hint(R.string.settings_devices_hint)
        for (p in Passthrough.entries) PassthroughRow(p, resumed)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(R.string.settings_startup)
        StartupSetting(resumed)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(R.string.settings_appearance)
        AppearanceSetting()

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(R.string.settings_display)
        DisplaySettings()

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(R.string.settings_sound)
        SoundSettings()

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(R.string.settings_storage)
        StorageRow(resumed)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Hint(R.string.settings_restart_hint)
    }
}

@Composable
private fun PassthroughRow(p: Passthrough, resumed: Int) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var enabled by remember(resumed) { mutableStateOf(p.isActive(context)) }
    var denied by rememberSaveable(p.key) { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.any { it }
        denied = !granted
        prefs.setEnabled(p, granted)
        enabled = granted
    }

    val title = when (p) {
        Passthrough.MICROPHONE -> R.string.pass_microphone
        Passthrough.SENSORS -> R.string.pass_sensors
        Passthrough.LOCATION -> R.string.pass_location
        Passthrough.CAMERA -> R.string.pass_camera
    }
    val summary = when {
        denied && !enabled -> R.string.pass_state_denied
        p == Passthrough.SENSORS -> R.string.pass_sensors_summary
        enabled -> R.string.pass_state_on
        else -> R.string.pass_state_off
    }
    SwitchRow(stringResource(title), stringResource(summary), enabled) { on ->
        when {
            !on -> {
                prefs.setEnabled(p, false)
                enabled = false
                denied = false
            }
            p.hasPermission(context) -> {
                prefs.setEnabled(p, true)
                enabled = true
            }
            else -> request.launch(p.permissions.toTypedArray())
        }
    }
    // Android stops showing the dialog after repeated denials
    if (denied && !enabled) {
        TextButton({ openAppSettings(context) }, Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.pass_open_settings))
        }
    }
}

@Composable
private fun StorageRow(resumed: Int) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    // Set while the user is in the system screen granting access
    var pending by rememberSaveable { mutableStateOf(false) }
    var refused by rememberSaveable { mutableStateOf(false) }
    var enabled by remember(resumed) {
        val access = StorageMounts.hasAccess(context)
        if (pending) {
            pending = false
            refused = !access
            if (access) prefs.storageAccess = true
        }
        mutableStateOf(prefs.storageAccess && access)
    }

    val legacyRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        refused = !granted
        prefs.storageAccess = granted
        enabled = granted
    }

    val summary = when {
        enabled -> R.string.storage_access_on
        refused -> R.string.storage_access_needs_permission
        else -> R.string.storage_access_off
    }
    SwitchRow(stringResource(R.string.storage_access), stringResource(summary), enabled) { on ->
        when {
            !on -> {
                prefs.storageAccess = false
                enabled = false
                refused = false
            }
            StorageMounts.hasAccess(context) -> {
                prefs.storageAccess = true
                enabled = true
            }
            Build.VERSION.SDK_INT >= 30 -> {
                pending = true
                openAllFilesSettings(context)
            }
            else -> legacyRequest.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    if (enabled) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            for (mount in StorageMounts.volumes(context)) {
                Text("${mount.guest}/  <-  ${mount.host.path}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StartupSetting(resumed: Int) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var enabled by remember { mutableStateOf(prefs.startOnBoot) }
    val pm = context.getSystemService(PowerManager::class.java)
    // Re-read after returning from the system dialog
    val unrestricted = remember(resumed) { pm.isIgnoringBatteryOptimizations(context.packageName) }
    SwitchRow(stringResource(R.string.start_on_boot), stringResource(R.string.start_on_boot_summary), enabled) {
        enabled = it
        prefs.startOnBoot = it
    }
    if (enabled && !unrestricted) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.battery_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({
                @SuppressLint("BatteryLife")   // keeping the environment alive is the app's purpose
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.fromParts("package", context.packageName, null))
                try {
                    context.startActivity(i)
                } catch (e: ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }) { Text(stringResource(R.string.battery_allow)) }
        }
    }
    Hint(R.string.services_hint)
}

@Composable
private fun AppearanceSetting() {
    val context = LocalContext.current
    val mode by AppTheme.mode(context).collectAsStateWithLifecycle()
    Column(FormPadding) {
        Choice(stringResource(R.string.theme), mode ?: ThemeMode.SYSTEM, ThemeMode.entries.toList(), {
                stringResource(when (it) {
                    ThemeMode.SYSTEM -> R.string.theme_system
                    ThemeMode.LIGHT -> R.string.theme_light
                    ThemeMode.DARK -> R.string.theme_dark
                })
            }, { AppTheme.set(context, it) })
    }
}

@Composable
private fun DisplaySettings() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val dm = context.resources.displayMetrics
    val short = remember { WindowSettings.shortSide(context) }
    val long = remember {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(m)
        maxOf(m.widthPixels, m.heightPixels)
    }
    var dpi by remember { mutableStateOf(prefs.dpi) }
    var mouse by remember { mutableStateOf(prefs.mouseMode) }
    val defaultDpi = prefs.defaultDpi(dm.density)
    Column(FormPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ResolutionField(prefs.resolution, short, long, stringResource(R.string.resolution_native)) {
            prefs.resolution = it
        }
        DpiChoice(dpi, stringResource(R.string.dpi_default, defaultDpi)) {
            dpi = it
            prefs.dpi = it
        }
        Text(stringResource(R.string.dpi_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Choice(stringResource(R.string.mouse_mode), mouse, MouseMode.entries.toList(),
            { mouseModeLabel(it, "") }, {
                mouse = it
                prefs.mouseMode = it
            })
    }
}

@Composable
private fun SoundSettings() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var buffer by remember { mutableStateOf(prefs.audioBufferMs) }
    Column(FormPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Choice(stringResource(R.string.audio_buffer), buffer, Prefs.AUDIO_BUFFERS, {
                if (it == Prefs.DEFAULT_AUDIO_BUFFER) stringResource(R.string.audio_buffer_default, it)
                else stringResource(R.string.audio_buffer_ms, it)
            }, {
                buffer = it
                prefs.audioBufferMs = it
                Session.audio?.setBuffer(it)
            })
        Text(stringResource(R.string.audio_buffer_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean,
                      onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The row handles the click
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(stringResource(text), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: Int) {
    Text(stringResource(text), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)))
}

/** System screen for MANAGE_EXTERNAL_STORAGE (API 30+). */
private fun openAllFilesSettings(context: Context) {
    if (Build.VERSION.SDK_INT < 30) return
    try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null)))
    } catch (e: ActivityNotFoundException) {
        // Some ROMs only have the list of all apps
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}
