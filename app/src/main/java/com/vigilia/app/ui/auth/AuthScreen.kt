package com.vigilia.app.ui.auth

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vigilia.app.ui.theme.*

/**
 * Full-screen auth flow handling both login and sign-up.
 * Calls [onAuthSuccess] when the user has been authenticated.
 */
@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthSuccess: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var isSignUpMode by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) onAuthSuccess()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {

        // Hero — pulsing amber shield
        val infiniteTransition = rememberInfiniteTransition(label = "auth_pulse")
        val glowScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glow_scale",
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
            Box(
                modifier = Modifier
                    .size(90.dp)
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
                    .size(64.dp)
                    .background(AccentAmber.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = AccentAmber,
                    modifier = Modifier.size(34.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Vigília",
            color = TextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )

        Text(
            text = if (isSignUpMode) "Criar nova conta" else "Bem-vindo de volta",
            color = TextSecondary,
            fontSize = 15.sp,
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Full name field — only visible on sign-up
        if (isSignUpMode) {
            OutlinedTextField(
                value = uiState.fullName,
                onValueChange = viewModel::onFullNameChanged,
                label = { Text("Nome completo") },
                singleLine = true,
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
    }
}

@Composable
private fun authTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentAmber,
    focusedLabelColor = AccentAmber,
    cursorColor = AccentAmber,
    unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
    unfocusedLabelColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    unfocusedContainerColor = Color(0xFF1A1A1A),
    focusedContainerColor = Color(0xFF1A1A1A),
)
