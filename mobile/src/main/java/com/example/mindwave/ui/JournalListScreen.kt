package com.example.mindwave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.SentimentNeutral
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.SentimentVeryDissatisfied
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mindwave.data.EmotionalJournal
import com.example.mindwave.data.XaiExplanation
import java.text.SimpleDateFormat
import java.util.*

/**
 * Journal list screen, chronological journal entries with stress score + XAI thumbnails.
 */
@Composable
fun JournalListScreen(
    entries: List<EmotionalJournal>,
    xaiByReading: Map<Long, List<XaiExplanation>>,
    stressScoreByReading: Map<Long, Float>,
    onAddEntry: () -> Unit = {},
    onDeleteEntry: (EmotionalJournal) -> Unit = {},
    onEditEntry: (EmotionalJournal) -> Unit = {},
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddEntry,
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, "Add entry",
                    tint = MaterialTheme.colorScheme.onPrimary)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Journal",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(16.dp))

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No journal entries yet.\nTap + to add one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(entries) { entry ->
                        JournalEntryCard(
                            entry = entry,
                            xaiExplanations = entry.readingId?.let { xaiByReading[it] } ?: emptyList(),
                            stressScore = entry.readingId?.let { stressScoreByReading[it] },
                            onDelete = { onDeleteEntry(entry) },
                            onEdit = { onEditEntry(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalEntryCard(
    entry: EmotionalJournal,
    xaiExplanations: List<XaiExplanation>,
    stressScore: Float?,
    onDelete: () -> Unit = {},
    onEdit: () -> Unit = {},
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val moodIcon: ImageVector = when (entry.userMood) {
        1    -> Icons.Filled.SentimentVeryDissatisfied
        2    -> Icons.Filled.SentimentDissatisfied
        3    -> Icons.Filled.SentimentNeutral
        4    -> Icons.Filled.SentimentSatisfied
        else -> Icons.Filled.SentimentVerySatisfied
    }
    val moodLabel = when (entry.userMood) {
        1    -> "Very stressed"
        2    -> "Tense"
        3    -> "Neutral"
        4    -> "Calm"
        else -> "Very happy"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            // Mood + timestamp
            Column(
                modifier = Modifier.width(76.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = moodIcon,
                    contentDescription = moodLabel,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    dateFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit entry",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete entry",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Note
                if (entry.note.isNotBlank()) {
                    Text(
                        entry.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // Tags
                if (entry.tags.isNotBlank()) {
                    Text(
                        entry.tags.split(",").joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // Likelihood at time of entry
                if (stressScore != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Prediction at the time: ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${"%.0f".format(stressScore * 100)}% stress likelihood",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                stressScore > 0.7f -> Color(0xFFE74C3C)
                                stressScore > 0.4f -> Color(0xFFF1C40F)
                                else -> Color(0xFF27AE60)
                            },
                        )
                    }
                }

                // Friendly signal contributors (no raw feature names)
                if (xaiExplanations.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    val groups = groupXaiExplanations(xaiExplanations).take(3)
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier.padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(group.icon, group.label,
                                modifier = Modifier.size(11.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                group.label,
                                modifier = Modifier.width(90.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Box(
                                modifier = Modifier
                                    .width((50 * group.share).dp)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete entry?") },
            text = { Text("This journal entry will be permanently removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

