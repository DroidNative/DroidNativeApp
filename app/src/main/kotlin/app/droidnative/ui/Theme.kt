package app.droidnative.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droidnative.env.Prefs
import app.droidnative.env.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

/** Theme choice shared by all screens, so a change applies at once. */
object AppTheme {
    private val mode = MutableStateFlow<ThemeMode?>(null)

    fun mode(context: Context): MutableStateFlow<ThemeMode?> {
        if (mode.value == null) mode.value = Prefs(context).themeMode
        return mode
    }

    fun set(context: Context, value: ThemeMode) {
        Prefs(context).themeMode = value
        mode.value = value
    }
}

/**
 * Material 3 with the system's dynamic colours where available (API 31+);
 * light or dark as the system says unless forced in the settings.
 */
@Composable
fun DroidNativeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val mode by AppTheme.mode(context).collectAsStateWithLifecycle()
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val colours = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colours, content = content)
}
