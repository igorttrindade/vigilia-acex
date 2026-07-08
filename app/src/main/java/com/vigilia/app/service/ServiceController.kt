package com.vigilia.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Helper object to manage the lifecycle of [MonitoringService].
 */
@Suppress("unused")
object ServiceController {

    // Remembers the last explicit calibration preference so that restarting the session
    // from the MonitoringScreen toggle (which has no access to the SetupScreen toggle
    // state) doesn't silently flip calibration back to the default.
    @Volatile
    private var lastCalibrationEnabled: Boolean = true

    // Preference for the low-light adaptation pipeline (Camera2Interop EV/FPS tuning + CLAHE).
    // Defaults to ON so night driving is monitored out of the box; user can flip it off in
    // SetupScreen if adaptation causes issues on a specific device.
    @Volatile
    var lastLowLightAdaptationEnabled: Boolean = true
        private set

    /**
     * Starts the fatigue monitoring service.
     *
     * @param context The context used to start the service.
     * @param calibrationEnabled If null, reuses the last explicit preference (defaults to
     *   true on first run). Pass an explicit value from SetupScreen to update it.
     * @param lowLightAdaptationEnabled If null, reuses the last explicit preference.
     */
    fun startMonitoring(
        context: Context,
        calibrationEnabled: Boolean? = null,
        lowLightAdaptationEnabled: Boolean? = null,
    ) {
        val effectiveCalibration = calibrationEnabled ?: lastCalibrationEnabled
        lastCalibrationEnabled = effectiveCalibration
        val effectiveLowLight = lowLightAdaptationEnabled ?: lastLowLightAdaptationEnabled
        lastLowLightAdaptationEnabled = effectiveLowLight
        val intent = Intent(context, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START
            putExtra(MonitoringService.EXTRA_CALIBRATION_ENABLED, effectiveCalibration)
            putExtra(MonitoringService.EXTRA_LOW_LIGHT_ADAPTATION_ENABLED, effectiveLowLight)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Stops the fatigue monitoring service.
     *
     * @param context The context used to stop the service.
     */
    fun stopMonitoring(context: Context) {
        val intent = Intent(context, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_STOP
        }
        context.startService(intent)
    }
}
