package com.vigilia.app.domain.scoring

import android.util.Log
import com.vigilia.app.domain.model.FatigueAssessment
import com.vigilia.app.domain.model.FatigueMetrics
import com.vigilia.app.domain.model.FatigueState
import java.util.ArrayDeque

/**
 * Core fatigue detection logic for the Vigília app.
 *
 * When [calibrationEnabled] is true (default), the scorer spends the first
 * [CALIBRATION_DURATION_MS] milliseconds measuring the driver's natural eye
 * openness and derives a personal [eyeClosedThreshold] instead of using the
 * generic default. This improves accuracy for drivers with naturally smaller
 * or larger eyes.
 */
class FatigueScorer(private val calibrationEnabled: Boolean = true) {

    private companion object {
        const val PERCLOS_WINDOW_MS = 20_000L
        const val BLINK_WINDOW_MS = 60_000L
        const val YAWN_THRESHOLD_PROB = 0.5f
        const val YAWN_DURATION_MS = 2_000L
        const val YAWN_RESET_MS = 5_000L
        const val SMOOTHING_ALPHA = 0.3f

        // Generic thresholds — replaced by calibrated values when calibration runs
        const val EYE_CLOSED_THRESHOLD_DEFAULT = 0.3f
        const val EYE_OPEN_THRESHOLD_DEFAULT = 0.4f

        const val SCORE_WEIGHT_PERCLOS = 50f
        const val SCORE_WEIGHT_BLINK = 20f
        const val SCORE_WEIGHT_YAWN = 25f

        const val BLINK_RATE_MIN = 15f
        const val BLINK_RATE_MAX = 20f
        const val BLINK_DEVIATION_LIMIT_LOW = 8f
        const val BLINK_DEVIATION_LIMIT_HIGH = 25f

        const val TRANSITION_NORMAL_TO_WARNING_SCORE = 40f
        const val TRANSITION_NORMAL_TO_WARNING_MS = 2_000L
        const val TRANSITION_WARNING_TO_FATIGUED_SCORE = 70f
        const val TRANSITION_WARNING_TO_FATIGUED_MS = 4_000L
        const val TRANSITION_WARNING_TO_NORMAL_SCORE = 25f
        const val TRANSITION_WARNING_TO_NORMAL_MS = 10_000L
        const val TRANSITION_FATIGUED_TO_WARNING_SCORE = 50f
        const val TRANSITION_FATIGUED_TO_WARNING_MS = 10_000L

        // Calibration
        const val CALIBRATION_DURATION_MS = 7_000L
        const val CALIBRATION_MIN_SAMPLES = 20
        const val EYE_CLOSED_RATIO = 0.40f   // closed = baseline * this
        const val EYE_CLOSED_MIN = 0.15f
        const val EYE_CLOSED_MAX = 0.45f
    }

    private data class FrameRecord(val timestampMs: Long, val isEyeClosed: Boolean)
    private val perclosWindow = ArrayDeque<FrameRecord>()
    private val blinkTimestamps = ArrayDeque<Long>()

    private var lastEyeOpenness = 1.0f
    private var isBlinking = false

    private var yawnStartTime: Long? = null
    private var lastYawnDetectedTime: Long? = null
    private var isCurrentlyYawning = false

    private var smoothedScore = 0f
    private var currentState = FatigueState.NORMAL

    private var transitionStartTime: Long? = null
    private var targetState: FatigueState? = null

    // Calibration state
    private var calibrationStartMs = -1L
    private val calibrationSamples = mutableListOf<Float>()
    private var eyeClosedThreshold = EYE_CLOSED_THRESHOLD_DEFAULT
    private var eyeOpenThreshold = EYE_OPEN_THRESHOLD_DEFAULT

    fun processFrame(metrics: FatigueMetrics): FatigueAssessment {
        if (!metrics.isFaceDetected) {
            currentState = FatigueState.NO_FACE
            transitionStartTime = null
            targetState = null
            return createAssessment(0f, metrics.timestampMs, false, 0f, false)
        }

        if (currentState == FatigueState.NO_FACE) {
            currentState = if (calibrationEnabled && calibrationStartMs < 0) FatigueState.CALIBRATING else FatigueState.NORMAL
        }

        val currentTime = metrics.timestampMs
        val eyeOpenness = (metrics.leftEyeOpenProbability + metrics.rightEyeOpenProbability) / 2f

        // Calibration phase — collect baseline before scoring begins
        if (calibrationEnabled && currentState == FatigueState.CALIBRATING) {
            if (calibrationStartMs < 0) calibrationStartMs = currentTime
            calibrationSamples.add(eyeOpenness)

            val elapsed = currentTime - calibrationStartMs
            val progress = (elapsed.toFloat() / CALIBRATION_DURATION_MS).coerceIn(0f, 1f)

            if (elapsed >= CALIBRATION_DURATION_MS) {
                finishCalibration()
                currentState = FatigueState.NORMAL
            } else {
                return FatigueAssessment(
                    score = 0f,
                    fatigueState = FatigueState.CALIBRATING,
                    blinkRate = 0f,
                    isYawning = false,
                    isFaceDetected = true,
                    timestampMs = currentTime,
                    calibrationProgress = progress,
                )
            }
        }

        // Start calibration on first face-detected frame
        if (calibrationEnabled && calibrationStartMs < 0) {
            calibrationStartMs = currentTime
            currentState = FatigueState.CALIBRATING
            return FatigueAssessment(
                score = 0f,
                fatigueState = FatigueState.CALIBRATING,
                blinkRate = 0f,
                isYawning = false,
                isFaceDetected = true,
                timestampMs = currentTime,
                calibrationProgress = 0f,
            )
        }

        val isEyeClosed = metrics.leftEyeOpenProbability < eyeClosedThreshold ||
                metrics.rightEyeOpenProbability < eyeClosedThreshold ||
                eyeOpenness < eyeClosedThreshold

        // 1. PERCLOS Calculation
        perclosWindow.addLast(FrameRecord(currentTime, isEyeClosed))
        while (perclosWindow.isNotEmpty() && currentTime - perclosWindow.first().timestampMs > PERCLOS_WINDOW_MS) {
            perclosWindow.removeFirst()
        }
        val perclos = if (perclosWindow.isEmpty()) 0f else {
            perclosWindow.count { it.isEyeClosed }.toFloat() / perclosWindow.size
        }

        // 2. Blink Detection
        if (!isBlinking && eyeOpenness < eyeClosedThreshold) {
            isBlinking = true
        } else if (isBlinking && eyeOpenness > eyeOpenThreshold) {
            blinkTimestamps.addLast(currentTime)
            isBlinking = false
        }
        while (blinkTimestamps.isNotEmpty() && currentTime - blinkTimestamps.first() > BLINK_WINDOW_MS) {
            blinkTimestamps.removeFirst()
        }
        val blinkRate = blinkTimestamps.size.toFloat()

        // 3. Yawn Detection
        if (metrics.mouthOpenProbability > YAWN_THRESHOLD_PROB) {
            if (yawnStartTime == null) {
                yawnStartTime = currentTime
            } else if (currentTime - yawnStartTime!! >= YAWN_DURATION_MS) {
                if (lastYawnDetectedTime == null || currentTime - lastYawnDetectedTime!! > YAWN_RESET_MS) {
                    isCurrentlyYawning = true
                    lastYawnDetectedTime = currentTime
                }
            }
        } else {
            yawnStartTime = null
        }

        if (isCurrentlyYawning && lastYawnDetectedTime != null && currentTime - lastYawnDetectedTime!! > YAWN_RESET_MS) {
            isCurrentlyYawning = false
        }

        // 4. Score Calculation
        val perclosContribution = perclos * SCORE_WEIGHT_PERCLOS
        // Suppress blink penalty while no blinks have been detected yet — avoids false
        // fatigue signals in the first frames when the 60-second window is still empty.
        val blinkContribution = if (blinkTimestamps.isEmpty()) 0f
            else calculateBlinkDeviationScore(blinkRate) * SCORE_WEIGHT_BLINK
        val yawnContribution = if (isCurrentlyYawning) SCORE_WEIGHT_YAWN else 0f

        val rawScore = perclosContribution + blinkContribution + yawnContribution
        smoothedScore = (SMOOTHING_ALPHA * rawScore) + (1f - SMOOTHING_ALPHA) * smoothedScore

        Log.d("FatigueScorer", "score=$smoothedScore state=$currentState perclos=$perclos blinkRate=$blinkRate yawning=$isCurrentlyYawning")

        // 5. State Machine with Hysteresis
        updateState(smoothedScore, currentTime)

        return createAssessment(smoothedScore, currentTime, true, blinkRate, isCurrentlyYawning)
    }

    fun reset() {
        perclosWindow.clear()
        blinkTimestamps.clear()
        lastEyeOpenness = 1.0f
        isBlinking = false
        yawnStartTime = null
        lastYawnDetectedTime = null
        isCurrentlyYawning = false
        smoothedScore = 0f
        currentState = FatigueState.NORMAL
        transitionStartTime = null
        targetState = null
        calibrationStartMs = -1L
        calibrationSamples.clear()
        eyeClosedThreshold = EYE_CLOSED_THRESHOLD_DEFAULT
        eyeOpenThreshold = EYE_OPEN_THRESHOLD_DEFAULT
    }

    private fun finishCalibration() {
        if (calibrationSamples.size < CALIBRATION_MIN_SAMPLES) {
            Log.w("FatigueScorer", "Calibration skipped: only ${calibrationSamples.size} samples, keeping defaults")
            return
        }
        val sorted = calibrationSamples.sorted()
        val p90Index = ((sorted.size - 1) * 0.90f).toInt()
        val baseline = sorted[p90Index]
        eyeClosedThreshold = (baseline * EYE_CLOSED_RATIO).coerceIn(EYE_CLOSED_MIN, EYE_CLOSED_MAX)
        eyeOpenThreshold = (eyeClosedThreshold + 0.10f).coerceIn(eyeClosedThreshold + 0.05f, 0.55f)
        Log.d("FatigueScorer", "Calibration done: baseline=$baseline closed=$eyeClosedThreshold open=$eyeOpenThreshold samples=${calibrationSamples.size}")
    }

    private fun calculateBlinkDeviationScore(blinkRate: Float): Float {
        return when {
            blinkRate in BLINK_RATE_MIN..BLINK_RATE_MAX -> 0f
            blinkRate < BLINK_RATE_MIN -> {
                val deviation = BLINK_RATE_MIN - blinkRate
                val maxDeviation = BLINK_RATE_MIN - BLINK_DEVIATION_LIMIT_LOW
                (deviation / maxDeviation).coerceIn(0f, 1f)
            }
            else -> {
                val deviation = blinkRate - BLINK_RATE_MAX
                val maxDeviation = BLINK_DEVIATION_LIMIT_HIGH - BLINK_RATE_MAX
                (deviation / maxDeviation).coerceIn(0f, 1f)
            }
        }
    }

    private fun updateState(score: Float, currentTime: Long) {
        val (newTargetState, requiredDuration) = when (currentState) {
            FatigueState.NORMAL -> {
                if (score > TRANSITION_NORMAL_TO_WARNING_SCORE) FatigueState.WARNING to TRANSITION_NORMAL_TO_WARNING_MS
                else null to 0L
            }
            FatigueState.WARNING -> {
                when {
                    score > TRANSITION_WARNING_TO_FATIGUED_SCORE -> FatigueState.FATIGUED to TRANSITION_WARNING_TO_FATIGUED_MS
                    score < TRANSITION_WARNING_TO_NORMAL_SCORE -> FatigueState.NORMAL to TRANSITION_WARNING_TO_NORMAL_MS
                    else -> null to 0L
                }
            }
            FatigueState.FATIGUED -> {
                if (score < TRANSITION_FATIGUED_TO_WARNING_SCORE) FatigueState.WARNING to TRANSITION_FATIGUED_TO_WARNING_MS
                else null to 0L
            }
            FatigueState.NO_FACE, FatigueState.CALIBRATING -> null to 0L
        }

        if (newTargetState != null) {
            if (targetState != newTargetState) {
                targetState = newTargetState
                transitionStartTime = currentTime
            } else {
                if (currentTime - (transitionStartTime ?: currentTime) >= requiredDuration) {
                    currentState = newTargetState
                    targetState = null
                    transitionStartTime = null
                }
            }
        } else {
            targetState = null
            transitionStartTime = null
        }
    }

    private fun createAssessment(
        score: Float,
        timestampMs: Long,
        isFaceDetected: Boolean,
        blinkRate: Float,
        isYawning: Boolean,
    ): FatigueAssessment {
        return FatigueAssessment(
            score = score.coerceIn(0f, 100f),
            fatigueState = currentState,
            blinkRate = blinkRate,
            isYawning = isYawning,
            isFaceDetected = isFaceDetected,
            timestampMs = timestampMs,
        )
    }
}
