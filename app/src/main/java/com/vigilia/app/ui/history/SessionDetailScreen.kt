package com.vigilia.app.ui.history

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigilia.app.data.repository.SessionTelemetryPoint
import com.vigilia.app.domain.model.FatigueState
import com.vigilia.app.domain.model.SessionSummary
import com.vigilia.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detail screen for a completed session.
 * Shows a summary card + score-over-time chart with alert markers.
 */
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: SessionDetailViewModel = viewModel(
        key = "session_detail_$sessionId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SessionDetailViewModel(application, sessionId) as T
            }
        },
    )
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
    ) {
        DetailTopBar(onBack = onBack)
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentAmber)
                }
            }
            uiState.notFound || uiState.summary == null -> {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Sessão não encontrada",
                        color = TextSecondary,
                        fontSize = 15.sp,
                    )
                }
            }
            else -> {
                DetailBody(
                    summary = uiState.summary!!,
                    timeline = uiState.timeline,
                )
            }
        }
    }
}

@Composable
private fun DetailTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Voltar",
                tint = TextPrimary,
            )
        }
        Text(
            text = "Detalhes da sessão",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DetailBody(
    summary: SessionSummary,
    timeline: List<SessionTelemetryPoint>,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SummaryHeaderCard(summary = summary)
        Spacer(modifier = Modifier.height(16.dp))
        MetricsGrid(summary = summary)
        Spacer(modifier = Modifier.height(20.dp))
        SectionTitle(text = "Score ao longo da sessão")
        Spacer(modifier = Modifier.height(10.dp))
        if (timeline.isEmpty()) {
            Surface(
                color = SurfaceDark,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Sem telemetria para este período",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            ScoreTimelineChart(
                summary = summary,
                timeline = timeline,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ChartLegend()
        }
        Spacer(modifier = Modifier.height(20.dp))
        SectionTitle(text = "Linha do tempo de alertas")
        Spacer(modifier = Modifier.height(10.dp))
        AlertsTimeline(summary = summary, timeline = timeline)
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SummaryHeaderCard(summary: SessionSummary) {
    val stateColor = colorForState(summary.dominantState)
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(stateColor.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "%.0f".format(summary.averageScore),
                    color = stateColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatSessionDate(summary.startTime),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${formatClock(summary.startTime)} — ${formatClock(summary.endTime)}  ·  ${formatDurationLong(summary.durationMs)}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                StateBadge(state = summary.dominantState)
            }
        }
    }
}

@Composable
private fun MetricsGrid(summary: SessionSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MetricTile(
            modifier = Modifier.weight(1f),
            label = "Score médio",
            value = "%.0f".format(summary.averageScore),
            valueColor = colorForScore(summary.averageScore),
        )
        MetricTile(
            modifier = Modifier.weight(1f),
            label = "Pico",
            value = "%.0f".format(summary.peakScore),
            valueColor = colorForScore(summary.peakScore),
        )
        MetricTile(
            modifier = Modifier.weight(1f),
            label = "Alertas",
            value = summary.totalAlerts.toString(),
            valueColor = if (summary.totalAlerts > 0) AlertRed else TextPrimary,
            icon = Icons.Default.NotificationsActive,
        )
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color,
    icon: ImageVector? = null,
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = valueColor.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = value,
                color = valueColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Score-over-time chart drawn on Canvas.
 * - Y: 0..100 score, with dashed thresholds at 50 (WARNING) and 70 (FATIGUED)
 * - X: elapsed time from session start to end
 * - Curve: smoothed line + soft area fill using an amber→red gradient
 * - Red dots at points where alertActive is true (approximate alarm moments)
 */
@Composable
private fun ScoreTimelineChart(
    summary: SessionSummary,
    timeline: List<SessionTelemetryPoint>,
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            ) {
                val w = size.width
                val h = size.height
                val leftPad = 28f
                val rightPad = 6f
                val topPad = 6f
                val bottomPad = 18f
                val chartW = w - leftPad - rightPad
                val chartH = h - topPad - bottomPad

                val startMs = summary.startTime
                val endMs = if (summary.endTime > startMs) summary.endTime else startMs + 1
                val range = (endMs - startMs).toFloat().coerceAtLeast(1f)

                fun xOf(ts: Long): Float =
                    leftPad + ((ts - startMs).coerceAtLeast(0L).toFloat() / range) * chartW

                fun yOf(score: Float): Float {
                    val clamped = score.coerceIn(0f, 100f)
                    return topPad + (1f - clamped / 100f) * chartH
                }

                // Grid + threshold lines (dashed)
                val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                val gridColor = Color(0xFF2A2A2A)
                listOf(0f, 25f, 50f, 75f, 100f).forEach { s ->
                    val y = yOf(s)
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPad, y),
                        end = Offset(w - rightPad, y),
                        strokeWidth = 1f,
                    )
                }
                // Threshold at 50 (WARNING) and 70 (FATIGUED)
                drawLine(
                    color = AccentAmber.copy(alpha = 0.6f),
                    start = Offset(leftPad, yOf(50f)),
                    end = Offset(w - rightPad, yOf(50f)),
                    strokeWidth = 1.2f,
                    pathEffect = dashed,
                )
                drawLine(
                    color = AlertRed.copy(alpha = 0.6f),
                    start = Offset(leftPad, yOf(70f)),
                    end = Offset(w - rightPad, yOf(70f)),
                    strokeWidth = 1.2f,
                    pathEffect = dashed,
                )

                // Build a smooth path via cubic Beziers between consecutive points
                if (timeline.size >= 2) {
                    val pts = timeline.map { Offset(xOf(it.timestamp), yOf(it.score)) }
                    val linePath = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) {
                            val p0 = pts[i - 1]
                            val p1 = pts[i]
                            val mid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                            quadraticBezierTo(p0.x, p0.y, mid.x, mid.y)
                        }
                        lineTo(pts.last().x, pts.last().y)
                    }
                    val areaPath = Path().apply {
                        addPath(linePath)
                        lineTo(pts.last().x, topPad + chartH)
                        lineTo(pts.first().x, topPad + chartH)
                        close()
                    }
                    drawPath(
                        path = areaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                AlertRed.copy(alpha = 0.35f),
                                AccentAmber.copy(alpha = 0.18f),
                                NormalGreen.copy(alpha = 0.05f),
                            ),
                            startY = topPad,
                            endY = topPad + chartH,
                        ),
                    )
                    drawPath(
                        path = linePath,
                        color = AccentAmber,
                        style = Stroke(width = 2.4f, cap = StrokeCap.Round),
                    )

                    // Alert dots
                    timeline.forEachIndexed { i, p ->
                        if (p.alertActive) {
                            val c = pts[i]
                            drawCircle(
                                color = AlertRed.copy(alpha = 0.35f),
                                radius = 6f,
                                center = c,
                            )
                            drawCircle(
                                color = AlertRed,
                                radius = 3.2f,
                                center = c,
                            )
                        }
                    }
                }
            }

            // X-axis timestamps
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 28.dp, end = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatClock(summary.startTime),
                    color = TextSecondary,
                    fontSize = 10.sp,
                )
                Text(
                    text = formatClock(summary.endTime),
                    color = TextSecondary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun ChartLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendChip(color = NormalGreen, label = "Normal (0-50)")
        LegendChip(color = AccentAmber, label = "Atenção (50-70)")
        LegendChip(color = AlertRed, label = "Fadigado (70+)")
    }
}

@Composable
private fun LegendChip(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, color = TextSecondary, fontSize = 11.sp)
    }
}

/**
 * Small horizontal ribbon showing when alerts fired during the session.
 * Positions each red mark proportionally to its elapsed time in the session.
 */
@Composable
private fun AlertsTimeline(
    summary: SessionSummary,
    timeline: List<SessionTelemetryPoint>,
) {
    val alertPoints = timeline.filter { it.alertActive }
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (alertPoints.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(NormalGreen, CircleShape),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Nenhum alerta registrado — viagem tranquila",
                        color = TextPrimary,
                        fontSize = 13.sp,
                    )
                }
            } else {
                Text(
                    text = "${summary.totalAlerts} alerta(s) no total",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                ) {
                    val w = size.width
                    val h = size.height
                    val startMs = summary.startTime
                    val range = (summary.endTime - startMs).toFloat().coerceAtLeast(1f)
                    // Track
                    drawLine(
                        color = Color(0xFF2A2A2A),
                        start = Offset(0f, h / 2f),
                        end = Offset(w, h / 2f),
                        strokeWidth = 3f,
                    )
                    alertPoints.forEach { p ->
                        val x = ((p.timestamp - startMs).coerceAtLeast(0L).toFloat() / range) * w
                        drawCircle(
                            color = AlertRed.copy(alpha = 0.3f),
                            radius = 7f,
                            center = Offset(x, h / 2f),
                        )
                        drawCircle(
                            color = AlertRed,
                            radius = 4f,
                            center = Offset(x, h / 2f),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = formatClock(summary.startTime), color = TextSecondary, fontSize = 10.sp)
                    Text(text = formatClock(summary.endTime), color = TextSecondary, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun colorForScore(score: Float): Color = when {
    score >= 70f -> AlertRed
    score >= 50f -> AccentAmber
    else -> NormalGreen
}

private fun colorForState(state: FatigueState): Color = when (state) {
    FatigueState.NORMAL -> NormalGreen
    FatigueState.WARNING -> AccentAmber
    FatigueState.FATIGUED -> AlertRed
    FatigueState.NO_FACE, FatigueState.CALIBRATING -> Color(0xFF6B7280)
}

private fun formatSessionDate(ts: Long): String {
    val sdf = SimpleDateFormat("EEEE, dd 'de' MMMM", Locale("pt", "BR"))
    return sdf.format(Date(ts)).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("pt", "BR")) else it.toString() }
}

private fun formatClock(ts: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}

private fun formatDurationLong(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}min"
        minutes > 0 -> "${minutes}min ${seconds}s"
        else -> "${seconds}s"
    }
}
