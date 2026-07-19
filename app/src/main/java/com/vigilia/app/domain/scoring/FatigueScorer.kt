package com.vigilia.app.domain.scoring

import android.util.Log
import com.vigilia.app.domain.model.FatigueAssessment
import com.vigilia.app.domain.model.FatigueMetrics
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.lighting.LightingMode
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
class FatigueScorer(
    private val calibrationEnabled: Boolean = true,
    private val lightingModeProvider: () -> LightingMode = { LightingMode.NORMAL },
    // Optional lux telemetry passthrough. When null (no TYPE_LIGHT sensor available) the
    // scorer still functions; the field is echoed into the produced assessment for CSV logging.
    private val ambientLuxProvider: () -> Float? = { null },
) {

    private companion object {
        // 30-second rolling window aligned with the PERCLOS literature and CLAUDE.md.
        // Was 6_000L briefly — that made the score sensitive to any 3-5s episode of
        // "eyes appear closed" (which happens easily when the driver glances aside).
        const val PERCLOS_WINDOW_MS = 30_000L
        const val BLINK_WINDOW_MS = 60_000L
        const val YAWN_THRESHOLD_PROB = 0.38f
        const val YAWN_DURATION_MS = 1_500L
        // Was 5_000L → 3_000L → 4_000L. 5s pinned the score high for too long. 3s made
        // recovery feel abrupt ("score derretendo instantaneamente" when yawn releases).
        //
        // New model (field-tested): treat a confirmed yawn as a fatigue impulse whose
        // score contribution HOLDS at full for a short window, then LINEARLY DECAYS toward
        // zero over a longer window. This lets the score honestly express "a yawn happened,
        // fatigue is elevated" while decaying naturally instead of falling off a cliff via
        // an arbitrary timer. Users reported the old 4s cliff-drop felt wrong — "the person
        // yawned, why did the score drop back to baseline in 4 seconds?".
        const val YAWN_HOLD_MS = 3_000L    // full-strength contribution for the first 3s post-confirmation
        const val YAWN_DECAY_MS = 30_000L  // then linearly decay to 0 over the next 30s
        // Brief mouth-close tolerance: door not reset mid-yawn due to speaking/coughing frame
        const val YAWN_GRACE_MS = 300L
        // Was 0.3f — score converged in ~4-5 frames (~150 ms) after any raw drop, giving
        // the "score derretendo" perception when a yawn released. 0.2 keeps 80% weight on
        // the previous smoothed value, extending convergence to ~10-15 frames (~400-500 ms)
        // — noticeably gentler drop without hiding real changes (transition gates still
        // require multi-second sustain).
        const val SMOOTHING_ALPHA = 0.2f

        // Generic thresholds — replaced by calibrated values when calibration runs
        const val EYE_CLOSED_THRESHOLD_DEFAULT = 0.3f
        const val EYE_OPEN_THRESHOLD_DEFAULT = 0.4f

        // PERCLOS is the primary fatigue signal (weight 65). Total sums to 90 (clamped
        // to 100) so a *combination* is required to reach FATIGUED (>70), not any single
        // signal alone. Blink was 20 → 15 → 10: focus-blink at the phone camera runs
        // 25-30/min naturally, driving the deviation to ~1.0 (10 pts) even for wide-awake
        // users; keeping blink at 15 pushed the baseline near WARNING when combined with
        // mild PERCLOS. Yawn was 25 → 15: a single yawn is a tier-2 signal in the
        // automotive literature and should not promote to FATIGUED on its own — real
        // fatigue is detected by PERCLOS+yawn in combination.
        const val SCORE_WEIGHT_PERCLOS = 65f
        const val SCORE_WEIGHT_BLINK = 10f
        const val SCORE_WEIGHT_YAWN = 15f

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
        // Was 5_000L → 3_000L → 4_000L. 5s felt "stuck" after recovery. 3s felt too
        // trigger-happy in the other direction — a brief posture correction dropped the
        // alert prematurely. 4s matches the upgrade gate (WARNING→FATIGUED = 4s), so both
        // directions require equal sustain. Combined with the slower smoothing (α=0.2) and
        // the 1s-longer yawn window, recovery lands around 8-10s total.
        const val TRANSITION_FATIGUED_TO_WARNING_MS = 4_000L

        // Cap on per-frame delta added to the transition accumulator. Prevents brief
        // excursions into the neutral score band from resetting recovery progress
        // while still preventing huge jumps after prolonged neutral gaps.
        const val TRANSITION_MAX_FRAME_DELTA_MS = 200L

        // Grace period before NO_FACE resets the state machine — absorbs brief detection glitches
        const val NO_FACE_GRACE_MS = 500L
        // LOW_LIGHT sees more MediaPipe detection dropouts than NORMAL — not as many as DARK.
        // Interpolated between NORMAL (500) and DARK (1500).
        const val NO_FACE_GRACE_MS_LOW_LIGHT = 1_000L
        // In DARK, MediaPipe drops face-detection more often. Widen grace so a run of failed
        // frames caused by low-light noise doesn't clear buffers on every headlight glare.
        const val NO_FACE_GRACE_MS_DARK = 1_500L

        // PERCLOS closure debounce: require this many consecutive raw-closed reads before a
        // frame counts as "closed" in the PERCLOS window. Filters MediaPipe blendshape jitter
        // (±0.05 openness noise) that was inflating baseline PERCLOS to 30-60 % in wide-awake
        // users when the front-camera AE masked a subjectively-dark ambient (Y=65-83, so the
        // LightingMonitor stayed in NORMAL). Real blinks (≥3 frames) and fatigue closures
        // (dozens of frames) still register — only single-frame flickers are rejected.
        // Strictly less than BLINK_MIN_CLOSED_FRAMES so 3-frame blinks still contribute here.
        const val PERCLOS_MIN_CLOSED_FRAMES = 2

        // Calibration
        const val CALIBRATION_DURATION_MS = 7_000L
        const val CALIBRATION_MIN_SAMPLES = 20
        // 0.60 → 0.40 → 0.30. Successive tightenings — the code always claimed alignment
        // with the PERCLOS-70 automotive standard ("frame counts as closed when openness
        // < 30 % of the calibrated open baseline") but the value 0.40 still meant 40 % of
        // baseline. Field test with 0.40 showed post-calibration score ≈ 40 in wide-awake
        // users because frames with openness 0.30-0.34 (screen focus, mild squint under
        // artificial light) counted as closed against a p90 × 0.40 threshold ≈ 0.34. At
        // 0.30 the threshold lands around 0.255 (with p90 ≈ 0.85) — only frames with real
        // closure count, and baseline PERCLOS drops to the expected 5-15 % for alert users.
        const val EYE_CLOSED_RATIO = 0.30f   // was 0.60, then 0.40
        // Was 0.15 — too permissive when calibration samples were noisy (dim light, subject
        // shifting). If p90 landed near 0.38, threshold pinned at 0.15 and frames with
        // openness 0.15-0.30 (still visibly open) counted as closed → PERCLOS baseline
        // inflated. 0.18 gives calibration a slightly stricter floor while still fitting
        // narrow-eyed users (p90 ≈ 0.45 → threshold = 0.18, exactly at the floor).
        const val EYE_CLOSED_MIN = 0.18f
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

        // Blink debounce: require this many consecutive frames below threshold before confirming closure.
        // Was 3 (≈100 ms at 30 fps) — field-reported that light/fast blinks (60-90 ms) were
        // being missed, users had to blink harder for detection to fire. Dropped to 2 (≈66 ms),
        // which still rejects single-frame MediaPipe flickers but captures natural fast blinks.
        // Matches PERCLOS_MIN_CLOSED_FRAMES so any confirmed blink also counts toward PERCLOS.
        const val BLINK_MIN_CLOSED_FRAMES = 2

        // Blink detection uses a SEPARATE (more lenient) threshold than PERCLOS. Rationale:
        // PERCLOS follows the automotive standard "closed = < 30 % of open baseline" which is
        // conservative (captures only genuine, sustained closure) — right for the fatigue
        // metric. Blinks, by contrast, are transient and often only bring the openness down
        // to ~50-60 % of baseline before rebounding, especially light/fast blinks. Under a
        // single 30 %-of-baseline threshold, MediaPipe was reading these light blinks as
        // "partial closure" (blendshape 0.28-0.35 vs. calibrated threshold ~0.255) and the
        // blink detector never fired. The literature (e.g. Wierwille et al.) uses ~50 % of
        // baseline for blink onset — matching that here.
        const val BLINK_CLOSED_THRESHOLD_DEFAULT = 0.50f
        const val BLINK_CLOSED_RATIO = 0.50f   // closed-for-blink = baseline * this
        const val BLINK_CLOSED_MIN = 0.30f     // floor for narrow-eyed calibrations
        const val BLINK_CLOSED_MAX = 0.60f     // ceiling so blink threshold never exceeds a sane range
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

        // Time-limit on the look-away freeze. Legit dashboard/mirror glances take 1-2s; past
        // that, the head position is no longer a "glance" — it's either sustained distraction
        // or actual fatigue (chin dropping toward chest). Under permanent freeze, fatigue with
        // head-down is silently masked (reported field bug: 20s of eyes-closed + head-down +
        // yawn → zero alerts). After this window we reset the fatigue buffers and resume
        // normal accumulation. Distraction with eyes open is protected by EAR — PERCLOS won't
        // inflate because openness stays above threshold.
        const val LOOK_AWAY_MAX_FREEZE_MS = 2_000L

        // Minimum buffer size before PERCLOS is trusted. After a time-limit exit we clear
        // perclosWindow so pre-look-away data doesn't contaminate; without this guard, the
        // first few frames would compute PERCLOS = closed/tiny_total = spurious 100%.
        // 60 frames ≈ 2s @ 30fps — smallest window that gives meaningful ratio.
        const val MIN_PERCLOS_FRAMES = 60

        // Microsleep detector — orthogonal to PERCLOS. Sustained continuous eye closure
        // promotes state directly, bypassing the PERCLOS-based FSM. Rationale: with weight
        // 65 and window 30 s, PERCLOS alone needs ~0.77 (23 s of the last 30 s closed) to
        // cross the WARNING gate (50). Field test showed 20 s of solid closure only reached
        // PERCLOS ≈ 0.68 → contribution 44 → below gate. Automotive DMS literature treats
        // 3-5 s of continuous closure as a critical microsleep event, so we detect it
        // categorically instead of diluting in the PERCLOS denominator.
        const val MICROSLEEP_WARNING_MS = 3_000L
        const val MICROSLEEP_FATIGUED_MS = 6_000L
    }

    private data class FrameRecord(val timestampMs: Long, val isEyeClosed: Boolean)
    private val perclosWindow = ArrayDeque<FrameRecord>()
    private val blinkTimestamps = ArrayDeque<Long>()

    private var isBlinking = false
    private var closedFrameCount = 0
    private var blinkStartTime: Long? = null
    private var monitoringStartMs = -1L

    // Debounce state for PERCLOS closure classification. `count` tracks consecutive raw-closed
    // reads; `lastClassified` holds the current debounced closed/open verdict. A frame flips
    // to closed only after PERCLOS_MIN_CLOSED_FRAMES consecutive raw-closed reads; a single
    // raw-open frame breaks the streak and resets both.
    private var perclosConsecutiveClosedCount = 0
    private var perclosLastClassifiedClosed = false

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
    // Two openness values per sample: `minAvg` is the min(blendshape, EAR) average used
    // at runtime (feeds perclosWindow seeding); `blendAvg` is blendshape-only and used
    // for the p90 baseline — scale-invariant so it works for narrow-eyed users where EAR
    // saturates below 1.0 even with eyes fully open. Timestamps are kept so finishCalibration()
    // can seed the perclosWindow with these frames, avoiding a zero-history transient right
    // when state transitions to NORMAL.
    private data class CalibrationSample(val ts: Long, val minAvg: Float, val blendAvg: Float)
    private val calibrationSamples = mutableListOf<CalibrationSample>()
    private var eyeClosedThreshold = EYE_CLOSED_THRESHOLD_DEFAULT
    private var eyeOpenThreshold = EYE_OPEN_THRESHOLD_DEFAULT

    // Independent, more lenient threshold used ONLY by the blink detector. Recalculated
    // by finishCalibration() as baseline * BLINK_CLOSED_RATIO; falls back to the default
    // when calibration is disabled.
    private var blinkClosedThreshold = BLINK_CLOSED_THRESHOLD_DEFAULT
    private var blinkOpenThreshold = BLINK_CLOSED_THRESHOLD_DEFAULT + 0.10f

    // Timestamp when the current look-away began (-1 = not looking away). Used on release
    // to shift buffer timestamps forward by the look-away duration so PERCLOS/blink windows
    // keep representing the last 30s / 60s of *forward-facing driving time*, not wall clock.
    private var lookAwayStartTime: Long = -1L
    // Snapshot of the assessment at the instant look-away began. Returned (with timestamp
    // updated) for every frame during look-away so smoothedScore / state / perclos stay
    // truly frozen — no drift from stale rawScore computed over a decimated buffer.
    private var lookAwayFrozenAssessment: FatigueAssessment? = null

    // Timestamp of the first debounced-closed frame in the current continuous-closure
    // streak. -1L when eyes are open or the streak was just broken. Feeds the microsleep
    // detector below.
    private var continuousClosureStartMs: Long = -1L

    fun processFrame(metrics: FatigueMetrics): FatigueAssessment {
        if (!metrics.isFaceDetected) {
            val now = metrics.timestampMs
            if (noFaceStartTime == null) noFaceStartTime = now
            val noFaceDuration = now - noFaceStartTime!!

            return if (noFaceDuration >= currentNoFaceGraceMs()) {
                // Sustained absence — transition to NO_FACE and drop stale detection buffers so
                // score doesn't jump back to WARNING/FATIGUED from old data when face returns.
                currentState = FatigueState.NO_FACE
                targetState = null
                transitionAccumulatedMs = 0L
                transitionLastCheckMs = 0L
                perclosWindow.clear()
                perclosConsecutiveClosedCount = 0
                perclosLastClassifiedClosed = false
                blinkTimestamps.clear()
                smoothedScore = 0f
                calibrationEligibleSinceMs = -1L
                firstCalibrationFrameMs = -1L
                calibrationBelowCount = 0
                continuousClosureStartMs = -1L
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

            calibrationSamples.add(
                CalibrationSample(currentTime, eyeOpenness, metrics.avgBlendshapeOpen)
            )

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
        //
        // We pause the scorer *completely*: no buffer add, NO buffer drain, no smoothing update.
        // Returning frames get the same FatigueAssessment snapshot taken at look-away entry.
        // On release we shift the timestamps of all buffered frames forward by the look-away
        // duration so the 30s/60s windows keep representing forward-facing driving time —
        // not wall-clock time. This eliminates the spike observed when the buffer decimates
        // during long look-aways and produces artificial PERCLOS on the first return frame.
        val isLookingAway = kotlin.math.abs(metrics.headYawDegrees) > LOOK_AWAY_YAW_DEGREES ||
                metrics.headPitchDegrees > LOOK_AWAY_PITCH_DEGREES_UP ||
                metrics.headPitchDegrees < -LOOK_AWAY_PITCH_DEGREES_DOWN

        // Look-away ENTRY: snapshot the current assessment; subsequent frames return it verbatim.
        if (isLookingAway && lookAwayStartTime < 0L) {
            lookAwayStartTime = currentTime
            lookAwayFrozenAssessment = createAssessment(
                score = smoothedScore,
                timestampMs = currentTime,
                isFaceDetected = true,
                blinkRate = blinkTimestamps.size.toFloat(),
                isYawning = isCurrentlyYawning,
            )
            // Clear microsleep timer: while looking away, head-pose is off-axis and eye
            // blendshapes are noisy — don't extend a "continuous closure" streak with
            // frames we're not measuring reliably.
            continuousClosureStartMs = -1L
        }

        // While LOOKING AWAY: two sub-paths depending on duration.
        //   1) < LOOK_AWAY_MAX_FREEZE_MS: quick glance (mirror/dashboard) — return the
        //      frozen snapshot; the buffer stays intact so nothing spuriously accumulates.
        //   2) >= LOOK_AWAY_MAX_FREEZE_MS: sustained rotation/tilt — no longer treated as
        //      a glance. Reset fatigue buffers and FALL THROUGH to normal processing so
        //      real fatigue signals (head-down + eyes-closed + yawn) can accumulate.
        //      Distraction with eyes open is safe: EAR keeps openness > threshold → PERCLOS
        //      stays 0.
        if (isLookingAway) {
            val lookAwayElapsed = currentTime - lookAwayStartTime
            if (lookAwayElapsed >= LOOK_AWAY_MAX_FREEZE_MS) {
                Log.d(
                    "FatigueScorer",
                    "Look-away freeze time-limit exceeded (${lookAwayElapsed}ms) — resetting fatigue buffers and resuming",
                )
                perclosWindow.clear()
                blinkTimestamps.clear()
                monitoringStartMs = currentTime
                perclosConsecutiveClosedCount = 0
                perclosLastClassifiedClosed = false
                closedFrameCount = 0
                isBlinking = false
                blinkStartTime = null
                continuousClosureStartMs = -1L
                // Yawn timers intentionally NOT reset — if the driver was mid-yawn when the
                // time-limit hit, the timer keeps counting and the yawn confirms shortly after.
                lookAwayStartTime = -1L
                lookAwayFrozenAssessment = null
                // Fall through to normal PERCLOS / blink / yawn / score computation below
            } else {
                perclosConsecutiveClosedCount = 0
                perclosLastClassifiedClosed = false
                yawnStartTime = null
                yawnGraceStart = null
                closedFrameCount = 0
                isBlinking = false
                blinkStartTime = null
                return lookAwayFrozenAssessment?.copy(timestampMs = currentTime)
                    ?: createAssessment(smoothedScore, currentTime, true, 0f, isCurrentlyYawning)
            }
        }

        // Look-away EXIT: shift buffer timestamps forward by the look-away duration so aged
        // frames don't drain prematurely on the next `while` sweep. Semantics: the buffer
        // represents "last N seconds of forward-facing driving", not wall clock.
        if (lookAwayStartTime >= 0L) {
            val lookAwayDurationMs = currentTime - lookAwayStartTime
            if (lookAwayDurationMs > 0) {
                val shiftedPerclos = perclosWindow.map {
                    FrameRecord(it.timestampMs + lookAwayDurationMs, it.isEyeClosed)
                }
                perclosWindow.clear()
                perclosWindow.addAll(shiftedPerclos)

                val shiftedBlinks = blinkTimestamps.map { it + lookAwayDurationMs }
                blinkTimestamps.clear()
                blinkTimestamps.addAll(shiftedBlinks)

                // Shift monitoringStartMs too so the blink-warmup window (BLINK_MIN_OBSERVATION_MS)
                // measures driving time, not wall clock — otherwise a long look-away would end
                // the warmup prematurely with a nearly-empty blink buffer.
                if (monitoringStartMs > 0) monitoringStartMs += lookAwayDurationMs
            }
            Log.d("FatigueScorer", "Look-away ended after ${lookAwayDurationMs}ms — buffers time-shifted, resuming scoring")
            lookAwayStartTime = -1L
            lookAwayFrozenAssessment = null
        }

        val rawEyeClosed = metrics.leftEyeOpenProbability < eyeClosedThreshold ||
                metrics.rightEyeOpenProbability < eyeClosedThreshold

        // Debounce: reject single-frame jitter from MediaPipe blendshapes. The classifier
        // flips to closed only after PERCLOS_MIN_CLOSED_FRAMES consecutive raw-closed reads;
        // one raw-open frame resets the streak. Real closures (blinks ≥3 frames, fatigue
        // closures much longer) pass unchanged with 1-frame delay (~33 ms).
        val debouncedEyeClosed = if (rawEyeClosed) {
            perclosConsecutiveClosedCount++
            if (perclosConsecutiveClosedCount >= PERCLOS_MIN_CLOSED_FRAMES) {
                perclosLastClassifiedClosed = true
            }
            perclosLastClassifiedClosed
        } else {
            perclosConsecutiveClosedCount = 0
            perclosLastClassifiedClosed = false
            false
        }

        // Microsleep tracking — anchor a timestamp on the first debounced-closed frame of
        // the current streak. A single open frame breaks the streak. See the override block
        // after updateState() for how this drives categorical WARNING/FATIGUED promotion.
        if (debouncedEyeClosed) {
            if (continuousClosureStartMs < 0L) continuousClosureStartMs = currentTime
        } else {
            continuousClosureStartMs = -1L
        }

        // 1. PERCLOS Calculation — buffer add + drain by age. We only reach here when NOT
        // looking away (early return above), so no isLookingAway guard is needed.
        perclosWindow.addLast(FrameRecord(currentTime, debouncedEyeClosed))
        while (perclosWindow.isNotEmpty() && currentTime - perclosWindow.first().timestampMs > PERCLOS_WINDOW_MS) {
            perclosWindow.removeFirst()
        }
        val perclos = when {
            perclosWindow.isEmpty() -> 0f
            // Guard: after a look-away time-limit reset, the buffer starts empty and any
            // early closed frames would compute perclos = closed/tiny_total = spurious
            // high value. Wait until we have MIN_PERCLOS_FRAMES of real data.
            perclosWindow.size < MIN_PERCLOS_FRAMES -> 0f
            else -> perclosWindow.count { it.isEyeClosed }.toFloat() / perclosWindow.size
        }

        if (monitoringStartMs < 0) monitoringStartMs = currentTime

        // 2. Blink Detection — uses min(left,right) to match PERCLOS OR logic and handle
        // asymmetric readings (e.g. one eye inflated by glasses reflection).
        // Temporal debounce: requires BLINK_MIN_CLOSED_FRAMES consecutive frames below threshold.
        // Uses blinkClosedThreshold / blinkOpenThreshold — more lenient than eyeClosedThreshold
        // so light/fast blinks (which only partially close the eye per MediaPipe) still register.
        // Max duration: closure > BLINK_MAX_DURATION_MS is sustained (PERCLOS), not a blink.
        val eyeMin = minOf(metrics.leftEyeOpenProbability, metrics.rightEyeOpenProbability)
        if (!isBlinking) {
            if (eyeMin < blinkClosedThreshold) {
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
        } else if (eyeMin > blinkOpenThreshold) {
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
        // isCurrentlyYawning tracks the *physical* yawn (mouth open past duration threshold,
        // until it closes past the grace period). lastYawnDetectedTime marks the instant
        // the yawn was first confirmed — that's the anchor for the hold+decay contribution
        // curve below. Multiple yawns each refresh the anchor.
        if (metrics.mouthOpenProbability > YAWN_THRESHOLD_PROB) {
            yawnGraceStart = null
            if (yawnStartTime == null) {
                yawnStartTime = currentTime
            } else if (currentTime - yawnStartTime!! >= YAWN_DURATION_MS && !isCurrentlyYawning) {
                // First confirmation of this yawn instance — anchor the decay curve here
                isCurrentlyYawning = true
                lastYawnDetectedTime = currentTime
            }
        } else {
            // Grace period: tolerate brief mouth closures (cough, speech) without ending the yawn
            if (yawnStartTime != null) {
                if (yawnGraceStart == null) {
                    yawnGraceStart = currentTime
                } else if (currentTime - yawnGraceStart!! > YAWN_GRACE_MS) {
                    yawnStartTime = null
                    yawnGraceStart = null
                    isCurrentlyYawning = false
                }
            }
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
        // Yawn contributes full weight during YAWN_HOLD_MS, then decays linearly to zero
        // across YAWN_DECAY_MS. Anchored at the instant the yawn was first confirmed.
        // Independent of whether the mouth is still open — physiologically, the fatigue
        // signal from a yawn persists beyond the mouth closing.
        val yawnContribution = lastYawnDetectedTime?.let { confirmedAt ->
            val elapsed = currentTime - confirmedAt
            when {
                elapsed < 0L -> 0f
                elapsed <= YAWN_HOLD_MS -> SCORE_WEIGHT_YAWN
                elapsed <= YAWN_HOLD_MS + YAWN_DECAY_MS -> {
                    val decayProgress = (elapsed - YAWN_HOLD_MS).toFloat() / YAWN_DECAY_MS.toFloat()
                    SCORE_WEIGHT_YAWN * (1f - decayProgress)
                }
                else -> 0f
            }
        } ?: 0f

        val rawScore = perclosContribution + blinkContribution + yawnContribution
        smoothedScore = (SMOOTHING_ALPHA * rawScore) + (1f - SMOOTHING_ALPHA) * smoothedScore

        Log.d("FatigueScorer", "score=$smoothedScore state=$currentState perclos=$perclos blinkRate=$blinkRate yawning=$isCurrentlyYawning lookAway=$isLookingAway yaw=${metrics.headYawDegrees} pitch=${metrics.headPitchDegrees}")

        // 5. State Machine with Hysteresis
        updateState(smoothedScore, currentTime)

        // 6. Microsleep override — sustained continuous closure promotes state directly,
        // bypassing the PERCLOS-based FSM. See constants MICROSLEEP_WARNING_MS /
        // MICROSLEEP_FATIGUED_MS for the fixed thresholds. Promotion is one-way — cannot
        // demote. While microsleep is above threshold, the FSM's demotion accumulator is
        // also cleared so state doesn't oscillate back to NORMAL when PERCLOS is still
        // below its MIN_PERCLOS_FRAMES guard (score reads as 0).
        if (continuousClosureStartMs >= 0L) {
            val closureMs = currentTime - continuousClosureStartMs
            val microsleepState = when {
                closureMs >= MICROSLEEP_FATIGUED_MS -> FatigueState.FATIGUED
                closureMs >= MICROSLEEP_WARNING_MS -> FatigueState.WARNING
                else -> null
            }
            if (microsleepState != null) {
                if (stateSeverity(microsleepState) > stateSeverity(currentState)) {
                    currentState = microsleepState
                }
                targetState = null
                transitionAccumulatedMs = 0L
            }
        }

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
        perclosConsecutiveClosedCount = 0
        perclosLastClassifiedClosed = false
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
        blinkClosedThreshold = BLINK_CLOSED_THRESHOLD_DEFAULT
        blinkOpenThreshold = BLINK_CLOSED_THRESHOLD_DEFAULT + 0.10f
        closedFrameCount = 0
        blinkStartTime = null
        monitoringStartMs = -1L
        lookAwayStartTime = -1L
        lookAwayFrozenAssessment = null
        continuousClosureStartMs = -1L
    }

    private fun finishCalibration() {
        if (calibrationSamples.size < CALIBRATION_MIN_SAMPLES) {
            Log.w("FatigueScorer", "Calibration skipped: only ${calibrationSamples.size} samples, keeping defaults")
            return
        }
        // Baseline uses blendshape-only openness — scale-invariant, so p90 correctly reflects
        // "eyes fully open" regardless of anatomical eye size. Falling back to min-based
        // openness (as before) would let EAR normalization deflate p90 for narrow-eyed users
        // and produce a threshold pinned at the EYE_CLOSED_MIN floor.
        val blendSorted = calibrationSamples.map { it.blendAvg }.sorted()
        val p90Index = ((blendSorted.size - 1) * 0.90f).toInt()
        val baseline = blendSorted[p90Index]
        eyeClosedThreshold = (baseline * EYE_CLOSED_RATIO).coerceIn(EYE_CLOSED_MIN, EYE_CLOSED_MAX)
        eyeOpenThreshold = (eyeClosedThreshold + 0.10f).coerceIn(
            eyeClosedThreshold + 0.05f,
            (eyeClosedThreshold + 0.20f).coerceAtMost(0.90f),
        )
        // Blink threshold — more lenient than PERCLOS so light/fast blinks (that only
        // partially close the eye per MediaPipe) still register. See BLINK_CLOSED_RATIO
        // docstring for rationale.
        blinkClosedThreshold = (baseline * BLINK_CLOSED_RATIO).coerceIn(BLINK_CLOSED_MIN, BLINK_CLOSED_MAX)
        blinkOpenThreshold = (blinkClosedThreshold + 0.10f).coerceAtMost(0.90f)

        // Seed the PERCLOS window with the just-collected calibration frames re-evaluated
        // against the new threshold. Uses the min-based openness (same metric the runtime
        // check uses) so seeded frames are comparable to real-time frames. Without seeding,
        // the buffer is empty when state flips to NORMAL — a single natural blink then
        // inflates perclos to 60-80 % (5 closed frames out of 6 total) and the score
        // spikes right after calibration.
        perclosWindow.clear()  // defensive
        for (sample in calibrationSamples) {
            perclosWindow.addLast(FrameRecord(sample.ts, sample.minAvg < eyeClosedThreshold))
        }
        // Reset debounce so the first monitoring frame starts a fresh streak. Calibration
        // frames are gated to be open, so the counter is naturally 0, but resetting is
        // defensive in case the stabilization gate ever admits a closed frame in the future.
        perclosConsecutiveClosedCount = 0
        perclosLastClassifiedClosed = false
        // Anchor blink warmup at the start of calibration so BLINK_MIN_OBSERVATION_MS (30s)
        // ticks in parallel with data collection — otherwise blink deviation only starts
        // penalizing 30 s after calibration ends, which is unnecessarily conservative.
        monitoringStartMs = calibrationSamples.first().ts

        Log.d("FatigueScorer", "Calibration done: baseline=$baseline closed=$eyeClosedThreshold open=$eyeOpenThreshold blinkClosed=$blinkClosedThreshold blinkOpen=$blinkOpenThreshold samples=${calibrationSamples.size} seededPerclos=${perclosWindow.size}")
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
            ambientLightLux = ambientLuxProvider(),
            lightingMode = lightingModeProvider().name,
        )
    }

    // Ordinal mapping used by the microsleep override to compare active-scoring states.
    // NO_FACE and CALIBRATING return -1 so they never participate — but the override block
    // is unreachable while state is either of those (early returns above).
    private fun stateSeverity(s: FatigueState): Int = when (s) {
        FatigueState.NORMAL -> 0
        FatigueState.WARNING -> 1
        FatigueState.FATIGUED -> 2
        else -> -1
    }

    private fun currentNoFaceGraceMs(): Long = when (lightingModeProvider()) {
        LightingMode.DARK -> NO_FACE_GRACE_MS_DARK
        LightingMode.LOW_LIGHT -> NO_FACE_GRACE_MS_LOW_LIGHT
        else -> NO_FACE_GRACE_MS
    }
}
