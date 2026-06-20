package com.vigilia.app.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vigilia.app.R
import com.vigilia.app.ui.theme.*

/**
 * Full-screen auth flow handling both login and sign-up.
 * Calls [onAuthSuccess] when the user has been authenticated.
 */
@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthSuccess: () -> Unit,
    onForgotPassword: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    var isSignUpMode by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) onAuthSuccess()
    }

    if (uiState.registrationPendingConfirmation) {
        RegistrationPendingScreen(onBack = {
            viewModel.clearError()
            isSignUpMode = false
        })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {

        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "Logo Vigília",
            modifier = Modifier.size(110.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Full name field — only visible on sign-up
        if (isSignUpMode) {
            OutlinedTextField(
                value = uiState.fullName,
                onValueChange = viewModel::onFullNameChanged,
                label = { Text("Nome completo") },
                singleLine = true,
                isError = uiState.nameError != null,
                supportingText = uiState.nameError?.let { msg ->
                    { Text(text = msg, color = AlertRed, fontSize = 12.sp) }
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = TextSecondary,
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
                colors = authTextFieldColors(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Email field
        OutlinedTextField(
            value = uiState.email,
            onValueChange = viewModel::onEmailChanged,
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

        Spacer(modifier = Modifier.height(12.dp))

        // Password field
        OutlinedTextField(
            value = uiState.password,
            onValueChange = viewModel::onPasswordChanged,
            label = { Text("Senha") },
            singleLine = true,
            isError = uiState.passwordError != null,
            supportingText = uiState.passwordError?.let { msg ->
                { Text(text = msg, color = AlertRed, fontSize = 12.sp) }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = TextSecondary,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = authTextFieldColors(),
            shape = RoundedCornerShape(12.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Error message
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

        // Primary action button
        Button(
            onClick = { if (isSignUpMode) viewModel.signUp() else viewModel.signIn() },
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
                    text = if (isSignUpMode) "Criar conta" else "Entrar",
                    color = BackgroundDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Toggle between login and sign-up
        TextButton(
            onClick = {
                isSignUpMode = !isSignUpMode
                viewModel.clearError()
            },
        ) {
            Text(
                text = if (isSignUpMode) "Já tem conta? Entrar" else "Não tem conta? Criar conta",
                color = TextSecondary,
                fontSize = 14.sp,
            )
        }

        if (!isSignUpMode) {
            TextButton(onClick = onForgotPassword) {
                Text(
                    text = "Esqueceu a senha?",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun RegistrationPendingScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
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
                    text = "Conta criada!",
                    color = NormalGreen,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Verifique seu e-mail e clique no link de confirmação para ativar o acesso.",
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
}

@Composable
internal fun authTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentAmber,
    focusedLabelColor = AccentAmber,
    cursorColor = AccentAmber,
    unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
    unfocusedLabelColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    unfocusedContainerColor = Color(0xFF1A1A1A),
    focusedContainerColor = Color(0xFF1A1A1A),
    errorBorderColor = AlertRed,
    errorLabelColor = AlertRed,
    errorCursorColor = AlertRed,
    errorContainerColor = Color(0xFF1A1A1A),
)
