package app.droidnative.display.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import android.view.View
import app.droidnative.R
import java.io.File
import kotlin.math.max

/**
 * Desktop wallpaper: the bundled default (res/drawable-nodpi/desktop_wallpaper.png)
 * or an image the user picked, copied to files/desktop/wallpaper.jpg.
 * Settings and the desktop's menu both change it; the desktop reloads it
 * on resume.
 */
object Wallpaper {

    // The default photo is landscape; portrait screens show this part of it
    private const val DEFAULT_FOCUS_X = 0.7f
    private const val DEFAULT_FOCUS_Y = 0.45f
    private const val MAX_SIDE = 2560

    fun file(context: Context) = File(context.filesDir, "desktop/wallpaper.jpg")

    fun isCustom(context: Context) = file(context).isFile

    /** Copies [uri] (scaled down to at most [MAX_SIDE]); false if it is no image. */
    fun set(context: Context, uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, MAX_SIDE, MAX_SIDE) }
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false
            val f = file(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.w("dn-desktop", "wallpaper $uri", e)
            false
        }
    }

    fun reset(context: Context) {
        file(context).delete()
    }

    /** The wallpaper decoded for a [w] x [h] screen, with its focus point. */
    fun load(context: Context, w: Int, h: Int): Loaded? = try {
        val f = file(context)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (f.isFile) {
            BitmapFactory.decodeFile(f.path, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, w, h) }
            BitmapFactory.decodeFile(f.path, opts)?.let { Loaded(it, 0.5f, 0.5f) }
        } else {
            BitmapFactory.decodeResource(context.resources, R.drawable.desktop_wallpaper, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, w, h) }
            BitmapFactory.decodeResource(context.resources, R.drawable.desktop_wallpaper, opts)
                ?.let { Loaded(it, DEFAULT_FOCUS_X, DEFAULT_FOCUS_Y) }
        }
    } catch (e: Exception) {
        Log.w("dn-desktop", "wallpaper", e)
        null
    }

    class Loaded(val bitmap: Bitmap, val focusX: Float, val focusY: Float)

    // Largest power of two that keeps the image at least as big as w x h
    private fun sample(bw: Int, bh: Int, w: Int, h: Int): Int {
        var s = 1
        while (bw / (s * 2) >= w && bh / (s * 2) >= h) s *= 2
        return s
    }
}

/** Fills the view with the wallpaper, cropped around its focus point. */
class WallpaperView(context: Context) : View(context) {
    private var image: Wallpaper.Loaded? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    fun show(loaded: Wallpaper.Loaded?) {
        image?.bitmap?.recycle()
        image = loaded
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val img = image
        if (img == null) {
            canvas.drawColor(0xFF1E2A33.toInt())
            return
        }
        val b = img.bitmap
        val scale = max(width.toFloat() / b.width, height.toFloat() / b.height)
        val sw = b.width * scale
        val sh = b.height * scale
        // Put the focus point in the middle, without leaving the image
        val dx = (width / 2f - sw * img.focusX).coerceIn(width - sw, 0f)
        val dy = (height / 2f - sh * img.focusY).coerceIn(height - sh, 0f)
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        canvas.drawBitmap(b, matrix, paint)
    }
}
