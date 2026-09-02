package com.vigilia.app.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vigilia.app.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onMonitoringStarted: () -> Unit,
    onRequestPermissionsGate: () -> Unit = {},
    pendingPermissionRequest: StateFlow<Boolean> = MutableStateFlow(false),
    onPendingPermissionConsumed: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        viewModel.onPermissionsResult(permissions)
    }

    // Dispara o prompt do SO depois que a TermsAcceptanceScreen(PERMISSION) confirma
    // o aceite e volta pra cá com savedStateHandle["pending_permission_request"] = true.
    val pending by pendingPermissionRequest.collectAsState()
    LaunchedEffect(pending) {
        if (pending) {
            onPendingPermissionConsumed()
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
        }
    }

    SetupContent(
        uiState = uiState,
        onCalibrationToggled = viewModel::onCalibrationToggled,
        onLowLightAdaptationToggled = viewModel::onLowLightAdaptationToggled,
        onRequestPermissions = onRequestPermissionsGate,
    ) {
        viewModel.startMonitoring()
        onMonitoringStarted()
    }
}

private data class PermissionExplanation(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

private val CameraExplanation = PermissionExplanation(
    icon = Icons.Default.CameraAlt,
    title = "Como usamos a câmera",
    body = "A câmera frontal é usada para analisar sinais de fadiga em tempo real: " +
        "abertura dos olhos (PERCLOS), taxa de piscadas, bocejos e posição da cabeça.\n\n" +
        "O processamento acontece 100% no seu celular. Nenhuma imagem, vídeo ou frame " +
        "é gravado, salvo em disco ou enviado para nossos servidores.\n\n" +
        "Apenas as métricas numéricas (score de fadiga, contagem de piscadas, etc.) " +
        "são armazenadas localmente e sincronizadas ao final da sessão.",
)

private val LocationExplanation = PermissionExplanation(
    icon = Icons.Default.LocationOn,
    title = "Como usamos a localização",
    body = "Registramos coordenadas GPS e velocidade a cada 2 segundos junto com as " +
        "métricas de fadiga, para dar contexto à sessão — por exemplo, saber em qual " +
        "trecho da viagem um alerta aconteceu.\n\n" +
        "Nada é enviado em tempo real. Os dados só sobem para o servidor quando a " +
        "sessão termina e a sincronização é executada.\n\n" +
        "Essa permissão é opcional. Se negar, o monitoramento de fadiga continua " +
        "funcionando normalmente — só não haverá contexto de localização nos relatórios.",
)

@Composable
fun SetupContent(
    uiState: SetupUiState,
    onCalibrationToggled: (Boolean) -> Unit,
    onLowLightAdaptationToggled: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onStartMonitoring: () -> Unit,
) {
    var explanationTarget by remember { mutableStateOf<PermissionExplanation?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(56.dp))

        // Hero — pulsing amber glow behind shield icon
        val infiniteTransition = rememberInfiniteTransition(label = "hero_pulse")
        val glowScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glow_scale",
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .scale(glowScale)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(AccentAmber.copy(alpha = 0.22f), Color.Transparent),
                        ),
                        CircleShape,
                    ),
            )
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(AccentAmber.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = AccentAmber,
                    modifier = Modifier.size(38.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Vigília",
            color = TextPrimary,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )

        Text(
            text = "Monitoramento de fadiga",
            color = TextSecondary,
            fontSize = 15.sp,
        )

        Spacer(modifier = Modifier.height(44.dp))

        // Permissions section
        Text(
            text = "PERMISSÕES",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
        )

        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                PermissionRow(
                    icon = Icons.Default.CameraAlt,
                    title = "Câmera",
                    isGranted = uiState.isCameraPermissionGranted,
                    onInfoClick = { explanationTarget = CameraExplanation },
                )
                HorizontalDivider(
                    color = BackgroundDark,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                PermissionRow(
                    icon = Icons.Default.LocationOn,
                    title = "Localização",
                    subtitle = "Para telemetria GPS e velocidade",
                    isGranted = uiState.isLocationPermissionGranted,
                    required = false,
                    onInfoClick = { explanationTarget = LocationExplanation },
                )
            }
        }

        if (!uiState.isCameraPermissionGranted || !uiState.isLocationPermissionGranted) {
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentAmber.copy(alpha = 0.15f),
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = AccentAmber,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Conceder Permissões",
                    color = AccentAmber,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Settings section
        Text(
            text = "CONFIGURAÇÕES",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
        )

        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                ToggleRow(
                    title = "Calibração",
                    subtitle = "Adapta os limites ao seu rosto (5-10s)",
                    checked = uiState.isCalibrationEnabled,
                    onCheckedChange = onCalibrationToggled,
                )
                ToggleRow(
                    title = "Adaptação a pouca luz",
                    subtitle = "Ajusta exposição e realça a imagem em túneis / noite",
                    checked = uiState.isLowLightAdaptationEnabled,
                    onCheckedChange = onLowLightAdaptationToggled,
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        PositioningWarningCard()

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onStartMonitoring,
            enabled = uiState.canStartMonitoring,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentAmber,
                disabledContainerColor = Color(0xFF1F2937),
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (uiState.canStartMonitoring) BackgroundDark else TextSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Iniciar Monitoramento",
                color = if (uiState.canStartMonitoring) BackgroundDark else TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    explanationTarget?.let { target ->
        PermissionExplanationDialog(
            explanation = target,
            onDismiss = { explanationTarget = null },
        )
    }
}

@Composable
fun PermissionRow(
    icon: ImageVector,
    title: String,
    isGranted: Boolean,
    subtitle: String? = null,
    required: Boolean = true,
    onInfoClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (isGranted) NormalGreen.copy(alpha = 0.15f) else TextSecondary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) NormalGreen else TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    isGranted -> "Permissão concedida"
                    subtitle != null -> subtitle
                    required -> "Permissão necessária"
                    else -> "Opcional"
                },
                color = if (isGranted) NormalGreen else TextSecondary,
                fontSize = 12.sp,
            )
        }
        if (onInfoClick != null) {
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Como usamos $title",
                    tint = TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isGranted) NormalGreen else TextSecondary.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PermissionExplanationDialog(
    explanation: PermissionExplanation,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            AccentAmber.copy(alpha = 0.15f),
                            RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = explanation.icon,
                        contentDescription = null,
                        tint = AccentAmber,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = explanation.title,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = explanation.body,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "Entendi",
                        color = BackgroundDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BackgroundDark,
                checkedTrackColor = AccentAmber,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = TextSecondary.copy(alpha = 0.2f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun PositioningWarningCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFFF59E0B).copy(alpha = 0.10f),
                shape = RoundedCornerShape(14.dp),
            )
            .border(
                width = 1.dp,
                color = Color(0xFFF59E0B).copy(alpha = 0.30f),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = AccentAmber,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "Posicionamento do celular",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "O celular deve estar voltado para o seu rosto, não para os passageiros. Fixe-o no painel ou para-brisa apontando para o motorista.",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Preview
@Composable
fun SetupScreenPreview() {
    VigiliaTheme {
        SetupContent(
            uiState = SetupUiState(
                isCameraPermissionGranted = true,
            ),
            onCalibrationToggled = {},
            onLowLightAdaptationToggled = {},
            onRequestPermissions = {},
        ) {
            // onStartMonitoring
        }
    }
}
