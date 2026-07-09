package com.vigilia.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
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
import com.vigilia.app.MainActivity
import com.vigilia.app.camera.CameraManager
import com.vigilia.app.data.telemetry.TelemetryWriter
import com.vigilia.app.domain.model.FatigueAssessment
import com.vigilia.app.domain.model.FatigueMetrics
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.domain.model.TelemetryRecord
import com.vigilia.app.domain.scoring.FatigueScorer
import com.vigilia.app.lighting.LightingMode
import com.vigilia.app.lighting.LightingMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        const val EXTRA_LOW_LIGHT_ADAPTATION_ENABLED = "extra_low_light_enabled"
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

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

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
    @Volatile private var lastAmbientLux: Float? = null

    private val lightingMonitor = LightingMonitor()
    val lightingMode: StateFlow<LightingMode> = lightingMonitor.mode

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
                Sensor.TYPE_LIGHT -> {
                    lastAmbientLux = event.values[0]
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
                val lowLightEnabled = intent.getBooleanExtra(EXTRA_LOW_LIGHT_ADAPTATION_ENABLED, true)
                startMonitoring(calibrationEnabled, lowLightEnabled)
            }
            ACTION_STOP -> stopMonitoring()
        }
        return START_NOT_STICKY
    }

    // When false, LightingMonitor still classifies for telemetry (so we can see how bad the
    // scene is in dashboards), but CameraManager.applyLightingMode and CLAHE preprocessing
    // are skipped — behavior is identical to the pre-Fase-1 app.
    @Volatile private var lowLightAdaptationEnabled: Boolean = true

    private fun startMonitoring(calibrationEnabled: Boolean = true, lowLightEnabled: Boolean = true) {
        if (isProcessRunning) return

        lowLightAdaptationEnabled = lowLightEnabled
        scorer = FatigueScorer(
            calibrationEnabled = calibrationEnabled,
            // Even when adaptation is off, the scorer still receives the mode for telemetry.
            // Only the *effect* of the mode (grace, camera tuning, CLAHE) is gated below.
            lightingModeProvider = { lightingMonitor.mode.value },
            ambientLuxProvider = { lastAmbientLux },
        )
        lightingMonitor.reset()
        startForeground(NOTIFICATION_ID, createNotification("Iniciando monitoramento..."))
        startLocationUpdates()
        startSensorUpdates()

        serviceScope.launch {
            var startupCompleted = false
            try {
                acquireWakeLock()
                preloadRingtone()
                sessionId = telemetryWriter.startSession()
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                isProcessRunning = true
                startupCompleted = true

                cameraManager.startCamera(
                    this@MonitoringService,
                    { metrics ->
                        // Stop processing immediately if flag is false
                        if (!isProcessRunning) return@startCamera

                        lightingMonitor.update(lastAmbientLux, metrics.frameLuminance, metrics.timestampMs)
                        val assessment = scorer.processFrame(metrics)
                        handleAssessment(assessment, metrics)
                        currentAssessment.value = assessment
                    }
                )

                // Observe lighting-mode transitions off the frame loop and hot-apply Camera2
                // options (EV/FPS/scene). Runs in serviceScope so it's cancelled on stop.
                // Skipped entirely when the user disabled adaptation via SetupScreen.
                if (lowLightAdaptationEnabled) {
                    launch {
                        lightingMonitor.mode.collect { mode ->
                            cameraManager.applyLightingMode(mode)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MonitoringService", "Failed to start monitoring", e)
                if (startupCompleted) {
                    // isProcessRunning was set, so stopMonitoring() does the full teardown.
                    stopMonitoring()
                } else {
                    // Rollback partial state: stopMonitoring() would early-return because
                    // isProcessRunning is still false, leaving WakeLock/foreground/sensors
                    // dangling. Release explicitly in reverse order of acquisition.
                    rollbackPartialStart()
                }
            }
        }
    }

    /**
     * Undo side effects of a failed [startMonitoring] before [isProcessRunning] flipped true.
     * Mirrors the teardown order of [stopMonitoring] for the resources that were actually
     * touched during startup (foreground, sensors, location acquired synchronously; wakelock
     * and ringtone acquired inside the coroutine's try block).
     */
    private fun rollbackPartialStart() {
        try { stopAlert() } catch (_: Exception) {}
        try { releaseWakeLock() } catch (_: Exception) {}
        try { stopLocationUpdates() } catch (_: Exception) {}
        try { stopSensorUpdates() } catch (_: Exception) {}
        currentAssessment.value = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun stopMonitoring() {
        if (!isProcessRunning) return

        isProcessRunning = false
        stopAlert()  // stop ringtone immediately rather than waiting for onDestroy
        currentAssessment.value = null
        stopLocationUpdates()
        stopSensorUpdates()
        // WakeLock was held throughout the session; release it now so battery doesn't drain
        // while the bound MonitoringScreen keeps this service instance alive between sessions.
        // onDestroy() still calls this too — releaseWakeLock() is idempotent.
        releaseWakeLock()

        // Release camera + MediaPipe now. Otherwise the bound MonitoringScreen keeps this
        // service alive across Stop→Start cycles, so onDestroy never fires and the next
        // startCamera short-circuits — leaving the old FaceAnalyzer feeding the new scorer
        // with no warmup gap, which caused calibration to sample the user mid-tap and
        // inflate the score at the start of the second session.
        cameraManager.stopCamera()

        // Null sessionId synchronously so any handleAssessment already in flight sees it
        // as null on the `val sId = sessionId ?: return` check and doesn't enqueue a new
        // writeRecord for a session that's being torn down.
        sessionId = null

        // Finalize the session summary off the Main thread. TelemetryWriter.writeMutex
        // serializes writeRecord() and stopSession() internally, so any writes still
        // enqueued in writerScope will run cleanly before or after stopSession(); after
        // stopSession() the writer's csvFile is null and further writes are no-ops.
        // If a new session starts before this finalization completes, the guard-rail in
        // TelemetryWriter.startSession() finalizes the orphan before creating the new one.
        writerScope.launch {
            try {
                telemetryWriter.stopSession()
            } catch (e: Exception) {
                Log.e("MonitoringService", "Stop session failed", e)
            }
        }

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
        updateNotification(assessment)

        val previousState = currentAssessment.value?.fatigueState ?: FatigueState.NORMAL
        val newState = assessment.fatigueState

        when {
            // Transition into a danger state — alert immediately (handles first-frame case too).
            // Update lastAlertTimeMs synchronously so rapid consecutive frames don't queue duplicates.
            (newState == FatigueState.WARNING || newState == FatigueState.FATIGUED) && newState != previousState -> {
                lastAlertTimeMs = System.currentTimeMillis()
                triggerAlert()
            }
            // Sustained FATIGUED — re-alert periodically after cooldown expires.
            // Cooldown is claimed synchronously to prevent multiple queued launches.
            newState == FatigueState.FATIGUED -> {
                val now = System.currentTimeMillis()
                if (now - lastAlertTimeMs >= ALERT_COOLDOWN_MS) {
                    lastAlertTimeMs = now
                    triggerAlert()
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
                        perclos = assessment.perclos,
                        perclosContribution = assessment.perclosContribution,
                        blinkContribution = assessment.blinkContribution,
                        yawnContribution = assessment.yawnContribution,
                        ambientLightLux = lastAmbientLux,
                        frameLuminance = metrics.frameLuminance,
                        lightingMode = lightingMonitor.mode.value.name,
                    )
                )
            }
        }
    }

    private fun triggerAlert() {
        val current = ringtone ?: run {
            preloadRingtone()
            ringtone ?: return
        }
        try {
            alertJob?.cancel()
            if (current.isPlaying) current.stop()
            current.play()
            alertJob = serviceScope.launch {
                delay(3000L)
                try { current.stop() } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.e("MonitoringService", "Alert failed", e) }
    }

    private fun stopAlert() {
        try { ringtone?.stop() } catch (_: Exception) {}
        alertJob?.cancel()
        alertJob = null
    }

    private fun preloadRingtone() {
        if (ringtone != null) return
        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtone = RingtoneManager.getRingtone(this, alertUri).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
            }
        } catch (e: Exception) {
            Log.e("MonitoringService", "Ringtone preload failed", e)
        }
    }

    private fun startSensorUpdates() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        // TYPE_LIGHT is optional (nem todo device tem). If absent, LightingMonitor still
        // works from the frame Y-channel alone — lux stays null in telemetry.
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopSensorUpdates() {
        sensorManager.unregisterListener(sensorListener)
        lastAccelX = null; lastAccelY = null; lastAccelZ = null
        lastGyroX = null; lastGyroY = null; lastGyroZ = null
        lastSpeed = null
        lastAmbientLux = null
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
        // 2 h upper bound. This is only a safety net in case both stopMonitoring() and
        // onDestroy() fail to run (e.g. process death). Real sessions release the lock
        // explicitly on Stop; a 10 h ceiling drained battery when that path was missed.
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vigilia:monitoring").apply {
            acquire(2 * 60 * 60 * 1000L)
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
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(content: String): Notification {
        // Tap opens MainActivity; the "Parar" action stops monitoring without needing to
        // reopen the app. Both PendingIntents use FLAG_IMMUTABLE (required from Android S)
        // and FLAG_UPDATE_CURRENT so subsequent notification updates reuse the same slots.
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, MonitoringService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vigília ativo")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Parar",
                stopPending,
            )
            .build()
    }

    private fun formatNotificationContent(assessment: FatigueAssessment): String {
        val stateLabel = when (assessment.fatigueState) {
            FatigueState.NORMAL -> "Normal"
            FatigueState.WARNING -> "Atenção"
            FatigueState.FATIGUED -> "Fadigado"
            FatigueState.NO_FACE -> "Rosto não detectado"
            FatigueState.CALIBRATING -> "Calibrando"
        }
        return if (assessment.fatigueState == FatigueState.CALIBRATING) {
            "$stateLabel · ${(assessment.calibrationProgress * 100).toInt()}%"
        } else {
            "$stateLabel · Score ${assessment.score.toInt()}"
        }
    }

    private fun updateNotification(assessment: FatigueAssessment) {
        if (!isProcessRunning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }
        notificationManager.notify(NOTIFICATION_ID, createNotification(formatNotificationContent(assessment)))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User explicitly swiped the app off the recent-apps screen. That's a stronger
        // "I'm done" signal than backgrounding (home button, switching to another app),
        // so we stop monitoring here — otherwise the driver hears the alarm playing from
        // an app they thought they had closed. Foreground services survive task removal
        // by default; overriding this is the correct place to react to the user gesture.
        //
        // Home button / app switch to Waze/WhatsApp/nav does NOT trigger onTaskRemoved,
        // so those cases keep monitoring alive (still the intended behavior for drivers).
        Log.i("MonitoringService", "Task removed — stopping monitoring")
        if (isProcessRunning) stopMonitoring()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isProcessRunning = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        sessionId = null
        cameraManager.stopCamera()
        stopAlert()
        ringtone = null
        releaseWakeLock()
        serviceScope.cancel()

        // stopMonitoring() already finalized the session summary and joined the writer jobs.
        // If the service is being destroyed without a preceding stopMonitoring (e.g. system
        // kill), TelemetryWriter.startSession's guard-rail will finalize the orphan on the
        // next run. Cancel the writer scope so no leftover work outlives the service.
        writerScope.cancel()

        SyncWorker.enqueue(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
