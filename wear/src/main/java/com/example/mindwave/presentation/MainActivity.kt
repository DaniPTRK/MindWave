package com.example.mindwave.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.*
import com.example.mindwave.data.SensorDebugState
import com.example.mindwave.data.WatchStressStore
import com.example.mindwave.presentation.theme.MindWaveTheme
import com.example.mindwave.sensor.SensorForegroundService
import com.example.mindwave.sync.WearDataSender
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import android.util.Log
import kotlin.math.roundToInt

// color palette

private val TealPrimary    = Color(0xFF26C6DA)
private val TealDeep       = Color(0xFF00838F)
private val TealTrack      = Color(0xFF00343B)
private val BubbleBg       = Color(0xFF0D2526)
private val WarnYellow     = Color(0xFFF1C40F)
private val WarnOrange     = Color(0xFFF39C12)
private val AlertRed       = Color(0xFFE74C3C)
private val GreenSafe      = Color(0xFF27AE60)
private val CoralHR        = Color(0xFFFF6B6B)   // coral/pink for heart rate petal

sealed class WearUiState {
    object Collecting : WearUiState()
    data class HasResult(val percent: Int) : WearUiState()
}

// label helper
fun stressScoreToLabel(score: Float): String = when {
    score < 0.40f -> "Stress unlikely"
    score < 0.70f -> "Elevated"
    score < 0.85f -> "High stress likely"
    else          -> "Alert zone"
}

private fun stressGaugeColor(percent: Int): Color = when {
    percent < 40 -> GreenSafe
    percent < 70 -> WarnYellow
    percent < 85 -> WarnOrange
    else         -> AlertRed
}


enum class PermissionStatus { CHECKING, REQUESTING, NEEDS_SETTINGS, GRANTED, DENIED }

class MainActivity : ComponentActivity() {
    private val TAG = "MWMainActivity"
    private val permissionStatus = mutableStateOf(PermissionStatus.CHECKING)
    private var serviceStarted = false

    private val samsungApi36Permissions = arrayOf(
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_SKIN_TEMPERATURE",
        "android.permission.health.READ_OXYGEN_SATURATION",
        Manifest.permission.ACTIVITY_RECOGNITION,
        "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA",
    )

    private val allPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            Log.i(TAG, "Permission results: $results")
            val denied = results.filterValues { !it }.keys
            if (denied.isEmpty()) {
                onPermissionsGranted()
            } else {
                val criticalDenied = denied.filter {
                    it != Manifest.permission.BODY_SENSORS && it != Manifest.permission.BODY_SENSORS_BACKGROUND
                }
                if (criticalDenied.isEmpty()) onPermissionsGranted()
                else permissionStatus.value = PermissionStatus.DENIED
            }
        }

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { capability ->
        Log.i(TAG, "Phone capability changed: ${capability.name}, nodes: ${capability.nodes.size}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        Log.i(TAG, "onCreate — ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
        WatchStressStore.load(this)
        checkAndRequestPermissions()
        try {
            Wearable.getCapabilityClient(this).addListener(capabilityListener, "mindwave_phone_app")
        } catch (e: Exception) {
            Log.w(TAG, "CapabilityClient not available: ${e.message}")
        }
        setContent {
            WearApp(
                permissionStatus = permissionStatus.value,
                onRetryPermissions = { permissionStatus.value = PermissionStatus.REQUESTING; launchPermissions() },
                onOpenSettings = { openAppSettings() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (permissionStatus.value != PermissionStatus.GRANTED && hasSufficientPermissions()) {
            onPermissionsGranted()
        }
        // If the service is already running, ask the watchdog to check connectivity immediately.
        // This means every time the user opens the app they get a fast reconnect if data has stopped.
        if (SensorForegroundService.isRunning.value) {
            SensorForegroundService.reconnectRequested.set(true)
            Log.i(TAG, "onResume — signalled watchdog for connectivity check")
        }
    }

    override fun onDestroy() {
        try { Wearable.getCapabilityClient(this).removeListener(capabilityListener) }
        catch (e: Exception) { Log.w(TAG, "CapabilityClient removeListener failed: ${e.message}") }
        super.onDestroy()
    }

    private fun checkAndRequestPermissions() {
        if (hasSufficientPermissions()) onPermissionsGranted()
        else { permissionStatus.value = PermissionStatus.REQUESTING; launchPermissions() }
    }

    private fun hasSufficientPermissions(): Boolean {
        val activityRecognition = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        val readHR = ContextCompat.checkSelfPermission(this, "android.permission.health.READ_HEART_RATE") == PackageManager.PERMISSION_GRANTED
        val bodySensors = ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
        return (activityRecognition && readHR) || bodySensors
    }

    private fun launchPermissions() {
        val toRequest = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            addAll(samsungApi36Permissions)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.distinct()
        if (toRequest.isEmpty()) onPermissionsGranted()
        else allPermissionsLauncher.launch(toRequest.toTypedArray())
    }

    private fun onPermissionsGranted() {
        permissionStatus.value = PermissionStatus.GRANTED
        if (!serviceStarted) {
            serviceStarted = true
            startForegroundService(Intent(this, SensorForegroundService::class.java))
            Log.i(TAG, "Started SensorForegroundService")
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        })
    }
}


@Composable
fun WearApp(
    permissionStatus: PermissionStatus = PermissionStatus.GRANTED,
    onRetryPermissions: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { 2 })

    MindWaveTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> FlowerGaugeScreen(permissionStatus, onRetryPermissions, onOpenSettings)
                    else -> DebugScreen()
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(2) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (pagerState.currentPage == index) 6.dp else 4.dp)
                            .clip(CircleShape)
                            .background(if (pagerState.currentPage == index) TealPrimary else Color(0xFF455A64)),
                    )
                }
            }
        }
    }
}

// The main screen, showing all sensor petal, central gauge and actions.

@Composable
fun FlowerGaugeScreen(
    permissionStatus: PermissionStatus,
    onRetryPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by WatchStressStore.flow.collectAsStateWithLifecycle()
    val serviceRunning by SensorForegroundService.isRunning.collectAsStateWithLifecycle()

    // Derive UI state, single result state regardless of stress level
    val uiState: WearUiState = remember(snapshot, serviceRunning) {
        when {
            !serviceRunning || !snapshot.hasData -> WearUiState.Collecting
            else -> WearUiState.HasResult(snapshot.percent)
        }
    }

    // Feedback overlay state, triggered by Alert screen "I'm fine" or explicit tap
    var showFeedback by remember { mutableStateOf(false) }
    var showBreathing by remember { mutableStateOf(false) }
    // Reset feedback only when reading changes AND the user is not mid-interaction.
    // Without this guard, a new stress score pushed while the popup is open would
    // immediately dismiss it before the user could respond.
    LaunchedEffect(snapshot.timestamp) {
        if (!showFeedback && !showBreathing) {
            showFeedback = false
        }
    }

    // Shared Prefs for mood dedup
    val prefs = remember { context.getSharedPreferences("mw_mood_prefs", android.content.Context.MODE_PRIVATE) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        TimeText()

        when (permissionStatus) {
            PermissionStatus.CHECKING, PermissionStatus.REQUESTING -> {
                Text("Checking\npermissions…", color = MaterialTheme.colors.onBackground,
                    textAlign = TextAlign.Center, fontSize = 13.sp)
            }
            PermissionStatus.DENIED -> PermissionErrorScreen("Body sensor\npermission needed", onRetryPermissions, "Grant")
            PermissionStatus.NEEDS_SETTINGS -> PermissionErrorScreen("Enable Body\nSensors in Settings", onOpenSettings, "Settings")
            PermissionStatus.GRANTED -> {
                if (!serviceRunning) {
                    Text("Starting sensors…", color = MaterialTheme.colors.onBackground, fontSize = 13.sp)
                } else {
                    // Main screen
                    AnimatedVisibility(visible = !showFeedback && !showBreathing,
                        enter = fadeIn(tween(300)) + scaleIn(tween(300)),
                        exit = fadeOut(tween(200)) + scaleOut(tween(200))) {
                        when (uiState) {
                            is WearUiState.Collecting -> CollectingScreen(
                                onBreathe = { showBreathing = true }
                            )
                            is WearUiState.HasResult -> ResultScreen(
                                percent = uiState.percent,
                                snapshot = snapshot,
                                onBreathe = { showBreathing = true },
                                onFeedbackRequest = { showFeedback = true },
                            )
                        }
                    }

                    // Breathing exercise overlay
                    AnimatedVisibility(visible = showBreathing,
                        enter = fadeIn(tween(300)) + scaleIn(tween(300)),
                        exit = fadeOut(tween(200)) + scaleOut(tween(200))) {
                        WearBreathingScreen(onDone = { showBreathing = false })
                    }

                    // Feedback popup overlay
                    AnimatedVisibility(visible = showFeedback && !showBreathing,
                        enter = fadeIn(tween(300)) + scaleIn(tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(200))) {
                        WearFeedbackPopup(
                            onYes = {
                                scope.launch {
                                    WearDataSender.sendMood(context, 1, snapshot.readingId)
                                    prefs.edit().putLong("mood_sent_for_ts", snapshot.timestamp).apply()
                                    showFeedback = false
                                }
                            },
                            onNo = {
                                scope.launch {
                                    WearDataSender.sendMood(context, 5, snapshot.readingId)
                                    prefs.edit().putLong("mood_sent_for_ts", snapshot.timestamp).apply()
                                    showFeedback = false
                                }
                            },
                            onNotSure = {
                                scope.launch {
                                    WearDataSender.sendMood(context, 3, snapshot.readingId)
                                    prefs.edit().putLong("mood_sent_for_ts", snapshot.timestamp).apply()
                                    showFeedback = false
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}


// Collecting screen (no result yet, spinner + petals)

@Composable
private fun CollectingScreen(onBreathe: () -> Unit) {
    val hr   by SensorDebugState.hrBpm.collectAsStateWithLifecycle()
    val temp by SensorDebugState.tempC.collectAsStateWithLifecycle()
    val eda  by SensorDebugState.edaMicroS.collectAsStateWithLifecycle()
    val acc  by SensorDebugState.accMag.collectAsStateWithLifecycle()
    val lastTemp by SensorDebugState.lastKnownTempC.collectAsStateWithLifecycle()
    val lastEda  by SensorDebugState.lastKnownEdaMicroS.collectAsStateWithLifecycle()
    val lastHr   by SensorDebugState.lastKnownHrBpm.collectAsStateWithLifecycle()

    val displayHr   = if (hr > 0)   hr   else lastHr
    val displayTemp = if (temp > 0) temp else lastTemp
    val displayEda  = if (eda > 0)  eda  else lastEda

    val infiniteTransition = rememberInfiniteTransition(label = "collecting")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Central gauge with animated spinner
        Box(contentAlignment = Alignment.Center, modifier = Modifier.offset(y = (-18).dp)) {
            Canvas(modifier = Modifier.size(72.dp)) {
                val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                drawArc(TealTrack, -90f, 360f, false, style = stroke)
                drawArc(TealPrimary.copy(alpha = 0.7f), sweepAngle - 90f, 90f, false, style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("—", color = TealPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("stress likelihood", color = Color(0xFF80CBC4), fontSize = 7.sp,
                    textAlign = TextAlign.Center)
            }
        }

        Text("Measuring…", color = MaterialTheme.colors.onBackground,
            fontWeight = FontWeight.Medium, fontSize = 11.sp,
            modifier = Modifier.offset(y = 22.dp))

        FlowerPetals(
            topLeft     = PetalData(Icons.Default.Favorite,                       "HR",   if (displayHr > 0)   "${displayHr.roundToInt()}" else "—", CoralHR),
            topRight    = PetalData(Icons.Default.WaterDrop,                      "EDA",  if (displayEda > 0)  "${"%.1f".format(displayEda)}" else "—", Color(0xFF4FC3F7)),
            bottomLeft  = PetalData(Icons.Default.Thermostat,                     "Temp", if (displayTemp > 0) "${"%.0f".format(displayTemp)}°" else "—", Color(0xFFFFB74D)),
            bottomRight = PetalData(Icons.AutoMirrored.Filled.DirectionsRun,      "Acc",  if (acc > 0)  "${acc.roundToInt()}" else "—", Color(0xFFCE93D8)),
        )
        BreatheActionRow(onBreathe = onBreathe)
    }
}

// Results screen, contains the central gauge, petals, and action buttons.

@Composable
private fun ResultScreen(
    percent: Int,
    snapshot: WatchStressStore.Snapshot,
    onBreathe: () -> Unit,
    onFeedbackRequest: () -> Unit,
) {
    val hr   by SensorDebugState.hrBpm.collectAsStateWithLifecycle()
    val temp by SensorDebugState.tempC.collectAsStateWithLifecycle()
    val eda  by SensorDebugState.edaMicroS.collectAsStateWithLifecycle()
    val acc  by SensorDebugState.accMag.collectAsStateWithLifecycle()
    val lastTemp by SensorDebugState.lastKnownTempC.collectAsStateWithLifecycle()
    val lastEda  by SensorDebugState.lastKnownEdaMicroS.collectAsStateWithLifecycle()
    val lastHr   by SensorDebugState.lastKnownHrBpm.collectAsStateWithLifecycle()

    val displayHr   = if (hr > 0)   hr   else lastHr
    val displayTemp = if (temp > 0) temp else lastTemp
    val displayEda  = if (eda > 0)  eda  else lastEda

    val gaugeColor = stressGaugeColor(percent)
    val animatedSweep by animateFloatAsState(
        targetValue = 360f * (percent.coerceIn(0, 100) / 100f),
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "gauge"
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Central gauge + label. Actions live in a bottom row to avoid overlap.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(y = (-26).dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(TealTrack, -90f, 360f, false, style = stroke)
                    drawArc(gaugeColor, -90f, animatedSweep, false, style = stroke)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$percent%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("stress likelihood", color = Color(0xFF80CBC4), fontSize = 7.sp,
                        textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(snapshot.label, color = gaugeColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        }

        FlowerPetals(
            topLeft     = PetalData(Icons.Default.Favorite,                  "HR",   if (displayHr > 0)   "${displayHr.roundToInt()}" else "—", CoralHR),
            topRight    = PetalData(Icons.Default.WaterDrop,                 "EDA",  if (displayEda > 0)  "${"%.1f".format(displayEda)}" else "—", Color(0xFF4FC3F7)),
            bottomLeft  = PetalData(Icons.Default.Thermostat,                "Temp", if (displayTemp > 0) "${displayTemp.roundToInt()}°" else "—", Color(0xFFFFB74D)),
            bottomRight = PetalData(Icons.AutoMirrored.Filled.DirectionsRun, "Acc",  if (acc > 0)  "${acc.roundToInt()}" else "—", Color(0xFFCE93D8)),
        )
        ResultActionRow(onBreathe = onBreathe, onFeedbackRequest = onFeedbackRequest)
    }
}

// Layout flower petal

data class PetalData(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val color: Color,
)

/**
 * Four sensor petal bubbles. Action buttons are rendered separately because a
 * fifth centered bubble overlaps the lower row on small round displays.
 */
@Composable
private fun FlowerPetals(
    topLeft: PetalData,
    topRight: PetalData,
    bottomLeft: PetalData,
    bottomRight: PetalData,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        FlowerPetalBubble(topLeft,     Alignment.BottomStart, paddingStart = 14.dp, paddingBottom = 98.dp)
        FlowerPetalBubble(topRight,    Alignment.BottomEnd,   paddingEnd   = 14.dp, paddingBottom = 98.dp)
        FlowerPetalBubble(bottomLeft,  Alignment.BottomStart, paddingStart = 18.dp, paddingBottom = 44.dp)
        FlowerPetalBubble(bottomRight, Alignment.BottomEnd,   paddingEnd   = 18.dp, paddingBottom = 44.dp)
    }
}

@Composable
fun WearFeedbackPopup(
    onYes: () -> Unit,
    onNo: () -> Unit,
    onNotSure: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC0A1A1A), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("How do you feel?", color = TealPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("Did you feel stressed?", color = Color(0xFF90A4AE), fontSize = 9.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onYes,
                    modifier = Modifier.size(38.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = AlertRed.copy(alpha = 0.8f)),
                ) { Icon(Icons.Default.SentimentVeryDissatisfied, "Yes", Modifier.size(18.dp), tint = Color.White) }
                Button(
                    onClick = onNotSure,
                    modifier = Modifier.size(38.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                ) { Icon(Icons.Default.SentimentNeutral, "Not sure", Modifier.size(18.dp), tint = Color.White) }
                Button(
                    onClick = onNo,
                    modifier = Modifier.size(38.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = GreenSafe.copy(alpha = 0.8f)),
                ) { Icon(Icons.Default.SentimentVerySatisfied, "No", Modifier.size(18.dp), tint = Color.White) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Yes",      color = Color(0xFF607D8B), fontSize = 8.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.width(38.dp))
                Text("Not sure", color = Color(0xFF607D8B), fontSize = 8.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.width(38.dp))
                Text("No",       color = Color(0xFF607D8B), fontSize = 8.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.width(38.dp))
            }
        }
    }
}

@Composable
private fun BoxScope.FlowerPetalBubble(
    data: PetalData,
    alignment: Alignment,
    paddingStart: Dp = 0.dp,
    paddingEnd: Dp = 0.dp,
    paddingBottom: Dp = 0.dp,
    paddingTop: Dp = 0.dp,
) {
    Column(
        modifier = Modifier
            .align(alignment)
            .padding(start = paddingStart, end = paddingEnd, bottom = paddingBottom, top = paddingTop)
            .size(44.dp)
            .clip(CircleShape)
            .background(BubbleBg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(data.icon, data.label, Modifier.size(13.dp), tint = data.color)
        Text(data.value, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = data.color,
            textAlign = TextAlign.Center, maxLines = 1)
        Text(data.label, fontSize = 7.sp, color = Color(0xFF546E7A),
            textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun BreatheActionRow(onBreathe: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        ActionBubble(icon = Icons.Default.Air, contentDescription = "Breathe", color = TealPrimary, onClick = onBreathe)
    }
}

@Composable
private fun ResultActionRow(
    onBreathe: () -> Unit,
    onFeedbackRequest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom,
    ) {
        ActionBubble(icon = Icons.Default.Air, contentDescription = "Breathe", color = TealPrimary, onClick = onBreathe)
        ActionBubble(icon = Icons.Default.Star, contentDescription = "Rate", color = Color.White, onClick = onFeedbackRequest)
    }
}

@Composable
private fun ActionBubble(
    icon: ImageVector,
    contentDescription: String,
    color: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = TealDeep),
    ) {
        Icon(icon, contentDescription, Modifier.size(15.dp), tint = color)
    }
}

// Permision err screen
@Composable
private fun PermissionErrorScreen(message: String, onAction: () -> Unit, actionLabel: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, color = AlertRed, textAlign = TextAlign.Center,
            fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Button(onClick = onAction, colors = ButtonDefaults.primaryButtonColors()) {
            Text(actionLabel, fontSize = 12.sp)
        }
    }
}

// Breathing exercises on watch
private enum class WearBreathPhase(val label: String, val durationMs: Long) {
    INHALE("Breathe in", 4_000),
    HOLD("Hold", 7_000),
    EXHALE("Breathe out", 8_000),
}

/**
 * Simple 4-7-8 breathing exercise that runs entirely on the watch.
 * No phone connection required. Runs 3 cycles then shows a done state.
 */
@Composable
fun WearBreathingScreen(onDone: () -> Unit) {
    var phase by remember { mutableStateOf(WearBreathPhase.INHALE) }
    var cycle by remember { mutableIntStateOf(1) }
    val totalCycles = 3
    var finished by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf((WearBreathPhase.INHALE.durationMs / 1000).toInt()) }

    val circleScale by animateFloatAsState(
        targetValue = when (phase) {
            WearBreathPhase.INHALE -> 1f
            WearBreathPhase.HOLD   -> 1f
            WearBreathPhase.EXHALE -> 0.45f
        },
        animationSpec = tween(
            durationMillis = phase.durationMs.toInt(),
            easing = LinearEasing,
        ),
        label = "breathScale",
    )

    val circleColorValue = when (phase) {
        WearBreathPhase.INHALE -> TealPrimary
        WearBreathPhase.HOLD   -> Color(0xFF80CBC4)
        WearBreathPhase.EXHALE -> TealDeep
    }

    // Drive the phase timer
    LaunchedEffect(phase, cycle, finished) {
        if (finished) return@LaunchedEffect
        secondsLeft = (phase.durationMs / 1000).toInt()
        while (secondsLeft > 0) {
            kotlinx.coroutines.delay(1_000)
            secondsLeft--
        }
        when (phase) {
            WearBreathPhase.INHALE -> phase = WearBreathPhase.HOLD
            WearBreathPhase.HOLD   -> phase = WearBreathPhase.EXHALE
            WearBreathPhase.EXHALE -> {
                if (cycle >= totalCycles) {
                    finished = true
                } else {
                    cycle++
                    phase = WearBreathPhase.INHALE
                }
            }
        }
    }

    MindWaveTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            if (finished) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Feel calmer?", color = TealPrimary,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Great work.", color = Color(0xFF80CBC4), fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onDone,
                        colors = ButtonDefaults.buttonColors(backgroundColor = TealDeep),
                        modifier = Modifier.height(30.dp).widthIn(min = 72.dp),
                    ) { Text("Done", fontSize = 10.sp, color = Color.White) }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(90.dp)) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val r = (size.minDimension / 2f) * circleScale
                            drawCircle(color = circleColorValue.copy(alpha = 0.25f), radius = r)
                            drawCircle(color = circleColorValue, radius = r,
                                style = Stroke(3.dp.toPx()))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(phase.label, color = Color.White,
                                fontWeight = FontWeight.SemiBold, fontSize = 9.sp,
                                textAlign = TextAlign.Center)
                            Text("$secondsLeft", color = circleColorValue,
                                fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Cycle $cycle/$totalCycles", color = Color(0xFF80CBC4), fontSize = 9.sp)
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = onDone,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F)),
                        modifier = Modifier.height(26.dp).widthIn(min = 52.dp),
                    ) { Text("Stop", fontSize = 9.sp, color = Color.White) }
                }
            }
        }
    }
}


