package com.vigilia.app.lighting

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LightingMode { NORMAL, LOW_LIGHT, DARK }

/**
 * State machine that classifies the current lighting condition from ambient lux
 * (when the device has a light sensor) and mean Y-channel luminance of the last frame.
 *
 * Entry/exit thresholds are asymmetric (+15 hysteresis on Y, +15 on lux) and a dwell
 * time is required before a mode change commits, so a brief tunnel or a passing headlight
 * cannot flip the mode.
 */
class LightingMonitor {

    private val _mode = MutableStateFlow(LightingMode.NORMAL)
    val mode: StateFlow<LightingMode> = _mode

    private var pendingTarget: LightingMode? = null
    private var pendingSinceMs: Long = 0L

    /**
     * Feeds a new sample into the machine and returns the current mode (which may or may
     * not have changed as a result). The mode changes only after the new target has been
     * sustained for the direction-dependent dwell window.
     *
     * @param lux ambient light in lux, or null on devices without TYPE_LIGHT.
     * @param frameLuminance mean Y-channel value of the frame, [0..255].
     * @param tsMs monotonic timestamp (e.g. System.nanoTime()/1_000_000).
     */
    fun update(lux: Float?, frameLuminance: Float, tsMs: Long): LightingMode {
        val current = _mode.value
        val target = classify(current, lux, frameLuminance)

        if (target == current) {
            pendingTarget = null
            return current
        }

        if (pendingTarget != target) {
            pendingTarget = target
            pendingSinceMs = tsMs
            return current
        }

        val dwellMs = if (target.ordinal > current.ordinal) DWELL_MS_DARKER else DWELL_MS_LIGHTER
        if (tsMs - pendingSinceMs >= dwellMs) {
            _mode.value = target
            pendingTarget = null
        }
        return _mode.value
    }

    fun reset() {
        _mode.value = LightingMode.NORMAL
        pendingTarget = null
        pendingSinceMs = 0L
    }

    private fun classify(current: LightingMode, lux: Float?, y: Float): LightingMode {
        // Asymmetric policy — "hard to enter, easy to exit":
        //   Entry: BOTH signals must agree (AND) — prevents false positives from front-camera
        //   AE producing frame Y 60-90 in normally-lit rooms; lux corroborates as ambient truth.
        //   Exit: Y ALONE is sufficient. The lux sensor is often mispositioned (covered by
        //   phone holder, bezel, reflective mount) and can stay pinned low even in a bright
        //   room, which used to trap the mode in LOW_LIGHT/DARK forever with the earlier
        //   symmetric AND-exit. Since Y is what the pipeline actually processes, when Y
        //   recovers we no longer need CLAHE/adaptation regardless of what lux reads.
        val luxIsDark = lux == null || lux < ENTER_DARK_LUX
        val luxIsLow = lux == null || lux < ENTER_LOW_LUX

        if (y < ENTER_DARK_Y && luxIsDark) return LightingMode.DARK

        return when (current) {
            LightingMode.DARK -> {
                if (y >= EXIT_DARK_Y) LightingMode.LOW_LIGHT
                else LightingMode.DARK
            }
            LightingMode.LOW_LIGHT -> {
                if (y >= EXIT_LOW_Y) LightingMode.NORMAL
                else LightingMode.LOW_LIGHT
            }
            LightingMode.NORMAL -> {
                if (y < ENTER_LOW_Y && luxIsLow) LightingMode.LOW_LIGHT
                else LightingMode.NORMAL
            }
        }
    }

    companion object {
        // Y thresholds were 40 / 90 — too high for typical front-camera output in normal
        // indoor lighting (spot-metered face frames land in 60-90 range routinely). Lowered
        // so LOW_LIGHT only triggers when frames are genuinely dim (below ~28% brightness).
        const val ENTER_DARK_Y = 30f     // was 40
        const val ENTER_LOW_Y = 65f      // was 90
        const val ENTER_DARK_LUX = 5f
        const val ENTER_LOW_LUX = 50f

        const val EXIT_DARK_Y = 45f      // was 55
        // Was 80 — too high for typical front-camera AE output in normally-lit indoor
        // scenes (Y usually maxes at 70-75 with spot-metering on the face). LOW_LIGHT got
        // stuck: entered legitimately when the room darkened, then the exit gate was
        // unreachable when lights came back on. 70 gives 5 pt hysteresis with ENTER_LOW_Y=65
        // — small window but the 3 s lighter dwell prevents flapping.
        const val EXIT_LOW_Y = 70f       // was 105, then 80
        const val EXIT_DARK_LUX = 20f
        const val EXIT_LOW_LUX = 65f

        const val DWELL_MS_DARKER = 2000L
        const val DWELL_MS_LIGHTER = 3000L
    }
}
