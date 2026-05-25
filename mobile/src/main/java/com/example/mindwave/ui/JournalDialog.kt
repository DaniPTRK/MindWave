package com.example.mindwave.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.SentimentNeutral
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.SentimentVeryDissatisfied
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Modal dialog for emotional journaling, fine-tuning with feedback.
 * User selects a mood 1–5, optionally writes a note, then saves to Room.
 */
@Composable
fun JournalDialog(
    onDismiss: () -> Unit,
    onSave: (mood: Int, note: String) -> Unit,
    initialMood: Int = 3,
    initialNote: String = "",
    title: String = "How are you feeling?",
    confirmLabel: String = "Save",
) {
    var mood by remember { mutableIntStateOf(initialMood) }
    var note by remember { mutableStateOf(initialNote) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title,
                     style = MaterialTheme.typography.titleMedium,
                     color = MaterialTheme.colorScheme.primary)

                // Mood slider 1 (calm) – 5 (stressed)
                Icon(
                    imageVector = moodIcon(mood),
                    contentDescription = moodLabel(mood),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = mood.toFloat(),
                    onValueChange = { mood = it.toInt() },
                    valueRange = 1f..5f,
                    steps = 3,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Calm", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Stressed", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(mood, note) }) { Text(confirmLabel) }
                }
            }
        }
    }
}

private fun moodIcon(mood: Int): ImageVector = when (mood) {
    1    -> Icons.Filled.SentimentVerySatisfied
    2    -> Icons.Filled.SentimentSatisfied
    3    -> Icons.Filled.SentimentNeutral
    4    -> Icons.Filled.SentimentDissatisfied
    else -> Icons.Filled.SentimentVeryDissatisfied
}

private fun moodLabel(mood: Int): String = when (mood) {
    1    -> "Very calm"
    2    -> "Calm"
    3    -> "Neutral"
    4    -> "Tense"
    else -> "Very stressed"
}

