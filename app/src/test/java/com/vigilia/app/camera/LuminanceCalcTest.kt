package com.vigilia.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class LuminanceCalcTest {

    private fun bufferOf(vararg values: Int): ByteBuffer {
        val bytes = ByteArray(values.size) { values[it].toByte() }
        return ByteBuffer.wrap(bytes)
    }

    private fun uniformBuffer(width: Int, height: Int, rowStride: Int, value: Int): ByteBuffer {
        val bytes = ByteArray(rowStride * height) { value.toByte() }
        return ByteBuffer.wrap(bytes)
    }

    @Test
    fun `mean of uniform bright buffer is that value`() {
        val buf = uniformBuffer(width = 64, height = 64, rowStride = 64, value = 200)
        val mean = FaceAnalyzer.computeYPlaneMean(buf, rowStride = 64, width = 64, height = 64)
        assertEquals(200f, mean, 0.5f)
    }

    @Test
    fun `mean of uniform dark buffer is that value`() {
        val buf = uniformBuffer(width = 640, height = 480, rowStride = 640, value = 15)
        val mean = FaceAnalyzer.computeYPlaneMean(buf, rowStride = 640, width = 640, height = 480)
        assertEquals(15f, mean, 0.5f)
    }

    @Test
    fun `handles unsigned byte values above 127`() {
        // Byte 0xFF is signed -1 in JVM. Must be masked to 255.
        val buf = uniformBuffer(width = 64, height = 64, rowStride = 64, value = 0xFF)
        val mean = FaceAnalyzer.computeYPlaneMean(buf, rowStride = 64, width = 64, height = 64)
        assertEquals(255f, mean, 0.5f)
    }

    @Test
    fun `respects rowStride padding`() {
        // 8x8 image with rowStride=16 (padding to the right). Content is 100 in the first 8 columns
        // and 0 in the padding — sampling must not read into the padding rows or misalign.
        val bytes = ByteArray(16 * 8)
        for (r in 0 until 8) {
            for (c in 0 until 8) bytes[r * 16 + c] = 100
            // columns 8..15 stay 0 (padding)
        }
        val buf = ByteBuffer.wrap(bytes)
        val mean = FaceAnalyzer.computeYPlaneMean(buf, rowStride = 16, width = 8, height = 8)
        assertEquals(100f, mean, 0.5f)
    }

    @Test
    fun `zero width or height returns zero`() {
        val buf = uniformBuffer(1, 1, 1, 100)
        assertEquals(0f, FaceAnalyzer.computeYPlaneMean(buf, 1, 0, 10), 0.001f)
        assertEquals(0f, FaceAnalyzer.computeYPlaneMean(buf, 1, 10, 0), 0.001f)
    }

    @Test
    fun `sampling never reads past buffer limit`() {
        // Small buffer, but caller passes larger width/height — must not crash.
        val buf = bufferOf(10, 20, 30, 40)
        val mean = FaceAnalyzer.computeYPlaneMean(buf, rowStride = 2, width = 100, height = 100)
        // Values just clamp to what's available; anything non-negative is fine here.
        assert(mean >= 0f)
    }
}
