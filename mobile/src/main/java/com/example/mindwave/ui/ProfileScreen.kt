package com.example.mindwave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mindwave.ui.theme.WaveDeep
import com.example.mindwave.ui.theme.WavePrimary

/**
 * Profile / Settings screen for account, alert threshold, quiet hours,
 * privacy & federated-learning controls, and data management.
 */
@Composable
fun ProfileScreen(
    onLogout: () -> Unit,
    vm: ProfileViewModel = viewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var demoLoaded       by remember { mutableStateOf(false) }
    var showDevSection   by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(WavePrimary, WaveDeep))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(vm.email, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("MindWave account", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SettingsCard(title = "Alert threshold") {
            Text(
                "Notify me when stress exceeds ${"%.0f".format(settings.alertThreshold * 100)}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = settings.alertThreshold,
                onValueChange = { vm.setThreshold(it) },
                valueRange = 0.5f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }

        SettingsCard(title = "Quiet hours") {
            ToggleRow(
                label = "Silence alerts overnight",
                description = "No notifications between %02d:00 and %02d:00"
                    .format(settings.quietStartHour, settings.quietEndHour),
                checked = settings.quietHoursEnabled,
                onCheckedChange = { vm.setQuietHoursEnabled(it) },
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF004D40).copy(alpha = 0.25f)
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Privacy & Federated Learning",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(10.dp))
                PrivacyDataRow(Icons.Filled.FavoriteBorder, "Raw biometrics",     "Never uploaded · stays on this phone")
                PrivacyDataRow(Icons.Filled.Psychology,     "Predictions",        "Local only · never sent to server")
                PrivacyDataRow(Icons.Filled.Book,           "Journal entries",    "Local only · your private notes")
                PrivacyDataRow(Icons.Filled.Hub,            "Federated update",   "Model weights only · encrypted")
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                ToggleRow(
                    label = "Contribute to the shared model",
                    description = "Help improve stress detection for everyone. Only encrypted model weights leave this device.",
                    checked = settings.flEnabled,
                    onCheckedChange = { vm.setFlEnabled(it) },
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    label = "Calendar correlation",
                    description = "Link stress peaks to upcoming calendar events for context-aware tips.",
                    checked = settings.calendarEnabled,
                    onCheckedChange = { vm.setCalendarEnabled(it) },
                )
            }
        }

        SettingsCard(title = "Your data") {
            Text("All biometric data stays on this device. You can erase it at any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
            ) {
                Icon(Icons.Filled.DeleteForever, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Erase all my data")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Code, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("Developer / Demo mode",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showDevSection = !showDevSection }) {
                        Text(if (showDevSection) "Hide" else "Show")
                    }
                }
                if (showDevSection) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Loads 7 days of synthetic readings, XAI and journal entries for demo purposes. This replaces any existing local data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { vm.seedDemoData { demoLoaded = true } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.PlayCircle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (demoLoaded) "✓ Demo data loaded" else "Load demo data")
                    }
                }
            }
        }

        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Filled.Logout, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Log out")
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Erase all data?") },
            text = { Text("This permanently deletes all stress readings, journal entries and settings on this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.deleteLocalData(onLogout)
                }) { Text("Erase", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PrivacyDataRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color(0xFF4DD0E1))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    label: String, description: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
