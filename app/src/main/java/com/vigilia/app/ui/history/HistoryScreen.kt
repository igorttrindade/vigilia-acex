package com.vigilia.app.ui.history

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.domain.model.SessionSummary
import com.vigilia.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * History screen composable.
 *
 * Calls [HistoryViewModel.loadSessions] every time the screen enters composition so the list
 * reflects sessions completed since the ViewModel was first created (e.g. after returning from
 * a monitoring session).
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.loadSessions()
        }
    }

    val uiState by viewModel.uiState.collectAsState()

    HistoryContent(
        uiState = uiState,
        onExportSession = viewModel::exportSession,
    )
}

@Composable
fun HistoryContent(
    uiState: HistoryUiState,
    onExportSession: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
    ) {
        // Header
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
        ) {
            Text(
                text = "Histórico",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            )
            Text(
                text = "Sessões de monitoramento",
                color = TextSecondary,
                fontSize = 14.sp,
            )
        }

        when {
            uiState.isLoading -> {
                val infiniteTransition = rememberInfiniteTransition(label = "loading")
                val loadingAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "loading_alpha",
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = AccentAmber,
                        modifier = Modifier.alpha(loadingAlpha),
                    )
                }
            }
            uiState.isEmpty -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .background(AccentAmber.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = AccentAmber.copy(alpha = 0.55f),
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Nenhuma sessão registrada",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Inicie um monitoramento para começar",
                        color = TextSecondary,
                        fontSize = 14.sp,
                    )
                }
            }
            else -> {
                StatsCard(
                    sessions = uiState.sessions,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(uiState.sessions) { session ->
                        SessionCard(session = session, onExport = { onExportSession(session.sessionId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(sessions: List<SessionSummary>, modifier: Modifier = Modifier) {
    val totalAlerts = sessions.sumOf { it.totalAlerts }
    val avgScore = if (sessions.isNotEmpty()) sessions.map { it.averageScore }.average().toFloat() else 0f

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatItem(label = "Sessões", value = sessions.size.toString())
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(36.dp)
                    .background(TextSecondary.copy(alpha = 0.2f))
            )
            StatItem(label = "Score Médio", value = "%.0f".format(avgScore))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(36.dp)
                    .background(TextSecondary.copy(alpha = 0.2f))
            )
            StatItem(
                label = "Alertas",
                value = totalAlerts.toString(),
                valueColor = if (totalAlerts > 0) AlertRed else TextPrimary,
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, valueColor: Color = TextPrimary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
fun SessionCard(
    session: SessionSummary,
    onExport: () -> Unit,
) {
    val stateColor = when (session.dominantState) {
        FatigueState.NORMAL -> NormalGreen
        FatigueState.WARNING -> AccentAmber
        FatigueState.FATIGUED -> AlertRed
        FatigueState.NO_FACE, FatigueState.CALIBRATING -> Color(0xFF6B7280)
    }

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Colored left border matching dominant state
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        stateColor,
                        RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    )
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = formatTimestamp(session.startTime),
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = formatDuration(session.durationMs),
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                    IconButton(
                        onClick = onExport,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Exportar",
                            tint = AccentAmber,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Score progress bar
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { (session.averageScore / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = stateColor,
                        trackColor = stateColor.copy(alpha = 0.15f),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "%.0f".format(session.averageScore),
                        color = stateColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateBadge(state = session.dominantState)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${session.totalAlerts} alertas",
                        color = if (session.totalAlerts > 0) AlertRed.copy(alpha = 0.85f) else TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun StateBadge(state: FatigueState) {
    val (color, label) = when (state) {
        FatigueState.NORMAL -> NormalGreen to "NORMAL"
        FatigueState.WARNING -> AccentAmber to "ATENÇÃO"
        FatigueState.FATIGUED -> AlertRed to "FADIGADO"
        FatigueState.NO_FACE, FatigueState.CALIBRATING -> Color.Gray to "SEM ROSTO"
    }

    Surface(
        color = color.copy(alpha = 0.18f),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = label,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    val hours = (millis / (1000 * 60 * 60))

    return when {
        hours > 0 -> "${hours}h ${minutes}min"
        minutes > 0 -> "${minutes}min ${seconds}s"
        else -> "${seconds}s"
    }
}

@Preview
@Composable
fun HistoryScreenPreview() {
    VigiliaTheme {
        HistoryContent(
            uiState = HistoryUiState(
                sessions = listOf(
                    SessionSummary(
                        sessionId = "1",
                        startTime = System.currentTimeMillis(),
                        endTime = System.currentTimeMillis(),
                        durationMs = 3600000,
                        totalAlerts = 2,
                        dominantState = FatigueState.NORMAL,
                        averageScore = 15f,
                        peakScore = 45f,
                    ),
                ),
                isLoading = false,
            ),
            onExportSession = {},
        )
    }
}
