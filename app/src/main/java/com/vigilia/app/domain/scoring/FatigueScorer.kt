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
        // Shorter window → PERCLOS accumulates faster; eyes closing for 5s fills ~83% of window
        const val PERCLOS_WINDOW_MS = 6_000L
        const val BLINK_WINDOW_MS = 60_000L
        const val YAWN_THRESHOLD_PROB = 0.38f
        const val YAWN_DURATION_MS = 1_500L
        const val YAWN_RESET_MS = 5_000L
        // Brief mouth-close tolerance: door not reset mid-yawn due to speaking/coughing frame
        const val YAWN_GRACE_MS = 300L
        const val SMOOTHING_ALPHA = 0.3f

        // Generic thresholds — replaced by calibrated values when calibration runs
        const val EYE_CLOSED_THRESHOLD_DEFAULT = 0.3f
        const val EYE_OPEN_THRESHOLD_DEFAULT = 0.4f

        // PERCLOS raised to 65 so fully-closed eyes alone can push score above FATIGUED threshold
        const val SCORE_WEIGHT_PERCLOS = 65f
        const val SCORE_WEIGHT_BLINK = 20f
        const val SCORE_WEIGHT_YAWN = 25f

        const val BLINK_RATE_MIN = 15f
        const val BLINK_RATE_MAX = 20f
        const val BLINK_DEVIATION_LIMIT_LOW = 8f
        const val BLINK_DEVIATION_LIMIT_HIGH = 25f

        const val TRANSITION_NORMAL_TO_WARNING_SCORE = 40f
        const val TRANSITION_NORMAL_TO_WARNING_MS = 2_000L
        // Lowered from 70 → 55: PERCLOS=1.0 alone (65 pts) now exceeds this threshold
        const val TRANSITION_WARNING_TO_FATIGUED_SCORE = 55f
        const val TRANSITION_WARNING_TO_FATIGUED_MS = 4_000L
        const val TRANSITION_WARNING_TO_NORMAL_SCORE = 25f
        const val TRANSITION_WARNING_TO_NORMAL_MS = 5_000L
        const val TRANSITION_FATIGUED_TO_WARNING_SCORE = 50f
        const val TRANSITION_FATIGUED_TO_WARNING_MS = 5_000L

        // Cap on per-frame delta added to the transition accumulator. Prevents brief
        // excursions into the neutral score band from resetting recovery progress
        // while still preventing huge jumps after prolonged neutral gaps.
        const val TRANSITION_MAX_FRAME_DELTA_MS = 200L

        // Grace period before NO_FACE resets the state machine — absorbs brief detection glitches
        const val NO_FACE_GRACE_MS = 500L

        // Calibration
        const val CALIBRATION_DURATION_MS = 7_000L
        const val CALIBRATION_MIN_SAMPLES = 20
        const val EYE_CLOSED_RATIO = 0.60f   // closed = baseline * this
        const val EYE_CLOSED_MIN = 0.15f
        const val EYE_CLOSED_MAX = 0.60f

        // Blink debounce: require this many consecutive frames below threshold before confirming closure
        const val BLINK_MIN_CLOSED_FRAMES = 3
        // Blink max: sustained closure beyond this is PERCLOS territory, not a blink
        const val BLINK_MAX_DURATION_MS = 500L
        // Warmup: don't penalize low blink rate until enough data has been collected
        const val BLINK_MIN_OBSERVATION_MS = 30_000L
    }

    private data class FrameRecord(val timestampMs: Long, val isEyeClosed: Boolean)
    private val perclosWindow = ArrayDeque<FrameRecord>()
    private val blinkTimestamps = ArrayDeque<Long>()

    private var isBlinking = false
    private var closedFrameCount = 0
    private var blinkStartTime: Long? = null
    private var monitoringStartMs = -1L

    private var yawnStartTime: Long? = null
    private var yawnGraceStart: Long? = null
    private var lastYawnDetectedTime: Long? = null
    private var isCurrentlyYawning = false

    private var smoothedScore = 0f
    private var currentState = FatigueState.NORMAL

    private var targetState: FatigueState? = null
    private var transitionAccumulatedMs = 0L
    private var transitionLastCheckMs = 0L

    // Tracks how long face has been absent; prevents single-frame glitches from resetting state
    private var noFaceStartTime: Long? = null

    // Calibration state
    private var calibrationStartMs = -1L
    private val calibrationSamples = mutableListOf<Float>()
    private var eyeClosedThreshold = EYE_CLOSED_THRESHOLD_DEFAULT
    private var eyeOpenThreshold = EYE_OPEN_THRESHOLD_DEFAULT

    fun processFrame(metrics: FatigueMetrics): FatigueAssessment {
        if (!metrics.isFaceDetected) {
            val now = metrics.timestampMs
            if (noFaceStartTime == null) noFaceStartTime = now
            val noFaceDuration = now - noFaceStartTime!!

            return if (noFaceDuration >= NO_FACE_GRACE_MS) {
                // Sustained absence — transition to NO_FACE and drop stale detection buffers so
                // score doesn't jump back to WARNING/FATIGUED from old data when face returns.
                currentState = FatigueState.NO_FACE
                targetState = null
                transitionAccumulatedMs = 0L
                transitionLastCheckMs = 0L
                perclosWindow.clear()
                blinkTimestamps.clear()
                smoothedScore = 0f
                createAssessment(0f, now, false, 0f, false)
            } else {
                // Brief glitch — hold current state so detection progress isn't lost
                createAssessment(smoothedScore, now, false, blinkTimestamps.size.toFloat(), isCurrentlyYawning)
            }
        }
        noFaceStartTime = null

        if (currentState == FatigueState.NO_FACE || (calibrationEnabled && calibrationStartMs < 0)) {
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

        val isEyeClosed = metrics.leftEyeOpenProbability < eyeClosedThreshold ||
                metrics.rightEyeOpenProbability < eyeClosedThreshold

        // 1. PERCLOS Calculation
        perclosWindow.addLast(FrameRecord(currentTime, isEyeClosed))
        while (perclosWindow.isNotEmpty() && currentTime - perclosWindow.first().timestampMs > PERCLOS_WINDOW_MS) {
            perclosWindow.removeFirst()
        }
        val perclos = if (perclosWindow.isEmpty()) 0f else {
            perclosWindow.count { it.isEyeClosed }.toFloat() / perclosWindow.size
        }

        if (monitoringStartMs < 0) monitoringStartMs = currentTime

        // 2. Blink Detection — uses min(left,right) to match PERCLOS OR logic and handle
        // asymmetric readings (e.g. one eye inflated by glasses reflection).
        // Temporal debounce: requires BLINK_MIN_CLOSED_FRAMES consecutive frames below threshold.
        // Max duration: closure > BLINK_MAX_DURATION_MS is sustained (PERCLOS), not a blink.
        val eyeMin = minOf(metrics.leftEyeOpenProbability, metrics.rightEyeOpenProbability)
        if (!isBlinking) {
            if (eyeMin < eyeClosedThreshold) {
                closedFrameCount++
                if (closedFrameCount >= BLINK_MIN_CLOSED_FRAMES) {
                    isBlinking = true
                    blinkStartTime = currentTime
                    closedFrameCount = 0
                }
            } else {
                closedFrameCount = 0
            }
        } else if (blinkStartTime != null && currentTime - blinkStartTime!! > BLINK_MAX_DURATION_MS) {
            // Sustained closure — PERCLOS territory, discard as blink
            isBlinking = false
            blinkStartTime = null
            closedFrameCount = 0
        } else if (eyeMin > eyeOpenThreshold) {
            blinkTimestamps.addLast(currentTime)
            isBlinking = false
            blinkStartTime = null
            closedFrameCount = 0
        }
        while (blinkTimestamps.isNotEmpty() && currentTime - blinkTimestamps.first() > BLINK_WINDOW_MS) {
            blinkTimestamps.removeFirst()
        }
        val blinkRate = blinkTimestamps.size.toFloat()

        // 3. Yawn Detection
        if (metrics.mouthOpenProbability > YAWN_THRESHOLD_PROB) {
            yawnGraceStart = null
            if (yawnStartTime == null) {
                yawnStartTime = currentTime
            } else if (currentTime - yawnStartTime!! >= YAWN_DURATION_MS) {
                if (lastYawnDetectedTime == null || currentTime - lastYawnDetectedTime!! > YAWN_RESET_MS) {
                    isCurrentlyYawning = true
                    lastYawnDetectedTime = currentTime
                }
            }
        } else {
            // Grace period: tolerate brief mouth closures (cough, speech) without resetting the timer
            if (yawnStartTime != null) {
                if (yawnGraceStart == null) {
                    yawnGraceStart = currentTime
                } else if (currentTime - yawnGraceStart!! > YAWN_GRACE_MS) {
                    yawnStartTime = null
                    yawnGraceStart = null
                }
            }
        }

        if (isCurrentlyYawning && lastYawnDetectedTime != null && currentTime - lastYawnDetectedTime!! > YAWN_RESET_MS) {
            isCurrentlyYawning = false
        }

        // 4. Score Calculation
        val perclosContribution = perclos * SCORE_WEIGHT_PERCLOS
        // Suppress blink penalty during warmup: with few blinks recorded, the rate looks
        // abnormally low (e.g. 1 blink → rate=1 → max penalty). Only score after 30 s of data.
        val elapsedMonitoringMs = currentTime - monitoringStartMs
        val blinkContribution = when {
            blinkTimestamps.isEmpty() -> 0f
            elapsedMonitoringMs < BLINK_MIN_OBSERVATION_MS -> 0f
            else -> calculateBlinkDeviationScore(blinkRate) * SCORE_WEIGHT_BLINK
        }
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
        isBlinking = false
        yawnStartTime = null
        yawnGraceStart = null
        lastYawnDetectedTime = null
        isCurrentlyYawning = false
        smoothedScore = 0f
        currentState = FatigueState.NORMAL
        targetState = null
        transitionAccumulatedMs = 0L
        transitionLastCheckMs = 0L
        noFaceStartTime = null
        calibrationStartMs = -1L
        calibrationSamples.clear()
        eyeClosedThreshold = EYE_CLOSED_THRESHOLD_DEFAULT
        eyeOpenThreshold = EYE_OPEN_THRESHOLD_DEFAULT
        closedFrameCount = 0
        blinkStartTime = null
        monitoringStartMs = -1L
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
        eyeOpenThreshold = (eyeClosedThreshold + 0.10f).coerceIn(
            eyeClosedThreshold + 0.05f,
            (eyeClosedThreshold + 0.20f).coerceAtMost(0.90f),
        )
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
                // Direction changed — start accumulation fresh
                targetState = newTargetState
                transitionAccumulatedMs = 0L
            } else {
                // Same target — accumulate time in target zone. Cap per-frame delta so a
                // brief excursion into the neutral band doesn't count toward the transition,
                // but the accumulator keeps its prior progress instead of resetting.
                val delta = (currentTime - transitionLastCheckMs).coerceAtMost(TRANSITION_MAX_FRAME_DELTA_MS)
                transitionAccumulatedMs += delta
            }
            transitionLastCheckMs = currentTime
            if (transitionAccumulatedMs >= requiredDuration) {
                currentState = newTargetState
                targetState = null
                transitionAccumulatedMs = 0L
            }
        }
        // Neutral zone: preserve targetState + accumulator; timer pauses (does not reset).
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
