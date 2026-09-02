package com.vigilia.app.ui.options

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vigilia.app.ui.theme.*

/**
 * Hub de configurações da conta: perfil, termos, política de privacidade,
 * histórico e logout. Substitui a antiga tab de Histórico na bottom bar.
 */
@Composable
fun OptionsScreen(
    onNavigateProfile: () -> Unit,
    onNavigateTerms: () -> Unit,
    onNavigatePrivacy: () -> Unit,
    onNavigateHistory: () -> Unit,
    onLogout: () -> Unit,
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Opções",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Conta e informações",
            color = TextSecondary,
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                OptionRow(
                    icon = Icons.Default.Person,
                    title = "Perfil",
                    subtitle = "Email e senha",
                    onClick = onNavigateProfile,
                )
                RowDivider()
                OptionRow(
                    icon = Icons.Default.Description,
                    title = "Termo de Uso",
                    subtitle = "Versão vigente",
                    onClick = onNavigateTerms,
                )
                RowDivider()
                OptionRow(
                    icon = Icons.Default.PrivacyTip,
                    title = "Política de Privacidade",
                    subtitle = "Como tratamos seus dados",
                    onClick = onNavigatePrivacy,
                )
                RowDivider()
                OptionRow(
                    icon = Icons.Default.History,
                    title = "Histórico",
                    subtitle = "Sessões anteriores",
                    onClick = onNavigateHistory,
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(
            onClick = { showLogoutConfirm = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AccentAmber,
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = AccentAmber.copy(alpha = 0.5f),
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                tint = AccentAmber,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Sair",
                color = AccentAmber,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showLogoutConfirm) {
        LogoutConfirmDialog(
            onConfirm = {
                showLogoutConfirm = false
                onLogout()
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = AccentAmber.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AccentAmber,
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
                text = subtitle,
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextSecondary.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = BackgroundDark,
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun LogoutConfirmDialog(
    onConfirm: () -> Unit,
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
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = AccentAmber,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Sair da conta?",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Você precisará entrar novamente com seu email e senha na próxima vez que abrir o aplicativo.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = TextSecondary.copy(alpha = 0.4f),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(text = "Cancelar", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = "Sair",
                            color = BackgroundDark,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
