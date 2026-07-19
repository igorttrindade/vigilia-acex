package com.vigilia.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import java.nio.ByteBuffer
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.vigilia.app.domain.model.FatigueMetrics
import com.vigilia.app.lighting.LightingMode
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size as CvSize
import org.opencv.imgproc.Imgproc

/**
 * Extracts face metrics from camera frames using MediaPipe FaceLandmarker.
 *
 * Uses 478-point face mesh with BlendShape coefficients:
 * - [eyeBlinkLeft] / [eyeBlinkRight]: 0=open, 1=closed — inverted to produce eye-open probability
 * - [jawOpen]: 0=closed, 1=fully open — used directly as mouth-open probability for yawn detection
 *
 * The FaceLandmarker model is initialized asynchronously on the main thread (which has a Looper,
 * required by MediaPipe internally) via [Handler.post]. This avoids blocking the calling thread
 * while still satisfying MediaPipe's threading requirements. Frames arriving before initialization
 * completes are emitted as NO_FACE metrics.
 *
 * @param context Used to load the bundled model asset.
 * @param onMetricsAvailable Callback emitted for every processed frame.
 */
class FaceAnalyzer(
    private val context: Context,
    private val onMetricsAvailable: (FatigueMetrics) -> Unit,
    private val lightingModeProvider: () -> LightingMode = { LightingMode.NORMAL },
) : ImageAnalysis.Analyzer {

    @Volatile private var faceLandmarker: FaceLandmarker? = null
    @Volatile private var closed = false

    // Serializes `analyze()` and `close()` so the native MediaPipe handle cannot be
    // released while a detect() call is in progress on the analysis executor. The
    // executor is single-threaded, so contention here is only between the executor
    // and Main during teardown — Main blocks for at most one detect (~10-30 ms), which
    // is acceptable at Stop.
    private val landmarkerLock = Any()

    // Sticky driver state: center (cx, cy) of the last-selected driver's bounding box in
    // normalized coordinates. Used by [pickDriverIndex] to bias selection toward temporal
    // continuity. `-1f` means "no prior driver" — first frame or after NO_FACE. Reset in
    // [close] and whenever face detection is lost. Only accessed from the analysis executor.
    private var lastDriverCenterX: Float = -1f
    private var lastDriverCenterY: Float = -1f

    // OpenCV reusable Mats — allocate once per analyzer, reuse per frame so CLAHE stays
    // ~10ms instead of the ~25ms it costs when Mats are reallocated. All access is on the
    // single-threaded analysis executor, so no synchronization needed.
    private val opencvReady: Boolean = try {
        OpenCVLoader.initLocal()
    } catch (e: Throwable) {
        Log.w("FaceAnalyzer", "OpenCV init failed — CLAHE will be skipped", e)
        false
    }
    // OpenCV lacks direct RGBA↔YCrCb converters — we go RGBA → RGB → YCrCb and back. All
    // Mats are reused across frames (see comment above).
    private val rgbaMat by lazy { Mat() }
    private val rgbMat by lazy { Mat() }
    private val yuvMat by lazy { Mat() }
    private val yuvChannels by lazy { ArrayList<Mat>(3) }
    private val gammaLutLow by lazy { buildGammaLut(GAMMA_LOW_LIGHT) }
    private val gammaLutDark by lazy { buildGammaLut(GAMMA_DARK) }
    private val clahe by lazy { Imgproc.createCLAHE(CLAHE_CLIP_LIMIT, CvSize(CLAHE_TILE_SIZE, CLAHE_TILE_SIZE)) }

    init {
        // FaceLandmarker.createFromOptions() requires a thread with a Looper; schedule on main.
        // analyze() returns NO_FACE for the few frames that arrive before init completes.
        Handler(Looper.getMainLooper()).post {
            if (closed) return@post
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .build()
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(MAX_FACES)
                    .setOutputFaceBlendshapes(true)
                    .setOutputFacialTransformationMatrixes(true)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .build()
                val lm = FaceLandmarker.createFromOptions(context, options)
                if (closed) lm.close() else faceLandmarker = lm
            } catch (e: Throwable) {
                Log.e("FaceAnalyzer", "FaceLandmarker init failed — face detection disabled", e)
            }
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        try {
            // Fast-path check outside the lock — if the analyzer is already closed we don't
            // need to serialize with close() at all.
            if (closed || faceLandmarker == null) {
                emitMetricsSafely(createNoFaceMetrics())
                return
            }
            try {
                // Serialize with close(): once we're inside this block, close() cannot free
                // the native landmarker until we're done. Re-read faceLandmarker inside the
                // lock because it may have been nulled by close() between the fast-path check
                // and here.
                synchronized(landmarkerLock) {
                    val landmarker = faceLandmarker
                    if (landmarker == null) {
                        emitMetricsSafely(createNoFaceMetrics())
                        return@synchronized
                    }
                    runAnalysis(imageProxy, landmarker)
                }
            } catch (e: Exception) {
                Log.e("FaceAnalyzer", "Detection failed", e)
                emitMetricsSafely(createNoFaceMetrics(0f))
            }
        } finally {
            // Always release the ImageProxy — leaking one exhausts CameraX's buffer pool and
            // stalls the entire analysis stream after ~4-8 frames. Wrapping the close() in
            // try/catch is belt-and-suspenders: close() itself can throw on some devices.
            try { imageProxy.close() } catch (t: Throwable) {
                Log.w("FaceAnalyzer", "imageProxy.close() failed", t)
            }
        }
    }

    /**
     * Emit metrics to the consumer with a safety catch. If the callback lambda (in
     * MonitoringService's frame loop) ever throws, the exception must NOT propagate to the
     * analysis executor thread — a single-threaded executor that dies here silently stops
     * the entire monitoring pipeline until the app is restarted. This is defense-in-depth
     * on top of MonitoringService.startCamera's own callback try/catch.
     */
    private fun emitMetricsSafely(metrics: com.vigilia.app.domain.model.FatigueMetrics) {
        try {
            onMetricsAvailable(metrics)
        } catch (t: Throwable) {
            Log.e("FaceAnalyzer", "onMetricsAvailable callback threw", t)
        }
    }

    private fun runAnalysis(imageProxy: ImageProxy, landmarker: FaceLandmarker) {
            // Read frame luminance from the Y plane before toBitmap() runs — toBitmap may
            // consume buffer positions on some devices. Cheap (~0.3ms) and feeds LightingMonitor.
            val yPlane = imageProxy.planes[0]
            val frameLuminance = computeYPlaneMean(
                yPlane.buffer,
                yPlane.rowStride,
                imageProxy.width,
                imageProxy.height,
            )

            // toBitmap() converts YUV_420_888 → ARGB_8888, avoiding MediaImageBuilder
            // compatibility issues across devices and CameraX versions.
            val rawBitmap = imageProxy.toBitmap()
            val mode = lightingModeProvider()
            val bitmap = if (shouldApplyClahe(mode) && opencvReady) {
                enhance(rawBitmap, gammaFor(mode))
            } else rawBitmap
            val mpImage = BitmapImageBuilder(bitmap).build()
            val imageOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
                .build()

            val result = landmarker.detect(mpImage, imageOptions)
            val blendshapesOpt = result.faceBlendshapes()
            val landmarksList = result.faceLandmarks()
            val matrixesOpt = result.facialTransformationMatrixes()

            // Multi-face driver selection: compute bboxes over stable central landmarks for
            // every returned face and pick the driver via area (with sticky bias). MediaPipe
            // orders results by detection confidence, not by size — the largest face is the
            // one closest to the front camera, which in a vehicle is always the driver.
            val bboxes = landmarksList.mapNotNull { computeBoundingBox(it) }
            val selectedIndex = if (bboxes.size == landmarksList.size) {
                pickDriverIndex(bboxes, lastDriverCenterX, lastDriverCenterY)
            } else {
                // At least one face had too few landmarks — treat all faces as unusable to
                // avoid indexing mismatch across the three parallel collections.
                -1
            }

            // Guard against inconsistent sizes across the three parallel collections.
            val blendshapesSize = if (blendshapesOpt.isPresent) blendshapesOpt.get().size else 0
            val matrixesSize = if (matrixesOpt.isPresent) matrixesOpt.get().size else 0
            val maxUsableIndex = minOf(blendshapesSize, landmarksList.size, matrixesSize) - 1
            val safeIndex = if (selectedIndex in 0..maxUsableIndex) selectedIndex else -1

            val metrics = if (safeIndex >= 0) {
                val shapes = blendshapesOpt.get()[safeIndex]
                val eyeBlinkLeft  = shapes.find { it.categoryName() == "eyeBlinkLeft"  }?.score()
                val eyeBlinkRight = shapes.find { it.categoryName() == "eyeBlinkRight" }?.score()
                val jawOpen       = shapes.find { it.categoryName() == "jawOpen"       }?.score() ?: 0f

                // If either eye blendshape is absent the model is uncertain — treat as no face
                // rather than silently defaulting to 0f (which would be read as "eyes wide open")
                if (eyeBlinkLeft == null || eyeBlinkRight == null) {
                    Log.w("FaceAnalyzer", "Eye blendshapes missing — discarding frame as NO_FACE")
                    lastDriverCenterX = -1f
                    lastDriverCenterY = -1f
                    createNoFaceMetrics(frameLuminance)
                } else {
                    val blendLeft  = (1f - eyeBlinkLeft).coerceIn(0f, 1f)
                    val blendRight = (1f - eyeBlinkRight).coerceIn(0f, 1f)
                    val blendAvg   = (blendLeft + blendRight) / 2f

                    // EAR (Eye Aspect Ratio) uses geometric eyelid distances — immune to lens
                    // reflections that inflate blendshape-based openness for glasses wearers.
                    // combineEyeOpenness() defaults to min(blend, ear) but falls back to
                    // blendshape alone when EAR appears to be a landmark artifact (very low
                    // reading while blendshape says clearly-open). See helper docs.
                    val (earLeft, earRight) = calculateEarOpenness(landmarksList[safeIndex])
                    val finalLeft  = combineEyeOpenness(blendLeft, earLeft)
                    val finalRight = combineEyeOpenness(blendRight, earRight)

                    // Head orientation from MediaPipe's transformation matrix for the selected face.
                    val (headYaw, headPitch) = extractYawPitchDegrees(matrixesOpt, safeIndex)

                    // Update sticky-driver state from the selected bbox center.
                    val bbox = bboxes[safeIndex]
                    lastDriverCenterX = (bbox[0] + bbox[2]) / 2f
                    lastDriverCenterY = (bbox[1] + bbox[3]) / 2f

                    Log.d(
                        "FaceAnalyzer",
                        "faces=${landmarksList.size} sel=$safeIndex " +
                            "blend[L=$blendLeft R=$blendRight avg=$blendAvg] " +
                            "ear[L=$earLeft R=$earRight] " +
                            "final[L=$finalLeft R=$finalRight] " +
                            "jaw=$jawOpen yaw=$headYaw pitch=$headPitch",
                    )
                    FatigueMetrics(
                        leftEyeOpenProbability  = finalLeft,
                        rightEyeOpenProbability = finalRight,
                        mouthOpenProbability    = jawOpen,
                        isFaceDetected          = true,
                        timestampMs             = System.nanoTime() / 1_000_000,
                        headYawDegrees          = headYaw,
                        headPitchDegrees        = headPitch,
                        frameLuminance          = frameLuminance,
                        avgBlendshapeOpen       = blendAvg,
                    )
                }
            } else {
                // No usable face detected — reset sticky state so the next detection starts
                // a fresh tracking session instead of biasing toward a stale center.
                lastDriverCenterX = -1f
                lastDriverCenterY = -1f
                createNoFaceMetrics(frameLuminance)
            }
        onMetricsAvailable(metrics)
    }

    fun close() {
        // Serialize with any in-flight analyze() so the native MediaPipe handle can't be
        // freed while detect() is still running. Main blocks here for at most one detect
        // (~10-30 ms). Also releases OpenCV Mats to avoid native heap growth across
        // Start/Stop cycles.
        synchronized(landmarkerLock) {
            closed = true
            lastDriverCenterX = -1f
            lastDriverCenterY = -1f
            val lm = faceLandmarker
            faceLandmarker = null
            try {
                lm?.close()
            } catch (e: Exception) {
                Log.w("FaceAnalyzer", "FaceLandmarker close failed", e)
            }
            // Release lazily-initialized OpenCV Mats. If enhance() was never called these
            // properties haven't been materialized, but touching them triggers the lazy
            // init which we then immediately release — cost is negligible.
            if (opencvReady) {
                try {
                    rgbaMat.release()
                    rgbMat.release()
                    yuvMat.release()
                    yuvChannels.forEach { it.release() }
                    yuvChannels.clear()
                    gammaLutLow.release()
                    gammaLutDark.release()
                } catch (e: Throwable) {
                    Log.w("FaceAnalyzer", "OpenCV Mat release failed", e)
                }
            }
        }
    }

    /**
     * Boosts contrast on the Y channel with CLAHE, then applies a gamma LUT for extra lift
     * in the dark end of the histogram. Both steps run in-place on reusable Mats — the
     * output Bitmap is a copy back so MediaPipe can consume it.
     */
    private fun enhance(bitmap: Bitmap, gamma: Float): Bitmap {
        return try {
            // OpenCV doesn't expose COLOR_RGBA2YCrCb / COLOR_YCrCb2RGBA — go through RGB.
            // Utils.bitmapToMat writes RGBA (CV_8UC4); drop alpha, convert to YCrCb, split,
            // enhance Y in-place, merge back, and convert to RGBA before matToBitmap.
            Utils.bitmapToMat(bitmap, rgbaMat)
            Imgproc.cvtColor(rgbaMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgbMat, yuvMat, Imgproc.COLOR_RGB2YCrCb)
            yuvChannels.clear()
            Core.split(yuvMat, yuvChannels)
            val yChannel = yuvChannels[0]
            clahe.apply(yChannel, yChannel)
            Core.LUT(yChannel, if (gamma == GAMMA_DARK) gammaLutDark else gammaLutLow, yChannel)
            Core.merge(yuvChannels, yuvMat)
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YCrCb2RGB)
            Imgproc.cvtColor(rgbMat, rgbaMat, Imgproc.COLOR_RGB2RGBA)
            val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbaMat, out)
            out
        } catch (e: Throwable) {
            Log.w("FaceAnalyzer", "CLAHE enhance failed — falling back to raw frame", e)
            bitmap
        }
    }

    private fun buildGammaLut(gamma: Float): Mat {
        val lut = Mat(1, 256, CvType.CV_8U)
        val data = ByteArray(256)
        val invGamma = 1.0 / gamma
        for (i in 0..255) {
            data[i] = (Math.pow(i / 255.0, invGamma) * 255.0).toInt().coerceIn(0, 255).toByte()
        }
        lut.put(0, 0, data)
        return lut
    }

    private fun createNoFaceMetrics(frameLuminance: Float = 0f) = FatigueMetrics(
        leftEyeOpenProbability  = 0f,
        rightEyeOpenProbability = 0f,
        mouthOpenProbability    = 0f,
        isFaceDetected          = false,
        timestampMs             = System.nanoTime() / 1_000_000,
        frameLuminance          = frameLuminance,
    )

    /**
     * Computes eye openness [0,1] for both eyes using Eye Aspect Ratio (EAR).
     *
     * EAR = vertical_eyelid_gap / horizontal_eye_width. Unlike blendshapes it is
     * purely geometric, so lens reflections from glasses do not corrupt the result.
     *
     * Landmark indices (MediaPipe 478-point mesh):
     *   Left  — outer:33  inner:133  upper:159  lower:145
     *   Right — outer:263 inner:362  upper:386  lower:374
     */
    private fun calculateEarOpenness(landmarks: List<NormalizedLandmark>): Pair<Float, Float> {
        if (landmarks.size < 478) return Pair(1f, 1f)

        fun ear(outerIdx: Int, innerIdx: Int, upperIdx: Int, lowerIdx: Int): Float {
            val vertical   = abs(landmarks[upperIdx].y() - landmarks[lowerIdx].y())
            val horizontal = abs(landmarks[outerIdx].x() - landmarks[innerIdx].x())
            return if (horizontal < 0.001f) 0f else vertical / horizontal
        }

        val leftEar  = ear(33, 133, 159, 145)
        val rightEar = ear(263, 362, 386, 374)

        return Pair(
            (leftEar  / EAR_OPEN_REFERENCE).coerceIn(0f, 1f),
            (rightEar / EAR_OPEN_REFERENCE).coerceIn(0f, 1f),
        )
    }

    /**
     * Decomposes MediaPipe's 4×4 facial transformation matrix into head yaw and pitch
     * (degrees, frontal = 0). Roll is not used downstream so it is skipped.
     *
     * MediaPipe stores the matrix column-major (16-element FloatArray), so element access is
     * m[col*4 + row]. Uses a Y-X-Z Euler decomposition (yaw around Y, pitch around X):
     *   pitch = asin(-R[1][2]) = asin(-m[9])
     *   yaw   = atan2(R[0][2], R[2][2]) = atan2(m[8], m[10])
     *
     * Sign convention (verified in-device with a debug log): positive yaw = head turned
     * to the driver's right; positive pitch = head tilted up. If a device reports mirrored
     * signs due to camera orientation, flip here. The downstream scorer only uses the
     * absolute magnitudes for the yaw check, so mirror errors on yaw are harmless.
     */
    private fun extractYawPitchDegrees(
        matrixesOpt: java.util.Optional<List<FloatArray>>,
        index: Int,
    ): Pair<Float, Float> {
        if (!matrixesOpt.isPresent) return Pair(0f, 0f)
        val list = matrixesOpt.get()
        if (index < 0 || index >= list.size) return Pair(0f, 0f)
        return decomposeYawPitchFromMatrix(list[index])
    }

    companion object {
        private const val MODEL_ASSET_PATH = "face_landmarker.task"

        // EAR for a fully-open eye in normalized landmark coordinates.
        // Used to map raw EAR → [0,1] openness probability.
        private const val EAR_OPEN_REFERENCE = 0.28f

        // EAR jitter guard. Real blinks push EAR toward 0 (eyelids touching), but so do
        // landmark errors — upper/lower eye landmarks can collapse or the horizontal
        // measurement can fall outside the eye entirely, producing spurious near-zero EAR
        // even when the eye is clearly open. When blendshape confidently reports "open"
        // (>= BLEND_CONFIDENT_OPEN) but EAR is below EAR_MIN_TRUSTED, we treat the EAR
        // reading as a landmark artifact and fall back to blendshape alone. Real blinks
        // still work because blendshape ALSO drops during a real blink, so this branch
        // does not fire and the min() path (glasses reflection protection) still applies.
        //
        // Threshold set at 0.05: real EAR during blink peak is typically 0.03-0.15 for
        // a fraction of the closure. Landmark errors produce EAR ≤ 0.03 sustained. A
        // higher threshold (originally 0.15) blocked the descending edge of real blinks
        // and caused detection lag — field-reported blink misses. 0.05 separates the two
        // populations without swallowing blink mid-frames.
        const val EAR_MIN_TRUSTED = 0.05f
        const val BLEND_CONFIDENT_OPEN = 0.30f

        // 8×8 grid ≈ 64 samples — enough for a stable mean, cheap enough to run per frame.
        private const val LUMINANCE_SAMPLES_PER_AXIS = 8

        // Maximum faces MediaPipe returns per frame. Covers driver + up to 3 back-seat
        // passengers. Chosen over 2 because MediaPipe orders results by detection confidence
        // (not by size); with only 2 slots a driver with poorer lighting/angle than 2 rear
        // passengers could be excluded from the returned set. With 4 slots the driver is
        // reliably present and the bbox-area heuristic selects them correctly.
        const val MAX_FACES = 4

        // Sticky-driver bias: on multi-face frames, prefer a candidate whose bbox center is
        // within this distance (normalized coordinates) of the previously-selected driver AND
        // whose area is at least STICKY_AREA_RATIO of the largest candidate. Prevents flicker
        // when a passenger leaning forward momentarily equalizes area with the driver.
        const val STICKY_CENTER_TOLERANCE = 0.15f
        const val STICKY_AREA_RATIO = 0.70f

        // Central stable landmarks used to compute the driver-selection bounding box: nose (1),
        // forehead (10), chin (152), left cheek (234), right cheek (454). Using only these 5
        // instead of all 478 makes the bbox more robust to oblique angles where face periphery
        // is foreshortened (which would artificially shrink the total bbox).
        private val BBOX_LANDMARK_INDICES = intArrayOf(1, 10, 152, 234, 454)

        // CLAHE tuning — moderate boost; higher clipLimit exaggerates noise in DARK.
        private const val CLAHE_CLIP_LIMIT = 2.0
        private const val CLAHE_TILE_SIZE = 8.0

        // Gamma > 1 brightens midtones. DARK gets a stronger nudge than LOW_LIGHT.
        const val GAMMA_LOW_LIGHT = 1.2f
        const val GAMMA_DARK = 1.4f

        /** True when the current lighting mode warrants CLAHE preprocessing. Pure — testable. */
        fun shouldApplyClahe(mode: LightingMode): Boolean = mode != LightingMode.NORMAL

        /**
         * Combines the two eye-openness sensors into a single value.
         *
         * Default behavior: return `min(blend, ear)` — the stricter reading wins, so a
         * closure detected by either sensor counts. This protects against glasses
         * reflections that inflate blendshape.
         *
         * Guard: when blendshape confidently reports open (≥ [BLEND_CONFIDENT_OPEN]) but
         * EAR is near zero (< [EAR_MIN_TRUSTED]), the EAR reading is almost certainly a
         * landmark artifact. In that case we ignore EAR and use blendshape alone —
         * otherwise PERCLOS inflates from isolated frames where landmarks jitter.
         *
         * Pure — testable in unit tests.
         */
        fun combineEyeOpenness(blend: Float, ear: Float): Float {
            val earUntrustworthy = ear < EAR_MIN_TRUSTED && blend > BLEND_CONFIDENT_OPEN
            return if (earUntrustworthy) blend else minOf(blend, ear)
        }

        /** Gamma factor to apply to the Y channel for the given mode. Pure — testable. */
        fun gammaFor(mode: LightingMode): Float = when (mode) {
            LightingMode.NORMAL -> 1f
            LightingMode.LOW_LIGHT -> GAMMA_LOW_LIGHT
            LightingMode.DARK -> GAMMA_DARK
        }

        /**
         * Mean of a coarse 8×8 sample grid across the Y plane of a YUV_420_888 image.
         *
         * The Y plane has one byte per pixel (unsigned 0..255). Bytes are signed in the
         * JVM so we mask with 0xFF. `rowStride` may exceed [width] on some devices due to
         * hardware padding — always index via `y * rowStride + x`, not `y * width + x`.
         */
        /**
         * Bounding box [minX, minY, maxX, maxY] over the stable central landmarks
         * ([BBOX_LANDMARK_INDICES]). Returns null if the landmark list is too short to
         * safely index — caller should treat that face as absent.
         */
        internal fun computeBoundingBox(landmarks: List<NormalizedLandmark>): FloatArray? {
            if (landmarks.size < 478) return null
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (idx in BBOX_LANDMARK_INDICES) {
                val lm = landmarks[idx]
                val x = lm.x()
                val y = lm.y()
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
            return floatArrayOf(minX, minY, maxX, maxY)
        }

        internal fun bboxArea(bbox: FloatArray): Float {
            val w = (bbox[2] - bbox[0]).coerceAtLeast(0f)
            val h = (bbox[3] - bbox[1]).coerceAtLeast(0f)
            return w * h
        }

        /**
         * Returns the index of the largest-area bbox, or -1 if the list is empty. On ties
         * the earliest index wins (deterministic).
         */
        internal fun pickLargestFaceIndex(bboxes: List<FloatArray>): Int {
            if (bboxes.isEmpty()) return -1
            var bestIdx = 0
            var bestArea = bboxArea(bboxes[0])
            for (i in 1 until bboxes.size) {
                val a = bboxArea(bboxes[i])
                if (a > bestArea) {
                    bestArea = a
                    bestIdx = i
                }
            }
            return bestIdx
        }

        /**
         * Picks the driver's face index applying (1) largest-area heuristic, then (2) a
         * sticky bias: if a candidate's center is within [STICKY_CENTER_TOLERANCE] of the
         * previous driver center AND its area is ≥ [STICKY_AREA_RATIO] of the largest, prefer
         * it over the raw largest. Pass negative sticky coords (`-1f`) when no prior driver
         * has been selected (first frame or after NO_FACE).
         *
         * Returns -1 if [bboxes] is empty.
         */
        internal fun pickDriverIndex(
            bboxes: List<FloatArray>,
            lastCenterX: Float,
            lastCenterY: Float,
        ): Int {
            if (bboxes.isEmpty()) return -1
            val largestIdx = pickLargestFaceIndex(bboxes)
            if (bboxes.size == 1) return largestIdx
            if (lastCenterX < 0f || lastCenterY < 0f) return largestIdx

            // Sticky bias: among candidates whose area is at least [STICKY_AREA_RATIO] of the
            // largest, pick the one whose center is closest to the previous driver's center —
            // provided that closest candidate is within [STICKY_CENTER_TOLERANCE]. If nobody
            // qualifies (all too far or too small), fall back to the largest.
            //
            // This handles the flicker case correctly: when a passenger leans forward and
            // briefly overtakes the driver's bbox area, both faces are candidates but the
            // driver's center is closer to the sticky center → driver wins, no swap.
            val largestArea = bboxArea(bboxes[largestIdx])
            val minAcceptableArea = largestArea * STICKY_AREA_RATIO
            var bestIdx = largestIdx
            var bestDist = Float.POSITIVE_INFINITY
            for (i in bboxes.indices) {
                if (bboxArea(bboxes[i]) < minAcceptableArea) continue
                val bbox = bboxes[i]
                val cx = (bbox[0] + bbox[2]) / 2f
                val cy = (bbox[1] + bbox[3]) / 2f
                val dx = cx - lastCenterX
                val dy = cy - lastCenterY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = i
                }
            }
            return if (bestDist <= STICKY_CENTER_TOLERANCE) bestIdx else largestIdx
        }

        /**
         * Pure math extracted from [extractYawPitchDegrees]: decomposes a MediaPipe 4×4
         * column-major transformation matrix into (yaw, pitch) degrees using Y-X-Z Euler.
         * Returns (0, 0) for arrays shorter than 16 so callers don't need extra guards.
         */
        internal fun decomposeYawPitchFromMatrix(m: FloatArray): Pair<Float, Float> {
            if (m.size < 16) return Pair(0f, 0f)
            val r02 = m[8]
            val r12 = m[9]
            val r22 = m[10]
            val pitchRad = asin((-r12).coerceIn(-1f, 1f))
            val yawRad = atan2(r02, r22)
            val radToDeg = 180.0 / Math.PI
            return Pair(
                (yawRad * radToDeg).toFloat(),
                (pitchRad * radToDeg).toFloat(),
            )
        }

        internal fun computeYPlaneMean(
            buffer: ByteBuffer,
            rowStride: Int,
            width: Int,
            height: Int,
        ): Float {
            if (width <= 0 || height <= 0) return 0f
            val stepX = (width / LUMINANCE_SAMPLES_PER_AXIS).coerceAtLeast(1)
            val stepY = (height / LUMINANCE_SAMPLES_PER_AXIS).coerceAtLeast(1)
            val limit = buffer.limit()
            var sum = 0L
            var count = 0
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val pos = y * rowStride + x
                    if (pos < limit) {
                        sum += buffer.get(pos).toInt() and 0xFF
                        count++
                    }
                    x += stepX
                }
                y += stepY
            }
            return if (count == 0) 0f else sum.toFloat() / count.toFloat()
        }
    }
}
