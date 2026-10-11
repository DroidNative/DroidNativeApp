package app.droidnative.apps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.droidnative.LaunchActivity
import app.droidnative.R

/**
 * Home screen shortcuts for Linux applications. They open
 * droidnative://app/<desktop entry id> in [LaunchActivity].
 */
object Shortcuts {
    const val SCHEME = "droidnative"
    const val HOST_APP = "app"

    fun uri(entry: DesktopEntry): Uri = Uri.Builder().scheme(SCHEME).authority(HOST_APP)
        .appendPath(entry.id).build()

    fun isSupported(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to pin a shortcut; false if it cannot. */
    fun pin(context: Context, entry: DesktopEntry, icon: Bitmap?): Boolean {
        if (!isSupported(context)) return false
        val intent = Intent(Intent.ACTION_VIEW, uri(entry), context, LaunchActivity::class.java)
        val info = ShortcutInfoCompat.Builder(context, "app:${entry.id}")
            .setShortLabel(entry.name)
            .setLongLabel(entry.name)
            .setIcon(icon?.let { IconCompat.createWithAdaptiveBitmap(adaptive(it)) }
                ?: IconCompat.createWithResource(context, R.drawable.ic_tab_overview))
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    // Adaptive icons are 108 units with the visible part in the middle 72;
    // the app icon sits in the inner 60 on white, as on a Linux desktop
    private fun adaptive(icon: Bitmap): Bitmap {
        val size = 216
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0xFFFFFFFF.toInt())
        val inner = size * 60 / 108
        val scaled = Bitmap.createScaledBitmap(icon, inner, inner, true)
        val off = (size - inner) / 2f
        canvas.drawBitmap(scaled, off, off, null)
        return out
    }
}
