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

    /**
     * Starts the fatigue monitoring service.
     *
     * @param context The context used to start the service.
     * @param calibrationEnabled If null, reuses the last explicit preference (defaults to
     *   true on first run). Pass an explicit value from SetupScreen to update it.
     */
    fun startMonitoring(context: Context, calibrationEnabled: Boolean? = null) {
        val effective = calibrationEnabled ?: lastCalibrationEnabled
        lastCalibrationEnabled = effective
        val intent = Intent(context, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START
            putExtra(MonitoringService.EXTRA_CALIBRATION_ENABLED, effective)
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
