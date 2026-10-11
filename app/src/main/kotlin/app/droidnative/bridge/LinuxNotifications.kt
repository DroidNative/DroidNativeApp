package app.droidnative.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.text.Html
import android.util.Log
import app.droidnative.R
import app.droidnative.apps.LinuxApps
import app.droidnative.apps.WindowSettings
import java.io.File
import org.json.JSONObject

/**
 * Desktop notifications of the guest as Android notifications: summary,
 * body (with its simple markup), icon or image, up to three action buttons.
 * Tapping invokes the "default" action and shows the application's window
 * ([NotificationOpenActivity]); dismissing tells the sender. Main thread.
 */
class LinuxNotifications(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val apps = LinuxApps(context)
    private val shown = HashSet<Int>()

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_LOW,
                context.getString(R.string.channel_linux_low), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_NORMAL,
                context.getString(R.string.channel_linux), NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_URGENT,
                context.getString(R.string.channel_linux_urgent), NotificationManager.IMPORTANCE_HIGH))
        }
    }

    fun post(m: JSONObject) {
        val id = m.optInt("id")
        val pid = m.optInt("pid")
        val appName = m.optString("app")
        // The sending application: hint first, else the process it belongs to
        val desktop = m.optString("desktop").ifEmpty { WindowSettings.resolveApp(context, pid) ?: "" }
        val entry = apps.findLoose(desktop) ?: apps.findLoose(appName)
        val title = m.optString("title").ifEmpty { entry?.name ?: appName }
        val body = markup(m.optString("body"))
        val actions = m.optJSONArray("actions")
        var hasDefault = false
        val buttons = ArrayList<Pair<String, String>>()
        for (i in 0 until (actions?.length() ?: 0)) {
            val a = actions!!.optJSONArray(i) ?: continue
            if (a.optString(0) == "default") hasDefault = true
            else if (buttons.size < 3) buttons += a.optString(0) to a.optString(1)
        }
        val resident = m.optBoolean("resident")
        val channel = when (m.optInt("urgency", 1)) { 0 -> CHANNEL_LOW; 2 -> CHANNEL_URGENT; else -> CHANNEL_NORMAL }

        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel)
                else @Suppress("DEPRECATION") Notification.Builder(context)
        b.setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSubText(entry?.name ?: appName.ifEmpty { null })
            .setShowWhen(true)
            .setAutoCancel(!resident)
            .setContentIntent(NotificationOpenActivity.pendingIntent(context, id, hasDefault, resident, pid, desktop))
            .setDeleteIntent(NotificationReceiver.dismiss(context, id))
        if (m.optInt("urgency", 1) == 2) b.setCategory(Notification.CATEGORY_ALARM)
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            b.setPriority(when (channel) {
                CHANNEL_LOW -> Notification.PRIORITY_LOW
                CHANNEL_URGENT -> Notification.PRIORITY_HIGH
                else -> Notification.PRIORITY_DEFAULT
            })
        }
        // The application's own icon; the penguin when it has none
        val appIcon = AppIcon.of(entry?.let { apps.icon(it) } ?: m.optString("icon").takeIf { !it.startsWith("/") }
            ?.let { apps.icon(it) })
        if (appIcon != null) appIcon.applyTo(b) else b.setSmallIcon(R.drawable.ic_penguin)
        (largeIcon(m) ?: appIcon?.bitmap?.let { scaled(it, ICON_SIZE) })?.let { b.setLargeIcon(it) }
        for ((i, button) in buttons.withIndex()) {
            @Suppress("DEPRECATION")
            b.addAction(Notification.Action.Builder(0, button.second,
                NotificationReceiver.action(context, id, i, button.first, resident)).build())
        }
        nm.notify(BASE + id, b.build())
        shown += id
    }

    fun cancel(id: Int) {
        nm.cancel(BASE + id)
        shown -= id
    }

    /** Removes everything the guest posted (the bridge went away). */
    fun clear() {
        for (id in shown) nm.cancel(BASE + id)
        shown.clear()
    }

    // image-data hint, image-path hint, the notification's icon
    private fun largeIcon(m: JSONObject): Bitmap? {
        m.optJSONObject("image")?.let { img -> rawImage(img)?.let { return it } }
        for (name in listOf(m.optString("imagePath"), m.optString("icon"))) {
            if (name.isEmpty()) continue
            val path = name.removePrefix("file://")
            val bitmap = if (path.startsWith("/")) apps.guestFile(path)?.let { decode(it) } else apps.icon(name)
            if (bitmap != null) return scaled(bitmap, ICON_SIZE)
        }
        return null
    }

    // Raw pixels written by dn-notifyd: RGB or RGBA rows with a stride
    private fun rawImage(img: JSONObject): Bitmap? = try {
        val file = apps.guestFile(img.getString("file")) ?: return null
        val w = img.getInt("width")
        val h = img.getInt("height")
        val stride = img.getInt("rowstride")
        val ch = img.getInt("channels")
        val data = file.readBytes()
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val o = y * stride + x * ch
            val a = if (ch == 4) data[o + 3].toInt() and 0xff else 0xff
            pixels[y * w + x] = (a shl 24) or ((data[o].toInt() and 0xff) shl 16) or
                ((data[o + 1].toInt() and 0xff) shl 8) or (data[o + 2].toInt() and 0xff)
        }
        file.delete()
        scaled(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888), ICON_SIZE)
    } catch (e: Exception) {
        Log.w(TAG, "notification image: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "dn-notify"
        const val BASE = 100_000
        private const val CHANNEL_LOW = "linux_low"
        private const val CHANNEL_NORMAL = "linux"
        private const val CHANNEL_URGENT = "linux_urgent"
        private const val ICON_SIZE = 256

        /** Body markup (b, i, u, a, img) to styled text; plain line breaks kept. */
        fun markup(body: String): CharSequence =
            if (body.isEmpty()) "" else Html.fromHtml(body.replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY).trimEnd()

        fun decode(file: File): Bitmap? = runCatching {
            android.graphics.BitmapFactory.decodeFile(file.path)
        }.getOrNull()

        /** [bitmap] no larger than [max] pixels per side (binder limits). */
        fun scaled(bitmap: Bitmap, max: Int): Bitmap {
            val big = maxOf(bitmap.width, bitmap.height)
            if (big <= max) return bitmap
            val f = max.toFloat() / big
            return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1),
                (bitmap.height * f).toInt().coerceAtLeast(1), true)
        }
    }
}
