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
    fun `exit from LOW_LIGHT to NORMAL requires clearing the hysteresis band`() {
        // Get into LOW_LIGHT.
        monitor.update(null, 50f, tsMs = 0L)
        monitor.update(null, 50f, tsMs = 2100L)
        assertEquals(LightingMode.LOW_LIGHT, monitor.mode.value)

        // Y=68 is above ENTER_LOW_Y=65 but below EXIT_LOW_Y=70 → still LOW_LIGHT (in the
        // hysteresis band, no target change).
        monitor.update(null, 68f, tsMs = 3000L)
        monitor.update(null, 68f, tsMs = 8000L)
        assertEquals(LightingMode.LOW_LIGHT, monitor.mode.value)

        // Y=85 crosses EXIT_LOW_Y=70. Dwell lighter = 3000ms.
        monitor.update(null, 85f, tsMs = 9000L)
        monitor.update(null, 85f, tsMs = 12100L)
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
    fun `LOW_LIGHT exits to NORMAL when Y recovers even if lux stays low`() {
        // Regression for the reported bug: adaptation got stuck in LOW_LIGHT and never
        // returned. Common cause is a lux sensor pinned low by a phone holder / mount /
        // reflective surface while the front camera sees a well-exposed frame. Exit now
        // uses Y alone, so a recovering frame lifts the mode regardless of the sensor.
        // Get into LOW_LIGHT via genuine dim signals.
        monitor.update(lux = 20f, frameLuminance = 50f, tsMs = 0L)
        monitor.update(lux = 20f, frameLuminance = 50f, tsMs = 2100L)
        assertEquals(LightingMode.LOW_LIGHT, monitor.mode.value)

        // Y jumps to 100 (bright frame), but lux stays pinned at 20 (below EXIT_LOW_LUX=65).
        // With the old symmetric-AND exit, this was stuck forever. With the new Y-alone
        // exit, it should transition to NORMAL after the lighter dwell (3 s).
        monitor.update(lux = 20f, frameLuminance = 100f, tsMs = 3000L)
        monitor.update(lux = 20f, frameLuminance = 100f, tsMs = 5000L)
        assertEquals(LightingMode.LOW_LIGHT, monitor.mode.value) // still within dwell
        monitor.update(lux = 20f, frameLuminance = 100f, tsMs = 6100L)
        assertEquals(LightingMode.NORMAL, monitor.mode.value)
    }

    @Test
    fun `implausible Y sensor pinned at zero keeps NORMAL not DARK`() {
        // Regression: on devices where the camera Y-plane reports 0 permanently (covered
        // lens, sensor driver bug), the FSM used to enter DARK on the first frame and
        // never exit — exit needs Y ≥ EXIT_DARK_Y=45, unreachable if Y is stuck at 0.
        // The sensor-sanity guard rejects Y < SENSOR_MIN_PLAUSIBLE_Y before classification.
        var t = 0L
        repeat(30) {
            monitor.update(lux = null, frameLuminance = 0f, tsMs = t)
            t += 1000L
        }
        assertEquals(LightingMode.NORMAL, monitor.mode.value)
    }

    @Test
    fun `implausible Y sensor allows escape from stale DARK`() {
        // Get into DARK via genuine signals, then simulate the sensor going bad (Y=0
        // permanently). The mode should fall back to NORMAL after the lighter dwell.
        monitor.update(lux = 2f, frameLuminance = 20f, tsMs = 0L)
        monitor.update(lux = 2f, frameLuminance = 20f, tsMs = 2100L)
        assertEquals(LightingMode.DARK, monitor.mode.value)

        // Y drops to 0 (implausible) — classifier returns NORMAL. Target becomes NORMAL,
        // dwell lighter = 3000ms (from DARK the first hop is DARK → LOW_LIGHT via classify,
        // but implausible signals short-circuit to NORMAL directly).
        var t = 3000L
        repeat(10) {
            monitor.update(lux = 2f, frameLuminance = 0f, tsMs = t)
            t += 500L
        }
        // After ≥ 3s of implausible signal, mode should have exited DARK.
        assertEquals(
            "Implausible Y should let the FSM escape DARK",
            LightingMode.NORMAL,
            monitor.mode.value,
        )
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
