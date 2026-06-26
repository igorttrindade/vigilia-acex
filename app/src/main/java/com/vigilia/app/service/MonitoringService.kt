package com.vigilia.app.service

import android.Manifest
import android.app.Notification
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.location.*
import com.vigilia.app.camera.CameraManager
import com.vigilia.app.data.telemetry.TelemetryWriter
import com.vigilia.app.domain.model.FatigueAssessment
import com.vigilia.app.domain.model.FatigueMetrics
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.domain.model.TelemetryRecord
import com.vigilia.app.domain.scoring.FatigueScorer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

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
        const val EXTRA_CALIBRATION_ENABLED = "extra_calibration_enabled"
        private const val CHANNEL_ID = "vigilia_monitoring"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_COOLDOWN_MS = 8_000L

        val currentAssessment = MutableStateFlow<FatigueAssessment?>(null)
    }

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var serviceScope: CoroutineScope
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var cameraManager: CameraManager
    private lateinit var scorer: FatigueScorer
    private lateinit var telemetryWriter: TelemetryWriter
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private var alertJob: Job? = null
    @Volatile private var lastAlertTimeMs = 0L
    @Volatile private var lastLatitude: Double? = null
    @Volatile private var lastLongitude: Double? = null
    @Volatile private var lastSpeed: Float? = null
    @Volatile private var lastAccelX: Float? = null
    @Volatile private var lastAccelY: Float? = null
    @Volatile private var lastAccelZ: Float? = null
    @Volatile private var lastGyroX: Float? = null
    @Volatile private var lastGyroY: Float? = null
    @Volatile private var lastGyroZ: Float? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.locations.lastOrNull() ?: return
            lastLatitude = loc.latitude
            lastLongitude = loc.longitude
            lastSpeed = if (loc.hasSpeed()) loc.speed else null
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    lastAccelX = event.values[0]
                    lastAccelY = event.values[1]
                    lastAccelZ = event.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    lastGyroX = event.values[0]
                    lastGyroY = event.values[1]
                    lastGyroZ = event.values[2]
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    @Volatile
    private var sessionId: String? = null
    @Volatile
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
        scorer = FatigueScorer() // replaced in startMonitoring with calibration flag
        telemetryWriter = TelemetryWriter(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val calibrationEnabled = intent.getBooleanExtra(EXTRA_CALIBRATION_ENABLED, true)
                startMonitoring(calibrationEnabled)
            }
            ACTION_STOP -> stopMonitoring()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring(calibrationEnabled: Boolean = true) {
        if (isProcessRunning) return

        scorer = FatigueScorer(calibrationEnabled)
        startForeground(NOTIFICATION_ID, createNotification("Iniciando monitoramento..."))
        startLocationUpdates()
        startSensorUpdates()

        serviceScope.launch {
            try {
                acquireWakeLock()
                sessionId = telemetryWriter.startSession()
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                isProcessRunning = true

                cameraManager.startCamera(
                    this@MonitoringService,
                    { metrics ->
                        // Stop processing immediately if flag is false
                        if (!isProcessRunning) return@startCamera

                        val assessment = scorer.processFrame(metrics)
                        handleAssessment(assessment, metrics)
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
        stopAlert()  // stop ringtone immediately rather than waiting for onDestroy
        currentAssessment.value = null
        stopLocationUpdates()
        stopSensorUpdates()

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

    private fun handleAssessment(assessment: FatigueAssessment, metrics: FatigueMetrics) {
        updateNotification("Monitorando... Estado: ${assessment.fatigueState}")

        val previousState = currentAssessment.value?.fatigueState ?: FatigueState.NORMAL
        val newState = assessment.fatigueState

        when {
            // Transition into a danger state — alert immediately (handles first-frame case too).
            // Update lastAlertTimeMs synchronously so rapid consecutive frames don't queue duplicates.
            (newState == FatigueState.WARNING || newState == FatigueState.FATIGUED) && newState != previousState -> {
                lastAlertTimeMs = System.currentTimeMillis()
                serviceScope.launch { triggerAlert() }
            }
            // Sustained FATIGUED — re-alert periodically after cooldown expires.
            // Cooldown is claimed synchronously to prevent multiple queued launches.
            newState == FatigueState.FATIGUED -> {
                val now = System.currentTimeMillis()
                if (now - lastAlertTimeMs >= ALERT_COOLDOWN_MS) {
                    lastAlertTimeMs = now
                    serviceScope.launch { triggerAlert() }
                }
            }
            // Returning to safe state — stop any active alert
            (newState == FatigueState.NORMAL || newState == FatigueState.NO_FACE) &&
                    (previousState == FatigueState.WARNING || previousState == FatigueState.FATIGUED) -> {
                serviceScope.launch { stopAlert() }
            }
        }

        val currentTime = System.currentTimeMillis()
        if ((currentTime - lastTelemetryWriteTime) >= telemetryIntervalMs) {
            val sId = sessionId ?: return
            lastTelemetryWriteTime = currentTime
            val alertActive = ringtone?.isPlaying == true
            writerScope.launch {
                telemetryWriter.writeRecord(
                    TelemetryRecord(
                        sessionId = sId,
                        timestamp = currentTime,
                        score = assessment.score,
                        state = assessment.fatigueState,
                        eyeOpenness = (metrics.leftEyeOpenProbability + metrics.rightEyeOpenProbability) / 2f,
                        blinkRate = assessment.blinkRate,
                        isYawning = assessment.isYawning,
                        isFaceDetected = assessment.isFaceDetected,
                        alertActive = alertActive,
                        latitude = lastLatitude,
                        longitude = lastLongitude,
                        speed = lastSpeed,
                        accelX = lastAccelX,
                        accelY = lastAccelY,
                        accelZ = lastAccelZ,
                        gyroX = lastGyroX,
                        gyroY = lastGyroY,
                        gyroZ = lastGyroZ,
                    )
                )
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
            alertJob = serviceScope.launch {
                delay(3000L)
                stopAlert()
            }
        } catch (e: Exception) { Log.e("MonitoringService", "Alert failed", e) }
    }

    private fun stopAlert() {
        ringtone?.stop()
        ringtone = null
        alertJob?.cancel()
        alertJob = null
    }

    private fun startSensorUpdates() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopSensorUpdates() {
        sensorManager.unregisterListener(sensorListener)
        lastAccelX = null; lastAccelY = null; lastAccelZ = null
        lastGyroX = null; lastGyroY = null; lastGyroZ = null
        lastSpeed = null
    }

    private fun startLocationUpdates() {
        val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.w("MonitoringService", "Location permission not granted, skipping location updates")
            return
        }

        // Immediately populate from the last cached location so early records aren't empty
        fusedLocationClient.lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    lastLatitude = loc.latitude
                    lastLongitude = loc.longitude
                    lastSpeed = if (loc.hasSpeed()) loc.speed else null
                    Log.d("MonitoringService", "Last known location: lat=$lastLatitude lon=$lastLongitude")
                } else {
                    Log.d("MonitoringService", "No cached location available")
                }
            }
            .addOnFailureListener { e ->
                Log.w("MonitoringService", "Failed to get last location", e)
            }

        // BALANCED uses WiFi/cell (works indoors); HIGH_ACCURACY adds GPS on top of that
        val priority = if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, 5_000L)
            .setMinUpdateIntervalMillis(3_000L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            .addOnFailureListener { e ->
                Log.e("MonitoringService", "requestLocationUpdates failed", e)
            }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        lastLatitude = null
        lastLongitude = null
    }

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
        currentAssessment.value = null
        sessionId = null
        cameraManager.stopCamera()
        stopAlert()
        releaseWakeLock()
        serviceScope.cancel()

        runBlocking {
            // Drain any telemetry writes still in flight before stopping the session
            writerScope.coroutineContext[kotlinx.coroutines.Job]
                ?.children?.toList()?.forEach { runCatching { it.join() } }

            try {
                telemetryWriter.stopSession()
            } catch (e: Exception) {
                Log.e("MonitoringService", "Stop session failed", e)
            }
            writerScope.cancel()
        }
        SyncWorker.enqueue(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
