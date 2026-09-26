package com.vigilia.app.ui.terms

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vigilia.app.terms.TermsConfig
import com.vigilia.app.ui.theme.*
import java.time.Instant

/** Contexto de onde a tela foi aberta — só afeta a copy do topo e o cancelamento. */
enum class TermsMode { SIGNUP, LOGIN, PERMISSION }

private enum class TermsStep { TOS, PRIVACY }

/**
 * Fluxo de aceite dos dois termos legais. Cada termo é apresentado em uma
 * etapa separada: primeiro o Termo de Uso, depois a Política de Privacidade.
 * Cada etapa exige que o usuário role até o fim antes de habilitar o aceite;
 * ao aceitar o segundo termo, [onAccepted] é chamado com os dois [Instant].
 */
@Composable
fun TermsAcceptanceScreen(
    mode: TermsMode,
    onAccepted: (tosAt: Instant, privacyAt: Instant) -> Unit,
    onCancel: () -> Unit = {},
) {
    val context = LocalContext.current
    val tosText = remember { readAsset(context, TermsConfig.TOS_ASSET) }
    val privacyText = remember { readAsset(context, TermsConfig.PRIVACY_ASSET) }
    var tosAcceptedAt by remember { mutableStateOf<Instant?>(null) }
    var step by remember { mutableStateOf(TermsStep.TOS) }

    val header = when (mode) {
        TermsMode.SIGNUP -> "Antes de criar sua conta"
        TermsMode.LOGIN -> "Atualizamos nossos termos"
        TermsMode.PERMISSION -> "Confirme o aceite dos termos"
    }
    val subtitle = when (step) {
        TermsStep.TOS -> "Leia o Termo de Uso abaixo. Role até o final para poder aceitar."
        TermsStep.PRIVACY -> "Agora leia a Política de Privacidade Geral. Role até o final para poder aceitar."
    }
    val cancelLabel = when (mode) {
        TermsMode.SIGNUP -> "Voltar"
        TermsMode.LOGIN -> "Sair"
        TermsMode.PERMISSION -> "Cancelar"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = AccentAmber,
            modifier = Modifier.size(36.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = header,
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = subtitle,
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )

        Spacer(modifier = Modifier.height(12.dp))

        StepIndicator(currentStep = step)

        Spacer(modifier = Modifier.height(16.dp))

        // Reset do estado de rolagem/aceite ao trocar de passo — cada etapa
        // renderiza seu próprio TermsCard com scrollState novo.
        when (step) {
            TermsStep.TOS -> TermsCard(
                title = "Termo de Uso",
                versionLabel = "v${TermsConfig.formatVersionForDisplay(TermsConfig.TOS_CURRENT_VERSION)}",
                body = tosText,
                acceptButtonLabel = "Li e aceito — continuar",
                modifier = Modifier.weight(1f),
                onAccept = {
                    tosAcceptedAt = Instant.now()
                    step = TermsStep.PRIVACY
                },
            )
            TermsStep.PRIVACY -> TermsCard(
                title = "Política de Privacidade Geral",
                versionLabel = "v${TermsConfig.formatVersionForDisplay(TermsConfig.PRIVACY_CURRENT_VERSION)}",
                body = privacyText,
                acceptButtonLabel = "Li e aceito",
                modifier = Modifier.weight(1f),
                onAccept = {
                    val tos = tosAcceptedAt ?: return@TermsCard
                    onAccepted(tos, Instant.now())
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = {
                when (step) {
                    TermsStep.TOS -> onCancel()
                    TermsStep.PRIVACY -> {
                        tosAcceptedAt = null
                        step = TermsStep.TOS
                    }
                }
            },
        ) {
            Text(
                text = when (step) {
                    TermsStep.TOS -> cancelLabel
                    TermsStep.PRIVACY -> "Voltar ao Termo de Uso"
                },
                color = TextSecondary,
                fontSize = 13.sp,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun StepIndicator(currentStep: TermsStep) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepDot(active = true, completed = currentStep == TermsStep.PRIVACY)
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(2.dp)
                .background(
                    if (currentStep == TermsStep.PRIVACY) AccentAmber
                    else TextSecondary.copy(alpha = 0.3f)
                ),
        )
        StepDot(active = currentStep == TermsStep.PRIVACY, completed = false)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when (currentStep) {
                TermsStep.TOS -> "Passo 1 de 2"
                TermsStep.PRIVACY -> "Passo 2 de 2"
            },
            color = TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun StepDot(active: Boolean, completed: Boolean) {
    val color = when {
        completed -> NormalGreen
        active -> AccentAmber
        else -> TextSecondary.copy(alpha = 0.3f)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, shape = RoundedCornerShape(50)),
    )
}

@Composable
private fun TermsCard(
    title: String,
    versionLabel: String,
    body: String,
    acceptButtonLabel: String,
    modifier: Modifier = Modifier,
    onAccept: () -> Unit,
) {
    val scrollState = rememberScrollState()
    // scrolledToEnd = true quando o usuário rolou até o fim OU o conteúdo é
    // curto o suficiente pra caber inteiro (maxValue == 0).
    // Tolerance de 150px: o scroll physics do Android para ligeiramente antes
    // de maxValue em textos longos (política de privacidade ~177 linhas),
    // deixando o botão eternamente desabilitado mesmo após o usuário rolar até
    // onde visualmente parece ser o fim.
    val scrolledToEnd by remember {
        derivedStateOf {
            scrollState.maxValue == 0 || scrollState.value >= scrollState.maxValue - 150
        }
    }

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = versionLabel,
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = TextSecondary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(12.dp)
                    .verticalScroll(scrollState),
            ) {
                MarkdownText(
                    text = body,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!scrolledToEnd) {
                Text(
                    text = "Role até o final do texto para habilitar o aceite.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = onAccept,
                enabled = scrolledToEnd,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentAmber,
                    disabledContainerColor = Color(0xFF1F2937),
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = acceptButtonLabel,
                    color = if (scrolledToEnd) BackgroundDark else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

private fun readAsset(context: Context, assetPath: String): String {
    return try {
        context.assets.open(assetPath).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.e("TermsAcceptanceScreen", "Falha ao ler asset $assetPath", e)
        "Erro ao carregar o texto. Reinicie o aplicativo."
    }
}
