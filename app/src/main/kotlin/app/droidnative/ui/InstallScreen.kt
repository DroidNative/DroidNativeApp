package app.droidnative.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droidnative.R
import app.droidnative.env.EnvController
import app.droidnative.env.VoidArch
import app.droidnative.env.EnvController.State
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.droidnative.apps.Xbps
import app.droidnative.env.EnvPaths
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Install tab: native packages (XbpsActivity), AppImages (AppImagesActivity), Flatpaks (FlatpakActivity) and Steam. */
@Composable
fun InstallScreen() {
    val activity = LocalContext.current
    val context = activity.applicationContext
    val status by EnvController.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { EnvController.refresh(context) }
    val ready = status.state == State.STOPPED || status.state == State.RUNNING
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.install_native_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.install_native_summary), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ activity.startActivity(XbpsActivity.intent(activity)) },
                    Modifier.fillMaxWidth(), enabled = ready) {
                    Text(stringResource(R.string.install_native))
                }
                if (!ready) Text(stringResource(R.string.install_needs_env), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.install_appimage_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.install_appimage_summary), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ activity.startActivity(AppImagesActivity.intent(activity)) },
                    Modifier.fillMaxWidth(), enabled = ready) {
                    Text(stringResource(R.string.appimage_manage))
                }
                if (!ready) Text(stringResource(R.string.install_needs_env), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val supported = VoidArch.current().supportsFlatpak
                Text(stringResource(R.string.install_flatpak_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.install_flatpak_summary), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ activity.startActivity(FlatpakActivity.intent(activity)) },
                    Modifier.fillMaxWidth(), enabled = ready && supported) {
                    Text(stringResource(R.string.flatpak_manage))
                }
                if (!supported) Text(stringResource(R.string.flatpak_unsupported), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (!ready) Text(stringResource(R.string.install_needs_env), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SteamCard(ready)
    }
}

/**
 * Steam: the xbps package droidnative-steam (a launcher that downloads
 * Valve's client on first start and runs it through box64; aarch64 only).
 * Installs and removals run as Xbps jobs, like in XbpsActivity.
 */
@Composable
private fun SteamCard(ready: Boolean) {
    val activity = LocalContext.current
    val context = activity.applicationContext
    val job by Xbps.job.collectAsStateWithLifecycle()
    val changes by Xbps.changes.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf<Boolean?>(null) }
    var ownJob by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf(false) }
    val supported = VoidArch.current() == VoidArch.AARCH64
    LaunchedEffect(changes, ready) {
        installed = if (!ready) null else withContext(Dispatchers.IO) {
            File(EnvPaths(context).rootfs, "var/db/xbps/.$STEAM_PKG-files.plist").exists()
        }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.install_steam_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.install_steam_summary), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val busy = job?.running == true
            if (installed == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("droidnative://app/steam"))
                            .setPackage(activity.packageName))
                    }, Modifier.weight(1f), enabled = !busy) { Text(stringResource(R.string.install_steam_open)) }
                    OutlinedButton({
                        ownJob = true
                        Xbps.remove(context, STEAM_PKG, recursive = true)
                    }, enabled = !busy) { Text(stringResource(R.string.install_steam_remove)) }
                }
            } else {
                Button({
                    ownJob = true
                    Xbps.install(context, listOf(STEAM_PKG), "Steam")
                }, Modifier.fillMaxWidth(), enabled = ready && supported && installed == false && !busy) {
                    Text(stringResource(R.string.install_steam))
                }
            }
            job?.takeIf { ownJob }?.let { XbpsJobCard(it, Modifier) { terminal = true } }
            if (!supported) Text(stringResource(R.string.install_steam_unsupported), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (!ready) Text(stringResource(R.string.install_needs_env), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (terminal) XbpsTerminalView { terminal = false }
}

private const val STEAM_PKG = "droidnative-steam"
