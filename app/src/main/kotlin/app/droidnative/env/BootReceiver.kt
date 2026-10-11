package app.droidnative.env

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the environment after boot when enabled in [Prefs.startOnBoot]. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // App storage is credential-encrypted, so LOCKED_BOOT_COMPLETED is useless
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs(context).startOnBoot && EnvPaths(context).rootfsReady.isFile)
            EnvService.start(context)
    }
}
