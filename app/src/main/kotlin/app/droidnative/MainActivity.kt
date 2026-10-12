package app.droidnative

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.droidnative.ui.ButtonsScreen
import app.droidnative.ui.DroidNativeTheme
import app.droidnative.ui.InstallScreen
import app.droidnative.ui.OverviewScreen
import app.droidnative.ui.SettingsScreen

/** Main window: Overview, Install and Settings tabs. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Window notifications (EnvService) and the service notice need it
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val notice = lastExitNotice()
        setContent {
            DroidNativeTheme {
                MainScreen()
                var shown by rememberSaveable { mutableStateOf(notice) }
                shown?.let {
                    AlertDialog(onDismissRequest = { shown = null },
                        title = { Text(stringResource(R.string.exit_title)) },
                        text = { Text(it) },
                        confirmButton = { TextButton({ shown = null }) { Text(stringResource(android.R.string.ok)) } })
                }
            }
        }
    }

    /**
     * Why Android ended DroidNative last time, if the user should know:
     * the low memory killer or a crash ends every Linux program with it,
     * silently. Told once per exit, and only for recent ones (API 30+).
     */
    private fun lastExitNotice(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val info = runCatching {
            getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 1)
        }.getOrNull()?.firstOrNull() ?: return null
        val prefs = getSharedPreferences("exit_notice", MODE_PRIVATE)
        if (info.timestamp <= prefs.getLong("seen", 0L)) return null
        prefs.edit().putLong("seen", info.timestamp).apply()
        if (System.currentTimeMillis() - info.timestamp > NOTICE_MAX_AGE_MS) return null
        return when (info.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> getString(R.string.exit_low_memory)
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> getString(R.string.exit_crash)
            else -> null
        }
    }

    companion object {
        private const val NOTICE_MAX_AGE_MS = 24 * 3600_000L
    }
}

private enum class Tab(val label: Int, val icon: Int) {
    OVERVIEW(R.string.tab_overview, R.drawable.ic_tab_overview),
    INSTALL(R.string.tab_install, R.drawable.ic_tab_install),
    BUTTONS(R.string.tab_buttons, R.drawable.ic_tab_buttons),
    SETTINGS(R.string.tab_settings, R.drawable.ic_tab_settings),
}

@Composable
private fun MainScreen() {
    var selected by rememberSaveable { mutableIntStateOf(Tab.OVERVIEW.ordinal) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (tab in Tab.entries) {
                    NavigationBarItem(
                        selected = selected == tab.ordinal,
                        onClick = { selected = tab.ordinal },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (Tab.entries[selected]) {
                Tab.OVERVIEW -> OverviewScreen()
                Tab.INSTALL -> InstallScreen()
                Tab.BUTTONS -> ButtonsScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}
