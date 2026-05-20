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
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
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
import com.vigilia.app.ui.theme.*

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onMonitoringStarted: () -> Unit,
    onLogout: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        viewModel.onPermissionsResult(permissions)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SetupContent(
            uiState = uiState,
            onCalibrationToggled = viewModel::onCalibrationToggled,
            onVideoToggled = viewModel::onVideoToggled,
            onRequestPermissions = {
                permissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.CAMERA,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
        ) {
            viewModel.startMonitoring()
            onMonitoringStarted()
        }

        IconButton(
            onClick = onLogout,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Logout,
                contentDescription = "Sair",
                tint = com.vigilia.app.ui.theme.TextSecondary,
            )
        }
    }
}

@Composable
fun SetupContent(
    uiState: SetupUiState,
    onCalibrationToggled: (Boolean) -> Unit,
    onVideoToggled: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onStartMonitoring: () -> Unit,
) {
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
                )
                HorizontalDivider(
                    color = BackgroundDark,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                PermissionRow(
                    icon = Icons.Default.LocationOn,
                    title = "Localização",
                    isGranted = uiState.isLocationPermissionGranted,
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
                HorizontalDivider(
                    color = BackgroundDark,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                ToggleRow(
                    title = "Gravar sessão em vídeo",
                    subtitle = "Desativado por padrão",
                    checked = uiState.isVideoEnabled,
                    onCheckedChange = onVideoToggled,
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
}

@Composable
fun PermissionRow(
    icon: ImageVector,
    title: String,
    isGranted: Boolean,
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
                text = if (isGranted) "Permissão concedida" else "Permissão necessária",
                color = if (isGranted) NormalGreen else TextSecondary,
                fontSize = 12.sp,
            )
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
                isLocationPermissionGranted = false,
            ),
            onCalibrationToggled = {},
            onVideoToggled = {},
            onRequestPermissions = {},
        ) {
            // onStartMonitoring
        }
    }
}
