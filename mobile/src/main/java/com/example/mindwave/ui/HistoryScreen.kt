package com.example.mindwave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mindwave.data.StressReading
import java.text.SimpleDateFormat
import java.util.*

/**
 * History screen — 7×24 weekly heatmap + monthly trend + per-signal charts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    readings: List<StressReading>,
    onCellClick: (hour: Int, dayOfWeek: Int) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
) {
    val calendar = remember { Calendar.getInstance() }
    var weekOffset by remember { mutableIntStateOf(0) }
    var sheetSignal by remember { mutableStateOf<SignalType?>(null) }

    // Compute the week start
    val weekStart = remember(weekOffset) {
        Calendar.getInstance().apply {
            add(Calendar.WEEK_OF_YEAR, weekOffset)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
    val weekEnd = remember(weekOffset) {
        (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 7) }
    }

    val weekReadings = readings.filter {
        it.timestamp in weekStart.timeInMillis until weekEnd.timeInMillis
    }
    val heatmapData = remember(weekReadings, weekStart) {
        buildHeatmap(weekReadings, weekStart)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Stress History",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        // Signal selector buttons
        Text(
            "View signal detail",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SignalButton(Icons.Filled.MonitorHeart, "Stress", Modifier.weight(1f)) {
                sheetSignal = SignalType.STRESS
            }
            SignalButton(Icons.Filled.Favorite, "HRV", Modifier.weight(1f)) {
                sheetSignal = SignalType.HRV
            }
            SignalButton(Icons.Filled.Thermostat, "Temp", Modifier.weight(1f)) {
                sheetSignal = SignalType.TEMPERATURE
            }
            SignalButton(Icons.Filled.WaterDrop, "EDA", Modifier.weight(1f)) {
                sheetSignal = SignalType.EDA
            }
        }

        // Week navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { weekOffset-- }) {
                Icon(Icons.Default.ArrowBack, "Previous week",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
            Text(
                "${dateFormat.format(weekStart.time)} – ${dateFormat.format(weekEnd.time)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            IconButton(onClick = { if (weekOffset < 0) weekOffset++ }) {
                Icon(Icons.Default.ArrowForward, "Next week",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        // Heatmap card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(28.dp))
                    days.forEach { day ->
                        Text(
                            day,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                for (hour in 0..23) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${hour}h",
                            modifier = Modifier.width(28.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (day in 0..6) {
                            val value = heatmapData[day][hour]
                            val cellColor = stressColor(value)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(0.5.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(cellColor)
                                    .clickable { onCellClick(hour, day) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LegendDot(Color(0xFF27AE60), "Low")
                    Spacer(Modifier.width(12.dp))
                    LegendDot(Color(0xFFF1C40F), "Medium")
                    Spacer(Modifier.width(12.dp))
                    LegendDot(Color(0xFFE74C3C), "High")
                    Spacer(Modifier.width(12.dp))
                    LegendDot(MaterialTheme.colorScheme.surfaceVariant, "No data")
                }
            }
        }

        // Monthly average summary
        val monthReadings = readings.filter {
            val cal = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            val now = Calendar.getInstance()
            cal.get(Calendar.MONTH) == now.get(Calendar.MONTH) &&
                    cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        }
        if (monthReadings.isNotEmpty()) {
            val avgStress = monthReadings.map { it.stressProbStress }.average()
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Monthly Average",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${"%.0f".format(avgStress * 100)}% average stress",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = stressColor(avgStress.toFloat()),
                    )
                    Text(
                        "${monthReadings.size} readings this month",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // Signal detail popup
    sheetSignal?.let { signal ->
        SignalDetailSheet(
            signal = signal,
            readings = readings,
            onDismiss = { sheetSignal = null },
        )
    }
}

@Composable
private fun SignalButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun buildHeatmap(
    readings: List<StressReading>,
    weekStart: Calendar
): Array<FloatArray> {
    val grid = Array(7) { FloatArray(24) { -1f } }
    val counts = Array(7) { IntArray(24) }
    val sums = Array(7) { FloatArray(24) }

    for (r in readings) {
        val cal = Calendar.getInstance().apply { timeInMillis = r.timestamp }
        val dayOfWeek = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        sums[dayOfWeek][hour] += r.stressProbStress
        counts[dayOfWeek][hour]++
    }
    for (d in 0..6) for (h in 0..23) {
        if (counts[d][h] > 0) grid[d][h] = sums[d][h] / counts[d][h]
    }
    return grid
}

private fun stressColor(value: Float): Color = when {
    value < 0f -> Color(0xFF1A3A52)
    value < 0.4f -> Color(0xFF27AE60)
    value < 0.7f -> Color(0xFFF1C40F)
    else -> Color(0xFFE74C3C)
}

