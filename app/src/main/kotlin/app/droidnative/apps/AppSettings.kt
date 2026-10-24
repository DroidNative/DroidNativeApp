package app.droidnative.apps

import android.content.Context
import org.json.JSONObject

/** OpenGL implementation for an application, see dn-gl in the guest. */
enum class GlBackend(val arg: String) { AUTO("auto"), ZINK("zink"), VIRGL("virgl"), GL4ES("gl4es"), LLVMPIPE("llvmpipe") }

/** How touches on a window reach the Linux application. */
enum class MouseMode {
    /** Touch events (wl_touch): for touch-aware programs. */
    TOUCH,
    /** Finger = mouse pointer: tap clicks, drag drags, long press right-clicks. */
    MOUSE,
    /** Screen as a touchpad with a cursor: move, tap to click, two fingers scroll. */
    TOUCHPAD,
}

enum class Rotation { AUTO, PORTRAIT, LANDSCAPE }

/** Which programs started by an application get its settings. */
enum class ChildPolicy {
    /** Every program the application starts. */
    ALL,
    /** Only programs that are not in the application list themselves. */
    UNLISTED,
    /** None: child programs get the defaults (or their own settings when listed). */
    NONE,
}

/**
 * Settings of one application; null fields fall back to the global
 * settings ([Prefs]). [resolution] uses [Resolution] syntax.
 */
data class AppSettings(
    val backend: GlBackend? = null,
    val resolution: String? = null,
    val dpi: Int? = null,
    val mouseMode: MouseMode? = null,
    val rotation: Rotation? = null,
    /** Open the on-screen keyboard when a text field becomes active. */
    val autoKeyboard: Boolean = true,
    /** Button layout shown over the app's windows (overlay/OverlayStore), null for none. */
    val overlay: String? = null,
    val children: ChildPolicy = ChildPolicy.ALL,
) {
    val isDefault get() = this == AppSettings()

    fun toJson(): String = JSONObject().apply {
        backend?.let { put("backend", it.name) }
        resolution?.let { put("resolution", it) }
        dpi?.let { put("dpi", it) }
        mouseMode?.let { put("mouse", it.name) }
        rotation?.let { put("rotation", it.name) }
        if (!autoKeyboard) put("autoKeyboard", false)
        overlay?.let { put("overlay", it) }
        put("children", children.name)
    }.toString()

    companion object {
        private inline fun <reified E : Enum<E>> JSONObject.enum(key: String): E? =
            optString(key, "").takeIf { it.isNotEmpty() }
                ?.let { v -> enumValues<E>().firstOrNull { it.name == v } }

        fun fromJson(text: String): AppSettings = try {
            val o = JSONObject(text)
            AppSettings(
                backend = o.enum<GlBackend>("backend"),
                resolution = o.optString("resolution", "").takeIf { it.isNotEmpty() },
                dpi = o.optInt("dpi", 0).takeIf { it > 0 },
                mouseMode = o.enum<MouseMode>("mouse"),
                rotation = o.enum<Rotation>("rotation"),
                autoKeyboard = o.optBoolean("autoKeyboard", true),
                overlay = o.optString("overlay", "").takeIf { it.isNotEmpty() },
                children = o.enum<ChildPolicy>("children") ?: ChildPolicy.ALL,
            )
        } catch (_: Exception) {
            AppSettings()
        }
    }
}

/** Per-application settings by desktop entry id. */
class AppSettingsStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun get(id: String): AppSettings = sp.getString(id, null)?.let { AppSettings.fromJson(it) } ?: AppSettings()

    fun has(id: String) = sp.contains(id)

    fun put(id: String, settings: AppSettings) {
        if (settings.isDefault) sp.edit().remove(id).apply()
        else sp.edit().putString(id, settings.toJson()).apply()
    }
}
