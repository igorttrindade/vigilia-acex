package com.vigilia.app.ui.options

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vigilia.app.ui.theme.*

/**
 * Leitor read-only de um asset markdown (Termo de Uso ou Política de
 * Privacidade). Sem botão de aceite e sem scroll-gate — apenas leitura.
 */
@Composable
fun TermsViewerScreen(
    title: String,
    versionLabel: String,
    assetPath: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val body = remember(assetPath) { readAsset(context, assetPath) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
    ) {
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = versionLabel,
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .border(
                    width = 1.dp,
                    color = TextSecondary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = body,
                color = TextPrimary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun readAsset(context: Context, assetPath: String): String {
    return try {
        context.assets.open(assetPath).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.e("TermsViewerScreen", "Falha ao ler asset $assetPath", e)
        "Erro ao carregar o texto. Reinicie o aplicativo."
    }
}
