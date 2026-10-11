package app.droidnative.env

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Android storage as seen by the guest:
 *   /media/sdcard         the phone's shared storage (/storage/emulated/0)
 *   /media/<volume id>    every other volume, e.g. an SD card (/storage/1A2B-3C4D)
 * Only when the user enabled it in the settings and Android granted access.
 */
object StorageMounts {
    const val GUEST_BASE = "/media"
    const val GUEST_PRIMARY = "$GUEST_BASE/sdcard"

    data class Mount(val host: File, val guest: String)

    /** Whether Android lets the app read and write all of shared storage. */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** Volumes that exist right now, whether or not access is enabled. */
    fun volumes(context: Context): List<Mount> {
        val primary = Environment.getExternalStorageDirectory()
        val out = ArrayList<Mount>()
        out += Mount(primary, GUEST_PRIMARY)
        // The app-specific dirs reveal the other volumes without having to
        // list /storage: /storage/<id>/Android/data/<package>/files
        for (dir in context.getExternalFilesDirs(null)) {
            val path = dir?.path ?: continue
            val cut = path.indexOf("/Android/")
            if (cut <= 0) continue
            val root = File(path.substring(0, cut))
            if (root == primary || out.any { it.host == root }) continue
            val id = root.name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            if (id.isEmpty() || id == "sdcard") continue
            out += Mount(root, "$GUEST_BASE/$id")
        }
        return out
    }

    /** What to bind into the guest now. */
    fun active(context: Context): List<Mount> =
        if (Prefs(context).storageAccess && hasAccess(context))
            volumes(context).filter { it.host.canRead() }
        else emptyList()
}
