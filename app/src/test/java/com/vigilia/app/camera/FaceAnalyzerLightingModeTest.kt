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
}
