package com.vigilia.app.ui.options

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vigilia.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var currentVisible by remember { mutableStateOf(false) }
    var newVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.passwordUpdated) {
        if (uiState.passwordUpdated) {
            currentPassword = ""
            newPassword = ""
            confirmPassword = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .verticalScroll(rememberScrollState()),
    ) {
        ProfileTopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            SectionLabel("CONTA")
            Surface(
                color = SurfaceDark,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    ReadOnlyInfoRow(label = "Email", value = uiState.email.ifBlank { "—" })
                    HorizontalDivider(
                        color = BackgroundDark,
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    ReadOnlyInfoRow(label = "Nome", value = uiState.fullName.ifBlank { "—" })
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            SectionLabel("TROCAR SENHA")
            Surface(
                color = SurfaceDark,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PasswordField(
                        value = currentPassword,
                        onValueChange = {
                            currentPassword = it
                            if (uiState.currentPasswordError != null) viewModel.clearErrors()
                        },
                        label = "Senha atual",
                        visible = currentVisible,
                        onToggleVisible = { currentVisible = !currentVisible },
                        error = uiState.currentPasswordError,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    PasswordField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            if (uiState.newPasswordError != null) viewModel.clearErrors()
                        },
                        label = "Nova senha",
                        visible = newVisible,
                        onToggleVisible = { newVisible = !newVisible },
                        error = uiState.newPasswordError,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    PasswordField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            if (uiState.newPasswordError != null) viewModel.clearErrors()
                        },
                        label = "Confirmar nova senha",
                        visible = confirmVisible,
                        onToggleVisible = { confirmVisible = !confirmVisible },
                        error = null,
                    )

                    if (uiState.passwordUpdated) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = NormalGreen,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Senha atualizada com sucesso",
                                color = NormalGreen,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    val canSubmit = currentPassword.isNotBlank() &&
                        newPassword.length >= 8 &&
                        newPassword == confirmPassword &&
                        !uiState.isLoading

                    Button(
                        onClick = {
                            viewModel.consumePasswordUpdated()
                            viewModel.changePassword(currentPassword, newPassword, confirmPassword)
                        },
                        enabled = canSubmit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentAmber,
                            disabledContainerColor = Color(0xFF1F2937),
                        ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                color = BackgroundDark,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Text(
                                text = "Atualizar senha",
                                color = if (canSubmit) BackgroundDark else TextSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ProfileTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Voltar",
                tint = TextPrimary,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Perfil",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 10.dp),
    )
}

@Composable
private fun ReadOnlyInfoRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = label, color = TextSecondary, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    error: String?,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Ocultar senha" else "Mostrar senha",
                    tint = TextSecondary,
                )
            }
        },
        isError = error != null,
        supportingText = error?.let { { Text(it, color = AlertRed, fontSize = 12.sp) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentAmber,
            unfocusedBorderColor = TextSecondary.copy(alpha = 0.4f),
            focusedLabelColor = AccentAmber,
            unfocusedLabelColor = TextSecondary,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = AccentAmber,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
