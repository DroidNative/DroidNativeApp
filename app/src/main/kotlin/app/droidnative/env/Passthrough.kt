package app.droidnative.env

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Phone hardware the user can hand to the Linux environment, with the
 * Android runtime permissions to request for it (empty: none required).
 */
enum class Passthrough(val key: String, val permissions: List<String>) {
    MICROPHONE("pass_microphone", listOf(Manifest.permission.RECORD_AUDIO)),
    // Accelerometer, gyroscope etc. need no runtime permission
    SENSORS("pass_sensors", emptyList()),
    // Android 12+ only shows the dialog when both are requested together;
    // the user may then grant approximate location only
    LOCATION("pass_location", listOf(Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION)),
    CAMERA("pass_camera", listOf(Manifest.permission.CAMERA));

    /** True when Android granted at least one of the permissions. */
    fun hasPermission(context: Context): Boolean =
        permissions.isEmpty() || permissions.any {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    /** Switched on by the user and still permitted by Android. */
    fun isActive(context: Context): Boolean =
        Prefs(context).isEnabled(this) && hasPermission(context)
}
