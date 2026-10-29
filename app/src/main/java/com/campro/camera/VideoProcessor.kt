package com.campro.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ColorMatrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.RgbMatrix
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects as MediaEffects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bakes a CAMPRO colour look into a recorded video (GPU, via Media3 Transformer). */
object VideoProcessor {

    /** Must be called on the main thread. [done] runs on the main thread too (null = success). */
    fun transcode(
        ctx: Context,
        input: File,
        output: File,
        filter: CamFilter,
        k: Float,
        removeAudio: Boolean,
        done: (Throwable?) -> Unit
    ) {
        val r = MediaMetadataRetriever()
        var w = 1920
        var h = 1080
        try {
            r.setDataSource(input.absolutePath)
            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: w
            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: h
        } catch (_: Exception) {
        } finally {
            r.release()
        }
        // roughly 0.15 bit per pixel per frame at 30 fps: ~37 Mbps for 4K, ~9 Mbps for 1080p
        val bitrate = (w.toLong() * h * 30L * 15L / 100L).coerceIn(6_000_000L, 60_000_000L).toInt()

        val effects = ArrayList<Effect>()
        if (!filter.isNone && k > 0.01f) effects.add(colorEffect(Looks.lerp(filter.matrix, k)))

        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setRemoveAudio(removeAudio)
            .setEffects(MediaEffects(emptyList(), effects))
            .build()

        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build()

        val transformer = Transformer.Builder(ctx)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done(null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    done(exportException)
                }
            })
            .build()
        transformer.start(item, output.absolutePath)
    }

    /**
     * A 4x5 Android ColorMatrix as a Media3 4x4 RGB matrix: the frame alpha is always 1, so the
     * per-channel offset can ride in the 4th column. Column-major order.
     */
    private fun colorEffect(cm: ColorMatrix): Effect {
        val a = cm.array
        val m = FloatArray(16)
        for (row in 0..2) {
            for (c in 0..2) m[c * 4 + row] = a[row * 5 + c]
            m[3 * 4 + row] = a[row * 5 + 3] + a[row * 5 + 4] / 255f
        }
        m[3 * 4 + 3] = 1f
        return object : RgbMatrix {
            override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = m
        }
    }
}

/** Muxes a video-only MP4 with a separately recorded AAC file (used for the Bluetooth mic). */
object AvMuxer {

    fun mux(video: File, audio: File, out: File, audioOffsetUs: Long) {
        val rotation = metaInt(video, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val videoDurUs = metaInt(video, MediaMetadataRetriever.METADATA_KEY_DURATION) * 1000L

        val ve = MediaExtractor()
        val ae = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ve.setDataSource(video.absolutePath)
            ae.setDataSource(audio.absolutePath)
            val vt = findTrack(ve, "video/")
            val at = findTrack(ae, "audio/")
            if (vt < 0 || at < 0) throw IllegalStateException("missing track")
            ve.selectTrack(vt)
            ae.selectTrack(at)

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
            val vi = muxer.addTrack(ve.getTrackFormat(vt))
            val ai = muxer.addTrack(ae.getTrackFormat(at))
            muxer.start()

            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var vDone = false
            var aDone = false
            while (!vDone || !aDone) {
                val vTime = if (vDone) Long.MAX_VALUE else ve.sampleTime
                if (!vDone && vTime < 0) {
                    vDone = true
                    continue
                }
                var aTime = Long.MAX_VALUE
                if (!aDone) {
                    val raw = ae.sampleTime
                    if (raw < 0) {
                        aDone = true
                        continue
                    }
                    aTime = raw - audioOffsetUs
                    if (aTime < 0) {
                        ae.advance()
                        continue
                    }
                    if (videoDurUs > 0 && aTime > videoDurUs) {
                        aDone = true
                        continue
                    }
                }
                if (!vDone && (aDone || vTime <= aTime)) {
                    val size = ve.readSampleData(buf, 0)
                    if (size < 0) {
                        vDone = true
                        continue
                    }
                    info.set(0, size, vTime, ve.sampleFlags)
                    muxer.writeSampleData(vi, buf, info)
                    ve.advance()
                } else {
                    val size = ae.readSampleData(buf, 0)
                    if (size < 0) {
                        aDone = true
                        continue
                    }
                    info.set(0, size, aTime, ae.sampleFlags)
                    muxer.writeSampleData(ai, buf, info)
                    ae.advance()
                }
            }
            muxer.stop()
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            ve.release()
            ae.release()
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun metaInt(f: File, key: Int): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(key)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            r.release()
        }
    }
}

/** Stores a finished MP4 in Movies/CAMPRO. */
object VideoSaver {

    fun store(ctx: Context, file: File): Uri? {
        val name = "CAMPRO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CAMPRO")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            cv.clear()
            cv.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, cv, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
