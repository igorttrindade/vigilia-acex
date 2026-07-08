package com.vigilia.app.lighting

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LightingMonitorTest {

    private lateinit var monitor: LightingMonitor

    @Before
    fun setUp() {
        monitor = LightingMonitor()
    }

    @Test
    fun `starts in NORMAL`() {
        assertEquals(LightingMode.NORMAL, monitor.mode.value)
    }

    @Test
    fun `bright frame keeps NORMAL indefinitely`() {
        var t = 0L
        repeat(10) {
            monitor.update(lux = 300f, frameLuminance = 180f, tsMs = t)
            t += 500
        }
        assertEquals(LightingMode.NORMAL, monitor.mode.value)
    }

    @Test
    fun `single dark frame does not flip mode`() {
        monitor.update(lux = 200f, frameLuminance = 150f, tsMs = 0L)
        monitor.update(lux = 2f, frameLuminance = 20f, tsMs = 100L)
        assertEquals(LightingMode.NORMAL, monitor.mode.value)
    }

    @Test
    fun `sustained darkness commits to DARK after darker dwell`() {
        // Frame Y=20, lux=2 — target = DARK. Dwell going darker = 2000ms.
        monitor.update(null, 20f, tsMs = 0L)
        monitor.update(null, 20f, tsMs = 1000L)
        assertEquals(LightingMode.NORMAL, monitor.mode.value) // still within dwell
        monitor.update(null, 20f, tsMs = 2000L)
        assertEquals(LightingMode.DARK, monitor.mode.value)
    }

    @Test
    fun `borderline low light commits to LOW_LIGHT not DARK`() {
        // Y=50 puts it in LOW_LIGHT range [30, 65).
        monitor.update(null, 50f, tsMs = 0L)
        monitor.update(null, 50f, tsMs = 2100L)
        assertEquals(LightingMode.LOW_LIGHT, monitor.mode.value)
    }

    @Test
    fun `exit from DARK uses hysteresis and lighter dwell`() {
        // Commit to DARK first.
        monitor.update(null, 20f, tsMs = 0L)
        monitor.update(null, 20f, tsMs = 2000L)
        assertEquals(LightingMode.DARK, monitor.mode.value)

        // Y=40 is above enter-DARK (30) but below EXIT_DARK_Y=45 → still DARK.
        monitor.update(null, 40f, tsMs = 3000L)
        monitor.update(null, 40f, tsMs = 7000L)
        assertEquals(LightingMode.DARK, monitor.mode.value)

        // Y=55 crosses EXIT_DARK_Y=45. Target becomes LOW_LIGHT (never NORMAL from DARK).
        // Dwell lighter = 3000ms.
        monitor.update(null, 55f, tsMs = 8000L)
        monitor.update(null, 55f, tsMs = 10000L)
        assertEquals(LightingMode.DARK, monitor.mode.value) // still within lighter dwell
        monitor.update(null, 55f, tsMs = 11000L)
        assertEquals(LightingMode.LOW_LIGHT, monitor.mode.value)
    }

    @Test
    fun `exit from LOW_LIGHT to NORMAL requires plus 15 hysteresis`() {
        // Get into LOW_LIGHT.
        monitor.update(null, 50f, tsMs = 0L)
        monitor.update(null, 50f, tsMs = 2100L)
        assertEquals(LightingMode.LOW_LIGHT, monitor.mode.value)

        // Y=75 is above ENTER_LOW_Y=65 but below EXIT_LOW_Y=80 → still LOW_LIGHT (no target change).
        monitor.update(null, 75f, tsMs = 3000L)
        monitor.update(null, 75f, tsMs = 8000L)
        assertEquals(LightingMode.LOW_LIGHT, monitor.mode.value)

        // Y=95 crosses EXIT_LOW_Y=80. Dwell lighter = 3000ms.
        monitor.update(null, 95f, tsMs = 9000L)
        monitor.update(null, 95f, tsMs = 12100L)
        assertEquals(LightingMode.NORMAL, monitor.mode.value)
    }

    @Test
    fun `brief tunnel does not flip to DARK`() {
        // Simulate a 1s dip into darkness followed by return to normal.
        monitor.update(null, 200f, tsMs = 0L)
        monitor.update(null, 20f, tsMs = 500L)
        monitor.update(null, 20f, tsMs = 1000L)
        monitor.update(null, 20f, tsMs = 1500L)
        monitor.update(null, 200f, tsMs = 1900L) // dip ended before 2000ms dwell
        monitor.update(null, 200f, tsMs = 3000L)
        assertEquals(LightingMode.NORMAL, monitor.mode.value)
    }

    @Test
    fun `null lux still classifies from frame Y alone`() {
        // Devices without TYPE_LIGHT — LightingMonitor must still work.
        monitor.update(lux = null, frameLuminance = 20f, tsMs = 0L)
        monitor.update(lux = null, frameLuminance = 20f, tsMs = 2000L)
        assertEquals(LightingMode.DARK, monitor.mode.value)
    }

    @Test
    fun `bright frame with low lux stays NORMAL (AND entry logic)`() {
        // Front-camera on a well-lit face in a room where the ambient sensor happens to read
        // low (e.g. sensor covered, glare, or the phone is under a desk lamp with the sensor
        // shaded). Entry requires BOTH signals to indicate dim — a bright frame overrides
        // the low lux reading.
        monitor.update(lux = 2f, frameLuminance = 150f, tsMs = 0L)
        monitor.update(lux = 2f, frameLuminance = 150f, tsMs = 2100L)
        assertEquals(LightingMode.NORMAL, monitor.mode.value)
    }

    @Test
    fun `both signals dark commits to DARK`() {
        // Genuine darkness — both the frame is dim and the ambient sensor confirms it.
        // AND entry: both must agree before flipping. Dwell 2000ms.
        monitor.update(lux = 2f, frameLuminance = 20f, tsMs = 0L)
        monitor.update(lux = 2f, frameLuminance = 20f, tsMs = 2100L)
        assertEquals(LightingMode.DARK, monitor.mode.value)
    }

    @Test
    fun `reset clears state`() {
        monitor.update(null, 20f, tsMs = 0L)
        monitor.update(null, 20f, tsMs = 2100L)
        assertEquals(LightingMode.DARK, monitor.mode.value)
        monitor.reset()
        assertEquals(LightingMode.NORMAL, monitor.mode.value)
    }
}
