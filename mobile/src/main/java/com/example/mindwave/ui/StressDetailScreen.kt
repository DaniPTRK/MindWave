package com.example.mindwave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirlineStops
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.SentimentNeutral
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.SentimentVeryDissatisfied
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mindwave.data.StressReading
import com.example.mindwave.data.XaiExplanation

/**
 * Full-screen XAI detail screen, shown when a stress threshold breach occurs.
 *
 * Shows: stress score, sensor contribution bars, context annotation,
 * action buttons, and journal quick-prompt.
 */
@Composable
fun StressDetailScreen(
    reading: StressReading?,
    xaiExplanations: List<XaiExplanation>,
    contextAnnotation: String? = null,
    onBreathingExercise: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onJournalEntry: (Int, String) -> Unit = { _, _ -> },
) {
    if (reading == null) {
        StressEmptyState()
        return
    }

    val stressProb = reading.stressProbStress
    val stressLabel = when {
        stressProb > 0.85f -> "Critical"
        stressProb > 0.7f -> "High"
        stressProb > 0.5f -> "Elevated"
        else -> "Relaxed"
    }
    val stressColor = when {
        stressProb > 0.85f -> Color(0xFFE74C3C)
        stressProb > 0.7f -> Color(0xFFE74C3C).copy(alpha = 0.8f)
        stressProb > 0.5f -> Color(0xFFF1C40F)
        else -> Color(0xFF27AE60)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Alert header
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = stressColor.copy(alpha = 0.15f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Warning",
                        modifier = Modifier.size(28.dp),
                        tint = stressColor,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Stress spike detected",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = stressColor)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${(stressProb * 100).toInt()}%",
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = stressColor,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(stressLabel,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        // Sensor contribution bars
        if (xaiExplanations.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("What triggered this?",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))

                    val groups = groupXaiExplanations(xaiExplanations)
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = group.icon,
                                contentDescription = group.label,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        group.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        "${"%.0f".format(group.share * 100)}%",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { group.share },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                                Text(
                                    group.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Context annotation
        if (!contextAnnotation.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF004D40).copy(alpha = 0.4f)
                ),
            ) {
                Row(modifier = Modifier.padding(16.dp)) {
                    Icon(
                        imageVector = Icons.Filled.CalendarToday,
                        contentDescription = "Calendar",
                        modifier = Modifier.size(22.dp),
                        tint = Color(0xFF4DD0E1),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        contextAnnotation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB2DFDB),
                    )
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onBreathingExercise,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.AirlineStops,
                    contentDescription = "Breathing",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(6.dp))
                Text("Breathing exercise")
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("I'm fine, dismiss")
            }
        }

        // Quick journal prompt
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("How are you feeling?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple(Icons.Filled.SentimentVeryDissatisfied, 1, "Very stressed"),
                            Triple(Icons.Filled.SentimentDissatisfied,     2, "Tense"),
                            Triple(Icons.Filled.SentimentNeutral,          3, "Neutral"),
                            Triple(Icons.Filled.SentimentSatisfied,        4, "Calm"),
                            Triple(Icons.Filled.SentimentVerySatisfied,    5, "Very calm"),
                        ).forEach { (icon, mood, label) ->
                            IconButton(onClick = { onJournalEntry(mood, "Quick: $label") }) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Friendly placeholder shown in the Stress tab when no reading is available yet
 */
@Composable
private fun StressEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Watch,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "No stress data yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect your smartwatch to start streaming heart-rate, EDA and " +
                "temperature data. Your stress insights and explanations will " +
                "appear here once readings come in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

