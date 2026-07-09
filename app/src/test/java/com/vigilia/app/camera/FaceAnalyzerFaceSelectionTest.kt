package com.vigilia.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class FaceAnalyzerFaceSelectionTest {

    private fun bbox(minX: Float, minY: Float, maxX: Float, maxY: Float): FloatArray =
        floatArrayOf(minX, minY, maxX, maxY)

    @Test
    fun `pickLargestFaceIndex returns -1 for empty list`() {
        assertEquals(-1, FaceAnalyzer.pickLargestFaceIndex(emptyList()))
    }

    @Test
    fun `pickLargestFaceIndex returns 0 for single face`() {
        val bboxes = listOf(bbox(0.4f, 0.3f, 0.6f, 0.7f))
        assertEquals(0, FaceAnalyzer.pickLargestFaceIndex(bboxes))
    }

    @Test
    fun `pickLargestFaceIndex picks larger of two disparate faces`() {
        // Face 0 area = 0.2 * 0.4 = 0.08; Face 1 area = 0.05 * 0.05 = 0.0025.
        val bboxes = listOf(
            bbox(0.2f, 0.1f, 0.4f, 0.5f),     // large (driver)
            bbox(0.7f, 0.4f, 0.75f, 0.45f),   // tiny (rear passenger)
        )
        assertEquals(0, FaceAnalyzer.pickLargestFaceIndex(bboxes))
    }

    @Test
    fun `pickLargestFaceIndex tolerates equal-area faces (deterministic winner)`() {
        // Ties resolve to the earliest index — deterministic, avoids flicker in edge cases.
        val bboxes = listOf(
            bbox(0.1f, 0.1f, 0.3f, 0.3f),
            bbox(0.6f, 0.6f, 0.8f, 0.8f),
        )
        assertEquals(0, FaceAnalyzer.pickLargestFaceIndex(bboxes))
    }

    @Test
    fun `pickDriverIndex returns minus one for empty list`() {
        assertEquals(-1, FaceAnalyzer.pickDriverIndex(emptyList(), -1f, -1f))
    }

    @Test
    fun `pickDriverIndex falls back to largest with no sticky state`() {
        val bboxes = listOf(
            bbox(0.1f, 0.1f, 0.3f, 0.3f),     // area 0.04
            bbox(0.4f, 0.2f, 0.9f, 0.9f),     // area 0.35 — largest
        )
        assertEquals(1, FaceAnalyzer.pickDriverIndex(bboxes, -1f, -1f))
    }

    @Test
    fun `pickDriverIndex prefers sticky candidate when close and sufficiently large`() {
        // Face 0 is the (slightly smaller) previous driver; Face 1 is a passenger who just
        // leaned forward and is now marginally larger. Sticky bias should keep the driver.
        // Face 0: bbox area = 0.6 * 0.5 = 0.30, center = (0.4, 0.45)
        // Face 1: bbox area = 0.6 * 0.6 = 0.36, center = (0.5, 0.5)
        val bboxes = listOf(
            bbox(0.10f, 0.20f, 0.70f, 0.70f), // driver — slightly smaller
            bbox(0.20f, 0.20f, 0.80f, 0.80f), // passenger — now largest
        )
        // Face 0's area = 0.30, face 1's largest area = 0.36. Ratio = 0.833 > 0.70 ✓
        // Face 0 center (0.4, 0.45) is within tolerance of the sticky center (0.4, 0.45).
        val selected = FaceAnalyzer.pickDriverIndex(bboxes, 0.4f, 0.45f)
        assertEquals("Sticky bias should keep face 0 as the driver", 0, selected)
    }

    @Test
    fun `pickDriverIndex falls back to largest when sticky candidate is too small`() {
        // Face 0 is much smaller than face 1 (< 70% ratio) — sticky bias does NOT trigger.
        val bboxes = listOf(
            bbox(0.35f, 0.40f, 0.45f, 0.50f), // tiny (area = 0.01)
            bbox(0.10f, 0.10f, 0.70f, 0.70f), // huge (area = 0.36)
        )
        // Sticky center is near face 0 (0.4, 0.45) — center match OK but area ratio fails.
        val selected = FaceAnalyzer.pickDriverIndex(bboxes, 0.4f, 0.45f)
        assertEquals("Sticky candidate too small — should fall back to largest", 1, selected)
    }

    @Test
    fun `pickDriverIndex falls back to largest when sticky candidate is out of tolerance`() {
        // Face 0 center is far from the sticky center (>0.15) — sticky bias does NOT trigger.
        val bboxes = listOf(
            bbox(0.10f, 0.20f, 0.70f, 0.70f), // driver-sized but far from sticky center
            bbox(0.15f, 0.25f, 0.80f, 0.85f), // similar size, closer to previous sticky
        )
        // Sticky center at (0.9, 0.9) is far from BOTH candidates.
        val selected = FaceAnalyzer.pickDriverIndex(bboxes, 0.9f, 0.9f)
        // Both candidates fail the tolerance check → falls back to largest.
        val expectedLargest = FaceAnalyzer.pickLargestFaceIndex(bboxes)
        assertEquals("Out of tolerance — should fall back to largest", expectedLargest, selected)
    }

    @Test
    fun `pickDriverIndex single face short-circuits without touching sticky state`() {
        val bboxes = listOf(bbox(0.4f, 0.4f, 0.6f, 0.6f))
        assertEquals(0, FaceAnalyzer.pickDriverIndex(bboxes, -1f, -1f))
        assertEquals(0, FaceAnalyzer.pickDriverIndex(bboxes, 0.4f, 0.5f))
    }

    @Test
    fun `decomposeYawPitchFromMatrix returns zero for identity matrix`() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val (yaw, pitch) = FaceAnalyzer.decomposeYawPitchFromMatrix(identity)
        assertEquals(0f, yaw, 0.01f)
        assertEquals(0f, pitch, 0.01f)
    }

    @Test
    fun `decomposeYawPitchFromMatrix computes yaw for known 30deg rotation`() {
        // Y-axis rotation by 30° (in column-major storage: m[c*4 + r]):
        //  cos30  0  sin30  0
        //    0    1    0    0
        // -sin30  0  cos30  0
        //    0    0    0    1
        // The extraction reads r02 = m[8], r12 = m[9], r22 = m[10].
        //   m[8]  = R[0][2] = sin30
        //   m[9]  = R[1][2] = 0
        //   m[10] = R[2][2] = cos30
        // → yaw = atan2(sin30, cos30) = 30°, pitch = asin(0) = 0.
        val c = kotlin.math.cos(30.0 * PI / 180.0).toFloat()
        val s = kotlin.math.sin(30.0 * PI / 180.0).toFloat()
        val m = floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s, 0f, c, 0f,
            0f, 0f, 0f, 1f,
        )
        val (yaw, pitch) = FaceAnalyzer.decomposeYawPitchFromMatrix(m)
        assertEquals("yaw should be 30°", 30f, yaw, 0.1f)
        assertEquals("pitch should be 0°", 0f, pitch, 0.1f)
    }

    @Test
    fun `decomposeYawPitchFromMatrix returns zero for matrix shorter than 16`() {
        val short = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f)
        val (yaw, pitch) = FaceAnalyzer.decomposeYawPitchFromMatrix(short)
        assertEquals(0f, yaw, 0.001f)
        assertEquals(0f, pitch, 0.001f)
    }

    @Test
    fun `bboxArea handles degenerate bbox`() {
        // Point-like bbox → area 0.
        assertEquals(0f, FaceAnalyzer.bboxArea(bbox(0.5f, 0.5f, 0.5f, 0.5f)), 0.0001f)
        // Inverted (max < min) → coerced to 0 so a corrupt bbox never spuriously wins selection.
        assertEquals(0f, FaceAnalyzer.bboxArea(bbox(0.7f, 0.7f, 0.3f, 0.3f)), 0.0001f)
    }

    @Test
    fun `MAX_FACES matches the numeric expectation from the plan`() {
        // Sentinel test: locks in the numFaces contract with MediaPipe. Change this only
        // when intentionally shifting the multi-face policy.
        assertEquals(4, FaceAnalyzer.MAX_FACES)
    }
}
