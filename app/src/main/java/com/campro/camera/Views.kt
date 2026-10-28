package com.campro.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Transparent overlay on top of the preview: vignette + living film grain. */
class EffectsView(ctx: Context) : View(ctx) {
    private var vignette = 0f
    private var grain = 0f
    private val tick = Runnable { invalidate() }

    fun set(v: Float, g: Float) {
        vignette = v
        grain = g
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        removeCallbacks(tick)
        if (vignette > 0.01f || grain > 0.01f) {
            Effects.draw(canvas, width.toFloat(), height.toFloat(), vignette, grain, true)
        }
        if (grain > 0.01f) postDelayed(tick, 90)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}

/** Rule-of-thirds grid. */
class GridView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, p)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, p)
        }
    }
}

/** Small vector-like icons drawn with Canvas (no image assets needed). */
class IconView(ctx: Context, private val type: Int) : View(ctx) {

    companion object {
        const val FLASH = 0
        const val GRID = 1
        const val FLIP = 2
        const val TIMER = 3
        const val EV = 4
    }

    /** FLASH: 0 = off, 1 = auto, 2 = on. GRID: 0 = hidden, 2 = shown. */
    var state = 0
        set(value) {
            field = value
            invalidate()
        }

    private val accent = Color.parseColor("#FFC93C")
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        when (type) {
            FLASH -> drawFlash(canvas, s, cx, cy)
            GRID -> drawGrid(canvas, s, cx, cy)
            FLIP -> drawFlip(canvas, s, cx, cy)
            TIMER -> drawTimer(canvas, s, cx, cy)
            EV -> drawEv(canvas, s, cx, cy)
        }
    }

    /** TIMER: state = seconds (0, 3, 10). */
    private fun drawTimer(c: Canvas, s: Float, cx: Float, cy: Float) {
        val r = s * 0.24f
        val col = if (state != 0) accent else Color.WHITE
        stroke.color = col
        c.drawCircle(cx, cy + s * 0.03f, r, stroke)
        c.drawLine(cx - r * 0.35f, cy - r - s * 0.07f, cx + r * 0.35f, cy - r - s * 0.07f, stroke)
        if (state != 0) {
            text.color = col
            text.textSize = s * 0.30f
            c.drawText(state.toString(), cx, cy + s * 0.03f + s * 0.10f, text)
        } else {
            c.drawLine(cx, cy + s * 0.03f, cx, cy + s * 0.03f - r * 0.6f, stroke)
            c.drawLine(cx, cy + s * 0.03f, cx + r * 0.45f, cy + s * 0.03f, stroke)
        }
    }

    /** EV: state != 0 means the slider is open. */
    private fun drawEv(c: Canvas, s: Float, cx: Float, cy: Float) {
        val r = s * 0.26f
        val col = if (state != 0) accent else Color.WHITE
        stroke.color = col
        c.drawCircle(cx, cy, r, stroke)
        val k = r * 0.5f
        c.drawLine(cx - k, cy - r * 0.25f, cx + k, cy - r * 0.25f, stroke)
        c.drawLine(cx, cy - r * 0.25f - k, cx, cy - r * 0.25f + k, stroke)
        c.drawLine(cx - k, cy + r * 0.45f, cx + k, cy + r * 0.45f, stroke)
    }

    private fun drawFlash(c: Canvas, s: Float, cx: Float, cy: Float) {
        val on = state != 0
        val col = if (on) accent else Color.WHITE
        val u = s * 0.56f
        val ox = cx - u / 2f
        val oy = cy - u / 2f
        val path = Path().apply {
            moveTo(ox + u * 0.60f, oy + u * 0.00f)
            lineTo(ox + u * 0.15f, oy + u * 0.56f)
            lineTo(ox + u * 0.47f, oy + u * 0.56f)
            lineTo(ox + u * 0.38f, oy + u * 1.00f)
            lineTo(ox + u * 0.88f, oy + u * 0.40f)
            lineTo(ox + u * 0.55f, oy + u * 0.40f)
            close()
        }
        if (state == 2) {
            fill.color = col
            c.drawPath(path, fill)
        } else {
            stroke.color = col
            c.drawPath(path, stroke)
        }
        if (state == 1) {
            text.color = col
            text.textSize = s * 0.28f
            c.drawText("A", cx + u * 0.62f, cy - u * 0.30f, text)
        }
        if (state == 0) {
            stroke.color = Color.WHITE
            c.drawLine(cx - u * 0.55f, cy - u * 0.55f, cx + u * 0.55f, cy + u * 0.55f, stroke)
        }
    }

    private fun drawGrid(c: Canvas, s: Float, cx: Float, cy: Float) {
        val u = s * 0.52f
        stroke.color = if (state != 0) accent else Color.WHITE
        val r = RectF(cx - u / 2f, cy - u / 2f, cx + u / 2f, cy + u / 2f)
        c.drawRoundRect(r, u * 0.1f, u * 0.1f, stroke)
        for (i in 1..2) {
            c.drawLine(r.left + u * i / 3f, r.top, r.left + u * i / 3f, r.bottom, stroke)
            c.drawLine(r.left, r.top + u * i / 3f, r.right, r.top + u * i / 3f, stroke)
        }
    }

    private fun drawFlip(c: Canvas, s: Float, cx: Float, cy: Float) {
        val r = s * 0.26f
        stroke.color = Color.WHITE
        fill.color = Color.WHITE
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val start = 30f
        val sweep = 280f
        c.drawArc(oval, start, sweep, false, stroke)
        val end = Math.toRadians((start + sweep).toDouble())
        val px = cx + r * cos(end).toFloat()
        val py = cy + r * sin(end).toFloat()
        val tx = -sin(end).toFloat()
        val ty = cos(end).toFloat()
        val nx = cos(end).toFloat()
        val ny = sin(end).toFloat()
        val h = s * 0.13f
        val head = Path().apply {
            moveTo(px + tx * h, py + ty * h)
            lineTo(px + nx * h * 0.85f, py + ny * h * 0.85f)
            lineTo(px - nx * h * 0.85f, py - ny * h * 0.85f)
            close()
        }
        c.drawPath(head, fill)
    }
}

/** Round shutter button: white ring + inner disc. */
class ShutterView(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = resources.displayMetrics.density * 4f
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    var busy = false
        set(value) {
            field = value
            disc.color = if (value) Color.parseColor("#FF7A18") else Color.WHITE
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
        canvas.drawCircle(cx, cy, r - ring.strokeWidth * 2.2f, disc)
    }
}
