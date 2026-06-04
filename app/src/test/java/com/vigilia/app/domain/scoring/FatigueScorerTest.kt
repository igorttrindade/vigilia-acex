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
        val metrics = FatigueMetrics(0.8f, 0.8f, 0.1f, false, 1000L)
        val assessment = scorer.processFrame(metrics)
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

        // Simulate 2 blinks
        repeat(2) {
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime))
            currentTime += 100
            scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)) // Closed
            currentTime += 100
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)) // Open again
            currentTime += 100
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
        // After the all-closed WARNING phase isBlinking=true, so the first open frame
        // immediately counts as a blink completion.
        repeat(3) {
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)); currentTime += 100
            scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)); currentTime += 100
        }

        // Closed eyes + yawn: PERCLOS(50) + blink_low(20) + yawn(25) = 95 → score > 70
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
        while (scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)).score >= 25f) {
            // Occasional closed frame keeps blink rate measurable
            if (currentTime % 3000L == 0L) {
                scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime))
                currentTime += 100
            }
            currentTime += 100
        }

        val startTransitionTime = currentTime

        // Sustained for 9.9s — must still be WARNING
        while (currentTime - startTransitionTime < 9900L) {
            assertEquals(FatigueState.WARNING, scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)).fatigueState)
            currentTime += 100
        }

        // At 10s threshold → NORMAL
        currentTime = startTransitionTime + 10000L
        assertEquals(FatigueState.NORMAL, scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)).fatigueState)
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
