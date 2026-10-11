package app.droidnative.bridge

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon

/**
 * A Linux application's icon on its notifications. Android draws the small
 * icon as a one-colour mask of its transparency, so the small icon is the
 * icon's outline ([mask]), tinted with its main colour; the full-colour
 * icon can only be the large icon.
 */
class AppIcon(val bitmap: Bitmap) {
    private val small = Icon.createWithBitmap(mask(LinuxNotifications.scaled(bitmap, SMALL_SIZE)))
    private val colour = mainColour(bitmap)

    fun applyTo(b: Notification.Builder) {
        b.setSmallIcon(small)
        if (colour != 0) b.setColor(colour)
    }

    companion object {
        private const val SMALL_SIZE = 96
        // Share of its bounding box an icon covers to count as solid
        private const val SOLID = 0.8f
        // Colour distance (sum over RGB) shown at full strength
        private const val DETAIL = 160

        fun of(bitmap: Bitmap?): AppIcon? = bitmap?.let { AppIcon(it) }

        /**
         * Shaped icons (headphones, a hammer) keep their transparency. A solid
         * one (a page, a box) would be a blob: it becomes its outline plus
         * whatever differs from its main fill colour.
         */
        fun mask(b: Bitmap): Bitmap {
            val w = b.width
            val h = b.height
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1; var opaque = 0
            val counts = HashMap<Int, Int>()
            for (y in 0 until h) for (x in 0 until w) {
                val c = px[y * w + x]
                if (Color.alpha(c) < 128) continue
                opaque++
                x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
                // Colours by 4 bits per channel
                val q = (Color.red(c) shr 4 shl 8) or (Color.green(c) shr 4 shl 4) or (Color.blue(c) shr 4)
                counts[q] = (counts[q] ?: 0) + 1
            }
            if (opaque == 0 || opaque < SOLID * (x1 - x0 + 1) * (y1 - y0 + 1)) return b
            val q = counts.maxByOrNull { it.value }!!.key
            val fr = (q shr 8 and 15) * 16 + 8
            val fg = (q shr 4 and 15) * 16 + 8
            val fb = (q and 15) * 16 + 8
            val edge = (w / 24).coerceAtLeast(1)
            fun clear(x: Int, y: Int) = x !in 0 until w || y !in 0 until h || Color.alpha(px[y * w + x]) < 128
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val c = px[y * w + x]
                if (Color.alpha(c) < 128) continue
                val border = clear(x - edge, y) || clear(x + edge, y) || clear(x, y - edge) || clear(x, y + edge)
                val d = Math.abs(Color.red(c) - fr) + Math.abs(Color.green(c) - fg) + Math.abs(Color.blue(c) - fb)
                val a = if (border) 255 else (d * 255 / DETAIL).coerceAtMost(255)
                out[y * w + x] = Color.argb(a, 255, 255, 255)
            }
            return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }

        /** Average of the icon's saturated opaque pixels; 0 for a grey icon. */
        fun mainColour(b: Bitmap): Int {
            val small = LinuxNotifications.scaled(b, 32)
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            val hsv = FloatArray(3)
            var r = 0L; var g = 0L; var bl = 0L; var n = 0
            for (c in px) {
                if (Color.alpha(c) < 128) continue
                Color.colorToHSV(c, hsv)
                if (hsv[1] < 0.3f || hsv[2] < 0.2f) continue
                r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++
            }
            return if (n == 0) 0 else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        }
    }
}
