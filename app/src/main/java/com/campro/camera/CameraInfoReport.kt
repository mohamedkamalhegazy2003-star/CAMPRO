package com.campro.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build

/** Human-readable dump of what the phone's camera HAL really exposes to apps. */
object CameraInfoReport {

    private fun level(v: Int?): String = when (v) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "?"
    }

    private val capNames = linkedMapOf(
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW to "RAW",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE to "BURST_CAPTURE",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING to "YUV_REPROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING to "PRIVATE_REPROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR to "MANUAL_SENSOR",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING to "MANUAL_POST_PROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS to "READ_SENSOR_SETTINGS",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA to "LOGICAL_MULTI_CAMERA",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR to "ULTRA_HIGH_RES_SENSOR",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING to "REMOSAIC_REPROCESSING"
    )

    fun build(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("${Build.MANUFACTURER} ${Build.MODEL}  (Android ${Build.VERSION.RELEASE}, ${Build.HARDWARE})\n")
        var anyRaw = false
        var worstLevel: Int? = null
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    else -> "ext"
                }
                val lv = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val pa = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
                val jpeg = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
                val raw = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
                if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) && raw != null &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                ) anyRaw = true
                if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK && lv != null) {
                    if (worstLevel == null || lv == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) worstLevel = lv
                }
                sb.append("\nCamera $id ($facing)\n")
                sb.append("  hardware level: ${level(lv)}\n")
                if (pa != null) sb.append("  sensor array: ${pa.width}x${pa.height}\n")
                if (focal != null) sb.append("  focal length: ${focal} mm\n")
                if (jpeg != null) sb.append("  max JPEG: ${jpeg.width}x${jpeg.height}\n")
                sb.append("  RAW size: ${raw?.let { "${it.width}x${it.height}" } ?: "not offered"}\n")
                val have = capNames.filterKeys { caps.contains(it) }.values
                sb.append("  capabilities: ${if (have.isEmpty()) "-" else have.joinToString(", ")}\n")
            }
        } catch (e: Exception) {
            sb.append("\n(could not read cameras: ${e.message})\n")
        }
        sb.append("\n")
        if (anyRaw) {
            sb.append("RAW (DNG): AVAILABLE ✓  —  pick the RAW mode in the bar.\nالـ RAW متاح ✓")
        } else {
            sb.append(
                "RAW (DNG): not offered to apps on this phone.\n" +
                    "This is what Camera-Patcher changes in the phone's camera library (needs root) — " +
                    "see docs/CAMERA_PATCHER_A35.md in the project.\n" +
                    "الـ RAW غير متاح للتطبيقات حاليًا"
            )
        }
        if (worstLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) {
            sb.append("\nHardware level is LIMITED (Camera-Patcher can raise it to FULL).")
        }
        return sb.toString()
    }
}
