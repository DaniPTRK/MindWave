package com.example.mindwave.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mindwave.R
import com.example.mindwave.data.StressReading
import com.example.mindwave.data.XaiExplanation
import com.example.mindwave.ui.theme.stressColor
import com.example.mindwave.ui.theme.stressLabel

/**
 * Today tab — the emotional center of the app.
 *
 * Shows: current stress gauge, signal status summary, XAI factors,
 * quick action (breathing), feedback, recommendations, journal button.
 */
@Composable
fun TodayScreen(
    latestReading: StressReading?,
    xaiExplanations: List<XaiExplanation>,
    recommendations: List<String>,
    onOpenDetail: () -> Unit = {},
    onStartBreathing: () -> Unit = {},
    onJournalClick: () -> Unit = {},
    onFeedback: (Boolean) -> Unit = {},
    onSensorStatusClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WaveHeader()
        StressGaugeCard(latestReading, onClick = onOpenDetail)

        SensorStatusStrip(latestReading, onSensorStatusClick)

        if (latestReading != null) {
            QuickStatsRow(latestReading)
        }

        if (xaiExplanations.isNotEmpty()) {
            XaiCard(xaiExplanations)
        }
        recommendations.forEach { msg -> StressRecommendationCard(message = msg) }

        if (latestReading != null) {
            QuickActionsRow(onStartBreathing = onStartBreathing)
        }
        FeedbackSection(onFeedback = onFeedback, onJournalClick = onJournalClick)
    }
}

@Composable
private fun WaveHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF006064), Color(0xFF00ACC1), Color(0xFF4DD0E1))
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.mindwave_logo),
                contentDescription = "MindWave logo",
                modifier = Modifier.size(72.dp),
            )
            Text("MindWave", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

private val trackColor = Color(0xFFE0F2F1)

@Composable
private fun StressGaugeCard(reading: StressReading?, onClick: () -> Unit) {
    val stressProb = reading?.stressProbStress ?: 0f
    val percent    = (stressProb * 100).toInt()
    val gaugeColor = stressColor(percent)
    val animated   by animateFloatAsState(
        targetValue  = stressProb.coerceIn(0f, 1f),
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "gauge",
    )

    Card(
        modifier  = Modifier.fillMaxWidth().clickable { onClick() },
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape     = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Current stress level",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.size(190.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke  = 22.dp.toPx()
                    val inset   = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val topLeft = Offset(inset, inset)
                    drawArc(trackColor, 135f, 270f, false, topLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(gaugeColor, 135f, 270f * animated, false, topLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$percent%", fontSize = 46.sp, fontWeight = FontWeight.Bold, color = gaugeColor)
                    Text(stressLabel(percent), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (reading == null) {
                Icon(Icons.Filled.Watch, null, Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text("No sensor data yet", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pair your Galaxy Watch, then start the MindWave service on the watch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text("Tap for full XAI breakdown", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


@Composable
private fun SensorStatusStrip(reading: StressReading?, onClick: () -> Unit) {
    val connected = reading != null
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(
            containerColor = if (connected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (connected) Icons.Filled.Watch else Icons.Filled.WatchOff,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (connected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (connected) "Watch connected · sensors active" else "Watch not connected",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (connected) {
                    Text("HR · EDA · TEMP · ACC",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Open Galaxy Wearable and start the MindWave service on the watch.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


@Composable
private fun QuickStatsRow(reading: StressReading) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickStat(Icons.Filled.Favorite,   "HRV",      "%.0f ms".format(reading.hrvRmssd), Modifier.weight(1f))
        QuickStat(Icons.Filled.WaterDrop,  "Sweat",    "%.1f µS".format(reading.edaScl),  Modifier.weight(1f))
        QuickStat(Icons.Filled.Thermostat, "Temp",     "%.1f°".format(reading.tempMean),   Modifier.weight(1f))
    }
}

@Composable
private fun QuickStat(icon: androidx.compose.ui.graphics.vector.ImageVector,
                      label: String, value: String, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun XaiCard(explanations: List<XaiExplanation>) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Why this score?", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            groupXaiExplanations(explanations).forEach { group ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(group.icon, group.label, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(group.label, modifier = Modifier.width(120.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface)
                    LinearProgressIndicator(
                        progress = { group.share },
                        modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${"%.0f".format(group.share * 100)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun QuickActionsRow(onStartBreathing: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onStartBreathing,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Filled.AirlineStops, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Breathe")
        }
    }
}

@Composable
fun StressRecommendationCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF004D40).copy(alpha = 0.6f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lightbulb, "Tip", Modifier.size(24.dp), tint = Color(0xFF4DD0E1))
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB2DFDB))
        }
    }
}

@Composable
private fun FeedbackSection(onFeedback: (Boolean) -> Unit, onJournalClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Was this accurate?", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                OutlinedButton(onClick = { onFeedback(true) }) {
                    Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Yes, stressed")
                }
                OutlinedButton(onClick = { onFeedback(false) }) {
                    Icon(Icons.Filled.Close, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("False alarm")
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onJournalClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(Icons.Filled.EditNote, null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(6.dp))
                Text("Open Journal", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}