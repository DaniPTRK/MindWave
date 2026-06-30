package com.example.mindwave.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Developer screen for on-device physical profiling and FL testing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    onBack: () -> Unit = {},
    vm: BenchmarkViewModel = viewModel(),
) {
    val records         by vm.records.collectAsStateWithLifecycle()
    val stats           by vm.stats.collectAsStateWithLifecycle()
    val measuredCount   by vm.measuredCount.collectAsStateWithLifecycle()
    val isBenchRunning  by vm.isBenchmarkRunning.collectAsStateWithLifecycle()
    val isFlRunning     by vm.isFlRunning.collectAsStateWithLifecycle()
    val statusMessage   by vm.statusMessage.collectAsStateWithLifecycle()
    val context         = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Benchmark & Profiling") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, "Refresh")
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A237E).copy(alpha = 0.7f)),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(18.dp), tint = Color(0xFF82B1FF))
                        Spacer(Modifier.width(8.dp))
                        Text("Environment", style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFF82B1FF), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        vm.deviceInfo,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color(0xFFBBDEFB),
                        lineHeight = 18.sp,
                    )
                }
            }

            if (statusMessage.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isBenchRunning || isFlRunning) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(statusMessage, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Inference Latency Benchmark",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Injects ${10 + 100} synthetic windows (10 warm-up + 100 measured) through " +
                        "the full pipeline. Results appear in the table below once complete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Measured windows collected: $measuredCount / 100",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (measuredCount >= 100) Color(0xFF27AE60) else MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.runBenchmark() },
                            enabled = !isBenchRunning,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isBenchRunning) "Running…" else "Run Benchmark")
                        }
                        OutlinedButton(
                            onClick = { vm.clearRecords() },
                            enabled = !isBenchRunning,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                        }
                    }
                }
            }

            // stats table
            if (stats != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Latency Statistics (ms)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                            IconButton(
                                onClick = {
                                    val csv = vm.exportCsv()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("latency_csv", csv))
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Filled.ContentCopy, "Copy CSV",
                                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // Table header
                        StatsTableRow(
                            stage = "Stage", mean = "mean", median = "p50",
                            p95 = "p95", p99 = "p99", max = "max",
                            isHeader = true,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        val s = stats!!
                        StatsTableRow("Total e2e",        s.total.mean,     s.total.median,     s.total.p95,     s.total.p99,     s.total.max)
                        StatsTableRow("Feature extract",  s.feature.mean,   s.feature.median,   s.feature.p95,   s.feature.p99,   s.feature.max)
                        StatsTableRow("Normalization",    s.normalize.mean, s.normalize.median, s.normalize.p95, s.normalize.p99, s.normalize.max)
                        StatsTableRow("TFLite inference", s.inference.mean, s.inference.median, s.inference.p95, s.inference.p99, s.inference.max)
                        StatsTableRow("XAI explain",      s.xai.mean,       s.xai.median,       s.xai.p95,       s.xai.p99,       s.xai.max,
                            note = if (s.xai.count == 0) "(skipped)" else null)
                        StatsTableRow("Room insert",      s.room.mean,      s.room.median,      s.room.p95,      s.room.p99,      s.room.max)
                        StatsTableRow("Alert eval",       s.alert.mean,     s.alert.median,     s.alert.p95,     s.alert.p99,     s.alert.max)
                        Spacer(Modifier.height(6.dp))
                        Text("n=${s.total.count} measured windows",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (records.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.BarChart, null, Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        Text("No data yet. Run the benchmark or process real watch windows.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Federated Learning",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Force-start an FL round immediately. The battery gate and minimum-feedback " +
                        "requirement are bypassed. Synthetic data is used if real feedback is insufficient.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { vm.forceStartFl() },
                        enabled = !isFlRunning,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4527A0)),
                    ) {
                        Icon(Icons.Filled.CloudSync, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isFlRunning) "FL round running…" else "Force FL Round Now")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsTableRow(
    stage: String,
    mean: Any,
    median: Any,
    p95: Any,
    p99: Any,
    max: Any,
    isHeader: Boolean = false,
    note: String? = null,
) {
    val weight = if (isHeader) FontWeight.Bold else FontWeight.Normal
    val color  = if (isHeader) MaterialTheme.colorScheme.onSurface
                 else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stage + (if (note != null) " $note" else ""),
            modifier = Modifier.weight(2.2f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = weight,
            color = color,
        )
        for (v in listOf(mean, median, p95, p99, max)) {
            Text(
                text = v.toString(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = weight,
                color = color,
                textAlign = TextAlign.End,
            )
        }
    }
}