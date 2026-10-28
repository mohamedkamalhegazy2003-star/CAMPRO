package com.campro.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Simple exposure fusion (Mertens-style, without pyramids):
 * every pixel of every frame gets a "well-exposedness" weight, the weights are smoothed on a coarse
 * grid (to avoid seams/halos) and the frames are blended with them.
 */
object HdrFusion {

    // weight for a luma value 0..255: highest around mid-grey
    private val well = FloatArray(256) {
        val y = it / 255f
        exp((-(y - 0.5f) * (y - 0.5f) / 0.08f).toDouble()).toFloat() + 0.002f
    }

    fun fuse(files: List<File>, maxPx: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files[0].absolutePath, bounds)
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPx) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val imgs = ArrayList<IntArray>()
        var w = 0
        var h = 0
        for (f in files) {
            val bm = BitmapFactory.decodeFile(f.absolutePath, opts)
                ?: throw IllegalStateException("decode failed")
            if (imgs.isEmpty()) {
                w = bm.width
                h = bm.height
            }
            val px = IntArray(w * h)
            if (bm.width == w && bm.height == h) {
                bm.getPixels(px, 0, w, 0, 0, w, h)
            } else {
                val sc = Bitmap.createScaledBitmap(bm, w, h, true)
                sc.getPixels(px, 0, w, 0, 0, w, h)
                if (sc !== bm) sc.recycle()
            }
            bm.recycle()
            imgs.add(px)
        }
        val n = imgs.size

        // coarse weight grid
        val cell = 16
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        val grid = Array(n) { FloatArray(gw * gh) }
        val cnt = FloatArray(gw * gh)
        for (y in 0 until h) {
            val rowCell = (y / cell) * gw
            val row = y * w
            for (x in 0 until w) {
                val ci = rowCell + x / cell
                cnt[ci] += 1f
                for (i in 0 until n) {
                    val p = imgs[i][row + x]
                    val l = (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8
                    grid[i][ci] += well[l]
                }
            }
        }
        for (i in 0 until n) for (c in cnt.indices) grid[i][c] /= max(cnt[c], 1f)

        // bilinear lookup tables
        val x0 = IntArray(w)
        val x1 = IntArray(w)
        val fx = FloatArray(w)
        for (x in 0 until w) {
            val g = (x + 0.5f) / cell - 0.5f
            val a = floor(g).toInt().coerceIn(0, gw - 1)
            x0[x] = a
            x1[x] = min(a + 1, gw - 1)
            fx[x] = (g - a).coerceIn(0f, 1f)
        }

        val out = IntArray(w * h)
        val ws = FloatArray(n)
        for (y in 0 until h) {
            val g = (y + 0.5f) / cell - 0.5f
            val ya = floor(g).toInt().coerceIn(0, gh - 1)
            val yb = min(ya + 1, gh - 1)
            val fy = (g - ya).coerceIn(0f, 1f)
            val ra = ya * gw
            val rb = yb * gw
            val row = y * w
            for (x in 0 until w) {
                var sum = 0f
                for (i in 0 until n) {
                    val gi = grid[i]
                    val top = gi[ra + x0[x]] * (1f - fx[x]) + gi[ra + x1[x]] * fx[x]
                    val bot = gi[rb + x0[x]] * (1f - fx[x]) + gi[rb + x1[x]] * fx[x]
                    val v = top * (1f - fy) + bot * fy
                    ws[i] = v
                    sum += v
                }
                var r = 0f
                var gg = 0f
                var b = 0f
                for (i in 0 until n) {
                    val p = imgs[i][row + x]
                    val wt = ws[i]
                    r += wt * ((p shr 16) and 255)
                    gg += wt * ((p shr 8) and 255)
                    b += wt * (p and 255)
                }
                val inv = 1f / sum
                val ri = (r * inv + 0.5f).toInt().coerceIn(0, 255)
                val gi2 = (gg * inv + 0.5f).toInt().coerceIn(0, 255)
                val bi = (b * inv + 0.5f).toInt().coerceIn(0, 255)
                out[row + x] = (0xFF shl 24) or (ri shl 16) or (gi2 shl 8) or bi
            }
        }
        imgs.clear()
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
