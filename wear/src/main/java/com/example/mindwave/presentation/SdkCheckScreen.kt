package com.example.mindwave.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.mindwave.data.SdkDiagnostics
import com.example.mindwave.data.SdkDiagnostics.CheckResult.Status
import com.example.mindwave.presentation.theme.MindWaveTheme
import kotlinx.coroutines.launch

@Composable
fun SdkCheckScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val results by SdkDiagnostics.results.collectAsStateWithLifecycle()
    val running by SdkDiagnostics.isRunning.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Auto-scroll as results come in
    LaunchedEffect(results.size) {
        if (results.isNotEmpty()) listState.animateScrollToItem(results.lastIndex)
    }

    MindWaveTheme {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.MonitorHeart, contentDescription = null,
                            tint = Color(0xFFAED6F1), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("SDK Check", color = Color(0xFFAED6F1),
                            fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { scope.launch { SdkDiagnostics.run(context) } },
                        enabled = !running,
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.primaryButtonColors(),
                    ) {
                        Text(
                            if (running) "Running…" else if (results.isEmpty()) "Run checks" else "Re-run",
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            if (results.isEmpty() && !running) {
                item {
                    Text(
                        "Tap Run checks to diagnose\nSDK access step by step.",
                        color = Color(0xFF78909C),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }

            items(results) { result ->
                CheckRow(result)
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun CheckRow(result: SdkDiagnostics.CheckResult) {
    val (statusIcon, labelColor, detailColor) = when (result.status) {
        Status.PASS    -> Triple(Icons.Filled.CheckCircle, Color(0xFF81C784), Color(0xFF66BB6A))
        Status.FAIL    -> Triple(Icons.Filled.Cancel,      Color(0xFFEF9A9A), Color(0xFFE57373))
        Status.WARN    -> Triple(Icons.Filled.Warning,     Color(0xFFFFCC80), Color(0xFFFFB74D))
        Status.PENDING -> Triple(Icons.Filled.HourglassBottom, Color(0xFF90CAF9), Color(0xFF90CAF9))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = when (result.status) {
                    Status.PASS    -> Color(0x1181C784)
                    Status.FAIL    -> Color(0x11E57373)
                    Status.WARN    -> Color(0x11FFB74D)
                    Status.PENDING -> Color(0x1190CAF9)
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(statusIcon, contentDescription = null,
                tint = labelColor, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(3.dp))
            Text(result.name, color = labelColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            result.detail, color = detailColor, fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace, maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}




