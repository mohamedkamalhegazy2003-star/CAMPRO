package com.campro.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.nio.ByteBuffer

/**
 * Software portrait video. Every camera frame goes through an on-device person-segmentation model,
 * the background is blurred, the colour look is applied, and the result is both shown on screen and
 * (while recording) written to an H.264 file through a MediaCodec input surface.
 */
class SoftPortrait(private val onFrame: (Bitmap) -> Unit) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "CAMPRO-PV"
    }

    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask()
            .build()
    )

    @Volatile var colorFilter: ColorMatrixColorFilter? = null

    private val lock = Any()
    private var encoder: SoftEncoder? = null

    @Volatile var recording = false
        private set
    @Volatile var firstFrameNs = 0L
        private set

    // reusable buffers
    private var bw = 0
    private var bh = 0
    private var rot: Bitmap? = null
    private var tiny: Bitmap? = null
    private var mid: Bitmap? = null
    private var outs = arrayOfNulls<Bitmap>(2)
    private var outIdx = 0
    private var maskBmp: Bitmap? = null
    private var maskBytes = ByteArray(0)
    private var prevMask = ByteArray(0)

    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val smallPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    override fun analyze(image: ImageProxy) {
        try {
            process(image)
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
        } finally {
            image.close()
        }
    }

    private fun ensure(w: Int, h: Int) {
        if (w == bw && h == bh && rot != null) return
        bw = w
        bh = h
        rot?.recycle()
        tiny?.recycle()
        mid?.recycle()
        outs.forEach { it?.recycle() }
        rot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tiny = Bitmap.createBitmap(maxOf(8, w / 14), maxOf(8, h / 14), Bitmap.Config.ARGB_8888)
        mid = Bitmap.createBitmap(maxOf(16, w / 5), maxOf(16, h / 5), Bitmap.Config.ARGB_8888)
        outs = arrayOf<Bitmap?>(
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        )
    }

    private fun process(image: ImageProxy) {
        val deg = image.imageInfo.rotationDegrees
        val src = image.toBitmap()
        val w = if (deg % 180 == 0) src.width else src.height
        val h = if (deg % 180 == 0) src.height else src.width
        ensure(w and 1.inv(), h and 1.inv())
        val rotB = rot!!

        // upright copy
        val m = Matrix()
        m.postRotate(deg.toFloat())
        val r = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        m.mapRect(r)
        m.postTranslate(-r.left, -r.top)
        Canvas(rotB).drawBitmap(src, m, smallPaint)
        src.recycle()

        // person mask
        val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(rotB, 0)))
        val mw = mask.width
        val mh = mask.height
        val buf = mask.buffer
        buf.rewind()
        val n = mw * mh
        if (maskBytes.size != n) {
            maskBytes = ByteArray(n)
            prevMask = ByteArray(n)
        }
        for (i in 0 until n) {
            val v = buf.float
            val t = ((v - 0.30f) / 0.40f).coerceIn(0f, 1f)
            val a = t * t * (3f - 2f * t)
            val cur = (a * 255f).toInt()
            // light temporal smoothing so the edge doesn't flicker
            val sm = (cur * 0.65f + (prevMask[i].toInt() and 255) * 0.35f).toInt()
            maskBytes[i] = sm.toByte()
        }
        System.arraycopy(maskBytes, 0, prevMask, 0, n)
        var mb = maskBmp
        if (mb == null || mb.width != mw || mb.height != mh) {
            mb?.recycle()
            mb = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
            maskBmp = mb
        }
        mb!!.copyPixelsFromBuffer(ByteBuffer.wrap(maskBytes))

        // compose: blurred, filtered background + sharp, filtered person
        val out = outs[outIdx]!!
        outIdx = outIdx xor 1
        val c = Canvas(out)
        val fw = bw.toFloat()
        val fh = bh.toFloat()
        val tb = tiny!!
        val md = mid!!
        Canvas(tb).drawBitmap(rotB, null, RectF(0f, 0f, tb.width.toFloat(), tb.height.toFloat()), smallPaint)
        Canvas(md).drawBitmap(tb, null, RectF(0f, 0f, md.width.toFloat(), md.height.toFloat()), smallPaint)
        bgPaint.colorFilter = colorFilter
        c.drawBitmap(md, null, RectF(0f, 0f, fw, fh), bgPaint)

        val layer = c.saveLayer(0f, 0f, fw, fh, null)
        c.drawBitmap(rotB, 0f, 0f, bgPaint)
        c.drawBitmap(mb, null, RectF(0f, 0f, fw, fh), maskPaint)
        c.restoreToCount(layer)

        onFrame(out)

        synchronized(lock) {
            val enc = encoder
            if (recording && enc != null) {
                if (firstFrameNs == 0L) firstFrameNs = SystemClock.elapsedRealtimeNanos()
                enc.draw(out)
            }
        }
    }

    /** Output size of the upright frames (valid after the first frame arrived). */
    fun frameSize(): Pair<Int, Int>? = if (bw > 0 && bh > 0) (bw and 1.inv()) to (bh and 1.inv()) else null

    fun startRecording(file: File): Boolean {
        val sz = frameSize() ?: return false
        return try {
            val enc = SoftEncoder(file, sz.first, sz.second)
            synchronized(lock) {
                encoder = enc
                firstFrameNs = 0L
                recording = true
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "encoder start failed", t)
            false
        }
    }

    /** Stops recording; [done] runs on a worker thread when the file is complete. */
    fun stopRecording(done: (Boolean) -> Unit) {
        val enc: SoftEncoder?
        synchronized(lock) {
            recording = false
            enc = encoder
            encoder = null
        }
        Thread {
            val ok = try {
                enc?.finish() ?: false
            } catch (t: Throwable) {
                Log.e(TAG, "encoder stop failed", t)
                false
            }
            done(ok)
        }.start()
    }

    fun release() {
        try { segmenter.close() } catch (_: Exception) {}
    }
}

/** H.264 encoder fed by drawing bitmaps on its input surface. */
class SoftEncoder(private val file: File, w: Int, h: Int) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val surface: Surface
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxStarted = false
    private var basePts = -1L
    private var frames = 0
    private val drain: Thread

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * 30 * 12 / 100).toInt().coerceIn(6_000_000, 24_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
        drain = Thread { drainLoop() }.also { it.start() }
    }

    fun draw(bmp: Bitmap) {
        try {
            val c = try { surface.lockHardwareCanvas() } catch (e: Exception) { surface.lockCanvas(null) }
            c.drawBitmap(bmp, 0f, 0f, null)
            surface.unlockCanvasAndPost(c)
            frames++
        } catch (t: Throwable) {
            Log.e("CAMPRO-PV", "draw failed", t)
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxStarted = true
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (buf != null && info.size > 0 && muxStarted) {
                            if (basePts < 0) basePts = info.presentationTimeUs
                            info.presentationTimeUs = (info.presentationTimeUs - basePts).coerceAtLeast(0)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("CAMPRO-PV", "drain failed", t)
        }
    }

    fun finish(): Boolean {
        try { codec.signalEndOfInputStream() } catch (_: Exception) {}
        drain.join(8000)
        var ok = frames > 0 && muxStarted
        try { if (muxStarted) muxer.stop() } catch (_: Exception) { ok = false }
        try { muxer.release() } catch (_: Exception) {}
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { surface.release() } catch (_: Exception) {}
        return ok && file.length() > 0
    }
}
