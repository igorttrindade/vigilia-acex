package com.vigilia.app.ui.monitoring

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.service.MonitoringService
import com.vigilia.app.ui.theme.*

/**
 * Real-time monitoring screen.
 * Passive observer that binds to MonitoringService for camera preview and metrics.
 */
@Composable
fun MonitoringScreen(
    viewModel: MonitoringViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var boundService by remember { mutableStateOf<MonitoringService?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Service connection to attach/detach preview without owning a CameraManager
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as MonitoringService.LocalBinder
                boundService = binder.getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
    }

    // Reactively attach preview when both service and view are ready
    LaunchedEffect(boundService, previewViewRef, uiState.isMonitoringActive) {
        val service = boundService
        val preview = previewViewRef
        if (service != null && preview != null && uiState.isMonitoringActive) {
            service.attachPreview(preview.surfaceProvider)
        }
    }

    // Bind to service lifecycle
    DisposableEffect(Unit) {
        val intent = android.content.Intent(context, MonitoringService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            boundService?.detachPreview()
            context.unbindService(connection)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Camera Preview (passive)
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 2. Vignette overlay — dark at top for readability, transparent mid, dark at bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.65f),
                            Color.Black.copy(alpha = 0.15f),
                            Color.Black.copy(alpha = 0.80f),
                        ),
                    )
                )
        )

        MonitoringOverlay(uiState = uiState)

        // 3. Bottom Dashboard
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            BottomMonitoringCard(
                uiState = uiState,
                onToggleMonitoring = {
                    if (uiState.isMonitoringActive) {
                        viewModel.stopMonitoring(context)
                    } else {
                        viewModel.startMonitoring(context)
                    }
                },
            )
        }
    }
}

@Composable
fun MonitoringOverlay(uiState: MonitoringUiState) {
    val assessment = uiState.assessment
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatePill(state = assessment?.fatigueState ?: FatigueState.NO_FACE)
            ScoreIndicator(score = assessment?.score ?: 0f, state = assessment?.fatigueState ?: FatigueState.NO_FACE)
        }
    }
}

@Composable
fun StatePill(state: FatigueState) {
    val (color, label, icon) = when (state) {
        FatigueState.NORMAL -> Triple(NormalGreen, "NORMAL", Icons.Default.Visibility)
        FatigueState.WARNING -> Triple(AccentAmber, "ATENÇÃO", Icons.Default.Warning)
        FatigueState.FATIGUED -> Triple(AlertRed, "FADIGADO", Icons.Default.Warning)
        FatigueState.NO_FACE -> Triple(Color(0xFF6B7280), "SEM ROSTO", Icons.Default.VisibilityOff)
    }

    val shouldPulse = state == FatigueState.WARNING || state == FatigueState.FATIGUED
    val infiniteTransition = rememberInfiniteTransition(label = "pill_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (shouldPulse) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pill_scale",
    )

    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = Modifier.scale(scale),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
fun ScoreIndicator(score: Float, state: FatigueState) {
    val color = when (state) {
        FatigueState.NORMAL -> NormalGreen
        FatigueState.WARNING -> AccentAmber
        FatigueState.FATIGUED -> AlertRed
        FatigueState.NO_FACE -> Color(0xFF6B7280)
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp)) {
        Canvas(modifier = Modifier.size(80.dp)) {
            val strokeWidth = 5.dp.toPx()
            drawArc(
                color = color.copy(alpha = 0.18f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            if (score > 1f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = (score / 100f) * 360f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score.toInt().toString(),
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp,
            )
            Text(
                text = "score",
                color = TextSecondary,
                fontSize = 9.sp,
                letterSpacing = 0.3.sp,
            )
        }
    }
}

@Composable
fun BottomMonitoringCard(uiState: MonitoringUiState, onToggleMonitoring: () -> Unit) {
    val accentColor = when (uiState.assessment?.fatigueState) {
        FatigueState.NORMAL -> NormalGreen
        FatigueState.WARNING -> AccentAmber
        FatigueState.FATIGUED -> AlertRed
        else -> Color(0xFF374151)
    }

    Surface(
        color = SurfaceDark.copy(alpha = 0.93f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // State-reactive accent line at top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, accentColor, Color.Transparent),
                        )
                    )
            )

            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Time and alerts
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column {
                        Text(
                            text = "SESSÃO",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            text = uiState.elapsedTimeFormatted,
                            color = TextPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "ALERTAS",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            text = uiState.alertCount.toString(),
                            color = if (uiState.alertCount > 0) AlertRed else TextPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IndicatorItem(label = "Olhos", active = uiState.assessment?.isFaceDetected == true)
                    IndicatorItem(label = "Piscar", active = (uiState.assessment?.blinkRate ?: 0f) > 0)
                    IndicatorItem(label = "Bocejo", active = uiState.assessment?.isYawning == true)
                    IndicatorItem(label = "Rosto", active = uiState.assessment?.isFaceDetected == true)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onToggleMonitoring,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isMonitoringActive) AlertRed else AccentAmber,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        imageVector = if (uiState.isMonitoringActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (uiState.isMonitoringActive) TextPrimary else BackgroundDark,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.isMonitoringActive) "Parar Monitoramento" else "Iniciar Monitoramento",
                        color = if (uiState.isMonitoringActive) TextPrimary else BackgroundDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun IndicatorItem(label: String, active: Boolean) {
    Surface(
        color = if (active) NormalGreen.copy(alpha = 0.14f) else Color(0xFF1A1A2E),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (active) NormalGreen else Color(0xFF4B5563),
                        shape = CircleShape,
                    )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                color = if (active) TextPrimary else TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
