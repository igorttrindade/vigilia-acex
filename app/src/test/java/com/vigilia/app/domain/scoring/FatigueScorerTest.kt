package com.vigilia.app.domain.scoring

import com.vigilia.app.domain.model.FatigueMetrics
import com.vigilia.app.domain.model.FatigueState
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

        // Closed eyes drive PERCLOS up until score exceeds 40
        while (scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)).score <= 40f) {
            currentTime += 100
        }

        val startTransitionTime = currentTime
        // Sustained for 1.9s — must still be NORMAL
        while (currentTime - startTransitionTime < 1900L) {
            assertEquals(FatigueState.NORMAL, scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)).fatigueState)
            currentTime += 100
        }

        // At 2s threshold → WARNING
        currentTime = startTransitionTime + 2000L
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
        assertTrue("blinkContribution must not exceed weight", closed.blinkContribution <= 15f + 0.01f)
        assertTrue("blinkContribution must be >= 0", closed.blinkContribution >= 0f)

        // With a yawn in progress, yawnContribution should be exactly 25 (or 0 if not yawning).
        // Feed 20 frames of open mouth after warmup → confirms yawn on-off.
        t += 100
        repeat(20) { s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.8f, true, t)); t += 100 }
        val yawningAssessment = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.8f, true, t))
        if (yawningAssessment.isYawning) {
            assertEquals("yawnContribution must equal SCORE_WEIGHT_YAWN when yawning", 25f, yawningAssessment.yawnContribution, 0.01f)
        } else {
            assertEquals("yawnContribution must be 0 when not yawning", 0f, yawningAssessment.yawnContribution, 0.01f)
        }
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
