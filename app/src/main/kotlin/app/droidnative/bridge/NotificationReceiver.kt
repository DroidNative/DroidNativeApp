package app.droidnative.bridge

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Buttons and dismissals of the guest's notifications ([LinuxNotifications],
 * [MediaPlayers]), sent back to dn-notifyd. Opens nothing: Android does not
 * let receivers start activities from notifications.
 */
class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, 0)
        when (intent.action) {
            ACTION_INVOKE -> {
                Bridge.send("action", "id" to id, "key" to (intent.getStringExtra(EXTRA_KEY) ?: ""))
                if (!intent.getBooleanExtra(EXTRA_RESIDENT, false)) {
                    context.getSystemService(NotificationManager::class.java).cancel(LinuxNotifications.BASE + id)
                    Bridge.send("closed", "id" to id)
                }
            }
            ACTION_DISMISS -> Bridge.send("closed", "id" to id)
            ACTION_MEDIA -> MediaPlayers.command(intent.getStringExtra(EXTRA_NAME) ?: "",
                intent.getStringExtra(EXTRA_KEY) ?: "")
        }
    }

    companion object {
        private const val ACTION_INVOKE = "app.droidnative.action.NOTIFICATION_ACTION"
        private const val ACTION_DISMISS = "app.droidnative.action.NOTIFICATION_DISMISS"
        private const val ACTION_MEDIA = "app.droidnative.action.MEDIA"
        private const val EXTRA_ID = "id"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_RESIDENT = "resident"

        private val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        private fun intent(context: Context, action: String) =
            Intent(context, NotificationReceiver::class.java).setAction(action)

        /** Button [index] of notification [id], invoking action [key]. */
        fun action(context: Context, id: Int, index: Int, key: String, resident: Boolean): PendingIntent =
            PendingIntent.getBroadcast(context, id * 4 + index,
                intent(context, ACTION_INVOKE).putExtra(EXTRA_ID, id).putExtra(EXTRA_KEY, key)
                    .putExtra(EXTRA_RESIDENT, resident), FLAGS)

        fun dismiss(context: Context, id: Int): PendingIntent =
            PendingIntent.getBroadcast(context, id * 4 + 3,
                intent(context, ACTION_DISMISS).putExtra(EXTRA_ID, id), FLAGS)

        // Request codes are per player and command; data keeps them apart
        fun media(context: Context, name: String, cmd: String): PendingIntent =
            PendingIntent.getBroadcast(context, 0,
                intent(context, ACTION_MEDIA).setData(android.net.Uri.parse("droidnative-media://$name/$cmd"))
                    .putExtra(EXTRA_NAME, name).putExtra(EXTRA_KEY, cmd), FLAGS)
    }
}
