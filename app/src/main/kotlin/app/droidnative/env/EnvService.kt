package app.droidnative.env

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.hardware.display.DisplayManager
import android.view.Display
import app.droidnative.R
import app.droidnative.devices.Devices
import app.droidnative.display.Compositor
import app.droidnative.display.WindowActivity
import kotlin.math.roundToInt

/**
 * Foreground service owning the environment: keeps the process (and with it
 * the compositor and proot children) alive while the app is in background.
 */
class EnvService : Service() {

    private val autoOpen = object : Compositor.Listener {
        override fun onToplevelNew(toplevel: Compositor.Toplevel) {
            if (!autoOpenWindows) return
            try {
                startActivity(WindowActivity.intent(this@EnvService, toplevel.id))
            } catch (e: Exception) {
                // Background activity starts can be refused; the window can
                // still be opened from the app.
                Log.w(TAG, "cannot open window for ${toplevel.id}", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startInForeground()
        compositor.addListener(autoOpen)
        startCompositor(this)
        Session.start(this)
        Devices.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        compositor.removeListener(autoOpen)
        Devices.stop()
        Session.stop()
        compositor.stop()
        isRunning = false
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL, getString(R.string.channel_env), NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
                      else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = builder
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.env_running))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "dn-env"
        private const val CHANNEL = "environment"
        private const val NOTIFICATION_ID = 1

        /** True between onCreate and onDestroy of the service. */
        @Volatile var isRunning = false
            private set

        /** Single compositor instance of the process. */
        val compositor = Compositor()

        /** wl_output scale the compositor runs with. */
        @Volatile var outputScale = 1
            private set

        /** Open a WindowActivity for every new toplevel (tests turn this off). */
        @Volatile var autoOpenWindows = true

        fun start(context: Context) {
            val i = Intent(context, EnvService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        /** Starts the compositor if needed; usable without the service (tests). */
        @Synchronized
        fun startCompositor(context: Context): Boolean {
            if (compositor.isRunning) return true
            val paths = EnvPaths(context)
            paths.ensureDirs()
            // DisplayManager works from any context (WindowManager needs a visual one)
            val display = context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            val refresh = display.refreshRate
            val prefs = Prefs(context)
            val config = Compositor.Config(
                socketPath = paths.waylandSocket.path,
                keymapPath = paths.keymap.path,
                tmpDir = context.cacheDir.path,
                scale = scaleFor(prefs.dpi ?: prefs.defaultDpi(dm.density)),
                displayWidth = dm.widthPixels,
                displayHeight = dm.heightPixels,
                physWidthMm = (dm.widthPixels / dm.xdpi * 25.4f).roundToInt(),
                physHeightMm = (dm.heightPixels / dm.ydpi * 25.4f).roundToInt(),
                refreshMhz = (refresh * 1000).roundToInt(),
                // Mouse modes are per window, emulated in WindowActivity
                touchMode = Compositor.TouchMode.NATIVE,
            )
            outputScale = config.scale
            return compositor.start(config)
        }

        /**
         * wl_output scale for a DPI: its whole part. The rest is applied per
         * program through toolkit variables (see LinuxApps.dpiEnv).
         */
        fun scaleFor(dpi: Int) = (dpi / 96).coerceAtLeast(1)

    }
}
