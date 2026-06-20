package com.vigilia.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vigilia.app.ui.theme.*

@Composable
fun ForgotPasswordScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var email by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (uiState.resetEmailSent) {
            EmailSentConfirmation(
                onBack = {
                    viewModel.clearResetState()
                    onBack()
                },
            )
        } else {
            ForgotPasswordForm(
                uiState = uiState,
                email = email,
                onEmailChange = {
                    email = it
                    viewModel.clearError()
                },
                onSend = { viewModel.sendPasswordReset(email) },
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun EmailSentConfirmation(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NormalGreen.copy(alpha = 0.12f),
                shape = RoundedCornerShape(16.dp),
            )
            .border(
                width = 1.dp,
                color = NormalGreen.copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(NormalGreen.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.MarkEmailRead,
                    contentDescription = null,
                    tint = NormalGreen,
                    modifier = Modifier.size(32.dp),
                )
            }

            Text(
                text = "Verifique seu e-mail",
                color = NormalGreen,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "Se este endereço estiver cadastrado, você receberá um link para redefinir sua senha em breve.",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    TextButton(onClick = onBack) {
        Text(
            text = "Voltar para o login",
            color = TextSecondary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun ForgotPasswordForm(
    uiState: AuthUiState,
    email: String,
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Voltar",
                tint = TextSecondary,
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "Redefinir senha",
        color = TextPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Informe seu e-mail e enviaremos um link para redefinir sua senha.",
        color = TextSecondary,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(32.dp))

    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text("E-mail") },
        singleLine = true,
        isError = uiState.emailError != null,
        supportingText = uiState.emailError?.let { msg ->
            { Text(text = msg, color = AlertRed, fontSize = 12.sp) }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
        colors = authTextFieldColors(),
        shape = RoundedCornerShape(12.dp),
    )

    Spacer(modifier = Modifier.height(8.dp))

    val errorMessage = uiState.errorMessage
    if (errorMessage != null) {
        Text(
            text = errorMessage,
            color = AlertRed,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = onSend,
        enabled = !uiState.isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentAmber,
            disabledContainerColor = AccentAmber.copy(alpha = 0.4f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = BackgroundDark,
                strokeWidth = 2.5.dp,
            )
        } else {
            Text(
                text = "Enviar link",
                color = BackgroundDark,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}
