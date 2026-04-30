package com.vigilia.app.domain.scoring

import com.vigilia.app.domain.model.FatigueAssessment
import com.vigilia.app.domain.model.FatigueMetrics
import com.vigilia.app.domain.model.FatigueState
import java.util.ArrayDeque

/**
 * Core fatigue detection logic for the Vigília app.
 *
 * This class processes individual frames of eye and mouth metrics to calculate a fatigue score (0-100)
 * and assess the user's fatigue state using computer vision signals.
 *
 * Key metrics used:
 * - PERCLOS (Percentage of Eye Closure): Calculated over a 30-second rolling window.
 * - Blink Rate: Tracked over a 60-second rolling window.
 * - Yawn Detection: Sustained mouth opening for at least 2 seconds.
 *
 * The scorer uses exponential smoothing and a hysteresis state machine to ensure stable transitions
 * between fatigue states (NORMAL, WARNING, FATIGUED).
 */
class FatigueScorer {

    private companion object {
        const val PERCLOS_WINDOW_MS = 30_000L
        const val BLINK_WINDOW_MS = 60_000L
        const val YAWN_THRESHOLD_PROB = 0.7f
        const val YAWN_DURATION_MS = 2_000L
        const val YAWN_RESET_MS = 5_000L
        const val EYE_CLOSED_THRESHOLD = 0.2f
        const val EYE_OPEN_THRESHOLD = 0.5f
        const val SMOOTHING_ALPHA = 0.3f

        const val SCORE_WEIGHT_PERCLOS = 50f
        const val SCORE_WEIGHT_BLINK = 30f
        const val SCORE_WEIGHT_YAWN = 20f

        const val BLINK_RATE_MIN = 15f
        const val BLINK_RATE_MAX = 20f
        const val BLINK_DEVIATION_LIMIT_LOW = 8f
        const val BLINK_DEVIATION_LIMIT_HIGH = 25f

        const val TRANSITION_NORMAL_TO_WARNING_SCORE = 40f
        const val TRANSITION_NORMAL_TO_WARNING_MS = 3_000L

        const val TRANSITION_WARNING_TO_FATIGUED_SCORE = 70f
        const val TRANSITION_WARNING_TO_FATIGUED_MS = 5_000L

        const val TRANSITION_WARNING_TO_NORMAL_SCORE = 25f
        const val TRANSITION_WARNING_TO_NORMAL_MS = 10_000L

        const val TRANSITION_FATIGUED_TO_WARNING_SCORE = 50f
        const val TRANSITION_FATIGUED_TO_WARNING_MS = 10_000L
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

    // State transition tracking
    private var transitionStartTime: Long? = null
    private var targetState: FatigueState? = null

    /**
     * Processes a new frame of fatigue metrics and returns the current assessment.
     *
     * @param metrics The raw metrics detected for the current frame.
     * @return A [FatigueAssessment] containing the calculated score and state.
     */
    fun processFrame(metrics: FatigueMetrics): FatigueAssessment {
        if (!metrics.isFaceDetected) {
            currentState = FatigueState.NO_FACE
            transitionStartTime = null
            targetState = null
            return createAssessment(0f, metrics.timestampMs, false, 0f, false)
        }

        if (currentState == FatigueState.NO_FACE) {
            currentState = FatigueState.NORMAL
        }

        val currentTime = metrics.timestampMs
        val eyeOpenness = (metrics.leftEyeOpenProbability + metrics.rightEyeOpenProbability) / 2f
        val isEyeClosed = metrics.leftEyeOpenProbability < EYE_CLOSED_THRESHOLD || 
                          metrics.rightEyeOpenProbability < EYE_CLOSED_THRESHOLD ||
                          eyeOpenness < EYE_CLOSED_THRESHOLD

        // 1. PERCLOS Calculation
        perclosWindow.addLast(FrameRecord(currentTime, isEyeClosed))
        while (perclosWindow.isNotEmpty() && currentTime - perclosWindow.first().timestampMs > PERCLOS_WINDOW_MS) {
            perclosWindow.removeFirst()
        }
        val perclos = if (perclosWindow.isEmpty()) 0f else {
            perclosWindow.count { it.isEyeClosed }.toFloat() / perclosWindow.size
        }

        // 2. Blink Detection
        if (!isBlinking && eyeOpenness < EYE_CLOSED_THRESHOLD) {
            isBlinking = true
        } else if (isBlinking && eyeOpenness > EYE_OPEN_THRESHOLD) {
            blinkTimestamps.addLast(currentTime)
            isBlinking = false
        }
        while (blinkTimestamps.isNotEmpty() && currentTime - blinkTimestamps.first() > BLINK_WINDOW_MS) {
            blinkTimestamps.removeFirst()
        }
        val blinkRate = blinkTimestamps.size.toFloat() // Blinks per minute (since window is 60s)

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

        val blinkDeviationScore = calculateBlinkDeviationScore(blinkRate)
        val blinkContribution = blinkDeviationScore * SCORE_WEIGHT_BLINK

        val yawnContribution = if (isCurrentlyYawning) SCORE_WEIGHT_YAWN else 0f

        val rawScore = perclosContribution + blinkContribution + yawnContribution
        smoothedScore = if (smoothedScore == 0f && rawScore > 0) {
            rawScore
        } else {
            (SMOOTHING_ALPHA * rawScore) + (1f - SMOOTHING_ALPHA) * smoothedScore
        }

        // 5. State Machine with Hysteresis
        updateState(smoothedScore, currentTime)

        return createAssessment(smoothedScore, currentTime, true, blinkRate, isCurrentlyYawning)
    }

    /**
     * Resets all internal buffers and state to initial values.
     */
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
    }

    private fun calculateBlinkDeviationScore(blinkRate: Float): Float {
        return when {
            blinkRate in BLINK_RATE_MIN..BLINK_RATE_MAX -> 0f
            blinkRate < BLINK_RATE_MIN -> {
                val deviation = BLINK_RATE_MIN - blinkRate
                val maxDeviation = BLINK_RATE_MIN - BLINK_DEVIATION_LIMIT_LOW
                (deviation / maxDeviation).coerceIn(0f, 1f)
            }
            else -> { // blinkRate > BLINK_RATE_MAX
                val deviation = blinkRate - BLINK_RATE_MAX
                val maxDeviation = BLINK_DEVIATION_LIMIT_HIGH - BLINK_RATE_MAX
                (deviation / maxDeviation).coerceIn(0f, 1f)
            }
        }
    }

    private fun updateState(score: Float, currentTime: Long) {
        val (newTargetState, requiredDuration) = when (currentState) {
            FatigueState.NORMAL -> {
                if (score > TRANSITION_NORMAL_TO_WARNING_SCORE) {
                    FatigueState.WARNING to TRANSITION_NORMAL_TO_WARNING_MS
                } else null to 0L
            }
            FatigueState.WARNING -> {
                when {
                    score > TRANSITION_WARNING_TO_FATIGUED_SCORE -> FatigueState.FATIGUED to TRANSITION_WARNING_TO_FATIGUED_MS
                    score < TRANSITION_WARNING_TO_NORMAL_SCORE -> FatigueState.NORMAL to TRANSITION_WARNING_TO_NORMAL_MS
                    else -> null to 0L
                }
            }
            FatigueState.FATIGUED -> {
                if (score < TRANSITION_FATIGUED_TO_WARNING_SCORE) {
                    FatigueState.WARNING to TRANSITION_FATIGUED_TO_WARNING_MS
                } else null to 0L
            }
            FatigueState.NO_FACE -> null to 0L
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
        isYawning: Boolean
    ): FatigueAssessment {
        return FatigueAssessment(
            score = score.coerceIn(0f, 100f),
            fatigueState = currentState,
            blinkRate = blinkRate,
            isYawning = isYawning,
            isFaceDetected = isFaceDetected,
            timestampMs = timestampMs
        )
    }
}
