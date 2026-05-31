/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.example.mindwave.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.SentimentNeutral
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.mindwave.data.WatchStressStore
import com.example.mindwave.presentation.theme.MindWaveTheme
import com.example.mindwave.sync.WearDataSender
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        // Seed the StateFlow with any previously persisted value so the
        // UI shows the last known stress % immediately on launch.
        WatchStressStore.load(this)
        setContent { WearApp() }
    }
}

private fun stressColor(percent: Int): Color = when {
    percent <= 30 -> Color(0xFF27AE60)
    percent <= 50 -> Color(0xFF82C91E)
    percent <= 65 -> Color(0xFFF1C40F)
    percent <= 80 -> Color(0xFFF39C12)
    else -> Color(0xFFE74C3C)
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Collect from StateFlow
    val snapshot by WatchStressStore.flow.collectAsStateWithLifecycle()
    var moodSent by remember { mutableStateOf(false) }

    MindWaveTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            TimeText()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                StressRing(percent = if (snapshot.hasData) snapshot.percent else 0)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (snapshot.hasData) snapshot.label else "No data yet",
                    color = MaterialTheme.colors.onBackground,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(10.dp))
                if (moodSent) {
                    Text(
                        "Thanks! Logged.",
                        color = MaterialTheme.colors.primary,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        "How do you feel?",
                        color = MaterialTheme.colors.onBackground,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MoodButton(Icons.Filled.SentimentVerySatisfied, "calm") {
                            sendMood(scope, context, 1)
                            {
                                moodSent = true
                            }
                        }
                        MoodButton(Icons.Filled.SentimentNeutral,       "neutral") {
                            sendMood(scope, context, 3)
                            {
                                moodSent = true
                            }
                        }
                        MoodButton(Icons.Filled.SentimentDissatisfied,  "stressed"){
                            sendMood(scope, context, 5)
                            {
                                moodSent = true
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = ButtonDefaults.secondaryButtonColors(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun sendMood(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    mood: Int,
    onSent: () -> Unit,
) {
    scope.launch {
        WearDataSender.sendMood(context, mood)
        onSent()
    }
}

@Composable
private fun StressRing(percent: Int) {
    val sweep = 360f * (percent.coerceIn(0, 100) / 100f)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = Color(0xFF00343B),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
            )
            drawArc(
                color = stressColor(percent),
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                style = stroke,
            )
        }
        Text(
            text = "$percent%",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
    }
}