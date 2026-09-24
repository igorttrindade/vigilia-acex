package com.vigilia.app.ui.monitoring

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.lighting.LightingMode
import com.vigilia.app.service.MonitoringService
import com.vigilia.app.ui.theme.*

// UI-only zoom on the PreviewView. Passengers behind/beside the driver were
// showing up in the on-screen preview, which reads as "the app is recording me"
// even though nothing about non-driver faces is persisted. This scale hides them
// visually. Does NOT touch ImageAnalysis — MediaPipe still sees the full frame,
// so pickDriverIndex + fatigue detection are unaffected. Tune between 1.3-1.5
// on device if the driver appears cropped or a passenger still leaks.
private const val PRIVACY_ZOOM_FACTOR = 2.016f

/**
 * Real-time monitoring screen.
 * Passive observer that binds to MonitoringService for camera preview and metrics.
 */
@Composable
fun MonitoringScreen(
    viewModel: MonitoringViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val sessionEndState by viewModel.sessionEndState.collectAsState()
    val context = LocalContext.current

    if (sessionEndState !is SessionEndState.Idle) {
        SessionEndFlowDialog(
            state = sessionEndState,
            onContinue = viewModel::continueToRating,
            onSubmitRating = { r, c -> viewModel.submitRating(context, r, c) },
            onSkipRating = viewModel::skipRating,
        )
    }

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
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            boundService?.detachPreview()
            if (bound) context.unbindService(connection)
        }
    }

    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        // 1. Camera Preview (passive) — scaled up so passengers don't appear on-screen.
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewViewRef = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .scale(PRIVACY_ZOOM_FACTOR),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringOverlay(uiState: MonitoringUiState) {
    val assessment = uiState.assessment
    var showInfoSheet by remember { mutableStateOf(false) }

    if (showInfoSheet) {
        ModalBottomSheet(
            onDismissRequest = { showInfoSheet = false },
            containerColor = SurfaceDark,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = "O que é o Score de Fadiga?",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Um número de 0 a 100 que mede sinais de cansaço captados pela câmera frontal do celular.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "O que é medido:",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                InfoBullet("Fechamento dos olhos", "Quanto tempo os olhos ficaram fechados (PERCLOS)")
                InfoBullet("Taxa de piscadas", "Piscadas acima ou abaixo do ritmo saudável")
                InfoBullet("Bocejos", "Boca aberta por mais de 1,5 segundo")
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Faixas de fadiga:",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                ScoreBandRow(color = NormalGreen, band = "0 – 50", label = "Normal")
                ScoreBandRow(color = AccentAmber, band = "50 – 70", label = "Atenção")
                ScoreBandRow(color = AlertRed, band = "70 – 100", label = "Fadigado")
            }
        }
    }

    var dismissedAtMs by rememberSaveable { mutableLongStateOf(0L) }
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(dismissedAtMs) {
        if (dismissedAtMs > 0L) {
            kotlinx.coroutines.delay(60_000L)
            tick = System.currentTimeMillis()
        }
    }

    @Suppress("UNUSED_EXPRESSION")
    tick

    val now = System.currentTimeMillis()
    val isDismissed = dismissedAtMs > 0L && (now - dismissedAtMs) < 60_000L
    val showBanner = uiState.showPositioningWarning && !isDismissed

    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatePill(state = assessment?.fatigueState ?: FatigueState.NO_FACE)
            Column(horizontalAlignment = Alignment.End) {
                ScoreIndicator(score = assessment?.score ?: 0f, state = assessment?.fatigueState ?: FatigueState.NO_FACE)
                TextButton(
                    onClick = { showInfoSheet = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "Nível de fadiga",
                        color = TextSecondary,
                        fontSize = 9.sp,
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "O que é isso?",
                        tint = TextSecondary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }

        if (assessment?.fatigueState == FatigueState.CALIBRATING) {
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { assessment.calibrationProgress },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF60A5FA),
                trackColor = Color(0xFF60A5FA).copy(alpha = 0.20f),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Calibrando para o seu rosto...",
                color = Color(0xFF60A5FA),
                fontSize = 12.sp,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        AnimatedVisibility(
            visible = showBanner,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            PositioningWarningBanner(onDismiss = { dismissedAtMs = System.currentTimeMillis() })
        }

        AnimatedVisibility(
            visible = uiState.lightingMode != LightingMode.NORMAL,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                LightingWarningBanner(mode = uiState.lightingMode)
            }
        }
    }
}

@Composable
fun LightingWarningBanner(mode: LightingMode) {
    val (title, subtitle) = when (mode) {
        LightingMode.DARK -> "Ambiente escuro" to "Adaptação noturna ativa — precisão pode variar."
        LightingMode.LOW_LIGHT -> "Luz reduzida" to "Adaptação noturna ativa."
        LightingMode.NORMAL -> return
    }
    Surface(
        color = Color(0xFF1A1A1A).copy(alpha = 0.90f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AccentAmber),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.VisibilityOff,
                contentDescription = null,
                tint = AccentAmber,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = AccentAmber,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    color = AccentAmber.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
fun PositioningWarningBanner(onDismiss: () -> Unit) {
    Surface(
        color = Color(0xFF1A1A1A).copy(alpha = 0.90f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AccentAmber),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = AccentAmber,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Celular mal posicionado",
                    color = AccentAmber,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Aponte a câmera frontal para o seu rosto",
                    color = AccentAmber.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Fechar",
                    tint = AccentAmber,
                    modifier = Modifier.size(18.dp),
                )
            }
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
        FatigueState.CALIBRATING -> Triple(Color(0xFF60A5FA), "CALIBRANDO", Icons.Default.Tune)
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
        FatigueState.CALIBRATING -> Color(0xFF60A5FA)
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
        FatigueState.CALIBRATING -> Color(0xFF60A5FA)
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

@Composable
private fun SessionEndFlowDialog(
    state: SessionEndState,
    onContinue: () -> Unit,
    onSubmitRating: (Int, String?) -> Unit,
    onSkipRating: () -> Unit,
) {
    val busy = state is SessionEndState.Syncing || state is SessionEndState.Submitting
    Dialog(
        onDismissRequest = { /* driver usa botões, back é bloqueado durante busy */ },
        properties = DialogProperties(
            dismissOnBackPress = !busy,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state) {
                    SessionEndState.Syncing -> SyncingContent()
                    SessionEndState.Success -> ResultContent(
                        icon = Icons.Default.CheckCircle,
                        tint = NormalGreen,
                        title = "Sessão sincronizada!",
                        onContinue = onContinue,
                    )
                    is SessionEndState.Failed -> ResultContent(
                        icon = Icons.Default.Warning,
                        tint = AlertRed,
                        title = "Não foi possível sincronizar",
                        message = state.message,
                        hint = "Você pode sincronizar manualmente pela tela de Histórico.",
                        onContinue = onContinue,
                    )
                    is SessionEndState.Rating -> RatingContent(
                        onSubmit = onSubmitRating,
                        onSkip = onSkipRating,
                    )
                    SessionEndState.Submitting -> {
                        CircularProgressIndicator(color = AccentAmber, strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Salvando avaliação…", color = TextPrimary, fontSize = 15.sp)
                    }
                    SessionEndState.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun SyncingContent() {
    CircularProgressIndicator(color = AccentAmber, strokeWidth = 3.dp)
    Spacer(Modifier.height(16.dp))
    Text(
        text = "Sincronizando dados da sessão…",
        color = TextPrimary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Não feche o app.",
        color = TextSecondary,
        fontSize = 13.sp,
    )
}

@Composable
private fun ResultContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    message: String? = null,
    hint: String? = null,
    onContinue: () -> Unit,
) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(48.dp))
    Spacer(Modifier.height(12.dp))
    Text(
        text = title,
        color = TextPrimary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        textAlign = TextAlign.Center,
    )
    if (message != null) {
        Spacer(Modifier.height(8.dp))
        Text(text = message, color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
    if (hint != null) {
        Spacer(Modifier.height(10.dp))
        Text(text = hint, color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onContinue,
        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continuar", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoBullet(title: String, description: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .background(AccentAmber, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text = title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ScoreBandRow(color: Color, band: String, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = band,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(72.dp),
        )
        Text(text = label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RatingContent(
    onSubmit: (Int, String?) -> Unit,
    onSkip: () -> Unit,
) {
    var stars by remember { mutableIntStateOf(0) }
    var comment by remember { mutableStateOf("") }
    Text(
        text = "Como foi essa sessão?",
        color = TextPrimary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Sua avaliação ajuda a melhorar o app",
        color = TextSecondary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..5) {
            IconButton(onClick = { stars = i }) {
                Icon(
                    imageVector = if (i <= stars) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "$i estrelas",
                    tint = if (i <= stars) AccentAmber else TextSecondary,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = comment,
        onValueChange = { if (it.length <= 255) comment = it },
        placeholder = { Text("Comentário (opcional)", color = TextSecondary) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp),
        supportingText = {
            Text(
                text = "${comment.length}/255",
                color = TextSecondary,
                fontSize = 11.sp,
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = AccentAmber,
            unfocusedBorderColor = TextSecondary.copy(alpha = 0.4f),
            cursorColor = AccentAmber,
        ),
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onSkip,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text("Pular", color = TextPrimary)
        }
        Button(
            onClick = { onSubmit(stars, comment.ifBlank { null }) },
            enabled = stars > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentAmber,
                contentColor = Color.Black,
                disabledContainerColor = AccentAmber.copy(alpha = 0.3f),
                disabledContentColor = Color.Black.copy(alpha = 0.5f),
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text("Enviar", fontWeight = FontWeight.SemiBold)
        }
    }
}
