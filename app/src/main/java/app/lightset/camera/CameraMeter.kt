package app.lightset.camera

import android.annotation.SuppressLint
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
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import app.lightset.color.CameraColorModel
import app.lightset.color.Matrix3
import app.lightset.color.ProcessedColorModel
import app.lightset.color.Xy
import app.lightset.color.cctDuv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reads the colour of the light falling on a white/grey card at the centre of the
 * frame. Prefers RAW sensor data; falls back to processed YUV with fixed daylight
 * white balance on cameras that don't expose RAW.
 */
class CameraMeter(context: Context) {
    data class Info(
        val cameraId: String,
        val source: MeterSource,
        val previewSize: Size,
        val description: String,
    )

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera-meter").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val lock = Mutex()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var images = Channel<Image>(Channel.UNLIMITED)

    private lateinit var chars: CameraCharacteristics
    private var colorModel: CameraColorModel? = null
    private var cfa = CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB

    var info: Info? = null
        private set

    /** Exposure compensation in the camera's steps; adjusted when readings clip or are dark. */
    private var evSteps = 0

    /** While locked, exposure stays fixed so samples can be compared or subtracted. */
    private var aeLocked = false

    /** Width and height of the sampled centre box, as a fraction of the frame. */
    @Volatile
    var patch = DEFAULT_PATCH
        private set

    /** Mean linear camera RGB of the centre patch (sensor-native for RAW). */
    class Sample(val rgb: DoubleArray, val level: Double, val warning: ExposureWarning)

    /** Picks a camera and preview size without opening anything. */
    fun probe(): Info {
        info?.let { return it }
        val backIds = manager.cameraIdList.filter {
            manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        }
        require(backIds.isNotEmpty()) { "No rear camera found" }
        val rawId = backIds.firstOrNull { rawUsable(manager.getCameraCharacteristics(it)) }
        val id = rawId ?: backIds.first()
        chars = manager.getCameraCharacteristics(id)
        val source = if (rawId != null) MeterSource.RAW else MeterSource.PROCESSED
        if (source == MeterSource.RAW) {
            colorModel = buildColorModel(chars)
            cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)!!
        }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val preview = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1920 && it.height <= 1440 }
            .sortedByDescending { it.width * it.height }
            .let { sizes -> sizes.firstOrNull { it.width * 3 == it.height * 4 } ?: sizes.first() }
        val description = when (source) {
            MeterSource.RAW -> "RAW sensor data, camera $id"
            else -> "No RAW on this phone — processed image (approximate absolute values, fine for matching)"
        }
        return Info(id, source, preview, description).also { info = it }
    }

    suspend fun open(texture: SurfaceTexture) = lock.withLock {
        closeLocked()
        aeLocked = false
        val info = probe()
        texture.setDefaultBufferSize(info.previewSize.width, info.previewSize.height)
        val preview = Surface(texture).also { previewSurface = it }

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val newReader = when (info.source) {
            MeterSource.RAW -> {
                val size = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width * it.height }
                ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 4)
            }
            else -> {
                val size = map.getOutputSizes(ImageFormat.YUV_420_888)
                    .filter { it.width <= 1280 }
                    .maxBy { it.width * it.height }
                ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)
            }
        }
        images = Channel(Channel.UNLIMITED)
        val channel = images
        newReader.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.let { img -> if (channel.trySend(img).isFailure) img.close() }
        }, handler)
        reader = newReader

        val cam = openDevice(info.cameraId)
        device = cam
        val s = createSession(cam, listOf(preview, newReader.surface))
        session = s
        s.setRepeatingRequest(baseRequest(cam, preview).build(), null, handler)
    }

    fun close() {
        // Closing must not wait for an in-flight measurement; the measurement
        // fails with a camera error and the caller ignores it.
        closeLocked()
    }

    /**
     * Average [frames] captures of the centre patch into one reading. Unless exposure is
     * locked, a patch that clips or sits outside [LOW_LEVEL]..[HIGH_LEVEL] gets exposure
     * compensation moved straight to what should put it at [TARGET_LEVEL], and the
     * reading is retaken. RAW is linear, so the change needed is log2(target / level).
     */
    suspend fun measure(frames: Int = 3): Reading = lock.withLock {
        var r = measureLocked(frames)
        if (aeLocked) return@withLock r
        val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: return@withLock r
        repeat(4) {
            val ev = when {
                // A clipped patch understates its level; step well down and look again.
                r.warning == ExposureWarning.CLIPPED -> -2.0
                r.level < LOW_LEVEL || r.level > HIGH_LEVEL ->
                    log2(TARGET_LEVEL / r.level.coerceAtLeast(1e-4)).coerceIn(-4.0, 4.0)
                else -> return@withLock r
            }
            val next = (evSteps + (ev / step).roundToInt()).let { e -> range?.let { e.coerceIn(it.lower, it.upper) } ?: e }
            if (next == evSteps) return@withLock r
            val prev = evSteps
            val before = r
            applyEv(next)
            Log.d(TAG, "exposure %+.1f EV for level %.3f: compensation now %.1f EV".format(ev, before.level, evSteps * step))
            r = measureLocked(frames)
            // At its exposure limit the camera ignores more compensation; undo it rather
            // than leave it raised to clip the next bright scene.
            if (next > prev && r.warning != ExposureWarning.CLIPPED && r.level < before.level * 1.2) {
                applyEv(prev)
                Log.d(TAG, "exposure at its limit; compensation back to %.1f EV".format(evSteps * step))
                return@withLock before
            }
        }
        r
    }

    private suspend fun applyEv(steps: Int) {
        evSteps = steps
        val s = checkNotNull(session)
        s.setRepeatingRequest(baseRequest(checkNotNull(device), checkNotNull(previewSurface)).build(), null, handler)
        kotlinx.coroutines.delay(700)
    }

    /** Locks or unlocks auto-exposure at its current values. */
    suspend fun setExposureLock(locked: Boolean) = lock.withLock {
        aeLocked = locked
        val s = session ?: return@withLock
        s.setRepeatingRequest(baseRequest(checkNotNull(device), checkNotNull(previewSurface)).build(), null, handler)
        Log.d(TAG, "exposure ${if (locked) "locked" else "unlocked"}")
    }

    /** Resizes the sampled box; exposure is metered on the new box from now on. */
    suspend fun setPatch(fraction: Float) = lock.withLock {
        patch = fraction.coerceIn(MIN_PATCH, MAX_PATCH)
        val s = session ?: return@withLock
        s.setRepeatingRequest(baseRequest(checkNotNull(device), checkNotNull(previewSurface)).build(), null, handler)
    }

    /** Raw patch sample, for subtracting one light condition from another. */
    suspend fun sample(frames: Int = 4): Sample = lock.withLock { sampleLocked(frames) }

    fun toReading(rgb: DoubleArray, level: Double, warning: ExposureWarning): Reading {
        val source = checkNotNull(info).source
        val xy: Xy = when (source) {
            MeterSource.RAW -> colorModel!!.toXy(rgb)
            else -> ProcessedColorModel.toXy(rgb)
        }
        return Reading(xy, cctDuv(xy), source, level, warning)
    }

    private suspend fun measureLocked(frames: Int): Reading {
        val smp = sampleLocked(frames)
        return toReading(smp.rgb, smp.level, smp.warning).also {
            Log.d(TAG, "reading frames=$frames ev=$evSteps sumRgb=${smp.rgb.joinToString { "%.5f".format(it) }} -> $it")
        }
    }

    private suspend fun sampleLocked(frames: Int): Sample {
        val cam = checkNotNull(device) { "Camera not open" }
        val s = checkNotNull(session)
        val r = checkNotNull(reader)
        val preview = checkNotNull(previewSurface)
        val info = checkNotNull(info)
        val request = baseRequest(cam, preview).apply { addTarget(r.surface) }.build()

        val sum = DoubleArray(3)
        var level = 0.0
        var clipped = false
        repeat(frames) {
            val (image, result) = captureFrame(s, request)
            val patch = try {
                withContext(Dispatchers.Default) {
                    when (info.source) {
                        MeterSource.RAW -> analyseRaw(image, result)
                        else -> analyseYuv(image)
                    }
                }
            } finally {
                image.close()
            }
            for (i in 0..2) sum[i] += patch.rgb[i]
            level = max(level, patch.level)
            clipped = clipped || patch.clipped
        }
        val warning = when {
            clipped -> ExposureWarning.CLIPPED
            level < 0.02 -> ExposureWarning.TOO_DARK
            else -> ExposureWarning.NONE
        }
        return Sample(DoubleArray(3) { sum[it] / frames }, level, warning)
    }

    fun release() {
        close()
        thread.quitSafely()
    }

    private fun closeLocked() {
        session?.close()
        session = null
        device?.close()
        device = null
        reader?.close()
        reader = null
        previewSurface?.release()
        previewSurface = null
        images.close()
    }

    private fun baseRequest(cam: CameraDevice, preview: Surface): CaptureRequest.Builder =
        cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            // The processed fallback depends on a fixed white balance. RAW doesn't,
            // and auto WB gives us the camera's own neutral estimate to log.
            set(
                CaptureRequest.CONTROL_AWB_MODE,
                if (info?.source == MeterSource.RAW) CameraMetadata.CONTROL_AWB_MODE_AUTO
                else CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            )
            set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            // Meter exposure on the sampled box only, not the whole frame, so a bright
            // card against a dark room isn't blown out.
            if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { a ->
                    val w = (a.width() * patch).toInt()
                    val h = (a.height() * patch).toInt()
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(
                        MeteringRectangle(a.left + (a.width() - w) / 2, a.top + (a.height() - h) / 2, w, h,
                            MeteringRectangle.METERING_WEIGHT_MAX)
                    ))
                }
            }
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps)
            set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
        }

    private suspend fun captureFrame(
        s: CameraCaptureSession,
        request: CaptureRequest,
    ): Pair<Image, TotalCaptureResult> = withTimeout(5000) {
        val done = CompletableDeferred<TotalCaptureResult>()
        s.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                done.complete(result)
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                done.completeExceptionally(IllegalStateException("Capture failed (${failure.reason})"))
            }
        }, handler)
        val result = done.await()
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
        var image = images.receive()
        while (ts != null && image.timestamp != ts) {
            image.close()
            image = images.receive()
        }
        image to result
    }

    private class Patch(val rgb: DoubleArray, val level: Double, val clipped: Boolean)

    private fun analyseRaw(image: Image, result: TotalCaptureResult): Patch {
        val plane = image.planes[0]
        val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.map { it.toDouble() }
            ?: chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)!!.let { p ->
                listOf(p.getOffsetForIndex(0, 0), p.getOffsetForIndex(1, 0),
                    p.getOffsetForIndex(0, 1), p.getOffsetForIndex(1, 1)).map { it.toDouble() }
            }
        val white = (result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)!!).toDouble()

        // Channel (0=R, 1=G, 2=B) at each 2×2 CFA position, row-major.
        val layout = when (cfa) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> intArrayOf(0, 1, 1, 2)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> intArrayOf(1, 0, 2, 1)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> intArrayOf(1, 2, 0, 1)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> intArrayOf(2, 1, 1, 0)
            else -> error("Unsupported colour filter arrangement $cfa")
        }

        val w = image.width
        val h = image.height
        val lo = 0.5 - patch / 2
        val hi = 0.5 + patch / 2
        val x0 = (w * lo).toInt() and 1.inv()
        val x1 = (w * hi).toInt() and 1.inv()
        val y0 = (h * lo).toInt() and 1.inv()
        val y1 = (h * hi).toInt() and 1.inv()
        // Sample roughly 200×200 CFA blocks regardless of sensor resolution.
        val step = max(2, ((x1 - x0) / 200) and 1.inv())

        val sum = DoubleArray(3)
        val count = IntArray(3)
        val posSum = DoubleArray(4)
        val posCount = IntArray(4)
        var saturated = 0
        var samples = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                for (pos in 0..3) {
                    val px = x + (pos and 1)
                    val py = y + (pos shr 1)
                    val raw = buf.getShort(py * rowStride + px * pixelStride).toInt() and 0xFFFF
                    if (raw >= white * 0.98) saturated++
                    samples++
                    val ch = layout[pos]
                    sum[ch] += raw - black[pos]
                    count[ch]++
                    posSum[pos] += raw - black[pos]
                    posCount[pos]++
                }
                x += step
            }
            y += step
        }
        val range = white - black.average()
        val mean = DoubleArray(3) { sum[it] / count[it] / range }
        val pos = DoubleArray(4) { p -> posSum[p] / posCount[p] / range }
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        Log.d(TAG, "raw ${w}x$h cfa=$cfa black=$black white=$white step=$step exp=$exposureNs iso=$iso " +
            "pos(r0c0,r0c1,r1c0,r1c1)=${pos.joinToString { "%.4f".format(it) }} " +
            "rgb=${mean.joinToString { "%.4f".format(it) }} " +
            "neutral=${result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.joinToString()} " +
            "awbGains=${result.get(CaptureResult.COLOR_CORRECTION_GAINS)}")
        return Patch(mean, mean.max(), saturated > samples / 50)
    }

    private fun analyseYuv(image: Image): Patch {
        val (yP, uP, vP) = image.planes
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val w = image.width
        val h = image.height
        val lo = 0.5 - patch / 2
        val hi = 0.5 + patch / 2
        val x0 = (w * lo).toInt() and 1.inv()
        val x1 = (w * hi).toInt()
        val y0 = (h * lo).toInt() and 1.inv()
        val y1 = (h * hi).toInt()
        // Big boxes cover many pixels; every other row and column is plenty.
        val step = if (patch > 0.4f) 4 else 2

        val sum = DoubleArray(3)
        var n = 0
        var clippedSamples = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val yy = (yBuf.get(y * yP.rowStride + x * yP.pixelStride).toInt() and 0xFF).toDouble()
                val ci = (y / 2) * uP.rowStride + (x / 2) * uP.pixelStride
                val cv = (y / 2) * vP.rowStride + (x / 2) * vP.pixelStride
                val u = (uBuf.get(ci).toInt() and 0xFF) - 128.0
                val v = (vBuf.get(cv).toInt() and 0xFF) - 128.0
                // JFIF full-range BT.601, as Camera2 YUV output uses.
                val rgb = doubleArrayOf(
                    yy + 1.402 * v,
                    yy - 0.344136 * u - 0.714136 * v,
                    yy + 1.772 * u,
                )
                for (i in 0..2) {
                    val c = rgb[i].coerceIn(0.0, 255.0)
                    if (c >= 250) clippedSamples++
                    sum[i] += ProcessedColorModel.srgbToLinear(c / 255.0)
                }
                n++
                x += step
            }
            y += step
        }
        val mean = DoubleArray(3) { sum[it] / n }
        return Patch(mean, mean.max(), clippedSamples > n * 3 / 50)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(id: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = cont.resume(camera)
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error"))
                }
            }, handler)
        }

    private suspend fun createSession(cam: CameraDevice, outputs: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs.map { OutputConfiguration(it) },
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
                    override fun onConfigureFailed(session: CameraCaptureSession) =
                        cont.resumeWithException(IllegalStateException("Camera session configuration failed"))
                },
            )
            cam.createCaptureSession(config)
        }

    companion object {
        private const val TAG = "LightSet"
        const val DEFAULT_PATCH = 0.2f
        const val MIN_PATCH = 0.06f
        const val MAX_PATCH = 0.8f

        /** Brightest-channel level (fraction of full scale) exposure aims for, and the band left alone. */
        private const val TARGET_LEVEL = 0.45
        private const val LOW_LEVEL = 0.15
        private const val HIGH_LEVEL = 0.85

        private fun rawUsable(c: CameraCharacteristics): Boolean {
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) return false
            val cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: return false
            if (cfa > CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR) return false
            return c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null &&
                c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) != null
        }

        private fun ColorSpaceTransform.toMatrix(): Matrix3 =
            Matrix3(DoubleArray(9) { i -> getElement(i % 3, i / 3).toDouble() })

        private fun buildColorModel(c: CameraCharacteristics): CameraColorModel {
            val k1 = CameraColorModel.exifIlluminantKelvin(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)!!)
                ?: 2856.0
            val cm2 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toMatrix()
            val k2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
                ?.let { CameraColorModel.exifIlluminantKelvin(it.toInt()) }
            val hasSecond = cm2 != null && k2 != null && k2 != k1
            return CameraColorModel(
                colorMatrix1 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)!!.toMatrix(),
                calibration1 = c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.toMatrix()
                    ?: Matrix3.IDENTITY,
                illuminant1Kelvin = k1,
                colorMatrix2 = if (hasSecond) cm2 else null,
                calibration2 = if (hasSecond) {
                    c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.toMatrix() ?: Matrix3.IDENTITY
                } else null,
                illuminant2Kelvin = if (hasSecond) k2 else null,
            )
        }
    }
}
