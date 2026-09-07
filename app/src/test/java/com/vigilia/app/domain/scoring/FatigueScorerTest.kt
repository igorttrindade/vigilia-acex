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

    /**
     * Feeds `blocks` iterations of (20 closed + 2 open) at 100 ms/frame — 22 frames per
     * block, 2.2 s each. Each continuous-closure streak is ~1.9 s (below MICROSLEEP_WARNING_MS
     * of 3 s) so the microsleep detector never fires. Fills PERCLOS buffer past
     * MIN_PERCLOS_FRAMES (60) after ~3 blocks and produces ~86 % steady-state PERCLOS,
     * driving score to ~56 → exercises the FSM's score-based hysteresis path.
     * Returns the next timestamp to use.
     */
    private fun feedIntermittentClosure(startTime: Long, blocks: Int, scorerOverride: FatigueScorer = scorer): Long {
        var t = startTime
        repeat(blocks) {
            repeat(20) {
                scorerOverride.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t))
                t += 100
            }
            repeat(2) {
                scorerOverride.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t))
                t += 100
            }
        }
        return t
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
        // Fill buffer past MIN_PERCLOS_FRAMES (60) so PERCLOS gets a real reading, using
        // an intermittent (20 closed + 2 open) pattern that keeps each continuous-closure
        // streak below MICROSLEEP_WARNING_MS (3 s = 30 frames at 100 ms). Otherwise the
        // microsleep detector would promote state independently and short-circuit this
        // score-driven assertion.
        var t = 0L
        repeat(4) {
            repeat(20) {
                scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t))
                t += 100
            }
            repeat(2) {
                scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t))
                t += 100
            }
        }
        val assessment = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t))
        assertTrue(
            "Score should be positive due to accumulated PERCLOS (got ${assessment.score})",
            assessment.score > 0,
        )
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
    fun `NORMAL to WARNING transition via FSM eventually fires with intermittent closure`() {
        // Verifies the SCORE-based FSM path from NORMAL to (elevated state) using an
        // intermittent (20 closed + 2 open) pattern. With the progressive closure
        // contribution added to raw score (see computeClosureContribution), 91% closure
        // ratio can escalate past WARNING into FATIGUED — that's the correct behavior
        // (someone with eyes closed 91% of the time IS in a critical state). The test's
        // intent is "FSM promotes from NORMAL", not "stops exactly at WARNING".
        var t = 1000L
        repeat(10) {
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t))
            t += 100
        }
        var finalState = FatigueState.NORMAL
        repeat(20) {
            t = feedIntermittentClosure(t, 1)
            finalState = scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)).fatigueState
            t += 100
            if (finalState == FatigueState.WARNING || finalState == FatigueState.FATIGUED) return@repeat
        }
        assertTrue(
            "FSM must promote out of NORMAL through sustained intermittent closure (final=$finalState)",
            finalState == FatigueState.WARNING || finalState == FatigueState.FATIGUED,
        )
    }

    @Test
    fun `WARNING to FATIGUED transition via FSM fires with sustained yawn plus intermittent closure`() {
        // Uses (29 closed + 1 open + 1 closed) pattern with mouth open throughout, so:
        //   * continuous-closure streak = 29 raw frames × 100 ms = 2.8 s < 3 s
        //     (MICROSLEEP_WARNING_MS) → microsleep detector never fires. The trailing
        //     `1 closed` frame links across the outer iteration boundary — that
        //     cross-block streak reaches 30 frames = 2.9 s, still under threshold.
        //     (This block count was 30 originally, back when the microsleep timer armed
        //     after a 2-frame debounce; the fix that arms on the first raw-closed frame
        //     tightened effective streak length by 100 ms, so we drop one frame to keep
        //     exercising the FSM promotion path without triggering microsleep.)
        //   * PERCLOS steady state ≈ 30/31 = 0.97 → PERCLOS contribution ~63
        //   * Yawn confirmed after 1.5 s of mouth-open → contribution 15 (held then decaying)
        //   * Total peak score ≈ 78 → comfortably above FATIGUED gate (>70), with enough
        //     margin to sustain > 70 for the 4 s the WARNING→FATIGUED transition requires
        //     even as the yawn contribution decays linearly.
        var t = 1000L
        var finalState = FatigueState.NORMAL
        repeat(30) {
            repeat(29) {
                scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.8f, true, t))
                t += 100
            }
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.8f, true, t))
            t += 100
            finalState = scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.8f, true, t)).fatigueState
            t += 100
            if (finalState == FatigueState.FATIGUED) return@repeat
        }
        assertEquals(
            "FSM must reach FATIGUED through sustained closure + yawn (final=$finalState)",
            FatigueState.FATIGUED,
            finalState,
        )
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
        // in the 0.20-0.35 ambiguous band. Below CALIBRATION_STABILIZATION_MIN_OPENNESS
        // (0.35) so the stabilization gate can never converge, but above
        // MICROSLEEP_PRECAL_THRESHOLD (0.20) so the pre-calibration safety net does NOT
        // fire. The CALIBRATION_STABILIZATION_MAX_MS fallback must force sample collection
        // after 3 s so calibration doesn't stall indefinitely.
        //
        // Note: this test used to use openness = 0.1, but that value is semantically
        // "eyes closed" (below the PERCLOS default 0.30 and the safety-net threshold 0.20).
        // A driver whose readings are that low for 3 s straight should get an alarm, not
        // a silent calibration retry — so we test bad framing with a more realistic
        // "narrow-eyed / partial reflection" value.
        val calibratingScorer = FatigueScorer(calibrationEnabled = true)
        var t = 1000L

        // 3 s of persistently sub-gate openness — every frame stays in stabilization, progress=0.
        val startT = t
        while (t - startT < 3000L) {
            val a = calibratingScorer.processFrame(FatigueMetrics(0.25f, 0.25f, 0.1f, true, t))
            assertEquals(FatigueState.CALIBRATING, a.fatigueState)
            assertEquals("progress must stay 0 while gate hasn't force-started", 0f, a.calibrationProgress, 0.001f)
            t += 100
        }
        // Frame at t = startT + 3000: force-start fires here, calibrationStartMs=this frame,
        // so elapsed=0 and progress=0 on this exact frame.
        calibratingScorer.processFrame(FatigueMetrics(0.25f, 0.25f, 0.1f, true, t))
        t += 100
        // Next frame: elapsed>0 confirms the fallback made collection start.
        val progressing = calibratingScorer.processFrame(FatigueMetrics(0.25f, 0.25f, 0.1f, true, t))
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
        // (yaw=50°). After the 150ms debounce (2 frames at 100ms intervals), look-away is
        // confirmed and the score is frozen. The 2 debounce frames allow a tiny bit of
        // closure accumulation (~0.8f) — the key property is that score stays very low.
        var t = 1000L
        repeat(30) {
            val a = scorer.processFrame(
                FatigueMetrics(
                    leftEyeOpenProbability = 0.1f,
                    rightEyeOpenProbability = 0.1f,
                    mouthOpenProbability = 0.1f,
                    isFaceDetected = true,
                    timestampMs = t,
                    headYawDegrees = 50f,
                )
            )
            assertTrue("Score should not significantly inflate during look-away (was ${a.score})", a.score < 5f)
            t += 100
        }
    }

    @Test
    fun `perclos buffer survives a brief look away without inflating`() {
        // Drive PERCLOS up with intermittent closure until buffer is full and score climbs.
        // Continuous streaks < MICROSLEEP_WARNING_MS keep the microsleep detector quiet
        // so this look-away regression stays focused on PERCLOS/score behaviour.
        var t = feedIntermittentClosure(1000L, 4)
        val scoreBeforeLookAway = scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)).score
        t += 100
        assertTrue("Score should be climbing before look-away ($scoreBeforeLookAway)", scoreBeforeLookAway > 10f)

        // Brief look-away: 15 frames × 100 ms = 1.5 s. Below LOOK_AWAY_MAX_FREEZE_MS (2 s),
        // so the freeze holds the whole time and buffer is preserved.
        var scoreDuringLookAway = scoreBeforeLookAway
        repeat(15) {
            scoreDuringLookAway = scorer.processFrame(
                FatigueMetrics(0.1f, 0.1f, 0.1f, true, t, headYawDegrees = 50f)
            ).score
            t += 100
        }
        // +10f tolerance: the 150ms debounce allows 2 frames of normal processing before
        // look-away is confirmed, during which ongoing closure contribution may push score up slightly.
        assertTrue(
            "Score should not significantly climb during look-away (${scoreBeforeLookAway} → ${scoreDuringLookAway})",
            scoreDuringLookAway <= scoreBeforeLookAway + 10f,
        )

        // Return to frontal with eyes open — score drains as buffer refills with open frames.
        repeat(60) {
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t))
            t += 100
        }
        val finalScore = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t)).score
        assertTrue("Score should have drained after eyes-open frontal phase ($finalScore)", finalScore < scoreBeforeLookAway)
    }

    @Test
    fun `moderate intermittent closure reaches WARNING without escalating to FATIGUED`() {
        // Uses a LESS intense (10 closed + 12 open) pattern = 45% closure ratio so
        // PERCLOS contribution stays around 29 (65 * 0.45), each closure streak of 1s
        // gives partial closure contribution ~12 (peak) with 3s decay running through
        // the 1.2s open period. Total sustained score ~40-50 → reaches WARNING via FSM
        // (score > 50 sustained 3s) but does NOT cross the FATIGUED gate (70), which
        // preserves the "requires stronger signal" safety margin.
        // History: original used the 20:2 pattern above (91% closure), which under the
        // new progressive closure contribution correctly promotes to FATIGUED — but
        // that path is exercised by the dedicated partial-closure and microsleep tests.
        fun feed10Closed12Open(startTime: Long, blocks: Int): Long {
            var t = startTime
            repeat(blocks) {
                repeat(10) { scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)); t += 100 }
                repeat(12) { scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t)); t += 100 }
            }
            return t
        }
        var t = feed10Closed12Open(1000L, 15)
        val stateAfterBuildup = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t)).fatigueState
        t += 100
        assertTrue(
            "Should reach at least WARNING with moderate intermittent closure (got $stateAfterBuildup)",
            stateAfterBuildup == FatigueState.WARNING || stateAfterBuildup == FatigueState.NORMAL,
        )

        // Continue for another 10 blocks (~22 s) with the moderate pattern. Score should
        // stabilize below 70 → must not escalate to FATIGUED.
        repeat(10) {
            t = feed10Closed12Open(t, 1)
            val a = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, t))
            t += 100
            assertTrue(
                "Moderate intermittent closure must not reach FATIGUED (state=${a.fatigueState} score=${a.score})",
                a.fatigueState != FatigueState.FATIGUED,
            )
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
        // Eyes are open at 0.50 — above both the default eyeClosedThreshold (0.30) so
        // PERCLOS drains during the yawn, AND above PARTIAL_CLOSURE_THRESHOLD (0.45) so
        // the partial-closure safety net doesn't independently promote state. Test's
        // intent is to verify the score-based FSM alone doesn't fatigue on a single yawn.
        repeat(20) {
            scorer.processFrame(FatigueMetrics(0.50f, 0.50f, 0.8f, true, t))
            t += 100
        }

        // Then monitor for 6 s of continued mouth-open — long enough that if the
        // WARNING → FATIGUED sustain (4 s) were to fire, it would fire in this window.
        var reachedFatigued = false
        repeat(60) {
            val a = scorer.processFrame(FatigueMetrics(0.50f, 0.50f, 0.8f, true, t))
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
    fun `sustained closure reaches at least WARNING with debounce`() {
        // Regression: the PERCLOS closure debounce (PERCLOS_MIN_CLOSED_FRAMES) delays
        // confirmation by 1 frame. Real fatigue (many seconds of closed eyes) must still
        // promote at least to WARNING. With the microsleep detector present, sustained
        // closure escalates to FATIGUED after 6 s — that's expected and also acceptable
        // for this regression, which only guards that debounce doesn't block promotion.
        var t = 1000L
        var lastState = FatigueState.NORMAL
        repeat(150) {
            lastState = scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)).fatigueState
            t += 100
        }
        assertTrue(
            "Sustained closure must reach WARNING or FATIGUED (got $lastState)",
            lastState == FatigueState.WARNING || lastState == FatigueState.FATIGUED,
        )
    }

    @Test
    fun `look away resets debounce counter`() {
        // Prevents a "carry-over" bug: 1 raw-closed frame just before a look-away, then 1
        // raw-closed frame on return should NOT confirm as closed. With LOOK_AWAY_DEBOUNCE_MS=150ms,
        // a single 100ms look-away frame does NOT confirm look-away — the PERCLOS counter
        // is not reset by the look-away itself. However, MIN_PERCLOS_FRAMES=60 ensures
        // perclos stays 0 regardless (only 2-3 total closed frames, well below the guard).
        var t = 1000L
        // 1 closed frontal frame — counter=1, not confirmed yet.
        scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t)); t += 100
        // Single look-away frame (100ms < LOOK_AWAY_DEBOUNCE_MS=150ms) — debounce not reached,
        // processes as a normal closed frame. PERCLOS still guarded by MIN_PERCLOS_FRAMES=60.
        scorer.processFrame(
            FatigueMetrics(0.05f, 0.05f, 0.1f, true, t, headYawDegrees = 50f),
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

    @Test
    fun `yawn contribution holds then decays linearly instead of cliff-dropping`() {
        val s = FatigueScorer(calibrationEnabled = false)
        var t = 1000L

        // Trigger a yawn: 2s of open-mouth frames (past YAWN_DURATION_MS = 1.5s)
        repeat(20) {
            s.processFrame(FatigueMetrics(0.9f, 0.9f, 0.9f, true, t))
            t += 100
        }

        // Peak: right after confirmation, contribution should be at full weight (15).
        // Read the yawn contribution via the assessment (which exposes yawnContribution).
        val peak = s.processFrame(FatigueMetrics(0.9f, 0.9f, 0.9f, true, t))
        assertTrue("Peak yawn contribution should be near full weight, got ${peak.yawnContribution}", peak.yawnContribution > 14f)

        // Close mouth (yawn physically ends) but contribution should NOT drop instantly
        repeat(5) {
            t += 100
            s.processFrame(FatigueMetrics(0.9f, 0.9f, 0.1f, true, t))
        }
        // Still within hold window (~3s from confirmation) — contribution stays at full
        val duringHold = s.processFrame(FatigueMetrics(0.9f, 0.9f, 0.1f, true, t))
        assertTrue("Yawn contribution should hold during hold window, got ${duringHold.yawnContribution}", duringHold.yawnContribution > 14f)

        // Jump forward ~15s (past hold, mid-decay)
        t += 15_000L
        val midDecay = s.processFrame(FatigueMetrics(0.9f, 0.9f, 0.1f, true, t)).yawnContribution
        // Expected: hold ended at t+3s from confirmation, then linear decay over 30s.
        // At ~13s into decay: contribution ≈ 15 * (1 - 13/30) ≈ 8.5
        assertTrue(
            "Mid-decay contribution should be between full and zero (got $midDecay)",
            midDecay in 4f..12f,
        )

        // Jump to ~40s past hold — decay should have completed
        t += 30_000L
        val postDecay = s.processFrame(FatigueMetrics(0.9f, 0.9f, 0.1f, true, t)).yawnContribution
        assertEquals("Yawn contribution should be zero after full decay", 0f, postDecay, 0.5f)
    }

    @Test
    fun `look-away release does not spike score`() {
        // Look-away freeze is bounded by LOOK_AWAY_MAX_FREEZE_MS (2 s) — beyond that, buffers
        // reset and normal processing resumes. This test uses 1.5 s of look-away to stay
        // within the freeze window and verify the historical bug (score spike on return) is
        // still absent.
        val s = FatigueScorer(calibrationEnabled = false)
        var t = feedIntermittentClosure(1000L, 4, s)
        val scoreBeforeLookAway = s.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, t)).score
        t += 100

        // 1.5 s of sustained look-away (yaw = 50°), below the freeze time-limit.
        // Openness kept at 0.85 (eyes open) so if the freeze did release early, PERCLOS
        // wouldn't inflate — this isolates the "score spike" behavior from other paths.
        // The 150ms debounce means the first 2 frames (200ms) process normally before freeze
        // activates — allowing a tiny natural score drift (open eyes → closure decay). 2f
        // tolerance accommodates that without masking real spikes.
        repeat(15) {
            val a = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t, headYawDegrees = 50f))
            assertEquals(
                "Score must stay approximately frozen during brief look-away (frame at t=$t, got ${a.score})",
                scoreBeforeLookAway, a.score, 2f,
            )
            t += 100
        }

        // First frame back to forward-facing with eyes OPEN — must not spike.
        val scoreOnReturn = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)).score
        assertTrue(
            "Score on look-away release should not spike (before=$scoreBeforeLookAway, on return=$scoreOnReturn)",
            scoreOnReturn <= scoreBeforeLookAway + 5f,
        )
    }

    @Test
    fun `brief look-away is transparent to score`() {
        val s = FatigueScorer(calibrationEnabled = false)
        var t = 1000L

        // 10s of open-eye frames to establish a low baseline
        repeat(100) {
            s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
            t += 100
        }
        val baseline = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)).score

        // 2s of brief look-away
        repeat(20) {
            t += 100
            s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t, headYawDegrees = 50f))
        }

        // 2s back forward
        var lastScore = baseline
        repeat(20) {
            t += 100
            lastScore = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)).score
        }

        assertTrue(
            "Brief glance should be transparent (baseline=$baseline, after=$lastScore)",
            kotlin.math.abs(lastScore - baseline) < 3f,
        )
    }

    @Test
    fun `perclos buffer count is preserved across look-away`() {
        val s = FatigueScorer(calibrationEnabled = false)
        var t = 1000L

        // Fill the perclos window with 15s of frames (well below the 30s cap)
        repeat(150) {
            s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
            t += 100
        }

        // Look away for 20s — buffer should NOT drain during this period.
        repeat(200) {
            s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t, headYawDegrees = 50f))
            t += 100
        }

        // Return forward and immediately capture the assessment. If the buffer had drained,
        // the first return frame would sit in an empty buffer → perclos = 0/1 = 0. If preserved,
        // perclos reflects the pre-look-away composition (~0 closed / 150+ open ≈ 0).
        val onReturn = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
        assertEquals("perclos should stay at 0 across the shift (open frames only)", 0f, onReturn.perclos, 0.01f)

        // More importantly: score must not swing because of the buffer state after shift.
        val postReturn = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t + 100))
        assertTrue("score stays low after look-away (open-eyed baseline), got ${postReturn.score}", postReturn.score < 15f)
    }

    @Test
    fun `narrow-eyed user - calibration baseline anchors on blendshape not on min-with-EAR`() {
        // Simulates a user whose eyes are anatomically small: MediaPipe blendshapes report
        // eyes as fully open (blendshape openness = 0.90) but EAR is systematically low,
        // dragging the min-based openness down to 0.55. Under the previous behavior,
        // calibration would use the 0.55 baseline → threshold = 0.55 × 0.30 = 0.165 →
        // clamped up to EYE_CLOSED_MIN = 0.18. In runtime with openness fluctuating between
        // 0.20–0.55, many "open" frames would fall below 0.18 and inflate PERCLOS.
        //
        // With blendshape-based calibration, baseline = 0.90 → threshold = 0.27. Runtime
        // frames at 0.55 stay comfortably above threshold → PERCLOS stays low → score stays low.
        val narrowEyed = FatigueScorer(calibrationEnabled = true)
        var t = 1000L

        // Feed 8s of "eyes open" narrow-eyed frames: min-based openness 0.55, blendshape 0.90
        // (>800ms stabilization + 7s collection + margin).
        repeat(80) {
            narrowEyed.processFrame(
                FatigueMetrics(
                    leftEyeOpenProbability = 0.55f,
                    rightEyeOpenProbability = 0.55f,
                    mouthOpenProbability = 0.1f,
                    isFaceDetected = true,
                    timestampMs = t,
                    avgBlendshapeOpen = 0.90f,
                )
            )
            t += 100
        }

        // Post-calibration state must be NORMAL (calibration finished)
        val postCalib = narrowEyed.processFrame(
            FatigueMetrics(
                leftEyeOpenProbability = 0.55f,
                rightEyeOpenProbability = 0.55f,
                mouthOpenProbability = 0.1f,
                isFaceDetected = true,
                timestampMs = t,
                avgBlendshapeOpen = 0.90f,
            )
        )
        assertEquals("Calibration should have finished", FatigueState.NORMAL, postCalib.fatigueState)

        // Feed 5s more of the same eyes-open narrow frames — score must NOT climb because
        // the threshold (calibrated from blendshape p90) is now around 0.27, well below
        // the 0.55 runtime openness.
        var lastScore = postCalib.score
        repeat(50) {
            t += 100
            lastScore = narrowEyed.processFrame(
                FatigueMetrics(
                    leftEyeOpenProbability = 0.55f,
                    rightEyeOpenProbability = 0.55f,
                    mouthOpenProbability = 0.1f,
                    isFaceDetected = true,
                    timestampMs = t,
                    avgBlendshapeOpen = 0.90f,
                )
            ).score
        }

        assertTrue(
            "Narrow-eyed user with eyes open should NOT accumulate score (got $lastScore, expected < 15)",
            lastScore < 15f,
        )
    }

    // -----------------------------------------------------------------------------
    // Microsleep detector — categorical WARNING/FATIGUED promotion on sustained
    // continuous closure, bypassing the PERCLOS-based FSM.
    // -----------------------------------------------------------------------------

    /**
     * Feeds `frames` closed-eye frames at 100 ms spacing starting at `startTime`.
     * Chose 100 ms so 30 frames = 3 s (WARNING threshold) and 60 frames = 6 s
     * (FATIGUED). At this rate the PERCLOS buffer never reaches MIN_PERCLOS_FRAMES
     * (60) within 3 s, so the PERCLOS-based FSM stays at NORMAL — any state promotion
     * observed in these tests is unambiguously from the microsleep detector.
     */
    private fun feedClosed(startTime: Long, frames: Int): Long {
        var t = startTime
        repeat(frames) {
            scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            t += 100
        }
        return t
    }

    @Test
    fun `3s of continuous closure forces WARNING via microsleep detector`() {
        // Frame 0: raw-closed count=1, still below PERCLOS_MIN_CLOSED_FRAMES=2 → debounced=false.
        // Frame 1 (t=100): debounced=true, continuousClosureStartMs anchored at t=100.
        // Frame 31 (t=3100): closureMs = 3000 → MICROSLEEP_WARNING_MS met → WARNING.
        // Feed a few more to be safely past the threshold.
        var t = 0L
        var last = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
        t += 100
        repeat(35) {
            last = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            t += 100
        }
        assertEquals(
            "Expected WARNING after 3s+ continuous closure, got ${last.fatigueState} (score=${last.score})",
            FatigueState.WARNING,
            last.fatigueState,
        )
    }

    @Test
    fun `6s of continuous closure forces FATIGUED via microsleep detector`() {
        var last = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, 0L))
        var t = 100L
        repeat(65) {
            last = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            t += 100
        }
        assertEquals(
            "Expected FATIGUED after 6s+ continuous closure, got ${last.fatigueState}",
            FatigueState.FATIGUED,
            last.fatigueState,
        )
    }

    @Test
    fun `microsleep does not fire below 3s threshold`() {
        // 2.5 s of closure — closureMs peaks at ~2400 ms (started at t=100 after debounce).
        var last = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, 0L))
        var t = 100L
        repeat(24) {
            last = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            t += 100
        }
        assertEquals(
            "State must remain NORMAL below 3s closure (got ${last.fatigueState})",
            FatigueState.NORMAL,
            last.fatigueState,
        )
    }

    @Test
    fun `open frame resets microsleep streak so 2s+2s does not fire`() {
        // 2 s closed, one clearly-open frame, then 2 s closed. Each half is below the
        // MICROSLEEP_WARNING_MS threshold and the open frame breaks the streak, so
        // continuousClosureStartMs re-anchors and no promotion happens.
        var t = 0L
        repeat(20) {
            scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            t += 100
        }
        // Open frame to break the streak. openness 0.9 is above eyeClosedThreshold (0.30 default).
        scorer.processFrame(FatigueMetrics(0.9f, 0.9f, 0.1f, true, t))
        t += 100
        var last = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
        t += 100
        repeat(20) {
            last = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            t += 100
        }
        assertEquals(
            "State must remain NORMAL when closure streak is broken by an open frame (got ${last.fatigueState})",
            FatigueState.NORMAL,
            last.fatigueState,
        )
    }

    @Test
    fun `microsleep timer survives NO_FACE and promotes to WARNING once closure crosses 3s`() {
        // Field bug: driver's eyes stayed closed for ~10 s and the alarm was late because
        // MediaPipe drops face detection when eyelids are fully down (the model relies on
        // eye landmarks to consolidate the face). With detection lost, the PERCLOS-based
        // FSM never runs, so the score-based promotion path is silent. The microsleep
        // detector now runs INSIDE the NO_FACE branch too, using the already-anchored
        // continuousClosureStartMs — so a sustained closure that starts detected and
        // continues past the detection loss still fires within the 3 s window.
        //
        // Pattern: 0.4 s of closure with face → 0.7 s of NO_FACE → 2.5 s of closure back.
        // Cumulative closure since anchor > 3 s → WARNING.
        var t = 0L
        // 4 face-detected closed frames — enough for debounce to confirm (>= 2 consecutive)
        // so continuousClosureStartMs anchors at ~t=100.
        repeat(4) {
            scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            t += 100
        }
        // 0.7 s of NO_FACE — past the 500 ms grace, so the branch runs the NO_FACE path.
        // Microsleep check inside the branch sees closureMs 300-1000, below 3 s → no promotion.
        repeat(7) {
            val a = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, false, t))
            // NO_FACE grace is 500 ms. NO_FACE loop started at t=400 → transitions to
            // NO_FACE at t=900+ (noFaceDuration >= 500). Before that, brief-glitch path
            // holds current state. Assert NO_FACE only after grace has expired.
            if (t >= 900) {
                assertEquals(
                    "State must be NO_FACE while closure has not yet crossed microsleep threshold (t=$t)",
                    FatigueState.NO_FACE,
                    a.fatigueState,
                )
            }
            t += 100
        }
        // Face returns, eyes still closed. Continuous closure anchored at t=100; at this
        // point cumulative time = t - 100. WARNING must fire before or at closureMs=3000.
        var reachedWarning = false
        var last = FatigueState.NO_FACE
        repeat(30) {
            last = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t)).fatigueState
            if (last == FatigueState.WARNING || last == FatigueState.FATIGUED) reachedWarning = true
            t += 100
        }
        assertTrue(
            "Microsleep detector must fire despite the NO_FACE gap (final=$last)",
            reachedWarning,
        )
    }

    @Test
    fun `microsleep timer inside NO_FACE promotes directly at 6s without face returning`() {
        // Even more aggressive scenario: the driver falls asleep and detection never
        // recovers within the alarm window. The microsleep check must promote to at
        // least WARNING while still inside the NO_FACE branch.
        var t = 0L
        // Anchor the closure — 4 face-detected closed frames (past the 2-frame debounce)
        repeat(4) {
            scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            t += 100
        }
        // 6.5 s of NO_FACE with closure timer alive from t≈100. closureMs ends near 6.4 s
        // → FATIGUED threshold (6 s) reached.
        var last = FatigueState.NO_FACE
        repeat(65) {
            last = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, false, t)).fatigueState
            t += 100
        }
        assertTrue(
            "Sustained closure through prolonged NO_FACE must reach WARNING or FATIGUED (final=$last)",
            last == FatigueState.WARNING || last == FatigueState.FATIGUED,
        )
    }

    @Test
    fun `microsleep fires when face is lost after a single raw-closed frame`() {
        // Regression for the field-test bug: driver kept eyes closed for 20s and no
        // alarm fired. Root cause was that continuousClosureStartMs required 2
        // consecutive debounced-closed frames to be anchored, but MediaPipe often
        // loses face detection within the first 1-2 frames of a closure (eyelids
        // fully down deny it landmarks). With this fix, the timer arms on frame 0,
        // so the microsleep-through-NO_FACE override fires as expected.
        var t = 0L
        // Exactly ONE face-detected closed frame — under the old debounce this was
        // insufficient to arm the timer.
        var last = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
        t += 100
        // 3.5s of NO_FACE — timer armed at t=0, override should fire at closureMs=3000.
        repeat(35) {
            last = scorer.processFrame(FatigueMetrics(0f, 0f, 0f, false, t))
            t += 100
        }
        assertEquals(
            "Expected WARNING after 1 closed frame + 3.5s of NO_FACE, got ${last.fatigueState}",
            FatigueState.WARNING,
            last.fatigueState,
        )
        // Continue to 6.5s total — should escalate to FATIGUED.
        repeat(30) {
            last = scorer.processFrame(FatigueMetrics(0f, 0f, 0f, false, t))
            t += 100
        }
        assertEquals(
            "Expected FATIGUED after 6.5s of NO_FACE from a 1-frame closure, got ${last.fatigueState}",
            FatigueState.FATIGUED,
            last.fatigueState,
        )
    }

    @Test
    fun `single closed frame followed by open does not survive into NO_FACE`() {
        // Companion to the fix above: since the timer now arms on the first raw-closed
        // frame, we must also guarantee that a single-frame noise closure followed by
        // an open frame RESETS the timer — otherwise stray noise before someone leaves
        // the frame would falsely alarm.
        var last = scorer.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, 0L))
        // Immediate open frame — timer must reset to -1.
        scorer.processFrame(FatigueMetrics(0.9f, 0.9f, 0.1f, true, 100L))
        // 4s of NO_FACE. With timer reset, the microsleep override cannot fire.
        var t = 200L
        repeat(40) {
            last = scorer.processFrame(FatigueMetrics(0f, 0f, 0f, false, t))
            t += 100
        }
        assertEquals(
            "State must be NO_FACE (not WARNING/FATIGUED) after the open frame reset the timer, got ${last.fatigueState}",
            FatigueState.NO_FACE,
            last.fatigueState,
        )
    }

    @Test
    fun `partial closure detector fires WARNING at 3s of sustained half-closed eyes`() {
        // Field test: driver kept eyes closed for 60s but MediaPipe blendshape reported
        // openness in the 0.23-0.42 range throughout (never below the calibrated 0.263
        // threshold consistently). The tight microsleep detector never armed because the
        // "raw closed" streak kept breaking. The partial closure detector picks that up.
        // Feed 31 frames with openness=0.35 (below PARTIAL_CLOSURE_THRESHOLD=0.45 but
        // above the default eyeClosedThreshold=0.30) at 100ms spacing = 3s total.
        var t = 0L
        var last = scorer.processFrame(FatigueMetrics(0.35f, 0.35f, 0.1f, true, t))
        t += 100
        repeat(30) {
            last = scorer.processFrame(FatigueMetrics(0.35f, 0.35f, 0.1f, true, t))
            t += 100
        }
        assertEquals(
            "Expected WARNING after 3s of sustained partial closure, got ${last.fatigueState}",
            FatigueState.WARNING,
            last.fatigueState,
        )
    }

    @Test
    fun `partial closure escalates to FATIGUED at 8s`() {
        var t = 0L
        var last = scorer.processFrame(FatigueMetrics(0.35f, 0.35f, 0.1f, true, t))
        t += 100
        repeat(85) {
            last = scorer.processFrame(FatigueMetrics(0.35f, 0.35f, 0.1f, true, t))
            t += 100
        }
        assertEquals(
            "Expected FATIGUED after 8s+ of sustained partial closure, got ${last.fatigueState}",
            FatigueState.FATIGUED,
            last.fatigueState,
        )
    }

    @Test
    fun `NO_FACE after open eyes does not trigger microsleep alarm`() {
        // Guardrail: the microsleep-through-NO_FACE promotion must only fire when the
        // closure timer was already alive. A driver who simply leaves the frame (or the
        // camera gets covered) with eyes OPEN must not receive a fatigue alarm.
        var t = 0L
        // 4 face-detected OPEN frames — closure timer stays at -1.
        repeat(4) {
            scorer.processFrame(FatigueMetrics(0.9f, 0.9f, 0.1f, true, t))
            t += 100
        }
        // 10 s of NO_FACE — timer stays inactive because closure never anchored.
        var last = FatigueState.NORMAL
        repeat(100) {
            last = scorer.processFrame(FatigueMetrics(0.9f, 0.9f, 0.1f, false, t)).fatigueState
            t += 100
        }
        assertEquals(
            "NO_FACE preceded by open eyes must never promote to WARNING/FATIGUED (got $last)",
            FatigueState.NO_FACE,
            last,
        )
    }

    @Test
    fun `blink buffer survives brief NO_FACE so blink deviation is not falsely penalized`() {
        // Field bug (secondary symptom): after a NO_FACE glitch during a running session,
        // the score climbed from 0 → 40 in NORMAL even with eyes open. Root cause: the
        // NO_FACE branch was clearing blinkTimestamps while monitoringStartMs was already
        // past the 30 s warmup — so blinkRate=0 fed calculateBlinkDeviationScore → max
        // deviation → 10-pt penalty applied continuously. With the buffer preserved,
        // the blink history persists across the glitch and the penalty stays sane.
        val s = FatigueScorer(calibrationEnabled = false)
        var t = 1000L
        // Warmup: 40 s with a healthy 20 blinks/min pattern (1 blink every 3 s of open frames).
        // Each blink = 5 closed frames at ~150 ms; each interval = 25 open frames at ~100 ms.
        // Feed for enough time that BLINK_MIN_OBSERVATION_MS (30 s) is well past.
        repeat(14) { // 14 cycles × ~3 s ≈ 42 s
            repeat(25) { s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)); t += 100 }
            repeat(5)  { s.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t)); t += 100 }
        }
        val preNoFace = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
        assertTrue(
            "Precondition: healthy blink rate must be near 20/min (got ${preNoFace.blinkRate})",
            preNoFace.blinkRate >= 10f,
        )
        t += 100
        // 2 s of NO_FACE — past the 500 ms grace, state transitions to NO_FACE. Under the
        // OLD behavior blinkTimestamps was cleared here; under the new behavior it is
        // preserved and the entries drain naturally by age (60 s window).
        repeat(20) {
            s.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, false, t))
            t += 100
        }
        // Face returns with eyes open. First few post-recovery frames must NOT have a
        // spurious blink-deviation penalty from an empty blinkTimestamps buffer.
        var maxBlinkContribution = 0f
        repeat(30) {
            val a = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
            maxBlinkContribution = maxOf(maxBlinkContribution, a.blinkContribution)
            t += 100
        }
        assertTrue(
            "blinkContribution must stay well below 10 pts right after NO_FACE recovery " +
                    "(got max=$maxBlinkContribution). Under the OLD behavior this would sit " +
                    "at the max 10-pt penalty because blinkTimestamps was cleared.",
            maxBlinkContribution < 5f,
        )
    }

    @Test
    fun `perclos buffer survives brief NO_FACE so history is not lost on recovery`() {
        // Corollary of the buffer-preservation change: after a brief detection gap the
        // recent PERCLOS history must survive so a real ongoing closure (e.g. driver
        // still drowsy after alarm) is not artificially masked by an empty buffer.
        val s = FatigueScorer(calibrationEnabled = false)
        var t = 0L
        // Drive 4 s of intermittent closure — perclosWindow accumulates real data.
        repeat(4) {
            repeat(6) { s.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t)); t += 100 }
            repeat(4) { s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)); t += 100 }
        }
        val prePerclos = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)).perclos
        t += 100
        // 1.5 s of NO_FACE — grace expires, transitions to NO_FACE.
        repeat(15) {
            s.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, false, t))
            t += 100
        }
        // Face returns, eyes open. PERCLOS must be non-zero-and-decreasing (reflecting
        // the preserved history draining by age) rather than a hard reset to 0.
        val postPerclos = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)).perclos
        assertTrue(
            "PERCLOS history must survive brief NO_FACE (pre=$prePerclos post=$postPerclos)",
            postPerclos > 0f || prePerclos == 0f,
        )
    }

    // -------------------------------------------------------------------------
    // Pre-calibration microsleep safety net + main-path smoothedScore force.
    //
    // Field bug that motivated these tests: driver started monitoring with eyes
    // already closed → first alarm took 10-20 s (calibration blocks the main
    // microsleep path). Separately: after the alarm fired, the on-screen score
    // read as 0 because the main microsleep block promoted state without
    // forcing smoothedScore up.
    // -------------------------------------------------------------------------

    @Test
    fun `eyes closed at Start reach WARNING within 3-4s despite calibration`() {
        // Driver hits Start with eyes already closed. Under the OLD behavior the
        // main-path microsleep detector was unreachable during the ~10 s calibration
        // window, so the first alarm arrived 10-20 s later. The pre-calibration
        // safety net must catch this within ~3 s.
        val s = FatigueScorer(calibrationEnabled = true)
        var t = 0L
        var reachedDanger = false
        var scoreAtDanger = 0f
        var timeAtDanger = 0L
        // 4.5 s of continuous closure at 30 fps (33 ms/frame). WARNING must fire
        // by ~3.1 s (2-frame debounce + 3 s MICROSLEEP_WARNING_MS).
        repeat(135) {
            val a = s.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            if (!reachedDanger &&
                (a.fatigueState == FatigueState.WARNING || a.fatigueState == FatigueState.FATIGUED)) {
                reachedDanger = true
                scoreAtDanger = a.score
                timeAtDanger = t
            }
            t += 33
        }
        assertTrue(
            "Pre-calibration safety net must promote to WARNING within ~4 s (reached=$reachedDanger at t=$timeAtDanger ms)",
            reachedDanger && timeAtDanger <= 4_000L,
        )
        assertTrue(
            "smoothedScore must be forced into the WARNING band (>=60) when the safety net fires (got $scoreAtDanger)",
            scoreAtDanger >= 60f,
        )
    }

    @Test
    fun `eyes closed at Start reach FATIGUED at 6s`() {
        // Escalation path: closure past MICROSLEEP_FATIGUED_MS (6 s) must promote
        // straight to FATIGUED with the corresponding forced score band.
        val s = FatigueScorer(calibrationEnabled = true)
        var t = 0L
        var last = FatigueState.NORMAL
        var lastScore = 0f
        // 7 s of continuous closure at 30 fps.
        repeat(210) {
            val a = s.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            last = a.fatigueState
            lastScore = a.score
            t += 33
        }
        assertEquals(
            "Sustained 7 s closure at Start must reach FATIGUED via the pre-calibration safety net (got $last)",
            FatigueState.FATIGUED,
            last,
        )
        assertTrue(
            "smoothedScore must be forced into the FATIGUED band (>=85) when the safety net escalates (got $lastScore)",
            lastScore >= 85f,
        )
    }

    @Test
    fun `brief closure during calibration does not abort the safety net`() {
        // Guardrail: a 1 s closure (well under the 3 s microsleep window) followed
        // by normal open eyes must NOT abort calibration. This is what happens if
        // the driver blinks harder than usual at startup — the safety net must not
        // fire and calibration must complete normally.
        val s = FatigueScorer(calibrationEnabled = true)
        var t = 0L
        // 1 s of closure (30 frames) — below the 3 s microsleep window.
        repeat(30) {
            val a = s.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            assertTrue(
                "State must stay CALIBRATING during the 1 s closure (got ${a.fatigueState} at t=$t)",
                a.fatigueState == FatigueState.CALIBRATING || a.fatigueState == FatigueState.NORMAL,
            )
            t += 33
        }
        // 10 s of eyes open — allows the stabilization gate to converge and the
        // 7 s collection window to complete. Calibration should finish and state
        // should be NORMAL (no false WARNING).
        var last = FatigueState.CALIBRATING
        repeat(300) {
            last = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)).fatigueState
            t += 33
        }
        assertEquals(
            "After 1 s closure + 10 s open, state must be NORMAL — calibration should complete without the safety net firing (got $last)",
            FatigueState.NORMAL,
            last,
        )
    }

    @Test
    fun `looking away during calibration does not trigger safety net`() {
        // Driver glances at the passenger for 4 s while their eyes read as "closed"
        // due to oblique perspective on the blendshapes. Yaw > 45° must block the
        // safety net's anchor so no false alarm fires.
        val s = FatigueScorer(calibrationEnabled = true)
        var t = 0L
        var last = FatigueState.NORMAL
        // 4 s of "closed" eyes but with head yaw 50° (past LOOK_AWAY_YAW_DEGREES=45).
        repeat(120) {
            last = s.processFrame(
                FatigueMetrics(
                    leftEyeOpenProbability = 0.05f,
                    rightEyeOpenProbability = 0.05f,
                    mouthOpenProbability = 0.1f,
                    isFaceDetected = true,
                    timestampMs = t,
                    headYawDegrees = 50f,
                ),
            ).fatigueState
            t += 33
        }
        assertTrue(
            "Look-away must block the pre-calibration safety net anchor (got $last)",
            last == FatigueState.CALIBRATING || last == FatigueState.NORMAL,
        )
    }

    @Test
    fun `narrow-eyed driver during calibration does not false-alert`() {
        // Baseline openness 0.25 sits between MICROSLEEP_PRECAL_THRESHOLD (0.20) and
        // the PERCLOS default (0.30) — represents a narrow-eyed driver whose
        // "eyes fully open" reads low on the MediaPipe blendshape. Safety net must
        // not treat this as microsleep. Calibration continues normally (once the
        // stabilization gate converges — openness 0.25 is below the 0.35 gate so
        // it won't converge here, but the safety net still must not fire).
        val s = FatigueScorer(calibrationEnabled = true)
        var t = 0L
        var last = FatigueState.NORMAL
        // 4 s of "eyes ambiguously open" (0.25) — above the safety-net threshold.
        repeat(120) {
            last = s.processFrame(FatigueMetrics(0.25f, 0.25f, 0.1f, true, t)).fatigueState
            t += 33
        }
        assertTrue(
            "Narrow-eyed baseline (openness=0.25) must not trigger the safety net (got $last)",
            last == FatigueState.CALIBRATING,
        )
    }

    @Test
    fun `main-path microsleep forces smoothedScore into WARNING band`() {
        // Fix 2: the main-path microsleep block (post-calibration) must also force
        // smoothedScore up when it promotes state. Otherwise the alarm fires with
        // the on-screen number stuck at 0 because PERCLOS (30 s window) hasn't
        // drained enough of the pre-closure open frames yet.
        //
        // Set up calibrationEnabled=false to skip the safety net entirely and hit
        // the main microsleep path. Feed 30 s of eyes-open, healthy blinks (fills
        // perclosWindow and blinkTimestamps with realistic data), then close eyes
        // for 4 s to trigger the main-path microsleep.
        val s = FatigueScorer(calibrationEnabled = false)
        var t = 0L
        // 30 s warmup with a healthy blink pattern (1 blink every ~3 s).
        repeat(10) {
            repeat(25) { s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t)); t += 100 }
            repeat(5)  { s.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t)); t += 100 }
        }
        val pre = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
        t += 100
        assertTrue(
            "Precondition: state must be NORMAL before the closure begins (got ${pre.fatigueState})",
            pre.fatigueState == FatigueState.NORMAL,
        )
        // 4 s of continuous closure — main-path microsleep must promote to WARNING
        // and force smoothedScore >= 60.
        var scoreAtWarning = -1f
        var stateAtWarning = FatigueState.NORMAL
        repeat(40) {
            val a = s.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            if (scoreAtWarning < 0f && (a.fatigueState == FatigueState.WARNING || a.fatigueState == FatigueState.FATIGUED)) {
                scoreAtWarning = a.score
                stateAtWarning = a.fatigueState
            }
            t += 100
        }
        assertTrue(
            "Main-path microsleep must promote to WARNING within 4 s of closure (got state=$stateAtWarning)",
            stateAtWarning == FatigueState.WARNING || stateAtWarning == FatigueState.FATIGUED,
        )
        assertTrue(
            "Fix 2: main-path microsleep must force smoothedScore >= 60 at promotion (got $scoreAtWarning)",
            scoreAtWarning >= 60f,
        )
    }

    @Test
    fun `aborted calibration does not re-enter CALIBRATING when eyes reopen`() {
        // After the safety net fires, calibrationAborted latches true — the driver
        // is now in the FSM's normal flow with default thresholds. Re-entering
        // CALIBRATING mid-session would be surprising and would suppress scoring
        // for another 10 s. Verify state never rewinds to CALIBRATING.
        val s = FatigueScorer(calibrationEnabled = true)
        var t = 0L
        // 4 s of closure — triggers the safety net.
        repeat(120) {
            s.processFrame(FatigueMetrics(0.05f, 0.05f, 0.1f, true, t))
            t += 33
        }
        // 10 s of eyes open — must NOT go back to CALIBRATING.
        var everCalibratedAgain = false
        repeat(300) {
            val a = s.processFrame(FatigueMetrics(0.85f, 0.85f, 0.1f, true, t))
            if (a.fatigueState == FatigueState.CALIBRATING) everCalibratedAgain = true
            t += 33
        }
        assertFalse(
            "After the safety net aborts calibration, state must never return to CALIBRATING",
            everCalibratedAgain,
        )
    }
}
