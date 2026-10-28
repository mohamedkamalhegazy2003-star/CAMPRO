package com.campro.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        var maxPx = 8_000_000
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

    private fun process(src: File, f: CamFilter, k: Float): Bitmap {
        var sample = 1
        while (true) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeFile(src.absolutePath, opts)
                    ?: throw IllegalStateException("decode failed")
                return compose(bmp, orientationOf(src), f, k, 1f)
            } catch (e: OutOfMemoryError) {
                sample *= 2
                if (sample > 8) throw e
            }
        }
    }

    private fun orientationOf(f: File): Int =
        ExifInterface(f.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

    /** Rotates by EXIF, applies the look, vignette and grain. Recycles [bmp]. */
    private fun compose(bmp: Bitmap, orientation: Int, f: CamFilter, k: Float, extraSat: Float): Bitmap {
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
        val r = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        m.mapRect(r)
        m.postTranslate(-r.left, -r.top)

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
