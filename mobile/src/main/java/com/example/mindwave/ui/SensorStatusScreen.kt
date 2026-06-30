package com.example.mindwave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mindwave.data.StressReading
import java.text.SimpleDateFormat
import java.util.*

/**
 * Secondary screen: Sensor Status / Device Health.
 *
 * Shows which sensors are active, last window timestamp,
 * and what hardware is required for each signal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorStatusScreen(
    latestReading: StressReading? = null,
    onBack: () -> Unit = {},
) {
    val connected = latestReading != null
    val lastUpdate = latestReading?.let {
        SimpleDateFormat("HH:mm:ss · d MMM", Locale.getDefault()).format(Date(it.timestamp))
    } ?: "Never"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sensor Status") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Connection card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (connected)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                ),
            ) {
                Row(modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (connected) Icons.Filled.Watch else Icons.Filled.WatchOff,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = if (connected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            if (connected) "Smartwatch connected" else "Watch not connected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Last window: $lastUpdate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Sensor rows
            Text("Signal sources", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            SensorRow(
                icon = Icons.Filled.Favorite,
                label = "Heart Rate",
                sublabel = "Beats per minute · ~1 Hz",
                status = if (connected) SensorStatus.ACTIVE else SensorStatus.OFFLINE,
                hardware = "Wear OS +3",
            )
            SensorRow(
                icon = Icons.Filled.WaterDrop,
                label = "EDA / Sweat",
                sublabel = "Skin conductance · ~4 Hz",
                status = if (connected) SensorStatus.ACTIVE else SensorStatus.OFFLINE,
                hardware = "Samsung Galaxy Watch +4",
            )
            SensorRow(
                icon = Icons.Filled.Thermostat,
                label = "Skin Temperature",
                sublabel = "Wrist temp · ~0.1 Hz",
                status = if (connected) SensorStatus.ACTIVE else SensorStatus.OFFLINE,
                hardware = "Samsung Galaxy Watch +4",
            )
            SensorRow(
                icon = Icons.Filled.DirectionsRun,
                label = "Accelerometer",
                sublabel = "Tri-axial wrist motion · ~25 Hz",
                status = if (connected) SensorStatus.ACTIVE else SensorStatus.OFFLINE,
                hardware = "Samsung Galaxy Watch +4",
            )

            // Privacy note
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF004D40).copy(alpha = 0.35f)
                ),
            ) {
                Row(modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(20.dp), tint = Color(0xFF4DD0E1))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Sensor data never leaves your phone. " +
                                "Only encrypted model weights are shared for federated learning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB2DFDB),
                    )
                }
            }

            // Pairing instructions if disconnected
            if (!connected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("How to connect", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        listOf(
                            "1. Open the MindWave app on the watch.",
                            "2. The sensor service will start automatically.",
                            "3. Return here, data will appear within 60 seconds.",
                        ).forEach { step ->
                            Text(step, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }

        }
    }
}

private enum class SensorStatus { ACTIVE, DEGRADED, OFFLINE }

@Composable
private fun SensorRow(
    icon: ImageVector,
    label: String,
    sublabel: String,
    status: SensorStatus,
    hardware: String,
) {
    val (dotColor, statusText) = when (status) {
        SensorStatus.ACTIVE   -> Color(0xFF27AE60) to "Active"
        SensorStatus.DEGRADED -> Color(0xFFF1C40F) to "Degraded"
        SensorStatus.OFFLINE  -> Color(0xFFE74C3C) to "Offline"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(sublabel, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Requires: $hardware",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(5.dp))
                Text(statusText, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, color = dotColor)
            }
        }
    }
}

