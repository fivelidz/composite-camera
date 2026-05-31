package com.fivelidz.compositecamera.pro

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The actual Camera2 capture engine. Given a [CameraCaps] and a list of [CaptureStep]s,
 * it opens the camera, runs a metering frame to derive baseline (exposure, iso) values,
 * then fires the bracket burst and emits each captured JPEG byte array back through [onFrame].
 *
 * Threading: Camera2 callbacks run on a dedicated HandlerThread.
 *
 * All public functions are main-thread safe. Errors propagate via the suspending [run].
 */
class BracketCapture(
    private val ctx: Context,
    private val caps: CameraCaps,
) {
    private val tag = "BracketCapture"

    private val handlerThread = HandlerThread("camera-bg").apply { start() }
    private val bgHandler = Handler(handlerThread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var previewSurface: Surface? = null

    /**
     * Run the full bracket. Returns when every frame has been captured (and onFrame called for each).
     *
     * @param previewSurface  Surface to keep the preview alive during capture (can be null for blind shot).
     * @param onMetered       Called once with (exposureNs, iso) after the auto-metering pass.
     *                        The caller uses this to build the bracket plan dynamically.
     * @param planBuilder     Given the metered exposure+ISO, produce the actual CaptureStep list.
     * @param onFrame         For each captured frame: (stepIndex, total, step, jpegBytes).
     */
    suspend fun run(
        previewSurface: Surface?,
        onMetered: (Long, Int) -> Unit,
        planBuilder: (meteredExposureNs: Long, meteredIso: Int) -> List<CaptureStep>,
        onFrame: suspend (Int, Int, CaptureStep, ByteArray) -> Unit,
    ) {
        this.previewSurface = previewSurface

        // 1. JPEG ImageReader at the largest size — this is the "capture" output.
        val jr = ImageReader.newInstance(
            caps.largestJpegSize.width,
            caps.largestJpegSize.height,
            ImageFormat.JPEG,
            /* maxImages = */ 4,
        )
        jpegReader = jr

        // 2. Open camera
        device = openCamera(caps.cameraId, bgHandler)

        // 3. Build the capture session with both preview + jpeg surfaces.
        val outputs = mutableListOf<Surface>(jr.surface)
        previewSurface?.let { outputs += it }
        session = createSession(device!!, outputs, bgHandler)

        try {
            // 4. Auto-metering pass — kick the AE+AF, then take one auto-mode capture to read its
            //    resulting SENSOR_EXPOSURE_TIME and SENSOR_SENSITIVITY values.
            val (meteredNs, meteredIso) = meterAuto()
            onMetered(meteredNs, meteredIso)

            // 5. Build the bracket plan.
            val steps = planBuilder(meteredNs, meteredIso)
            Log.i(tag, "Bracket plan: ${steps.size} steps")

            // 6. Fire the burst, one step at a time so we can wait for the focus lens to settle
            //    before each focus-bucket transition. Within a single focus-bucket we burst.
            captureBurst(steps, onFrame)
        } finally {
            try { session?.close() } catch (_: Throwable) {}
            try { device?.close()  } catch (_: Throwable) {}
            try { jpegReader?.close() } catch (_: Throwable) {}
        }
    }

    fun release() {
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close()  } catch (_: Throwable) {}
        try { jpegReader?.close() } catch (_: Throwable) {}
        handlerThread.quitSafely()
    }

    // -------- internals --------

    @Suppress("MissingPermission")
    private suspend fun openCamera(id: String, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { cont ->
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { cont.resume(c) }
                override fun onDisconnected(c: CameraDevice) {
                    c.close()
                    if (cont.isActive) cont.resumeWithException(RuntimeException("Camera disconnected"))
                }
                override fun onError(c: CameraDevice, error: Int) {
                    c.close()
                    if (cont.isActive) cont.resumeWithException(RuntimeException("Camera error $error"))
                }
            }, handler)
        }

    private suspend fun createSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        @Suppress("DEPRECATION")
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { cont.resume(s) }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(RuntimeException("Capture session config failed"))
            }
        }, handler)
    }

    /**
     * Fires a single auto-mode capture and reads back the metered (exposureNs, iso).
     * Doesn't actually save the JPEG — we just want the resulting CaptureResult metadata.
     */
    private suspend fun meterAuto(): Pair<Long, Int> = suspendCancellableCoroutine { cont ->
        val session = this.session!!
        val device = this.device!!
        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            previewSurface?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        }.build()

        // Repeating request to keep AE running, then capture one frame to read it.
        try {
            session.setRepeatingRequest(req, null, bgHandler)
        } catch (t: Throwable) {
            if (cont.isActive) cont.resumeWithException(t); return@suspendCancellableCoroutine
        }

        // Give AE ~600ms to settle, then snapshot the metadata.
        bgHandler.postDelayed({
            try {
                session.capture(req, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        sess: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 33_000_000L
                        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 400
                        try { session.stopRepeating() } catch (_: Throwable) {}
                        if (cont.isActive) cont.resume(exp to iso)
                    }
                    override fun onCaptureFailed(
                        sess: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        if (cont.isActive) cont.resume(33_000_000L to 400)
                    }
                }, bgHandler)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        }, 600)
    }

    /**
     * Captures every step in [steps] sequentially, calling [onFrame] for each completed JPEG.
     *
     * We rely on the JPEG ImageReader's onImageAvailable listener, paired with a per-request
     * counter, to associate captured images back to their step.
     */
    private suspend fun captureBurst(
        steps: List<CaptureStep>,
        onFrame: suspend (Int, Int, CaptureStep, ByteArray) -> Unit,
    ) {
        val session = this.session!!
        val device = this.device!!
        val jr = this.jpegReader!!

        // We'll use a small queue: imageReader emits images in capture order.
        val images = java.util.concurrent.ArrayBlockingQueue<ByteArray>(steps.size + 4)
        jr.setOnImageAvailableListener({ reader ->
            val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val buf = plane.buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                images.put(bytes)
            } finally { img.close() }
        }, bgHandler)

        for ((index, step) in steps.withIndex()) {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jr.surface)
                previewSurface?.let { addTarget(it) }

                if (caps.supportsManualSensor) {
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, step.exposureNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, step.iso)
                } else {
                    // Best-effort fallback: use AE compensation
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    // Translate EV-step to AE-comp units. Most phones use 1/6 EV per step.
                    val approxEv = ((step.exposureBucket - 1)) * 6
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, approxEv)
                }

                if (caps.supportsManualPostProc) {
                    set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, step.focusDiopters)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                }

                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, caps.sensorOrientation)
            }.build()

            // Wait briefly between focus buckets so the lens settles.
            if (index > 0 && steps[index].focusBucket != steps[index - 1].focusBucket) {
                Thread.sleep(180)
            }

            captureSingle(session, req)

            // Block (with timeout) for the image to come back.
            val bytes = images.poll(4, java.util.concurrent.TimeUnit.SECONDS)
                ?: error("Timed out waiting for image for step $index")
            onFrame(index, steps.size, step, bytes)
        }
    }

    private suspend fun captureSingle(
        session: CameraCaptureSession,
        req: CaptureRequest,
    ): TotalCaptureResult = suspendCancellableCoroutine { cont ->
        try {
            session.capture(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    sess: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    if (cont.isActive) cont.resume(result)
                }
                override fun onCaptureFailed(
                    sess: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    if (cont.isActive) cont.resumeWithException(
                        RuntimeException("capture failed: reason=${failure.reason}")
                    )
                }
            }, bgHandler)
        } catch (t: Throwable) {
            if (cont.isActive) cont.resumeWithException(t)
        }
    }
}
