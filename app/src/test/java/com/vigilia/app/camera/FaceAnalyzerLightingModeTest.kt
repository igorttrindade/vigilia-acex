package com.vigilia.app.camera

import com.vigilia.app.lighting.LightingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAnalyzerLightingModeTest {

    @Test
    fun `shouldApplyClahe false in NORMAL`() {
        assertFalse(FaceAnalyzer.shouldApplyClahe(LightingMode.NORMAL))
    }

    @Test
    fun `shouldApplyClahe true in LOW_LIGHT`() {
        assertTrue(FaceAnalyzer.shouldApplyClahe(LightingMode.LOW_LIGHT))
    }

    @Test
    fun `shouldApplyClahe true in DARK`() {
        assertTrue(FaceAnalyzer.shouldApplyClahe(LightingMode.DARK))
    }

    @Test
    fun `gammaFor NORMAL is identity`() {
        assertEquals(1f, FaceAnalyzer.gammaFor(LightingMode.NORMAL), 0.001f)
    }

    @Test
    fun `gammaFor LOW_LIGHT is 1_2`() {
        assertEquals(FaceAnalyzer.GAMMA_LOW_LIGHT, FaceAnalyzer.gammaFor(LightingMode.LOW_LIGHT), 0.001f)
    }

    @Test
    fun `gammaFor DARK is stronger than LOW_LIGHT`() {
        val low = FaceAnalyzer.gammaFor(LightingMode.LOW_LIGHT)
        val dark = FaceAnalyzer.gammaFor(LightingMode.DARK)
        assertTrue("DARK gamma should exceed LOW_LIGHT gamma", dark > low)
    }

    // ---- combineEyeOpenness: EAR-jitter rejection guard ----

    @Test
    fun `combineEyeOpenness rejects EAR jitter when blendshape confidently reports open`() {
        // Landmark artifact: blendshape says clearly open, EAR says near-zero.
        // We must ignore EAR and use blendshape alone.
        val result = FaceAnalyzer.combineEyeOpenness(blend = 0.85f, ear = 0.02f)
        assertEquals(0.85f, result, 0.001f)
    }

    @Test
    fun `combineEyeOpenness respects real blink where both sensors agree`() {
        // Real blink: blendshape also drops → EAR-jitter guard does NOT fire →
        // min(blend, ear) wins as before.
        val result = FaceAnalyzer.combineEyeOpenness(blend = 0.10f, ear = 0.05f)
        assertEquals(0.05f, result, 0.001f)
    }

    @Test
    fun `combineEyeOpenness respects glasses reflection where EAR is above trust floor`() {
        // Glasses reflection inflates blendshape (0.80) but EAR captures the closure at
        // 0.30, still above EAR_MIN_TRUSTED. Guard does not fire, min wins — preserves
        // the original glasses-reflection protection.
        val result = FaceAnalyzer.combineEyeOpenness(blend = 0.80f, ear = 0.30f)
        assertEquals(0.30f, result, 0.001f)
    }

    @Test
    fun `combineEyeOpenness does not fire guard when blendshape is ambiguous`() {
        // Blendshape below BLEND_CONFIDENT_OPEN (0.30) — we don't trust either sensor
        // exclusively, so min still wins.
        val result = FaceAnalyzer.combineEyeOpenness(blend = 0.25f, ear = 0.02f)
        assertEquals(0.02f, result, 0.001f)
    }

    @Test
    fun `combineEyeOpenness allows mid-blink EAR to trigger detection`() {
        // Regression case: mid-blink, EAR has dropped ahead of blendshape.
        // ear=0.10 is above the (tightened) EAR_MIN_TRUSTED=0.05 → guard does NOT
        // fire → min wins → returns 0.10, low enough to count as closed downstream.
        // With the original 0.15 threshold this frame was rejected, delaying blinks.
        val result = FaceAnalyzer.combineEyeOpenness(blend = 0.50f, ear = 0.10f)
        assertEquals(0.10f, result, 0.001f)
    }
}
