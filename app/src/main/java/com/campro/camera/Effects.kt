package com.campro.camera

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/** Vignette + film grain, shared by the live preview overlay and the saved photo. */
object Effects {

    val noise: Bitmap by lazy {
        val size = 256
        val rnd = Random(7)
        val px = IntArray(size * size)
        for (i in px.indices) {
            val v = if (rnd.nextBoolean()) rnd.nextInt(0, 70) else rnd.nextInt(185, 256)
            px[i] = Color.argb(255, v, v, v)
        }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private val rnd = Random(System.nanoTime())

    fun draw(canvas: Canvas, w: Float, h: Float, vignette: Float, grain: Float, jitter: Boolean) {
        if (vignette > 0.01f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val radius = hypot(w, h) / 2f
            val edge = Color.argb((vignette * 255f).toInt().coerceIn(0, 255), 0, 0, 0)
            p.shader = RadialGradient(
                w / 2f, h / 2f, radius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, edge),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, p)
        }
        if (grain > 0.01f) {
            val p = Paint()
            val sh = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            val s = max(1f, minOf(w, h) / 700f)
            val m = Matrix()
            m.setScale(s, s)
            if (jitter) m.postTranslate(rnd.nextInt(256).toFloat(), rnd.nextInt(256).toFloat())
            sh.setLocalMatrix(m)
            p.shader = sh
            p.alpha = (grain * 75f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, p)
        }
    }
}

/** Allocation-free version of [Effects.draw] for the live preview (called ~10x per second). */
class LiveEffects {
    private val vp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gp = Paint()
    private val grainShader by lazy { BitmapShader(Effects.noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val m = Matrix()
    private val rnd = Random(System.nanoTime())
    private var vw = 0f
    private var vh = 0f
    private var vv = -1f

    fun draw(canvas: Canvas, w: Float, h: Float, vignette: Float, grain: Float) {
        if (vignette > 0.01f) {
            if (vw != w || vh != h || vv != vignette) {
                vw = w
                vh = h
                vv = vignette
                val edge = Color.argb((vignette * 255f).toInt().coerceIn(0, 255), 0, 0, 0)
                vp.shader = RadialGradient(
                    w / 2f, h / 2f, hypot(w, h) / 2f,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, edge),
                    floatArrayOf(0f, 0.50f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, vp)
        }
        if (grain > 0.01f) {
            val s = max(1f, minOf(w, h) / 700f)
            m.setScale(s, s)
            m.postTranslate(rnd.nextInt(256).toFloat(), rnd.nextInt(256).toFloat())
            grainShader.setLocalMatrix(m)
            gp.shader = grainShader
            gp.alpha = (grain * 75f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, gp)
        }
    }
}
