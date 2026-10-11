package app.droidnative.bridge

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.droidnative.LaunchActivity
import app.droidnative.apps.LinuxApps
import app.droidnative.apps.WindowSettings
import app.droidnative.display.Compositor
import app.droidnative.display.WindowActivity
import app.droidnative.env.EnvService

/**
 * Tap on a guest notification (no UI of its own): invokes the "default"
 * action and shows the sending application's window. If it has none, waits
 * briefly for the window the action may open, or starts the application.
 * An activity because Android 12+ blocks activity starts from receivers.
 */
class NotificationOpenActivity : Activity(), Compositor.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var waiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Invisible while it waits: touches go to whatever is below
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val pid = intent.getIntExtra(EXTRA_PID, 0)
        val desktop = intent.getStringExtra(EXTRA_DESKTOP) ?: ""
        val hasDefault = intent.getBooleanExtra(EXTRA_DEFAULT, false)
        // Media notifications use negative ids and have no actions
        if (id >= 0) {
            if (hasDefault) Bridge.send("action", "id" to id, "key" to "default")
            if (!intent.getBooleanExtra(EXTRA_RESIDENT, false)) {
                getSystemService(NotificationManager::class.java).cancel(LinuxNotifications.BASE + id)
                Bridge.send("closed", "id" to id)
            }
        }

        val window = EnvService.compositor.toplevels.values.firstOrNull {
            it.pid == pid || (desktop.isNotEmpty() && WindowSettings.resolveApp(this, it.pid) == desktop)
        }
        when {
            window != null -> startActivity(WindowActivity.intent(this, window.id))
            hasDefault -> {
                waiting = true
                EnvService.compositor.addListener(this)
                handler.postDelayed({ finish() }, WAIT_MS)
                return
            }
            desktop.isNotEmpty() -> LinuxApps(this).find(desktop)?.let { LaunchActivity.start(this, it) }
        }
        finish()
    }

    override fun onToplevelNew(toplevel: Compositor.Toplevel) {
        handler.post {
            if (!waiting || isFinishing) return@post
            startActivity(WindowActivity.intent(this, toplevel.id))
            finish()
        }
    }

    override fun onDestroy() {
        if (waiting) EnvService.compositor.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val WAIT_MS = 3000L
        private const val EXTRA_ID = "id"
        private const val EXTRA_PID = "pid"
        private const val EXTRA_DESKTOP = "desktop"
        private const val EXTRA_DEFAULT = "default"
        private const val EXTRA_RESIDENT = "resident"

        fun pendingIntent(context: Context, id: Int, hasDefault: Boolean, resident: Boolean,
                          pid: Int, desktop: String): PendingIntent {
            val i = Intent(context, NotificationOpenActivity::class.java)
                .setData(android.net.Uri.parse("droidnative-notification://$id"))
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_PID, pid).putExtra(EXTRA_DESKTOP, desktop)
                .putExtra(EXTRA_DEFAULT, hasDefault).putExtra(EXTRA_RESIDENT, resident)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            return PendingIntent.getActivity(context, id, i, PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        }
    }
}
