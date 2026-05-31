package com.fivelidz.compositecamera.pro

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import android.util.Size

/**
 * Probes the rear camera for the capabilities we need:
 *  - manual_sensor       (set exposure time + ISO)
 *  - manual_post_processing (lock AWB, set focus distance)
 *
 * If a phone doesn't support these, we fall back to auto-exposure + AF lock + AE compensation,
 * which still gives a degraded bracket but doesn't refuse to run.
 */
data class CameraCaps(
    val cameraId: String,
    val exposureRangeNs: Range<Long>,
    val isoRange: Range<Int>,
    val minFocusDistanceDiopters: Float, // 0f = infinity, larger = closer
    val supportsManualSensor: Boolean,
    val supportsManualPostProc: Boolean,
    val supportsRaw: Boolean,
    val activeArraySize: android.graphics.Rect,
    val largestJpegSize: Size,
    val sensorOrientation: Int,
) {
    companion object {
        fun probe(ctx: Context): CameraCaps? {
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // Pick the highest-resolution rear-facing camera.
            val ids = mgr.cameraIdList
            var bestId: String? = null
            var bestPixels = 0L
            for (id in ids) {
                val ch = mgr.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(android.graphics.ImageFormat.JPEG) ?: continue
                val largest = sizes.maxByOrNull { it.width.toLong() * it.height } ?: continue
                val pixels = largest.width.toLong() * largest.height
                if (pixels > bestPixels) {
                    bestPixels = pixels
                    bestId = id
                }
            }
            val id = bestId ?: return null
            val ch = mgr.getCameraCharacteristics(id)
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val manualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val manualPostProc = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
            val raw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

            val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: Range(100_000L, 100_000_000L) // 0.1 ms – 100 ms fallback
            val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?: Range(100, 1600)
            val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: android.graphics.Rect(0, 0, 4000, 3000)
            val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.ImageFormat.JPEG)!!
            val largest = sizes.maxByOrNull { it.width.toLong() * it.height }!!
            val orient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            return CameraCaps(
                cameraId = id,
                exposureRangeNs = expRange,
                isoRange = isoRange,
                minFocusDistanceDiopters = minFocus,
                supportsManualSensor = manualSensor,
                supportsManualPostProc = manualPostProc,
                supportsRaw = raw,
                activeArraySize = active,
                largestJpegSize = largest,
                sensorOrientation = orient,
            )
        }
    }
}
