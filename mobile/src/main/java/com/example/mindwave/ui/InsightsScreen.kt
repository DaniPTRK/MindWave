package com.example.mindwave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mindwave.data.StressReading
import com.example.mindwave.data.XaiExplanation
import com.example.mindwave.ui.theme.stressColor
import java.text.SimpleDateFormat
import java.util.*

/**
 * Insights tab, explains to the user why the model thinks they're stressed.
 * Shows user-friendly summaries of main contributing factors, recent stress peaks,
 * weekly patterns, and an optional expandable section with technical details for the curious.
 */
@Composable
fun InsightsScreen(
    latestReading: StressReading?,
    xaiExplanations: List<XaiExplanation>,
    allReadings: List<StressReading>,
    onOpenDetail: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Insights", style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary)

        if (latestReading == null || xaiExplanations.isEmpty()) {
            InsightsEmptyState()
            return@Column
        }

        SensorContributionSection(xaiExplanations, latestReading, onOpenDetail)

        val peaks = remember(allReadings) {
            allReadings
                .filter { it.stressProbStress > 0.65f }
                .sortedByDescending { it.stressProbStress }
                .take(3)
        }
        if (peaks.isNotEmpty()) {
            RecentPeaksCard(peaks)
        }

        WeeklyPatternCard(allReadings)

        TechnicalDetailCard(xaiExplanations)
    }
}

// Sensor contribution, shows the factors which had the most impact
@Composable
private fun SensorContributionSection(
    explanations: List<XaiExplanation>,
    reading: StressReading,
    onOpenDetail: () -> Unit,
) {
    val groups = groupXaiExplanations(explanations)
    val accGroup = groups.firstOrNull { it.label == "Movement" }
    val showArtifactWarning = accGroup != null && accGroup.share > 0.35f

    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenDetail() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Main factors", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text("Tap for detail →", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))

            groups.forEach { group ->
                val impactLabel = when {
                    group.share > 0.35f -> "high impact"
                    group.share > 0.2f  -> "medium impact"
                    else                -> "low impact"
                }
                val impactColor = when {
                    group.share > 0.35f -> Color(0xFFE74C3C)
                    group.share > 0.2f  -> Color(0xFFF1C40F)
                    else                -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(group.icon, group.label, Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(group.label, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(group.description, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(impactLabel, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, color = impactColor)
                }
                LinearProgressIndicator(
                    progress = { group.share },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).padding(start = 32.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }

            // Artifact warning
            if (showArtifactWarning) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFFFFF9C4), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(18.dp),
                        tint = Color(0xFFF39C12))
                    Spacer(Modifier.width(8.dp))
                    Text("High movement detected, this may be a motion artifact rather than true stress.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF7B4F00))
                }
            }
        }
    }
}


@Composable
private fun RecentPeaksCard(peaks: List<StressReading>) {
    val fmt = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Recent stress peaks", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            peaks.forEach { reading ->
                val pct = (reading.stressProbStress * 100).toInt()
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp)
                        .background(stressColor(pct), RoundedCornerShape(5.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text(fmt.format(Date(reading.timestamp)),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("$pct%", style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold, color = stressColor(pct))
                }
            }
        }
    }
}

// Weekly pattern, shows average stress and best/worst moments in the past week
@Composable
private fun WeeklyPatternCard(readings: List<StressReading>) {
    val now = System.currentTimeMillis()
    val weekAgo = now - 7L * 24 * 3_600_000
    val weekReadings = readings.filter { it.timestamp >= weekAgo }
    if (weekReadings.isEmpty()) return

    val avg = weekReadings.map { it.stressProbStress }.average().toFloat()
    val best = weekReadings.minByOrNull { it.stressProbStress }
    val worst = weekReadings.maxByOrNull { it.stressProbStress }
    val confirmed = weekReadings.count { it.stressScore == 1 }
    val dayFmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("This week", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            WeekStatRow(Icons.Filled.BarChart, "Average stress",
                "${"%.0f".format(avg * 100)}%", stressColor((avg * 100).toInt()))
            worst?.let {
                WeekStatRow(Icons.AutoMirrored.Filled.TrendingUp, "Highest",
                    "${"%.0f".format(it.stressProbStress * 100)}% · ${dayFmt.format(Date(it.timestamp))}",
                    Color(0xFFE74C3C))
            }
            best?.let {
                WeekStatRow(Icons.AutoMirrored.Filled.TrendingDown, "Most relaxed",
                    "${"%.0f".format(it.stressProbStress * 100)}% · ${dayFmt.format(Date(it.timestamp))}",
                    Color(0xFF27AE60))
            }
            WeekStatRow(Icons.Filled.ThumbUp, "Confirmed events",
                "$confirmed readings", MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun WeekStatRow(icon: androidx.compose.ui.graphics.vector.ImageVector,
                        label: String, value: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun TechnicalDetailCard(explanations: List<XaiExplanation>) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Science, null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Technical feature detail",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Text("Per-feature saliency (23 model inputs)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                val sorted = explanations.sortedBy { it.rank }.take(10)
                val maxImp = sorted.maxOfOrNull { it.importance } ?: 1f
                sorted.forEach { xai ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(xai.featureName,
                            modifier = Modifier.width(130.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(modifier = Modifier
                            .weight(xai.importance / maxImp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)))
                        Spacer(modifier = Modifier.weight((1f - xai.importance / maxImp).coerceAtLeast(0.01f)))
                        Text("${"%.1f".format(xai.importance * 100)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (explanations.size > 10) {
                    Spacer(Modifier.height(4.dp))
                    Text("… and ${explanations.size - 10} more features",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun InsightsEmptyState() {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.BarChart, null, Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            Spacer(Modifier.height(16.dp))
            Text("No insights yet", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text("Insights appear after the first stress reading arrives from your watch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}