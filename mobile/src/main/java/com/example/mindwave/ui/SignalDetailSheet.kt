package com.example.mindwave.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mindwave.data.StressReading
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * Signal type that can be charted in [SignalDetailSheet].
 */
enum class SignalType(val label: String, val unit: String) {
    STRESS("Stress level", "%"),
    HRV("HRV (RMSSD)", "ms"),
    TEMPERATURE("Skin temperature", "°C"),
    EDA("Skin conductance", "µS"),
}

/** Time range for the chart. */
enum class TimeRange(val label: String) {
    DAY("D"),
    WEEK("W"),
    MONTH("M"),
}

/**
 * Bottom-sheet style popup that shows a line chart + avg/peak stats
 * for a selected biometric signal over D / W / M range.
 *
 * Usage from HistoryScreen:
 *   var sheetSignal by remember { mutableStateOf<SignalType?>(null) }
 *   sheetSignal?.let { signal ->
 *       SignalDetailSheet(signal = signal, readings = readings, onDismiss = { sheetSignal = null })
 *   }
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalDetailSheet(
    signal: SignalType,
    readings: List<StressReading>,
    onDismiss: () -> Unit,
) {
    var timeRange by remember { mutableStateOf(TimeRange.WEEK) }

    // Filter readings to the selected time range
    val now = System.currentTimeMillis()
    val cutoff = when (timeRange) {
        TimeRange.DAY   -> now - 24L * 3_600_000
        TimeRange.WEEK  -> now - 7L * 24 * 3_600_000
        TimeRange.MONTH -> now - 30L * 24 * 3_600_000
    }
    val filtered = remember(readings, timeRange) {
        readings.filter { it.timestamp >= cutoff }.sortedBy { it.timestamp }
    }

    // Extract the series values
    val values: List<Pair<Long, Float>> = remember(filtered, signal) {
        filtered.map { r ->
            val v = when (signal) {
                SignalType.STRESS      -> r.stressProbStress * 100f
                SignalType.HRV        -> r.hrvRmssd
                SignalType.TEMPERATURE -> r.tempMean
                SignalType.EDA        -> r.edaScl
            }
            r.timestamp to v
        }
    }

    val avg   = if (values.isEmpty()) 0f else values.map { it.second }.average().toFloat()
    val peak  = if (values.isEmpty()) 0f else values.maxOf { it.second }
    val low   = if (values.isEmpty()) 0f else values.minOf { it.second }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {

                // ── Header row: title · time-range selector · close ──────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        signal.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    // D / W / M scroller
                    TimeRange.entries.forEach { range ->
                        val selected = range == timeRange
                        TextButton(
                            onClick = { timeRange = range },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (selected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Text(
                                range.label,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 14.sp,
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Stat chips: avg / peak / low ─────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatChip("Avg", formatValue(avg, signal), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    StatChip("Peak", formatValue(peak, signal), Color(0xFFE74C3C), Modifier.weight(1f))
                    StatChip("Low", formatValue(low, signal), Color(0xFF27AE60), Modifier.weight(1f))
                }

                Spacer(Modifier.height(16.dp))

                // ── Line chart ───────────────────────────────────────────────────────
                if (values.size >= 2) {
                    SignalLineChart(
                        values = values,
                        signal = signal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (values.isEmpty()) "No data for this period" else "Not enough data to chart",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── X-axis label ─────────────────────────────────────────────────────
                if (values.size >= 2) {
                    val fmt = when (timeRange) {
                        TimeRange.DAY   -> SimpleDateFormat("HH:mm", Locale.getDefault())
                        TimeRange.WEEK  -> SimpleDateFormat("EEE", Locale.getDefault())
                        TimeRange.MONTH -> SimpleDateFormat("MMM d", Locale.getDefault())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            fmt.format(Date(values.first().first)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            fmt.format(Date(values.last().first)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SignalLineChart(
    values: List<Pair<Long, Float>>,
    signal: SignalType,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val gridColor = MaterialTheme.colorScheme.surfaceVariant

    val minV = values.minOf { it.second }
    val maxV = values.maxOf { it.second }
    val rangeV = max(maxV - minV, 0.01f)
    val minT = values.first().first.toFloat()
    val maxT = values.last().first.toFloat()
    val rangeT = max(maxT - minT, 1f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padV = 8.dp.toPx()

        fun xOf(ts: Long) = (ts.toFloat() - minT) / rangeT * w
        fun yOf(v: Float) = h - padV - (v - minV) / rangeV * (h - 2 * padV)

        // Horizontal grid lines (3)
        repeat(4) { i ->
            val y = h - padV - i / 3f * (h - 2 * padV)
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
        }

        // Fill under line
        val fillPath = Path().apply {
            moveTo(xOf(values.first().first), h)
            values.forEach { (ts, v) -> lineTo(xOf(ts), yOf(v)) }
            lineTo(xOf(values.last().first), h)
            close()
        }
        drawPath(fillPath, fillColor)

        // Line
        val linePath = Path().apply {
            values.forEachIndexed { i, (ts, v) ->
                if (i == 0) moveTo(xOf(ts), yOf(v)) else lineTo(xOf(ts), yOf(v))
            }
        }
        drawPath(linePath, lineColor, style = Stroke(width = 2.dp.toPx(),
            cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Dots at data points (only if few enough)
        if (values.size <= 30) {
            values.forEach { (ts, v) ->
                drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(xOf(ts), yOf(v)))
            }
        }
    }
}

private fun formatValue(v: Float, signal: SignalType): String = when (signal) {
    SignalType.STRESS      -> "${"%.0f".format(v)}%"
    SignalType.HRV        -> "${"%.0f".format(v)} ms"
    SignalType.TEMPERATURE -> "${"%.1f".format(v)}°"
    SignalType.EDA        -> "${"%.1f".format(v)} µS"
}

