package com.campro.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Real RAW (DNG) capture through Camera2.
 *
 * Samsung only exposes the RAW capability to apps on some devices/after the camera HAL was patched
 * (see docs/CAMERA_PATCHER_A35.md). [findRawCamera] tells whether this phone currently offers it.
 */
class RawCam(private val ctx: Context) {

    companion object {
        private const val TAG = "CAMPRO-RAW"

        /** Camera id and characteristics of a camera of [facing] that offers RAW_SENSOR output, or null. */
        fun findRawCamera(ctx: Context, facing: Int): Pair<String, CameraCharacteristics>? {
            return try {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                var best: Pair<String, CameraCharacteristics>? = null
                var bestArea = -1L
                for (id in cm.cameraIdList) {
                    val ch = cm.getCameraCharacteristics(id)
                    if (ch.get(CameraCharacteristics.LENS_FACING) != facing) continue
                    val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                    if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) continue
                    val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                    if (map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()) continue
                    val pa = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    val area = if (pa != null) pa.width.toLong() * pa.height else 0L
                    if (area > bestArea) {
                        bestArea = area
                        best = id to ch
                    }
                }
                best
            } catch (e: Exception) {
                Log.w(TAG, "findRawCamera failed", e)
                null
            }
        }

        private fun exifOrientation(sensorDegrees: Int, front: Boolean): Int = when (sensorDegrees) {
            90 -> if (front) ExifInterface.ORIENTATION_ROTATE_270 else ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> if (front) ExifInterface.ORIENTATION_ROTATE_90 else ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }

    private val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("campro-raw").also { it.start() }
    private val handler = Handler(thread.looper)
    private val exec = Executors.newSingleThreadExecutor()
    private val saver = Executors.newSingleThreadExecutor()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var previewSurface: Surface? = null

    @Volatile private var closed = false

    // pairing of the RAW image and its capture result
    private val pairLock = Any()
    private var pendingImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var pendingCb: ((Uri?) -> Unit)? = null

    var rawSize: Size? = null
        private set

    fun open(
        cameraId: String,
        ch: CameraCharacteristics,
        texture: SurfaceTexture,
        onReady: () -> Unit,
        onFail: (String) -> Unit
    ) {
        characteristics = ch
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val raw = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxByOrNull { it.width.toLong() * it.height }!!
        rawSize = raw

        // a 4:3 preview that is not huge
        val previews = map.getOutputSizes(SurfaceTexture::class.java).toList()
        val ratio = raw.width.toFloat() / raw.height
        val pv = previews
            .filter { Math.abs(it.width.toFloat() / it.height - ratio) < 0.02f && it.width <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: previews.filter { it.width <= 1920 }.maxByOrNull { it.width.toLong() * it.height }
            ?: previews.first()
        texture.setDefaultBufferSize(pv.width, pv.height)
        val surface = Surface(texture)
        previewSurface = surface

        val rd = ImageReader.newInstance(raw.width, raw.height, ImageFormat.RAW_SENSOR, 2)
        rd.setOnImageAvailableListener({ r ->
            val img = try { r.acquireNextImage() } catch (e: Exception) { null }
            if (img != null) onImage(img)
        }, handler)
        reader = rd

        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) {
                        camera.close()
                        return
                    }
                    device = camera
                    try {
                        val cfg = SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(surface), OutputConfiguration(rd.surface)),
                            exec,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if (closed) {
                                        s.close()
                                        return
                                    }
                                    session = s
                                    try {
                                        val rq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(surface)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        }
                                        s.setRepeatingRequest(rq.build(), null, handler)
                                        onReady()
                                    } catch (e: Exception) {
                                        onFail("preview: ${e.message}")
                                    }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    onFail("RAW session could not be configured")
                                }
                            }
                        )
                        camera.createCaptureSession(cfg)
                    } catch (e: Exception) {
                        onFail("session: ${e.message}")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    onFail("camera error $error")
                }
            }, handler)
        } catch (e: SecurityException) {
            onFail("camera permission")
        } catch (e: Exception) {
            onFail("open: ${e.message}")
        }
    }

    fun capture(onDone: (Uri?) -> Unit) {
        val cam = device
        val s = session
        val rd = reader
        if (cam == null || s == null || rd == null) {
            onDone(null)
            return
        }
        synchronized(pairLock) {
            pendingImage?.close()
            pendingImage = null
            pendingResult = null
            pendingCb = onDone
        }
        try {
            val rq = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rd.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            s.capture(rq.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    synchronized(pairLock) { pendingResult = result }
                    tryFinish()
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "capture failed: ${failure.reason}")
                    finishWith(null)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "capture error", e)
            finishWith(null)
        }
    }

    private fun onImage(img: Image) {
        synchronized(pairLock) {
            if (pendingCb == null) {
                img.close()
                return
            }
            pendingImage?.close()
            pendingImage = img
        }
        tryFinish()
    }

    private fun finishWith(uri: Uri?) {
        val cb: ((Uri?) -> Unit)?
        synchronized(pairLock) {
            cb = pendingCb
            pendingCb = null
            pendingImage?.close()
            pendingImage = null
            pendingResult = null
        }
        cb?.invoke(uri)
    }

    private fun tryFinish() {
        val img: Image
        val res: TotalCaptureResult
        val cb: ((Uri?) -> Unit)
        synchronized(pairLock) {
            img = pendingImage ?: return
            res = pendingResult ?: return
            cb = pendingCb ?: return
            pendingImage = null
            pendingResult = null
            pendingCb = null
        }
        val ch = characteristics
        saver.execute {
            var uri: Uri? = null
            try {
                uri = writeDng(ch!!, res, img)
            } catch (t: Throwable) {
                Log.e(TAG, "dng write failed", t)
            } finally {
                try { img.close() } catch (_: Exception) {}
            }
            cb(uri)
        }
    }

    private fun writeDng(ch: CameraCharacteristics, res: CaptureResult, img: Image): Uri? {
        val name = "CAMPRO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".dng"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CAMPRO")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        return try {
            val front = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val dng = DngCreator(ch, res)
            try {
                dng.setOrientation(exifOrientation(sensor, front))
                resolver.openOutputStream(uri)!!.use { dng.writeImage(it, img) }
            } finally {
                dng.close()
            }
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, cv, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    fun close() {
        closed = true
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { previewSurface?.release() } catch (_: Exception) {}
        synchronized(pairLock) {
            pendingImage?.close()
            pendingImage = null
            pendingResult = null
            pendingCb = null
        }
        session = null
        device = null
        reader = null
        previewSurface = null
        saver.shutdown()
        exec.shutdown()
        thread.quitSafely()
    }
}
