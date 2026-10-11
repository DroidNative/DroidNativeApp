package app.droidnative.apps

import android.content.Context
import app.droidnative.LaunchActivity
import app.droidnative.env.EnvPaths
import java.io.File

/**
 * OctoXBPS, the xbps front end behind "Install Native (xbps) Package":
 * a settings preset for a phone held upright, and its start.
 */
object OctoXbps {
    const val DESKTOP_ID = "octoxbps"
    private const val MARKER = "DroidNative_Preset"

    // QSettings keys of octoxbps (src/constants.h). Narrow columns and no
    // groups panel: the package list then fits a portrait window.
    private val PRESET = linkedMapOf(
        "Show_Groups_Panel" to "0",
        "Package_Icon_Column_Width" to "24",
        "Package_Name_Column_Width" to "220",
        "Package_Version_Column_Width" to "110",
        "Skip_Mirror_Check_At_Startup" to "1",
        "Instant_Search" to "true",
        "Terminal_Font_Point_Size" to "8",
        MARKER to "1",
    )

    /**
     * Writes the preset into ~/.config/octoxbps/octoxbps.conf unless one was
     * written before (the user's later changes stay).
     */
    fun applyPreset(context: Context) {
        val conf = File(EnvPaths(context).rootfs, "home/user/.config/octoxbps/octoxbps.conf")
        val old = if (conf.isFile) conf.readLines() else emptyList()
        if (old.any { it.startsWith("$MARKER=") }) return
        conf.parentFile?.mkdirs()
        val out = ArrayList<String>()
        var inGeneral = false
        var written = false
        for (line in old) {
            if (line.startsWith("[")) {
                if (inGeneral && !written) { PRESET.forEach { (k, v) -> out += "$k=$v" }; written = true }
                inGeneral = line == "[General]"
            }
            if (inGeneral && PRESET.keys.any { line.startsWith("$it=") }) continue
            out += line
        }
        if (!written) {
            if (!inGeneral) out += "[General]"
            PRESET.forEach { (k, v) -> out += "$k=$v" }
        }
        conf.writeText(out.joinToString("\n") + "\n")
    }

    /**
     * Starts OctoXBPS in portrait (unless the user chose another rotation),
     * always with emulated mouse clicks: its lists and buttons expect a mouse.
     */
    fun start(context: Context): Boolean {
        val apps = LinuxApps(context)
        val entry = apps.find(DESKTOP_ID) ?: return false
        val store = AppSettingsStore(context)
        store.putDefault(DESKTOP_ID, AppSettings(rotation = Rotation.PORTRAIT))
        store.put(DESKTOP_ID, store.get(DESKTOP_ID).copy(mouseMode = MouseMode.MOUSE))
        applyPreset(context)
        LaunchActivity.start(context, entry)
        return true
    }
}
