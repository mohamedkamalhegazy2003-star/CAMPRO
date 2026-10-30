package com.campro.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Multi-frame merge used by Night Sight and Super Res Zoom.
 *
 * Every frame is aligned to the first one (coarse search on a 1/4 size image, then a fine search at
 * full size), the aligned frames are averaged (random noise drops by about sqrt(N)), and the result is
 * brightened (night) and sharpened.
 */
object BurstFusion {

    private const val DS = 4

    fun fuse(files: List<File>, maxPx: Int, night: Boolean, zoom: Float = 1f): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files[0].absolutePath, bounds)
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }

        var w = 0
        var h = 0
        var sumR = CharArray(0)
        var sumG = CharArray(0)
        var sumB = CharArray(0)
        var refSmall = IntArray(0)
        var refLuma = ByteArray(0)
        var n = 0

        for ((idx, f) in files.withIndex()) {
            val bm = BitmapFactory.decodeFile(f.absolutePath, opts) ?: continue
            if (idx == 0) {
                w = bm.width
                h = bm.height
                sumR = CharArray(w * h)
                sumG = CharArray(w * h)
                sumB = CharArray(w * h)
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

            var tx = 0
            var ty = 0
            if (idx == 0) {
                refSmall = smallLuma(px, w, h)
                refLuma = ByteArray(w * h) { lum(px[it]).toByte() }
            } else {
                val small = smallLuma(px, w, h)
                val c = coarse(refSmall, small, w / DS, h / DS)
                tx = c.first * DS
                ty = c.second * DS
                val r = fine(refLuma, px, w, h, tx, ty)
                tx = r.first
                ty = r.second
            }

            for (y in 0 until h) {
                val sy = (y + ty).coerceIn(0, h - 1)
                val srow = sy * w
                val drow = y * w
                for (x in 0 until w) {
                    val p = px[srow + (x + tx).coerceIn(0, w - 1)]
                    val i = drow + x
                    sumR[i] = (sumR[i].code + ((p shr 16) and 255)).toChar()
                    sumG[i] = (sumG[i].code + ((p shr 8) and 255)).toChar()
                    sumB[i] = (sumB[i].code + (p and 255)).toChar()
                }
            }
            n++
        }
        if (n == 0) throw IllegalStateException("no frames")
        refLuma = ByteArray(0)

        // brightness lift for night shots
        var gammaLut: IntArray? = null
        if (night) {
            var total = 0L
            var cnt = 0
            var i = 0
            while (i < w * h) {
                total += (sumR[i].code * 77 + sumG[i].code * 150 + sumB[i].code * 29) shr 8
                cnt++
                i += 16
            }
            val mean = (total.toFloat() / cnt / n / 255f).coerceIn(0.01f, 0.95f)
            val p = (ln(0.38f) / ln(mean)).coerceIn(0.45f, 1f)
            gammaLut = IntArray(256) { ((it / 255f).pow(p) * 255f + 0.5f).toInt().coerceIn(0, 255) }
        }

        val out = IntArray(w * h)
        val inv = 1f / n
        for (i in out.indices) {
            var r = (sumR[i].code * inv + 0.5f).toInt().coerceIn(0, 255)
            var g = (sumG[i].code * inv + 0.5f).toInt().coerceIn(0, 255)
            var b = (sumB[i].code * inv + 0.5f).toInt().coerceIn(0, 255)
            if (gammaLut != null) {
                r = gammaLut[r]
                g = gammaLut[g]
                b = gammaLut[b]
            }
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        sumR = CharArray(0)
        sumG = CharArray(0)
        sumB = CharArray(0)

        if (night) {
            sharpen(out, w, h, 0.25f, 1)
        } else {
            // the more the shot is zoomed, the more detail/contrast recovery it gets
            val z = (zoom - 1.5f).coerceIn(0f, 5f)
            sharpen(out, w, h, 0.60f + 0.10f * z, 1)
            sharpen(out, w, h, 0.35f + 0.08f * z, 2)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun lum(p: Int): Int = (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8

    /** 1/4-size luma: the mean of each 4x4 block. */
    private fun smallLuma(px: IntArray, w: Int, h: Int): IntArray {
        val gw = w / DS
        val gh = h / DS
        val out = IntArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var s = 0
                for (dy in 0 until DS) {
                    val row = (gy * DS + dy) * w + gx * DS
                    for (dx in 0 until DS) s += lum(px[row + dx])
                }
                out[gy * gw + gx] = s / (DS * DS)
            }
        }
        return out
    }

    /** Best (dx, dy) in small-image pixels so that cur(x+dx, y+dy) matches ref(x, y). */
    private fun coarse(ref: IntArray, cur: IntArray, gw: Int, gh: Int): Pair<Int, Int> {
        val rad = 8
        var best = Long.MAX_VALUE
        var bx = 0
        var by = 0
        for (dy in -rad..rad) {
            for (dx in -rad..rad) {
                var sad = 0L
                var y = rad
                while (y < gh - rad) {
                    var x = rad
                    val row = y * gw
                    val crow = (y + dy) * gw + dx
                    while (x < gw - rad) {
                        sad += abs(ref[row + x] - cur[crow + x])
                        x += 2
                    }
                    y += 2
                }
                // prefer small shifts on ties
                sad += (abs(dx) + abs(dy)).toLong()
                if (sad < best) {
                    best = sad
                    bx = dx
                    by = dy
                }
            }
        }
        return bx to by
    }

    /** Refines the shift at full resolution (+-3 px) around the coarse result. */
    private fun fine(ref: ByteArray, cur: IntArray, w: Int, h: Int, cx: Int, cy: Int): Pair<Int, Int> {
        var best = Long.MAX_VALUE
        var bx = cx
        var by = cy
        val x0 = w / 4
        val x1 = w * 3 / 4
        val y0 = h / 4
        val y1 = h * 3 / 4
        for (ddy in -3..3) {
            for (ddx in -3..3) {
                val tx = cx + ddx
                val ty = cy + ddy
                var sad = 0L
                var y = y0
                while (y < y1) {
                    val sy = (y + ty).coerceIn(0, h - 1)
                    var x = x0
                    while (x < x1) {
                        val sx = (x + tx).coerceIn(0, w - 1)
                        sad += abs((ref[y * w + x].toInt() and 255) - lum(cur[sy * w + sx]))
                        x += 4
                    }
                    y += 4
                }
                if (sad < best) {
                    best = sad
                    bx = tx
                    by = ty
                }
            }
        }
        return bx to by
    }

    private fun sharpen(px: IntArray, w: Int, h: Int, a: Float, step: Int) {
        val src = px.copyOf()
        for (y in step until h - step) {
            val row = y * w
            for (x in step until w - step) {
                val i = row + x
                val c = src[i]
                val l = src[i - step]
                val r = src[i + step]
                val u = src[i - step * w]
                val d = src[i + step * w]
                var res = 0xFF shl 24
                var s = 16
                while (s >= 0) {
                    val v = (c shr s) and 255
                    val nb = ((l shr s) and 255) + ((r shr s) and 255) + ((u shr s) and 255) + ((d shr s) and 255)
                    val o = max(0, min(255, (v + a * (4 * v - nb) + 0.5f).toInt()))
                    res = res or (o shl s)
                    s -= 8
                }
                px[i] = res
            }
        }
    }
}
