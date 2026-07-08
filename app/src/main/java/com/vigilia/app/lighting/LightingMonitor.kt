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
        if (y < ENTER_DARK_Y || (lux != null && lux < ENTER_DARK_LUX)) return LightingMode.DARK

        return when (current) {
            LightingMode.DARK -> {
                if (y >= EXIT_DARK_Y && (lux == null || lux >= EXIT_DARK_LUX)) LightingMode.LOW_LIGHT
                else LightingMode.DARK
            }
            LightingMode.LOW_LIGHT -> {
                if (y >= EXIT_LOW_Y && (lux == null || lux >= EXIT_LOW_LUX)) LightingMode.NORMAL
                else LightingMode.LOW_LIGHT
            }
            LightingMode.NORMAL -> {
                if (y < ENTER_LOW_Y || (lux != null && lux < ENTER_LOW_LUX)) LightingMode.LOW_LIGHT
                else LightingMode.NORMAL
            }
        }
    }

    companion object {
        const val ENTER_DARK_Y = 40f
        const val ENTER_LOW_Y = 90f
        const val ENTER_DARK_LUX = 5f
        const val ENTER_LOW_LUX = 50f

        const val EXIT_DARK_Y = 55f
        const val EXIT_LOW_Y = 105f
        const val EXIT_DARK_LUX = 20f
        const val EXIT_LOW_LUX = 65f

        const val DWELL_MS_DARKER = 2000L
        const val DWELL_MS_LIGHTER = 3000L
    }
}
