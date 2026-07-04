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
        // 30-second rolling window aligned with the PERCLOS literature and CLAUDE.md.
        // Was 6_000L briefly — that made the score sensitive to any 3-5s episode of
        // "eyes appear closed" (which happens easily when the driver glances aside).
        const val PERCLOS_WINDOW_MS = 30_000L
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

        // PERCLOS is the primary fatigue signal (weight 65). Total sums to 105 (clamped
        // to 100) so a *combination* is required to reach FATIGUED (>70), not any single
        // signal alone. BLINK was 20 — a moderately high rate (25/min under focus / dry
        // eyes) hit the max deviation and drove score into WARNING alongside PERCLOS.
        // Lowered to 15 so blink is still a real signal but not a sole tipping factor.
        const val SCORE_WEIGHT_PERCLOS = 65f
        const val SCORE_WEIGHT_BLINK = 15f
        const val SCORE_WEIGHT_YAWN = 25f

        // The "healthy" blink range was 15-20/min from PERCLOS literature, but that
        // literature assumes drivers looking at the road, not at a phone camera. Users
        // focused on the app's front camera blink 20-26/min naturally (focus, dry eyes,
        // ambient lighting). Widening MAX to 24 and the deviation ceiling to 32 keeps
        // blink deviation useful for extreme rates (>30/min) without penalizing typical
        // in-app usage.
        const val BLINK_RATE_MIN = 15f
        const val BLINK_RATE_MAX = 24f                   // was 20
        const val BLINK_DEVIATION_LIMIT_LOW = 8f
        const val BLINK_DEVIATION_LIMIT_HIGH = 32f       // was 25

        // Was 40 + 2 s — even after the PERCLOS-70 realignment (`3c30b8a`) some users saw
        // WARNING while just blinking normally. 50 + 3 s means PERCLOS alone needs 77 %
        // closure sustained 3 s, and blink alone (max 15 pts) can never tip on its own —
        // a combination of signals is required. FATIGUED gate (70) unchanged.
        const val TRANSITION_NORMAL_TO_WARNING_SCORE = 50f
        const val TRANSITION_NORMAL_TO_WARNING_MS = 3_000L
        // Restored to 70 (was 55). At 55, PERCLOS=~0.85 alone (55 pts of the 65-pt weight)
        // was enough to promote to FATIGUED — an isolated look-away episode could trigger it.
        // 70 requires a combination of PERCLOS + blink deviation + yawn, matching CLAUDE.md.
        const val TRANSITION_WARNING_TO_FATIGUED_SCORE = 70f
        const val TRANSITION_WARNING_TO_FATIGUED_MS = 4_000L
        // Was 25 — combined with the previously-inflated PERCLOS threshold (EYE_CLOSED_RATIO=0.60),
        // score got stuck ~26-30 in baseline usage and recovery never converged. Now that the
        // PERCLOS threshold is aligned with the PERCLOS-70 literature standard, 30 makes recovery
        // reachable while still requiring a real drop in fatigue signals.
        const val TRANSITION_WARNING_TO_NORMAL_SCORE = 30f
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
        // Was 0.60 — with baseline p90 ≈ 0.85, the calibrated eyeClosedThreshold came out at
        // ~0.51, clamped by EYE_CLOSED_MAX = 0.60. Normally-open frames (openness 0.40–0.55)
        // were classified as closed → PERCLOS inflated to 30–40 % steady state and false
        // WARNINGs after ~30 s. Realigned with the PERCLOS-70 automotive standard: a frame
        // counts as "closed" only when openness < 30 % of the calibrated open baseline.
        const val EYE_CLOSED_RATIO = 0.40f   // was 0.60
        const val EYE_CLOSED_MIN = 0.15f
        const val EYE_CLOSED_MAX = 0.45f     // was 0.60
        // Require a consecutive run of well-framed, eyes-open frames before calibration
        // starts collecting samples. Guards against sampling the user immediately after
        // they tap Start (dedo saindo do botão, cabeça inclinada), which would drag the
        // baseline down and produce an inflated eyeClosedThreshold. The gate is bounded
        // by a hard timeout so bad framing/lighting can't block calibration forever —
        // the p90 during sample collection filters most residual noise anyway.
        const val CALIBRATION_STABILIZATION_MS = 800L
        const val CALIBRATION_STABILIZATION_MIN_OPENNESS = 0.35f
        // Absolute upper bound for the stabilization gate. After this, sample collection
        // starts even if the gate never converged (glasses reflections, low light, etc).
        const val CALIBRATION_STABILIZATION_MAX_MS = 3_000L
        // Absorb ~1 natural blink (~3 frames @ 30 fps) without resetting the eligibility
        // timer. A sustained closure beyond this still resets — that's the correct signal
        // that the eyes aren't reliably visible yet.
        const val CALIBRATION_STABILIZATION_TOLERANCE_FRAMES = 3

        // Blink debounce: require this many consecutive frames below threshold before confirming closure
        const val BLINK_MIN_CLOSED_FRAMES = 3
        // Blink max: sustained closure beyond this is PERCLOS territory, not a blink
        const val BLINK_MAX_DURATION_MS = 500L
        // Warmup: don't penalize low blink rate until enough data has been collected
        const val BLINK_MIN_OBSERVATION_MS = 30_000L

        // Look-away thresholds: when the driver's head is rotated beyond these limits (mirrors,
        // dashboard, side windows), MediaPipe's eye blendshapes inflate because the eyelids
        // appear more closed in oblique perspective. Pausing PERCLOS/blink/yawn accumulation
        // during these frames prevents falsely scoring natural in-vehicle looking as fatigue.
        const val LOOK_AWAY_YAW_DEGREES = 25f
        const val LOOK_AWAY_PITCH_DEGREES_UP = 20f     // head tilted upward
        const val LOOK_AWAY_PITCH_DEGREES_DOWN = 25f   // head tilted downward (slightly more permissive — driver glances at dashboard)
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
    private var calibrationEligibleSinceMs = -1L
    // Timestamp of the very first face-detected frame in this session. Used to enforce
    // CALIBRATION_STABILIZATION_MAX_MS as an absolute upper bound on the stabilization gate.
    private var firstCalibrationFrameMs = -1L
    // Consecutive frames below the stabilization openness threshold — tolerates natural
    // blinks without resetting the eligibility timer.
    private var calibrationBelowCount = 0
    // Pairs of (timestampMs, openness). Timestamps are kept so finishCalibration() can
    // seed the perclosWindow with these frames, avoiding a zero-history transient right
    // when state transitions to NORMAL.
    private val calibrationSamples = mutableListOf<Pair<Long, Float>>()
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
                calibrationEligibleSinceMs = -1L
                firstCalibrationFrameMs = -1L
                calibrationBelowCount = 0
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
            // Stabilization gate: wait for CALIBRATION_STABILIZATION_MS of consecutive
            // frames with eyes clearly open before starting to collect samples. Tolerates
            // brief closures (natural blinks) via calibrationBelowCount so the gate isn't
            // reset every 3-4 s by normal blinking. Bounded by CALIBRATION_STABILIZATION_MAX_MS
            // so bad framing/lighting/glasses reflections can't block calibration forever —
            // sample collection starts anyway after the timeout and p90 filters residual noise.
            if (calibrationStartMs < 0) {
                if (firstCalibrationFrameMs < 0) firstCalibrationFrameMs = currentTime
                val elapsedInGate = currentTime - firstCalibrationFrameMs

                if (eyeOpenness >= CALIBRATION_STABILIZATION_MIN_OPENNESS) {
                    if (calibrationEligibleSinceMs < 0) calibrationEligibleSinceMs = currentTime
                    calibrationBelowCount = 0
                } else {
                    calibrationBelowCount++
                    if (calibrationBelowCount > CALIBRATION_STABILIZATION_TOLERANCE_FRAMES) {
                        // Sustained low openness — reset the eligibility timer. Keep
                        // firstCalibrationFrameMs so the 3 s hard cap still ticks.
                        calibrationEligibleSinceMs = -1L
                        calibrationBelowCount = 0
                    }
                }

                val stableEnough = calibrationEligibleSinceMs >= 0 &&
                        currentTime - calibrationEligibleSinceMs >= CALIBRATION_STABILIZATION_MS
                val forceStart = elapsedInGate >= CALIBRATION_STABILIZATION_MAX_MS

                if (stableEnough || forceStart) {
                    if (forceStart && !stableEnough) {
                        Log.w("FatigueScorer", "Calibration stabilization budget elapsed (${elapsedInGate}ms) — starting collection anyway")
                    }
                    calibrationStartMs = currentTime
                    // fall through to sample collection below
                } else {
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
            }

            calibrationSamples.add(currentTime to eyeOpenness)

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

        // Look-away detection: when head yaw/pitch is outside the natural forward range, the
        // driver is checking mirrors/dashboard/side — a normal in-vehicle behavior. MediaPipe's
        // blendshapes read those frames as "eyes more closed" because eyelids look shorter in
        // oblique perspective, so counting them toward PERCLOS/blink/yawn produces false alerts.
        // We pause accumulation instead: the buffer keeps its prior frames, no new closed/open
        // frames are added, blinks aren't confirmed, and yawn timers reset. Aged frames still
        // drain from the buffer by timestamp so a long look-away doesn't leave stale data behind.
        val isLookingAway = kotlin.math.abs(metrics.headYawDegrees) > LOOK_AWAY_YAW_DEGREES ||
                metrics.headPitchDegrees > LOOK_AWAY_PITCH_DEGREES_UP ||
                metrics.headPitchDegrees < -LOOK_AWAY_PITCH_DEGREES_DOWN

        val isEyeClosed = metrics.leftEyeOpenProbability < eyeClosedThreshold ||
                metrics.rightEyeOpenProbability < eyeClosedThreshold

        // 1. PERCLOS Calculation — skip the addLast when looking away, but always drain by age.
        if (!isLookingAway) {
            perclosWindow.addLast(FrameRecord(currentTime, isEyeClosed))
        }
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
        // Paused entirely when looking away — an eye that looks closed in oblique perspective
        // isn't a real blink.
        val eyeMin = minOf(metrics.leftEyeOpenProbability, metrics.rightEyeOpenProbability)
        if (!isLookingAway) {
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
        }
        while (blinkTimestamps.isNotEmpty() && currentTime - blinkTimestamps.first() > BLINK_WINDOW_MS) {
            blinkTimestamps.removeFirst()
        }
        val blinkRate = blinkTimestamps.size.toFloat()

        // 3. Yawn Detection — reset the yawn timers when looking away since the mouth is
        // not reliably observable in oblique perspective. Prevents false yawn confirmations
        // from side profile artifacts.
        if (isLookingAway) {
            yawnStartTime = null
            yawnGraceStart = null
        } else if (metrics.mouthOpenProbability > YAWN_THRESHOLD_PROB) {
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

        Log.d("FatigueScorer", "score=$smoothedScore state=$currentState perclos=$perclos blinkRate=$blinkRate yawning=$isCurrentlyYawning lookAway=$isLookingAway yaw=${metrics.headYawDegrees} pitch=${metrics.headPitchDegrees}")

        // 5. State Machine with Hysteresis
        updateState(smoothedScore, currentTime)

        return createAssessment(
            score = smoothedScore,
            timestampMs = currentTime,
            isFaceDetected = true,
            blinkRate = blinkRate,
            isYawning = isCurrentlyYawning,
            perclos = perclos,
            perclosContribution = perclosContribution,
            blinkContribution = blinkContribution,
            yawnContribution = yawnContribution,
        )
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
        calibrationEligibleSinceMs = -1L
        firstCalibrationFrameMs = -1L
        calibrationBelowCount = 0
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
        val opennessSorted = calibrationSamples.map { it.second }.sorted()
        val p90Index = ((opennessSorted.size - 1) * 0.90f).toInt()
        val baseline = opennessSorted[p90Index]
        eyeClosedThreshold = (baseline * EYE_CLOSED_RATIO).coerceIn(EYE_CLOSED_MIN, EYE_CLOSED_MAX)
        eyeOpenThreshold = (eyeClosedThreshold + 0.10f).coerceIn(
            eyeClosedThreshold + 0.05f,
            (eyeClosedThreshold + 0.20f).coerceAtMost(0.90f),
        )

        // Seed the PERCLOS window with the just-collected calibration frames re-evaluated
        // against the new threshold. Without seeding, the buffer is empty when state flips
        // to NORMAL — a single natural blink then inflates perclos to 60-80 % (5 closed
        // frames out of 6 total) and the score spikes right after calibration.
        perclosWindow.clear()  // defensive
        for ((ts, openness) in calibrationSamples) {
            perclosWindow.addLast(FrameRecord(ts, openness < eyeClosedThreshold))
        }
        // Anchor blink warmup at the start of calibration so BLINK_MIN_OBSERVATION_MS (30s)
        // ticks in parallel with data collection — otherwise blink deviation only starts
        // penalizing 30 s after calibration ends, which is unnecessarily conservative.
        monitoringStartMs = calibrationSamples.first().first

        Log.d("FatigueScorer", "Calibration done: baseline=$baseline closed=$eyeClosedThreshold open=$eyeOpenThreshold samples=${calibrationSamples.size} seededPerclos=${perclosWindow.size}")
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
        perclos: Float = 0f,
        perclosContribution: Float = 0f,
        blinkContribution: Float = 0f,
        yawnContribution: Float = 0f,
    ): FatigueAssessment {
        return FatigueAssessment(
            score = score.coerceIn(0f, 100f),
            fatigueState = currentState,
            blinkRate = blinkRate,
            isYawning = isYawning,
            isFaceDetected = isFaceDetected,
            timestampMs = timestampMs,
            perclos = perclos,
            perclosContribution = perclosContribution,
            blinkContribution = blinkContribution,
            yawnContribution = yawnContribution,
        )
    }
}
