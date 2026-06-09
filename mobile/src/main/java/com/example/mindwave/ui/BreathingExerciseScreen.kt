package com.example.mindwave.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mindwave.ui.theme.WaveDeep
import com.example.mindwave.ui.theme.WavePrimary
import kotlinx.coroutines.delay

/**
 * Guided 4-7-8 breathing exercise.
 *
 * Inhale 4 s, hold 7 s, exhale 8 s. The circle expands on inhale,
 * holds, then contracts on exhale, looping for a configurable number of cycles
 */
private enum class BreathPhase(val label: String, val seconds: Int) {
    INHALE("Breathe in", 4),
    HOLD("Hold", 7),
    EXHALE("Breathe out", 8),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreathingExerciseScreen(
    onClose: () -> Unit = {},
    totalCycles: Int = 4,
) {
    var phase by remember { mutableStateOf(BreathPhase.INHALE) }
    var cycle by remember { mutableIntStateOf(1) }
    var running by remember { mutableStateOf(true) }
    var secondsLeft by remember { mutableIntStateOf(BreathPhase.INHALE.seconds) }

    // Target scale per phase: small on exhale/start, large during inhale+hold.
    val targetScale = when (phase) {
        BreathPhase.INHALE -> 1f
        BreathPhase.HOLD -> 1f
        BreathPhase.EXHALE -> 0.55f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = phase.seconds * 1000),
        label = "breathScale",
    )
    val circleColor by animateColorAsState(
        targetValue = if (phase == BreathPhase.EXHALE) WaveDeep else WavePrimary,
        animationSpec = tween(800),
        label = "breathColor",
    )

    // Phase/cycle driver.
    LaunchedEffect(running, phase, cycle) {
        if (!running) return@LaunchedEffect
        secondsLeft = phase.seconds
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
        when (phase) {
            BreathPhase.INHALE -> phase = BreathPhase.HOLD
            BreathPhase.HOLD -> phase = BreathPhase.EXHALE
            BreathPhase.EXHALE -> {
                if (cycle >= totalCycles) {
                    running = false
                } else {
                    cycle++
                    phase = BreathPhase.INHALE
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Breathing") },
                actions = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "4-7-8 technique",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))

            Box(
                modifier = Modifier.size(280.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(circleColor, circleColor.copy(alpha = 0.4f))
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (running) phase.label else "Done",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                        )
                        if (running) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "$secondsLeft",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
            Text(
                if (running) "Cycle $cycle of $totalCycles" else "Nicely done. Feel the calm.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(24.dp))

            if (running) {
                OutlinedButton(onClick = { running = false }) {
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = {
                        cycle = 1
                        phase = BreathPhase.INHALE
                        running = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                ) {
                    Text("Restart")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}
