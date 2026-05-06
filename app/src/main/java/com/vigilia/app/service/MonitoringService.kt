package com.vigilia.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.vigilia.app.camera.CameraManager
import com.vigilia.app.data.telemetry.TelemetryWriter
import com.vigilia.app.domain.model.FatigueAssessment
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.domain.model.TelemetryRecord
import com.vigilia.app.domain.scoring.FatigueScorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask

/**
 * Foreground Service that orchestrates the full monitoring pipeline.
 *
 * It integrates CameraX, ML Kit analysis, fatigue scoring, and telemetry persistence.
 * It also manages audio alerts and ensures the CPU stays awake during monitoring.
 */
class MonitoringService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_START = "com.vigilia.app.START_MONITORING"
        const val ACTION_STOP = "com.vigilia.app.STOP_MONITORING"
        private const val CHANNEL_ID = "vigilia_monitoring"
        private const val NOTIFICATION_ID = 1

        /**
         * Exposed StateFlow for the UI to collect the current fatigue assessment.
         */
        val currentAssessment = MutableStateFlow<FatigueAssessment?>(null)
    }

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var serviceScope: CoroutineScope
    
    private lateinit var cameraManager: CameraManager
    private lateinit var scorer: FatigueScorer
    private lateinit var telemetryWriter: TelemetryWriter
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private var alertTimer: Timer? = null

    private var sessionId: String? = null
    private var lastTelemetryWriteTime = 0L
    private val telemetryIntervalMs = 2000L

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        
        cameraManager = CameraManager(this)
        scorer = FatigueScorer()
        telemetryWriter = TelemetryWriter(this)
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        startForeground(NOTIFICATION_ID, createNotification("Iniciando monitoramento..."))
        
        acquireWakeLock()
        
        serviceScope.launch {
            try {
                sessionId = telemetryWriter.startSession()
                
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                
                cameraManager.startCamera(
                    this@MonitoringService,
                    { metrics ->
                        val assessment = scorer.processFrame(metrics)
                        currentAssessment.value = assessment
                        
                        handleAssessment(assessment)
                    },
                )
            } catch (e: Exception) {
                Log.e("MonitoringService", "Failed to start monitoring", e)
                stopSelf()
            }
        }
    }

    private fun handleAssessment(assessment: FatigueAssessment) {
        // 1. Update Notification
        updateNotification("Monitorando... Estado: ${assessment.fatigueState}")

        // 2. Audio Alert Logic
        val previousState = currentAssessment.value?.fatigueState
        if (assessment.fatigueState != previousState) {
            if (
                (assessment.fatigueState == FatigueState.WARNING) ||
                (assessment.fatigueState == FatigueState.FATIGUED)
            ) {
                triggerAlert()
            } else if (
                (assessment.fatigueState == FatigueState.NORMAL) ||
                (assessment.fatigueState == FatigueState.NO_FACE)
            ) {
                stopAlert()
            }
        }

        // 3. Telemetry Throttling
        val currentTime = System.currentTimeMillis()
        if ((currentTime - lastTelemetryWriteTime) >= telemetryIntervalMs) {
            val sId = sessionId ?: return
            serviceScope.launch {
                telemetryWriter.writeRecord(
                    TelemetryRecord(
                        sessionId = sId,
                        timestamp = assessment.timestampMs,
                        score = assessment.score,
                        state = assessment.fatigueState,
                        eyeOpenness = 0f, // Not explicitly tracked in assessment but derived
                        blinkRate = assessment.blinkRate,
                        isYawning = assessment.isYawning,
                        isFaceDetected = assessment.isFaceDetected,
                        alertActive = isAlertActive(),
                    ),
                )
                lastTelemetryWriteTime = currentTime
            }
        }
    }

    private fun triggerAlert() {
        stopAlert() // Clear any existing
        
        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtone = RingtoneManager.getRingtone(this, alertUri).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                play()
            }
            
            // Stop after 3 seconds
            alertTimer = Timer().apply {
                schedule(
                    object : TimerTask() {
                        override fun run() {
                            stopAlert()
                        }
                    },
                    3000L,
                )
            }
        } catch (e: Exception) {
            Log.e("MonitoringService", "Failed to play alert", e)
        }
    }

    private fun stopAlert() {
        ringtone?.stop()
        ringtone = null
        alertTimer?.cancel()
        alertTimer = null
    }

    private fun isAlertActive(): Boolean = ringtone?.isPlaying == true

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vigilia:monitoring").apply {
            // Acquire with 10-hour timeout as a safety fallback
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("MonitoringService", "Error releasing WakeLock", e)
        } finally {
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        val name = "Monitoramento Vigília"
        val descriptionText = "Notificações do monitoramento de fadiga em tempo real"
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
            description = descriptionText
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vigília ativo")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    override fun onDestroy() {
        // Order as requested: DESTROYED before cancelling scope
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        
        serviceScope.launch {
            telemetryWriter.stopSession()
        }
        
        cameraManager.stopCamera()
        stopAlert()
        releaseWakeLock()
        
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
