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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droidnative.R
import app.droidnative.env.EnvController
import app.droidnative.env.VoidArch
import app.droidnative.env.EnvController.State

/** Install tab: native packages (XbpsActivity), AppImages (AppImagesActivity) and Flatpaks (FlatpakActivity). */
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
                    Icon(painterResource(R.drawable.ic_tab_install), contentDescription = null,
                        Modifier.padding(end = 8.dp))
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
    }
}
