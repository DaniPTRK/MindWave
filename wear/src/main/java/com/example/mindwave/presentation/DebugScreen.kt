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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.mindwave.data.SensorDebugState
import com.example.mindwave.presentation.theme.MindWaveTheme
import kotlin.math.roundToInt

@Composable
fun DebugScreen() {
    val hr        by SensorDebugState.hrBpm.collectAsStateWithLifecycle()
    val temp      by SensorDebugState.tempC.collectAsStateWithLifecycle()
    val eda       by SensorDebugState.edaMicroS.collectAsStateWithLifecycle()
    val acc       by SensorDebugState.accMag.collectAsStateWithLifecycle()
    val hrBuf     by SensorDebugState.hrBufferSize.collectAsStateWithLifecycle()
    val edaBuf    by SensorDebugState.edaBufSize.collectAsStateWithLifecycle()
    val tempBuf   by SensorDebugState.tempBufSize.collectAsStateWithLifecycle()
    val accBuf    by SensorDebugState.accBufSize.collectAsStateWithLifecycle()
    val status    by SensorDebugState.sensorStatus.collectAsStateWithLifecycle()
    val sdkConn   by SensorDebugState.sdkConnected.collectAsStateWithLifecycle()
    val sendLog   by SensorDebugState.sendLog.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    LaunchedEffect(sendLog.size) {
        if (sendLog.isNotEmpty()) listState.animateScrollToItem(sendLog.size - 1)
    }

    MindWaveTheme {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Science, contentDescription = null,
                        tint = Color(0xFF64B5F6), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sensor Debug", color = Color(0xFF64B5F6),
                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            item {
                IconDebugRow(
                    icon = if (sdkConn) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                    iconTint = if (sdkConn) Color(0xFF81C784) else Color(0xFFEF9A9A),
                    label = "SDK",
                    value = if (sdkConn) "connected" else "not connected",
                    valueColor = if (sdkConn) Color(0xFF81C784) else Color(0xFFEF9A9A),
                )
            }
            item {
                DebugRow(label = "Status", value = status, wrap = true)
            }

            item { Spacer(Modifier.height(4.dp)) }
            item {
                Text("── Live sensors ──", color = Color(0xFF90A4AE), fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }

            item {
                IconDebugRow(
                    icon = Icons.Filled.Favorite, iconTint = Color(0xFFEF5350),
                    label = "HR",
                    value = if (hr > 0) "${hr.roundToInt()} bpm  [buf:$hrBuf]" else "— bpm  [buf:$hrBuf]",
                    valueColor = if (hr > 0) Color(0xFFEF5350) else Color(0xFF90A4AE),
                )
            }
            item {
                IconDebugRow(
                    icon = Icons.Filled.Thermostat, iconTint = Color(0xFFFFB74D),
                    label = "Temp",
                    value = if (temp > 0) "${"%.1f".format(temp)} °C  [buf:$tempBuf]" else "— °C  [buf:$tempBuf]",
                    valueColor = if (temp > 0) Color(0xFFFFB74D) else Color(0xFF90A4AE),
                )
            }
            item {
                IconDebugRow(
                    icon = Icons.Filled.WaterDrop, iconTint = Color(0xFF4FC3F7),
                    label = "EDA",
                    value = if (eda > 0) "${"%.2f".format(eda)} µS  [buf:$edaBuf]" else "— µS  [buf:$edaBuf]",
                    valueColor = if (eda > 0) Color(0xFF4FC3F7) else Color(0xFF90A4AE),
                )
            }
            item {
                IconDebugRow(
                    icon = Icons.Filled.Vibration, iconTint = Color(0xFFCE93D8),
                    label = "ACC",
                    value = if (acc > 0) "${"%.0f".format(acc)} mg  [buf:$accBuf]" else "— mg  [buf:$accBuf]",
                    valueColor = if (acc > 0) Color(0xFFCE93D8) else Color(0xFF90A4AE),
                )
            }

            item { Spacer(Modifier.height(4.dp)) }
            item {
                Text("Send logs", color = Color(0xFF90A4AE), fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }

            if (sendLog.isEmpty()) {
                item {
                    Text("No sends yet, waiting for 60s phone ping",
                        color = Color(0xFF78909C),
                        fontSize = 10.sp, modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center)
                }
            } else {
                items(sendLog) { event ->
                    val color = if (event.success) Color(0xFF81C784) else Color(0xFFEF9A9A)
                    val statusIcon = if (event.success) Icons.Filled.CheckCircle else Icons.Filled.Cancel
                    val detail = if (event.success)
                        "HR=${event.hrSamples} T=${event.tempSamples} E=${event.edaSamples} A=${event.accSamples}"
                    else event.error.take(40)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(statusIcon, null, tint = color, modifier = Modifier.size(10.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "${event.time}  $detail",
                            color = color,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun IconDebugRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    valueColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widthIn(max = 64.dp)) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
            Text(label, color = Color(0xFF90A4AE), fontSize = 10.sp)
        }
        Text(
            text = value, color = valueColor, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DebugRow(
    label: String,
    value: String,
    valueColor: Color = Color.White,
    wrap: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = label, color = Color(0xFF90A4AE), fontSize = 10.sp,
            modifier = Modifier.widthIn(max = 52.dp))
        Text(
            text = value, color = valueColor, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
            maxLines = if (wrap) 3 else 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}


