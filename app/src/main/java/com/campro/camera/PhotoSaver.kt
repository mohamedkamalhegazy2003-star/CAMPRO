package com.campro.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** Applies a look to a captured JPEG and stores it in Pictures/CAMPRO. */
object PhotoSaver {

    fun save(ctx: Context, src: File, filter: CamFilter, k: Float): Uri? = store(ctx) { out ->
        if (filter.isNone || k <= 0.01f) {
            // untouched original: copy the camera JPEG as-is (full quality + EXIF)
            src.inputStream().use { it.copyTo(out) }
        } else {
            val bmp = process(src, filter, k)
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
            bmp.recycle()
        }
    }

    /** HDR+: fuses an exposure bracket (dark, normal, bright) and then applies the look. */
    fun saveHdr(ctx: Context, files: List<File>, filter: CamFilter, k: Float): Uri? = store(ctx) { out ->
        val orientation = orientationOf(files[files.size / 2])
        var maxPx = 13_000_000
        var bmp: Bitmap? = null
        while (bmp == null) {
            try {
                val fused = HdrFusion.fuse(files, maxPx)
                bmp = compose(fused, orientation, filter, k, 1.12f)
            } catch (e: OutOfMemoryError) {
                maxPx /= 2
                if (maxPx < 500_000) throw e
            }
        }
        bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
        bmp.recycle()
    }

    /** Night Sight / Super Res Zoom: aligned multi-frame merge, then the look. */
    fun saveBurst(ctx: Context, files: List<File>, filter: CamFilter, k: Float, night: Boolean): Uri? =
        store(ctx) { out ->
            val orientation = orientationOf(files[0])
            var maxPx = 13_000_000
            var bmp: Bitmap? = null
            while (bmp == null) {
                try {
                    val fused = BurstFusion.fuse(files, maxPx, night)
                    bmp = compose(fused, orientation, filter, k, 1f)
                } catch (e: OutOfMemoryError) {
                    maxPx /= 2
                    if (maxPx < 500_000) throw e
                }
            }
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
            bmp.recycle()
        }

    private fun store(ctx: Context, write: (OutputStream) -> Unit): Uri? {
        val name = "CAMPRO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CAMPRO")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { write(it) }
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, cv, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * Full-resolution look: the JPEG is decoded in 512-row tiles (so even a 50 MP photo never needs a
     * second full-size bitmap) and drawn, rotated and filtered, into one output bitmap.
     * Falls back to a smaller output only when memory runs out.
     */
    private fun process(src: File, f: CamFilter, k: Float): Bitmap {
        var scale = 1f
        while (true) {
            try {
                return renderTiled(src, f, k, scale)
            } catch (e: OutOfMemoryError) {
                scale /= 2f
                if (scale < 0.2f) throw e
            }
        }
    }

    private fun renderTiled(src: File, f: CamFilter, k: Float, scale: Float): Bitmap {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, b)
        val w = b.outWidth
        val h = b.outHeight
        if (w <= 0 || h <= 0) throw IllegalStateException("decode failed")

        val m = orientMatrix(orientationOf(src), w, h)
        m.postScale(scale, scale)
        val r = RectF(0f, 0f, w.toFloat(), h.toFloat())
        m.mapRect(r)
        val out = Bitmap.createBitmap(r.width().roundToInt(), r.height().roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(Looks.lerp(f.matrix, k))
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

        val dec = BitmapRegionDecoder.newInstance(src.absolutePath)
        try {
            val tm = Matrix()
            var y = 0
            while (y < h) {
                val th = min(512, h - y)
                val tile = dec.decodeRegion(Rect(0, y, w, y + th), opts)
                tm.set(m)
                tm.preTranslate(0f, y.toFloat())
                canvas.drawBitmap(tile, tm, paint)
                tile.recycle()
                y += th
            }
        } finally {
            dec.recycle()
        }

        Effects.draw(canvas, out.width.toFloat(), out.height.toFloat(), f.vignette * k, f.grain * k, false)
        return out
    }

    private fun orientationOf(f: File): Int =
        ExifInterface(f.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

    /** EXIF orientation as a matrix whose output starts at (0,0). */
    private fun orientMatrix(orientation: Int, w: Int, h: Int): Matrix {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
        }
        val r = RectF(0f, 0f, w.toFloat(), h.toFloat())
        m.mapRect(r)
        m.postTranslate(-r.left, -r.top)
        return m
    }

    /** Rotates by EXIF, applies the look, vignette and grain. Recycles [bmp]. */
    private fun compose(bmp: Bitmap, orientation: Int, f: CamFilter, k: Float, extraSat: Float): Bitmap {
        val m = orientMatrix(orientation, bmp.width, bmp.height)
        val r = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        m.mapRect(r)
        val out = try {
            Bitmap.createBitmap(r.width().roundToInt(), r.height().roundToInt(), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            bmp.recycle()
            throw e
        }
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val cm = ColorMatrix()
        if (extraSat != 1f) {
            cm.setSaturation(extraSat)
            cm.postConcat(Looks.lerp(f.matrix, k))
        } else {
            cm.set(Looks.lerp(f.matrix, k))
        }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bmp, m, paint)
        bmp.recycle()

        Effects.draw(canvas, out.width.toFloat(), out.height.toFloat(), f.vignette * k, f.grain * k, false)
        return out
    }
}
