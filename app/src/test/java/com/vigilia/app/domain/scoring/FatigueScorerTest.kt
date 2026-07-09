package com.vigilia.app.domain.scoring

import com.vigilia.app.domain.model.FatigueMetrics
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.lighting.LightingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FatigueScorerTest {

    private lateinit var scorer: FatigueScorer

    @Before
    fun setUp() {
        scorer = FatigueScorer(calibrationEnabled = false)
    }

    /**
     * Drives the scorer with closed eyes until WARNING state is reached naturally
     * through PERCLOS accumulation + hysteresis timer. Returns the next timestamp to use.
     */
    private fun advanceToWarning(startTime: Long): Long {
        var t = startTime
        while (scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)).fatigueState != FatigueState.WARNING) {
            t += 100
        }
        return t + 100
    }

    @Test
    fun `processFrame with no face detected returns NO_FACE state`() {
        // First frame starts the grace timer; second frame at 600ms exceeds the 500ms grace period.
        scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, false, 1000L))
        val assessment = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, false, 1600L))
        assertEquals(FatigueState.NO_FACE, assessment.fatigueState)
        assertEquals(0f, assessment.score, 0.01f)
    }

    @Test
    fun `PERCLOS calculation correctly identifies closed eyes`() {
        // Send 10 frames, 5 closed, 5 open
        for (i in 0 until 5) {
            scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, i * 100L))
        }
        for (i in 5 until 10) {
            val assessment = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, i * 100L))
            if (i == 9) {
                // 5 of 10 frames closed → PERCLOS = 50% → PERCLOS contribution > 0
                assertTrue("Score should be positive due to PERCLOS", assessment.score > 0)
            }
        }
    }

    @Test
    fun `Blink detection correctly counts blinks`() {
        var currentTime = 1000L

        // Each blink needs BLINK_MIN_CLOSED_FRAMES (3) consecutive closed frames to be counted
        repeat(2) {
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)); currentTime += 100
            // 3 closed frames to satisfy debounce
            repeat(3) {
                scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)); currentTime += 100
            }
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)); currentTime += 100 // Open again
        }

        val finalAssessment = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime))
        assertEquals(2f, finalAssessment.blinkRate, 0.01f)
    }

    @Test
    fun `Yawn detection triggers after 2 seconds of mouth opening`() {
        var currentTime = 1000L

        // Mouth open for 2s - should trigger
        repeat(20) {
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.8f, true, currentTime))
            currentTime += 100
        }

        val finalAssessment = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.8f, true, currentTime))
        assertTrue("Should be yawning at ${currentTime}ms", finalAssessment.isYawning)
    }

    @Test
    fun `NORMAL to WARNING transition requires sustained score`() {
        var currentTime = 1000L

        // Open eyes — score ~0 (no blinks, no PERCLOS, no yawn)
        repeat(10) {
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime))
            currentTime += 100
        }

        // Closed eyes drive PERCLOS up until score exceeds 50
        while (scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)).score <= 50f) {
            currentTime += 100
        }

        val startTransitionTime = currentTime
        // Sustained for 2.9s — must still be NORMAL
        while (currentTime - startTransitionTime < 2900L) {
            assertEquals(FatigueState.NORMAL, scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)).fatigueState)
            currentTime += 100
        }

        // At 3s threshold → WARNING
        currentTime = startTransitionTime + 3000L
        assertEquals(FatigueState.WARNING, scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)).fatigueState)
    }

    @Test
    fun `WARNING to FATIGUED transition requires sustained score`() {
        var currentTime = advanceToWarning(1000L)

        // Register blinks so blinkTimestamps is non-empty and blink contribution is active.
        repeat(3) {
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)); currentTime += 100
            scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)); currentTime += 100
        }

        // Drain score below the WARNING→NORMAL threshold (25) so the target switches to
        // NORMAL — that direction change resets the transition accumulator. When push
        // raises score back above 55, direction flips to FATIGUED and accumulation starts
        // fresh at 0, aligning with startTransitionTime.
        while (scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)).score >= 25f) {
            currentTime += 100
        }

        // Now push score above 70 with closed eyes + yawn. The very first frame that
        // exceeds 70 is when the scorer sets transitionStartTime = currentTime.
        while (scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.8f, true, currentTime)).score <= 70f) {
            currentTime += 100
        }

        val startTransitionTime = currentTime
        // Sustained for 3.9s — must still be WARNING
        while (currentTime - startTransitionTime < 3900L) {
            assertEquals(FatigueState.WARNING, scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.8f, true, currentTime)).fatigueState)
            currentTime += 100
        }

        // At 4s threshold → FATIGUED
        currentTime = startTransitionTime + 4000L
        assertEquals(FatigueState.FATIGUED, scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.8f, true, currentTime)).fatigueState)
    }

    @Test
    fun `WARNING to NORMAL transition requires sustained low score`() {
        var currentTime = advanceToWarning(1000L)

        // Open eyes to drain PERCLOS. The first open frame after the closed-eye WARNING
        // phase registers a blink (isBlinking was true), so blink contribution becomes active.
        while (scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)).score >= 30f) {
            // Occasional closed frame keeps blink rate measurable
            if (currentTime % 3000L == 0L) {
                scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime))
                currentTime += 100
            }
            currentTime += 100
        }

        val startTransitionTime = currentTime

        // Sustained for 4.9s — must still be WARNING
        while (currentTime - startTransitionTime < 4900L) {
            assertEquals(FatigueState.WARNING, scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)).fatigueState)
            currentTime += 100
        }

        // At 5s threshold → NORMAL
        currentTime = startTransitionTime + 5000L
        assertEquals(FatigueState.NORMAL, scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)).fatigueState)
    }

    @Test
    fun `calibration starts after short stabilization when frames are good`() {
        // With good framing (openness=0.85) the stabilization gate opens around 800 ms.
        // On the frame that opens the gate, calibrationStartMs=currentTime so elapsed=0
        // and progress=0. Progress advances from the frame *after* the gate opens.
        val calibratingScorer = FatigueScorer(calibrationEnabled = true)
        var t = 1000L

        // Feed frames while still inside the stabilization window (< 800 ms).
        val stableStart = t
        while (t - stableStart < 800L) {
            val a = calibratingScorer.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
            assertEquals(FatigueState.CALIBRATING, a.fatigueState)
            assertEquals("progress must stay 0 during stabilization", 0f, a.calibrationProgress, 0.001f)
            t += 100
        }
        // Frame at t = stableStart + 800: gate opens, calibrationStartMs is set here.
        // Progress is still 0 at this exact frame (elapsed=0).
        calibratingScorer.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
        t += 100
        // Next frame: elapsed>0 so progress must be > 0.
        val progressing = calibratingScorer.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
        assertEquals(FatigueState.CALIBRATING, progressing.fatigueState)
        assertTrue("Collection should be progressing (progress > 0)", progressing.calibrationProgress > 0f)
    }

    @Test
    fun `calibration tolerates a natural blink during stabilization`() {
        // A 3-frame blink (~100 ms) in the middle of the stabilization window must not
        // reset the eligibility timer — that was the bug causing 15-60 s stalls.
        val calibratingScorer = FatigueScorer(calibrationEnabled = true)
        var t = 1000L

        // 500 ms of good frames.
        repeat(5) {
            val a = calibratingScorer.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
            assertEquals(0f, a.calibrationProgress, 0.001f)
            t += 100
        }
        // 3 blink frames (openness dropped near 0) — must be tolerated.
        repeat(3) {
            calibratingScorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            t += 100
        }
        // 200 more ms of good frames — cumulative eligible time is >= 800 ms.
        // Since the tolerance kept calibrationEligibleSinceMs alive, the gate opens.
        repeat(2) { calibratingScorer.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)); t += 100 }
        val next = calibratingScorer.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
        assertTrue(
            "Blink of 3 frames must not reset gate; collection should have started (progress > 0, got ${next.calibrationProgress})",
            next.calibrationProgress > 0f,
        )
    }

    @Test
    fun `calibration falls back after 3s of bad framing`() {
        // Persistently bad framing (glasses reflection, harsh lighting) — openness stays
        // at 0.1. Stabilization can never converge, but the CALIBRATION_STABILIZATION_MAX_MS
        // safety net must force sample collection after 3 s so calibration doesn't stall
        // indefinitely.
        val calibratingScorer = FatigueScorer(calibrationEnabled = true)
        var t = 1000L

        // 3 s of persistently low openness — every frame stays in stabilization, progress=0.
        val startT = t
        while (t - startT < 3000L) {
            val a = calibratingScorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t))
            assertEquals(FatigueState.CALIBRATING, a.fatigueState)
            assertEquals("progress must stay 0 while gate hasn't force-started", 0f, a.calibrationProgress, 0.001f)
            t += 100
        }
        // Frame at t = startT + 3000: force-start fires here, calibrationStartMs=this frame,
        // so elapsed=0 and progress=0 on this exact frame.
        calibratingScorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t))
        t += 100
        // Next frame: elapsed>0 confirms the fallback made collection start.
        val progressing = calibratingScorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t))
        assertEquals(FatigueState.CALIBRATING, progressing.fatigueState)
        assertTrue(
            "Fallback must kick in after 3 s — progress should be > 0 (got ${progressing.calibrationProgress})",
            progressing.calibrationProgress > 0f,
        )
    }

    @Test
    fun `consecutive scorer instances do not inflate score at start of second session`() {
        // Simulates the reported bug: two Start→Stop cycles on the MonitoringScreen with
        // calibration enabled. Each session gets a fresh scorer (mirrors MonitoringService
        // recreating it on startMonitoring). After the second session's calibration + a
        // handful of normal frames, the score must stay well below WARNING (40) — the
        // regression symptom is a jump to 60–70 within a few hundred milliseconds.
        fun runSessionAndReturnFinalScore(startTime: Long): Float {
            val s = FatigueScorer(calibrationEnabled = true)
            var t = startTime
            // 9 s of well-framed, eyes-open frames — covers stabilization gate (800 ms)
            // + full sample collection window (7 s) with margin.
            repeat(90) { s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)); t += 100 }
            // 30 frames (~1 s) of normal usage with openness 0.6 — well above any sane
            // calibrated threshold. Score must remain low.
            var last = s.processFrame(FatigueMetrics(0.6f, 0.6f, 0.1f, true, t))
            repeat(29) { t += 100; last = s.processFrame(FatigueMetrics(0.6f, 0.6f, 0.1f, true, t)) }
            return last.score
        }

        val firstScore = runSessionAndReturnFinalScore(1000L)
        val secondScore = runSessionAndReturnFinalScore(1_000_000L) // large gap simulates a fresh session
        assertTrue("First session final score should be low ($firstScore)", firstScore < 30f)
        assertTrue("Second session final score should not be inflated ($secondScore)", secondScore < 30f)
    }

    @Test
    fun `look away frames do not inflate perclos`() {
        // 30 frames with eyes reading "closed" (openness=0.1) but head clearly turned
        // (yaw=35°). The scorer must skip these frames — perclos stays 0, score stays 0.
        var t = 1000L
        repeat(30) {
            val a = scorer.processFrame(
                FatigueMetrics(
                    leftEyeOpenProbability = 0.1f,
                    rightEyeOpenProbability = 0.1f,
                    mouthOpenProbability = 0.1f,
                    isFaceDetected = true,
                    timestampMs = t,
                    headYawDegrees = 35f,
                )
            )
            assertEquals(0f, a.score, 0.01f)
            t += 100
        }
    }

    @Test
    fun `perclos buffer survives a brief look away without inflating`() {
        // Frontal closed-eye frames accumulate PERCLOS → score climbs.
        var t = 1000L
        repeat(60) {
            scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t))
            t += 100
        }
        val scoreBeforeLookAway = scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)).score
        assertTrue("Score should be climbing before look-away ($scoreBeforeLookAway)", scoreBeforeLookAway > 10f)

        // Look-away phase: 20 frames with head turned. Buffer stays frozen, score cannot climb further.
        var scoreDuringLookAway = scoreBeforeLookAway
        repeat(20) {
            t += 100
            scoreDuringLookAway = scorer.processFrame(
                FatigueMetrics(0.1f, 0.1f, 0.1f, true, t, headYawDegrees = 40f)
            ).score
        }
        // Score during look-away must not exceed the pre-look-away score by more than smoothing tail
        // (the exponential smoother continues on the frozen rawScore, so a small delta is OK).
        assertTrue(
            "Score should not climb during look-away (${scoreBeforeLookAway} → ${scoreDuringLookAway})",
            scoreDuringLookAway <= scoreBeforeLookAway + 5f,
        )

        // Return to frontal with eyes open — score drains as the buffer refills with open frames.
        repeat(60) {
            t += 100
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t))
        }
        val finalScore = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t)).score
        assertTrue("Score should have drained after eyes-open frontal phase ($finalScore)", finalScore < scoreBeforeLookAway)
    }

    @Test
    fun `fatigued now requires more than perclos alone`() {
        // Feed only closed-eye frontal frames indefinitely (no yawn, no blinks). rawScore
        // steady state = perclos * 65 = 65 pts. With the 70-pt FATIGUED threshold restored,
        // the state must reach WARNING but never FATIGUED without a yawn or blink deviation.
        var t = 1000L
        var lastState = FatigueState.NORMAL
        // Run for 15 s of continuous eyes-closed frontal — plenty to hit steady state and
        // trip the NORMAL→WARNING transition (score>40 sustained 2s).
        repeat(150) {
            lastState = scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)).fatigueState
            t += 100
        }
        assertEquals("Should reach WARNING with sustained PERCLOS alone", FatigueState.WARNING, lastState)

        // Continue for another 10 s. With smoothed score converging to ~65 (below 70),
        // it must never promote to FATIGUED.
        repeat(100) {
            val a = scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t))
            assertTrue(
                "Should not promote to FATIGUED without yawn/blink deviation (state=${a.fatigueState} score=${a.score})",
                a.fatigueState != FatigueState.FATIGUED,
            )
            t += 100
        }
    }

    @Test
    fun `frequent blinks alone do not promote to warning`() {
        // Regression for the user complaint: piscando por alguns segundos entrava em WARNING.
        // With the PERCLOS-70 realignment (EYE_CLOSED_RATIO=0.40) and weight adjustments,
        // 60 s of normal frames with a blink every 2 s (30 blinks/min, high but not sleepy)
        // should stay in NORMAL. Openness 0.70 is comfortably above the calibrated threshold.
        var t = 1000L
        // Blink pattern: 5 frames closed (blink) + 15 frames open (750 ms open + 150 ms blink)
        // per 2-second interval → 30 blinks/min.
        repeat(30) {
            // 15 frames open
            repeat(15) { scorer.processFrame(FatigueMetrics(0.70f, 0.70f, 0.1f, true, t)); t += 100 }
            // 5 frames blink (closed)
            repeat(5) { scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t)); t += 100 }
        }
        val finalAssessment = scorer.processFrame(FatigueMetrics(0.70f, 0.70f, 0.1f, true, t))
        assertTrue(
            "Frequent blinks alone should not reach WARNING (state=${finalAssessment.fatigueState}, score=${finalAssessment.score})",
            finalAssessment.fatigueState == FatigueState.NORMAL,
        )
    }

    @Test
    fun `state recovers from warning to normal when driver is alert`() {
        // Regression for the second user complaint: stuck in WARNING after returning to normal.
        // Drive score up until WARNING, then feed 15 s of clearly-open frames — must transition
        // back to NORMAL (needs TRANSITION_WARNING_TO_NORMAL_SCORE=30 + realigned threshold).
        var currentTime = advanceToWarning(1000L)
        // Confirm we're really in WARNING.
        var current = scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime))
        assertEquals(FatigueState.WARNING, current.fatigueState)
        currentTime += 100

        // 15 s of eyes-open frames (openness=0.80) — score should drain and state transitions back.
        var lastState = current.fatigueState
        repeat(150) {
            lastState = scorer.processFrame(FatigueMetrics(0.80f, 0.80f, 0.1f, true, currentTime)).fatigueState
            currentTime += 100
        }
        assertEquals(
            "Should have recovered from WARNING to NORMAL after 15 s of alert framing",
            FatigueState.NORMAL,
            lastState,
        )
    }

    @Test
    fun `assessment exposes subscores for telemetry`() {
        // Instrumentation guarantee: FatigueAssessment carries perclos + the three
        // *Contribution sub-scores so the aggregated score can be dissected in the CSV.
        // We validate that the fields track the internal calculation across a mixed frame sequence.
        val s = FatigueScorer(calibrationEnabled = false)
        var t = 1000L

        // 40 s of closed-eye frames — well past blink warmup (30 s) so blink deviation activates.
        repeat(400) { s.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)); t += 100 }
        val closed = s.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t))
        assertTrue("perclos should be close to 1 with sustained closed eyes (was ${closed.perclos})", closed.perclos > 0.9f)
        assertTrue("perclosContribution must be > 0", closed.perclosContribution > 0f)
        assertTrue("perclosContribution must not exceed weight", closed.perclosContribution <= 65f + 0.01f)
        assertTrue("blinkContribution must not exceed weight", closed.blinkContribution <= 10f + 0.01f)
        assertTrue("blinkContribution must be >= 0", closed.blinkContribution >= 0f)

        // With a yawn in progress, yawnContribution should be exactly SCORE_WEIGHT_YAWN (or 0 if not yawning).
        // Feed 20 frames of open mouth after warmup → confirms yawn on-off.
        t += 100
        repeat(20) { s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.8f, true, t)); t += 100 }
        val yawningAssessment = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.8f, true, t))
        if (yawningAssessment.isYawning) {
            assertEquals("yawnContribution must equal SCORE_WEIGHT_YAWN when yawning", 15f, yawningAssessment.yawnContribution, 0.01f)
        } else {
            assertEquals("yawnContribution must be 0 when not yawning", 0f, yawningAssessment.yawnContribution, 0.01f)
        }
    }

    @Test
    fun `score stays low right after calibration ends`() {
        // Regression for the reported behavior: score used to jump after calibration
        // because the perclosWindow started empty. Seeding it with calibration frames
        // should keep the score close to 0 through the transition.
        val s = FatigueScorer(calibrationEnabled = true)
        var t = 1000L

        // Feed 9 s of clearly-open frames: stabilization gate (800 ms) + full 7 s
        // collection window + margin.
        repeat(90) { s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)); t += 100 }

        // At this point calibration has ended and currentState should be NORMAL. Feed
        // 30 more open frames and assert the score never goes above a small threshold.
        var maxScore = 0f
        repeat(30) {
            val a = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
            assertEquals(FatigueState.NORMAL, a.fatigueState)
            maxScore = maxOf(maxScore, a.score)
            t += 100
        }
        assertTrue("Score should stay very low right after calibration (max was $maxScore)", maxScore < 5f)
    }

    @Test
    fun `single blink right after calibration does not spike score`() {
        // A single blink (5 closed frames) immediately after calibration used to inflate
        // PERCLOS to 60-80 % because the empty buffer had no history to dilute it. With
        // the seeded buffer (~90 frames from calibration), the same blink adds ≈5% of
        // closed frames and the score stays low.
        val s = FatigueScorer(calibrationEnabled = true)
        var t = 1000L

        // Complete calibration with clean open frames.
        repeat(90) { s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)); t += 100 }

        // Blink: 5 frames closed
        var maxScore = 0f
        repeat(5) {
            val a = s.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            maxScore = maxOf(maxScore, a.score)
            t += 100
        }
        // Followed by 30 open frames
        repeat(30) {
            val a = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
            maxScore = maxOf(maxScore, a.score)
            t += 100
        }
        assertTrue(
            "A single post-calibration blink should not spike the score (max was $maxScore)",
            maxScore < 15f,
        )
    }

    @Test
    fun `elevated blink rate stays normal with widened healthy range`() {
        // User complaint after 3c30b8a: "even blinking as a normal person" was pushing
        // score into WARNING. Users focused on the front-camera app blink 20-26/min
        // naturally. With BLINK_RATE_MAX widened from 20 to 24, a rate around 25/min
        // now yields a mild deviation contribution instead of the maximum penalty.
        // Feed 90 s of well-framed frames + one blink every 2.4 s (25/min). State
        // must stay NORMAL end-to-end.
        var t = 1000L
        // Blink every 2.4 s: 21 frames open (openness 0.75) + 3 frames closed (blink).
        // That gives 25 blinks in 60 s ≈ 37 blinks in 90 s → rate 24.7/min.
        repeat(37) {
            repeat(21) { scorer.processFrame(FatigueMetrics(0.75f, 0.75f, 0.1f, true, t)); t += 100 }
            repeat(3)  { scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t)); t += 100 }
        }
        val finalAssessment = scorer.processFrame(FatigueMetrics(0.75f, 0.75f, 0.1f, true, t))
        assertEquals(
            "Elevated but normal blink rate must not trigger WARNING (state=${finalAssessment.fatigueState}, score=${finalAssessment.score})",
            FatigueState.NORMAL,
            finalAssessment.fatigueState,
        )
    }

    @Test
    fun `single yawn from a WARNING baseline does not promote to FATIGUED`() {
        // Regression for the reported issue: user parado no computador (baseline elevated
        // enough to reach WARNING) yawned once and the app jumped to FATIGUED. With
        // SCORE_WEIGHT_YAWN = 15 (was 25) and BLINK weight = 10 (was 15), the extra 15 pts
        // from a yawn on top of a WARNING baseline (~50) tops out at ~65 — below the 70-pt
        // FATIGUED threshold. Real fatigue (sustained PERCLOS + repeated yawns) still
        // promotes and is covered by `WARNING to FATIGUED transition requires sustained score`.
        var t = advanceToWarning(1000L)
        // At this point score is just past 50 (WARNING gate) with PERCLOS ≈ 0.77.

        // Simulate one yawn: 20 frames of mouth open (~2 s, above YAWN_DURATION_MS = 1.5 s).
        // Eyes remain half-open (openness 0.35 — above default closed threshold 0.30) so
        // PERCLOS starts to drain during the yawn instead of continuing to climb.
        repeat(20) {
            scorer.processFrame(FatigueMetrics(0.35f, 0.35f, 0.8f, true, t))
            t += 100
        }

        // Then monitor for 6 s of continued mouth-open — long enough that if the
        // WARNING → FATIGUED sustain (4 s) were to fire, it would fire in this window.
        var reachedFatigued = false
        repeat(60) {
            val a = scorer.processFrame(FatigueMetrics(0.35f, 0.35f, 0.8f, true, t))
            if (a.fatigueState == FatigueState.FATIGUED) reachedFatigued = true
            t += 100
        }
        assertFalse(
            "Single yawn on a WARNING baseline must not reach FATIGUED (yawn is tier-2 signal)",
            reachedFatigued,
        )
    }

    @Test
    fun `single frame closure noise does not accumulate into perclos`() {
        // Regression for the reported field bug: user parado no PC em ambiente subjetivamente
        // escuro (Y=65-83, então LightingMonitor permanece em NORMAL) via score subir para
        // WARNING em <1min. CSV mostrou eyeOpenness alternando entre 0.9 e 0.0/0.5 em snapshots
        // consecutivos — jitter de single-frame da blendshape do MediaPipe. Sem debounce esses
        // frames isolados eram contados como closed no PERCLOS. Com debounce, apenas closures
        // com pelo menos 2 frames consecutivos contam.
        var t = 1000L
        // 900 frames alternando open/closed a 30 fps ≈ 30 s. Cada frame closed é isolado.
        repeat(450) {
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t)); t += 33
            scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)); t += 33
        }
        val finalAssessment = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t))
        assertTrue(
            "Single-frame noise must not inflate perclos (was ${finalAssessment.perclos})",
            finalAssessment.perclos < 0.05f,
        )
        assertTrue(
            "Score must stay low with debounced noise (was ${finalAssessment.score})",
            finalAssessment.score < 5f,
        )
        assertEquals(FatigueState.NORMAL, finalAssessment.fatigueState)
    }

    @Test
    fun `two consecutive closed frames still count toward perclos`() {
        // Verifies the debounce doesn't over-suppress: paired closures (2+ consecutive frames)
        // must register. Pattern: 1 open, 2 closed, 1 open — 4-frame block repeated. After
        // debounce, frame 2 of each closed pair confirms (frame 1 stayed open due to gate).
        // So ~1 in 4 frames records closed → perclos ≈ 25 %.
        var t = 1000L
        repeat(200) {
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t)); t += 33
            scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)); t += 33
            scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)); t += 33
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t)); t += 33
        }
        val a = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t))
        assertTrue(
            "Debounce must still let 2-frame closures through — perclos should be measurable (was ${a.perclos})",
            a.perclos > 0.15f,
        )
    }

    @Test
    fun `real blink pattern of three closed frames still counted in perclos`() {
        // Real blinks last 100–400 ms = 3–12 frames at 30 fps — well above the 2-frame
        // debounce threshold. A blink pattern (17 open, 3 closed) repeated should still show
        // measurable PERCLOS from the confirmed closed portion of each blink.
        var t = 1000L
        // Blink every ~666 ms → 30 s of data. Frame 1 of each blink stays "unconfirmed" (false);
        // frames 2 and 3 record closed. So 2 of every 20 frames = 10 % perclos expected.
        repeat(30) {
            repeat(17) { scorer.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)); t += 33 }
            repeat(3) { scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t)); t += 33 }
        }
        val a = scorer.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
        assertTrue(
            "Real blinks (3 frames closed) must still contribute to perclos (was ${a.perclos})",
            a.perclos > 0.05f && a.perclos < 0.20f,
        )
    }

    @Test
    fun `sustained closure still reaches WARNING with debounce`() {
        // Regression: the debounce delays confirmation by exactly 1 frame (~33 ms) — negligible
        // against the 3 s NORMAL→WARNING sustain gate. Real fatigue (many seconds of closed
        // eyes) must still promote to WARNING.
        var t = 1000L
        var lastState = FatigueState.NORMAL
        // 15 s of continuous closed frames — 150 frames at 100 ms intervals.
        repeat(150) {
            lastState = scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)).fatigueState
            t += 100
        }
        assertEquals(
            "Sustained closure must still reach WARNING with debounce active",
            FatigueState.WARNING,
            lastState,
        )
    }

    @Test
    fun `look away resets debounce counter`() {
        // Prevents a "carry-over" bug: 1 raw-closed frame just before a look-away, then 1
        // raw-closed frame on return should NOT confirm as closed (counter must reset during
        // look-away so the streak restarts fresh).
        var t = 1000L
        // 1 closed frontal frame — counter=1, not confirmed yet.
        scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t)); t += 100
        // Look-away frame with closed eyes — must reset counter without recording anything
        // in perclosWindow.
        scorer.processFrame(
            FatigueMetrics(0.05f, 0.05f, 0.1f, true, t, headYawDegrees = 40f),
        ); t += 100
        // Return to frontal with 1 closed frame — should be counter=1 (fresh streak), not
        // confirmed yet.
        val returning = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
        // Perclos should still be 0 because no 2-consecutive-frame streak has ever completed.
        assertTrue(
            "Look-away must reset debounce; returning single closed frame must not confirm (perclos was ${returning.perclos})",
            returning.perclos < 0.01f,
        )
    }

    @Test
    fun `LOW_LIGHT grace uses 1000 ms window`() {
        // Verifies the widened NO_FACE grace for LOW_LIGHT mode: 1000 ms (interpolated
        // between NORMAL=500 and DARK=1500). Grace is measured from the first no-face
        // frame — a 900 ms dropout should NOT flip to NO_FACE; a 1100 ms dropout should.
        val lowLightScorer = FatigueScorer(
            calibrationEnabled = false,
            lightingModeProvider = { LightingMode.LOW_LIGHT },
        )
        // Prime with a detected frame so state is NORMAL.
        lowLightScorer.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, 1000L))

        // First no-face frame at t=1100 starts the grace timer (duration=0).
        val start = lowLightScorer.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, false, 1100L))
        assertFalse(
            "Grace just started, must not yet be NO_FACE (state=${start.fatigueState})",
            start.fatigueState == FatigueState.NO_FACE,
        )

        // At t=2000, duration=900 ms — under 1000 ms LOW_LIGHT grace.
        val under = lowLightScorer.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, false, 2000L))
        assertFalse(
            "At 900 ms no-face in LOW_LIGHT, must not yet be NO_FACE (state=${under.fatigueState})",
            under.fatigueState == FatigueState.NO_FACE,
        )

        // At t=2200, duration=1100 ms — over the 1000 ms LOW_LIGHT grace.
        val over = lowLightScorer.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, false, 2200L))
        assertEquals(
            "At 1100 ms no-face in LOW_LIGHT, grace should have expired",
            FatigueState.NO_FACE,
            over.fatigueState,
        )
    }

    @Test
    fun `debounce state cleared across reset`() {
        // A raw-closed frame primed just before reset() must not carry a partial streak into
        // the next session — reset() must clear both count and last-classified.
        scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, 1000L))
        scorer.reset()

        // 1 closed frame post-reset — should be counter=1 (fresh), not confirmed.
        val afterReset = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, 2000L))
        assertTrue(
            "Reset must clear debounce state so single closed frame post-reset stays unconfirmed (perclos was ${afterReset.perclos})",
            afterReset.perclos < 0.01f,
        )
    }

    @Test
    fun `Reset clears internal state`() {
        // Trigger a yawn
        var currentTime = 1000L
        repeat(20) {
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.8f, true, currentTime))
            currentTime += 100
        }

        assertTrue(scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.8f, true, currentTime)).isYawning)

        scorer.reset()

        // After reset: no PERCLOS, no blinks, no yawn → score = 0
        val assessment = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime))
        assertFalse(assessment.isYawning)
        assertEquals(0f, assessment.score, 0.01f)
        assertEquals(FatigueState.NORMAL, assessment.fatigueState)
    }
}
