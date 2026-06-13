package com.example.mindwave.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Runs sequential diagnostic checks and streams results as a list.
 * Page 3 of the watch UI reads from this object.
 */
object SdkDiagnostics {

    data class CheckResult(
        val name: String,
        val status: Status,
        val detail: String,
    ) {
        enum class Status { PASS, FAIL, WARN, PENDING }
    }

    private val _results = MutableStateFlow<List<CheckResult>>(emptyList())
    val results: StateFlow<List<CheckResult>> = _results.asStateFlow()
    val isRunning = MutableStateFlow(false)

    suspend fun run(context: Context) {
        if (isRunning.value) return
        isRunning.value = true
        val out = mutableListOf<CheckResult>()
        _results.value = emptyList()

        fun emit(r: CheckResult) { out.add(r); _results.value = out.toList() }
        fun replaceLast(r: CheckResult) {
            if (out.isNotEmpty()) out[out.lastIndex] = r
            _results.value = out.toList()
        }

        // ── 1. Device info ───────────────────────────────────────────────────
        val mfr = Build.MANUFACTURER
        val isSamsung = mfr.equals("samsung", ignoreCase = true)
        emit(
            CheckResult(
                "Device",
                if (isSamsung) CheckResult.Status.PASS else CheckResult.Status.WARN,
                "${Build.MODEL} API ${Build.VERSION.SDK_INT}" +
                    if (!isSamsung) " ⚠ not Samsung" else " ✓",
            )
        )

        // ── 2. BODY_SENSORS (API ≤35) ────────────────────────────────────────
        val bodySensors = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        val isApi36Plus = Build.VERSION.SDK_INT >= 36
        emit(
            CheckResult(
                "BODY_SENSORS",
                if (bodySensors) CheckResult.Status.PASS
                else if (isApi36Plus) CheckResult.Status.WARN   // expected on API 36
                else CheckResult.Status.FAIL,
                if (bodySensors) "Granted"
                else if (isApi36Plus) "Denied (expected on API 36 — READ_HEART_RATE used instead)"
                else "DENIED — Settings > Permissions",
            )
        )

        // ── 3. API 36 Samsung permissions ───────────────────────────────────
        val activityRec = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
        val readHR = ContextCompat.checkSelfPermission(
            context, "android.permission.health.READ_HEART_RATE"
        ) == PackageManager.PERMISSION_GRANTED
        val readAdditional = runCatching {
            ContextCompat.checkSelfPermission(
                context, "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        emit(
            CheckResult(
                "ACTIVITY_REC",
                if (activityRec) CheckResult.Status.PASS else CheckResult.Status.FAIL,
                if (activityRec) "Granted" else "DENIED — required for ACC/EDA trackers",
            )
        )
        emit(
            CheckResult(
                "READ_HR",
                if (readHR) CheckResult.Status.PASS else CheckResult.Status.FAIL,
                if (readHR) "Granted" else "DENIED — required on API 36 for HR",
            )
        )
        emit(
            CheckResult(
                "READ_ADDITIONAL",
                if (readAdditional) CheckResult.Status.PASS else CheckResult.Status.WARN,
                if (readAdditional) "Granted" else "Not granted — needed for BIA/EDA/PPG",
            )
        )

        // ── 3. Samsung Health permission ─────────────────────────────────────
        val samsungHealthPerm = runCatching {
            ContextCompat.checkSelfPermission(
                context, "com.samsung.android.permission.HEALTH_HARDWARE"
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        emit(
            CheckResult(
                "Samsung perm",
                if (!isSamsung || samsungHealthPerm) CheckResult.Status.PASS
                else CheckResult.Status.WARN,
                when {
                    !isSamsung -> "N/A"
                    samsungHealthPerm -> "Granted"
                    else -> "Not granted — may cause POLICY_ERROR"
                },
            )
        )

        // ── 4. Samsung Health package installed ──────────────────────────────
        val healthPkgInstalled = runCatching {
            context.packageManager.getPackageInfo("com.samsung.android.service.health", 0)
            true
        }.getOrDefault(false)
        emit(
            CheckResult(
                "SHealth pkg",
                when {
                    healthPkgInstalled -> CheckResult.Status.PASS
                    isSamsung -> CheckResult.Status.FAIL
                    else -> CheckResult.Status.WARN
                },
                if (healthPkgInstalled) "Installed" else "Not found",
            )
        )

        // ── 5. SDK connection test ────────────────────────────────────────────
        emit(CheckResult("SDK connect", CheckResult.Status.PENDING, "Connecting…"))
        val (sdkSt, sdkMsg) = testSdkConnection(context)
        replaceLast(CheckResult("SDK connect", sdkSt, sdkMsg))

        // ── 6. Developer mode hint ────────────────────────────────────────────
        emit(
            CheckResult(
                "Dev mode tip",
                CheckResult.Status.WARN,
                "adb shell am broadcast -a com.samsung.android.service.health.platform.action.START_DEVELOPER_MODE",
            )
        )

        isRunning.value = false
    }

    private suspend fun testSdkConnection(context: Context): Pair<CheckResult.Status, String> =
        suspendCoroutine { cont ->
            var resumed = false
            fun done(status: CheckResult.Status, msg: String) {
                if (!resumed) { resumed = true; cont.resume(status to msg) }
            }
            try {
                // Use a container array so the lambda can close over it before assignment
                val svcHolder = arrayOfNulls<HealthTrackingService>(1)
                val listener = object : ConnectionListener {
                    override fun onConnectionSuccess() {
                        val trackerMsg = runCatching {
                            svcHolder[0]!!.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                            "HR tracker OK"
                        }.getOrElse { "HR tracker: ${it.message}" }
                        runCatching { svcHolder[0]!!.disconnectService() }
                        done(CheckResult.Status.PASS, "Connected ✓  $trackerMsg")
                    }
                    override fun onConnectionEnded() {
                        done(CheckResult.Status.WARN, "Connection ended unexpectedly")
                    }
                    override fun onConnectionFailed(e: HealthTrackerException) {
                        val isPolicyError = e.errorCode == 1006 ||
                            e.message?.contains("POLICY", ignoreCase = true) == true
                        done(
                            CheckResult.Status.FAIL,
                            if (isPolicyError)
                                "SDK_POLICY_ERROR (${e.errorCode}) — run adb developer mode cmd"
                            else
                                "Failed (${e.errorCode}): ${e.message}"
                        )
                    }
                }
                val svc = HealthTrackingService(listener, context)
                svcHolder[0] = svc
                svc.connectService()
                // Timeout after 8s
                GlobalScope.launch(Dispatchers.IO) {
                    delay(8_000)
                    done(CheckResult.Status.WARN, "Timeout — no response after 8s")
                }
            } catch (e: Exception) {
                done(CheckResult.Status.FAIL, "${e.javaClass.simpleName}: ${e.message}")
            }
        }
}


