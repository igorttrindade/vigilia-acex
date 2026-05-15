package com.vigilia.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.Preview
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Timer
import java.util.TimerTask

/**
 * Foreground Service that orchestrates the full monitoring pipeline.
 */
class MonitoringService : Service(), LifecycleOwner {

    inner class LocalBinder : Binder() {
        fun getService(): MonitoringService = this@MonitoringService
    }

    private val binder = LocalBinder()

    companion object {
        const val ACTION_START = "com.vigilia.app.START_MONITORING"
        const val ACTION_STOP = "com.vigilia.app.STOP_MONITORING"
        private const val CHANNEL_ID = "vigilia_monitoring"
        private const val NOTIFICATION_ID = 1

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
    
    // Internal flag to immediately stop processing frames
    @Volatile
    private var isProcessRunning = false

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
            ACTION_STOP -> stopMonitoring()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        if (isProcessRunning) return

        isProcessRunning = true
        startForeground(NOTIFICATION_ID, createNotification("Iniciando monitoramento..."))
        acquireWakeLock()
        
        serviceScope.launch {
            try {
                sessionId = telemetryWriter.startSession()
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                
                cameraManager.startCamera(
                    this@MonitoringService,
                    { metrics ->
                        // Stop processing immediately if flag is false
                        if (!isProcessRunning) return@startCamera
                        
                        val assessment = scorer.processFrame(metrics)
                        handleAssessment(assessment)
                        currentAssessment.value = assessment
                    }
                )
            } catch (e: Exception) {
                Log.e("MonitoringService", "Failed to start monitoring", e)
                stopMonitoring()
            }
        }
    }

    private fun stopMonitoring() {
        if (!isProcessRunning) return
        
        isProcessRunning = false
        currentAssessment.value = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        stopSelf()
    }

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        if (!isProcessRunning) return
        cameraManager.updatePreview(this, surfaceProvider)
    }

    fun detachPreview() {
        cameraManager.updatePreview(this, null)
    }

    private fun handleAssessment(assessment: FatigueAssessment) {
        updateNotification("Monitorando... Estado: ${assessment.fatigueState}")

        val previousAssessment = currentAssessment.value
        if (assessment.fatigueState != previousAssessment?.fatigueState) {
            if (assessment.fatigueState == FatigueState.WARNING || assessment.fatigueState == FatigueState.FATIGUED) {
                triggerAlert()
            } else if (assessment.fatigueState == FatigueState.NORMAL || assessment.fatigueState == FatigueState.NO_FACE) {
                stopAlert()
            }
        }

        val currentTime = System.currentTimeMillis()
        if ((currentTime - lastTelemetryWriteTime) >= telemetryIntervalMs) {
            val sId = sessionId ?: return
            serviceScope.launch {
                telemetryWriter.writeRecord(
                    TelemetryRecord(
                        sessionId = sId,
                        timestamp = System.currentTimeMillis(),
                        score = assessment.score,
                        state = assessment.fatigueState,
                        eyeOpenness = 0f, 
                        blinkRate = assessment.blinkRate,
                        isYawning = assessment.isYawning,
                        isFaceDetected = assessment.isFaceDetected,
                        alertActive = isAlertActive(),
                    )
                )
                lastTelemetryWriteTime = currentTime
            }
        }
    }

    private fun triggerAlert() {
        stopAlert()
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
            alertTimer = Timer().apply {
                schedule(object : TimerTask() { override fun run() { stopAlert() } }, 3000L)
            }
        } catch (e: Exception) { Log.e("MonitoringService", "Alert failed", e) }
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
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { Log.e("MonitoringService", "WakeLock error", e) }
        finally { wakeLock = null }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Monitoramento", NotificationManager.IMPORTANCE_LOW)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vigília ativo")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        if (!isProcessRunning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    override fun onDestroy() {
        isProcessRunning = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        
        runBlocking(Dispatchers.IO) {
            try {
                telemetryWriter.stopSession()
            } catch (e: Exception) {
                Log.e("MonitoringService", "Stop session failed", e)
            }
        }
        
        currentAssessment.value = null
        sessionId = null
        cameraManager.stopCamera()
        stopAlert()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
