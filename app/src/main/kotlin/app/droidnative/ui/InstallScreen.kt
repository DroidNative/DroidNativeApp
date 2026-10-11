package app.droidnative.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import app.droidnative.apps.OctoXbps
import app.droidnative.env.EnvController
import app.droidnative.env.EnvController.State

/** Install tab: native packages through OctoXBPS. Flatpak and AppImage follow later. */
@Composable
fun InstallScreen() {
    val context = LocalContext.current.applicationContext
    val status by EnvController.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { EnvController.refresh(context) }
    val ready = status.state == State.STOPPED || status.state == State.RUNNING
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.install_native_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.install_native_summary), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({
                        if (!OctoXbps.start(context))
                            Toast.makeText(context, R.string.install_native_missing, Toast.LENGTH_LONG).show()
                    },
                    Modifier.fillMaxWidth(), enabled = ready) {
                    Icon(painterResource(R.drawable.ic_tab_install), contentDescription = null,
                        Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.install_native))
                }
                if (!ready) Text(stringResource(R.string.install_needs_env), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
