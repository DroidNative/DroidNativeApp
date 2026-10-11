package app.droidnative.env

import android.content.Context
import app.droidnative.apps.MouseMode

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Persistent settings. */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("droidnative", Context.MODE_PRIVATE)

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", false)
        set(v) = sp.edit().putBoolean("start_on_boot", v).apply()

    /**
     * Rendering resolution in [app.droidnative.apps.Resolution] syntax
     * ("720" or "50%"), null for native. Windows render at this size and
     * Android scales them up.
     */
    var resolution: String?
        get() = sp.getString("resolution_spec", null)
            // Before percentages it was an Int of pixels
            ?: runCatching { sp.getInt("resolution", 0) }.getOrDefault(0).takeIf { it > 0 }?.toString()
        set(v) = sp.edit().putString("resolution_spec", v?.trim()?.ifEmpty { null }).remove("resolution").apply()

    /**
     * Linux UI density in dots per inch (96 = scale 1), null for the default
     * from the display density. Independent of the resolution.
     */
    var dpi: Int?
        get() = sp.getInt("dpi", 0).takeIf { it > 0 }
        set(v) = sp.edit().putInt("dpi", v ?: 0).apply()

    /** Default DPI: 96 per whole Android density step (2.75 -> 192). */
    fun defaultDpi(density: Float) = 96 * density.toInt().coerceAtLeast(1)

    /** Colour scheme of DroidNative's own screens. */
    var themeMode: ThemeMode
        get() = ThemeMode.entries.firstOrNull { it.name == sp.getString("theme", null) } ?: ThemeMode.SYSTEM
        set(v) = sp.edit().putString("theme", v.name).apply()

    var mouseMode: MouseMode
        get() = MouseMode.entries.firstOrNull { it.name == sp.getString("mouse_mode", null) } ?: MouseMode.MOUSE
        set(v) = sp.edit().putString("mouse_mode", v.name).apply()

    /**
     * Device passthrough switches. A switch only counts while its Android
     * permission is granted as well, see [Passthrough.isActive].
     */
    fun isEnabled(p: Passthrough): Boolean = sp.getBoolean(p.key, false)
    fun setEnabled(p: Passthrough, enabled: Boolean) = sp.edit().putBoolean(p.key, enabled).apply()

    /** Bind Android storage into the guest (needs the all-files permission). */
    var storageAccess: Boolean
        get() = sp.getBoolean("storage_access", false)
        set(v) = sp.edit().putBoolean("storage_access", v).apply()
}
