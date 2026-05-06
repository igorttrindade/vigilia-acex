package com.vigilia.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Helper object to manage the lifecycle of [MonitoringService].
 */
@Suppress("unused")
object ServiceController {

    /**
     * Starts the fatigue monitoring service.
     *
     * @param context The context used to start the service.
     */
    fun startMonitoring(context: Context) {
        val intent = Intent(context, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START
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
