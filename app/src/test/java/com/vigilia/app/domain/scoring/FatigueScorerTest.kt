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
                // Total 10 frames, 5 closed. PERCLOS = 0.5.
                // Weight is 50, so contribution is 25.
                assertTrue("Score should be increasing due to PERCLOS", assessment.score > 0)
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
        
        // Feed metrics that give score ~30 (e.g. 0 blinks, eyes open)
        repeat(10) {
            scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime))
            currentTime += 100
        }

        // Trigger high score (> 40)
        while (scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)).score <= 40f) {
            currentTime += 100
        }
        
        val startTransitionTime = currentTime
        // Sustained for 1.9s
        while (currentTime - startTransitionTime < 1900L) {
            assertEquals(FatigueState.NORMAL, scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)).fatigueState)
            currentTime += 100
        }

        // Now reach 2s
        currentTime = startTransitionTime + 2000L
        assertEquals(FatigueState.WARNING, scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)).fatigueState)
    }

    @Test
    fun `WARNING to FATIGUED transition requires sustained score`() {
        var currentTime = 1000L
        
        // Get into WARNING first (cheat by jumping time)
        scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime))
        currentTime += 3000L
        assertEquals(FatigueState.WARNING, scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)).fatigueState)

        // Trigger FATIGUED score (> 70)
        while (scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.8f, true, currentTime)).score <= 70f) {
            currentTime += 100
        }
        
        val startTransitionTime = currentTime
        // Sustained for 3.9s
        while (currentTime - startTransitionTime < 3900L) {
            assertEquals(FatigueState.WARNING, scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.8f, true, currentTime)).fatigueState)
            currentTime += 100
        }

        // Now reach 4s
        currentTime = startTransitionTime + 4000L
        assertEquals(FatigueState.FATIGUED, scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.8f, true, currentTime)).fatigueState)
    }

    @Test
    fun `WARNING to NORMAL transition requires sustained low score`() {
        var currentTime = 1000L
        
        // Get into WARNING first
        scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime))
        currentTime += 3000L
        assertEquals(FatigueState.WARNING, scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime)).fatigueState)

        // Lower score below 25 by adding blinks and waiting for smoothing
        while (scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)).score >= 25f) {
            // Add a blink occasionally to keep blink rate normal
            if (currentTime % 3000L == 0L) {
                scorer.processFrame(FatigueMetrics(0.1f, 0.1f, 0.1f, true, currentTime))
                currentTime += 100
            }
            currentTime += 100
        }
        
        val startTransitionTime = currentTime
        
        // Sustained for 9.9s
        while (currentTime - startTransitionTime < 9900L) {
            assertEquals(FatigueState.WARNING, scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime)).fatigueState)
            currentTime += 100
        }
        
        // Now reach 10s
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
        
        val assessment = scorer.processFrame(FatigueMetrics(0.8f, 0.8f, 0.1f, true, currentTime))
        assertFalse(assessment.isYawning)
        assertEquals(30f, assessment.score, 1.0f)
        assertEquals(FatigueState.NORMAL, assessment.fatigueState)
    }
}
