package com.example.mindwave.ui

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mindwave.data.LatencyRecord
import com.example.mindwave.data.MindWaveDatabase
import com.example.mindwave.fl.FLTrainingWorker
import com.example.mindwave.inference.StressInferenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/**
 * Drives the BenchmarkScreen.
 *
 * Loads latency records from Room, computes statistics, and exposes
 * actions for running the benchmark and force-starting an FL round.
 */
class BenchmarkViewModel(app: Application) : AndroidViewModel(app) {

    private val db = MindWaveDatabase.getInstance(app)

    private val _records = MutableStateFlow<List<LatencyRecord>>(emptyList())
    val records: StateFlow<List<LatencyRecord>> = _records.asStateFlow()

    private val _stats = MutableStateFlow<LatencyStats?>(null)
    val stats: StateFlow<LatencyStats?> = _stats.asStateFlow()

    private val _measuredCount = MutableStateFlow(0)
    val measuredCount: StateFlow<Int> = _measuredCount.asStateFlow()

    private val _isBenchmarkRunning = MutableStateFlow(false)
    val isBenchmarkRunning: StateFlow<Boolean> = _isBenchmarkRunning.asStateFlow()

    private val _isFlRunning = MutableStateFlow(false)
    val isFlRunning: StateFlow<Boolean> = _isFlRunning.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    /** Device environment info */
    val deviceInfo: String by lazy {
        "Device: ${Build.MODEL} (${Build.VERSION.RELEASE})\n" +
        "CPU: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}\n" +
        "RAM: ${getRamMb(app)} MB"
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val all     = db.latencyDao().getAll(limit = 200)
            val measured = db.latencyDao().getMeasured()
            _records.value = all
            _measuredCount.value = measured.size
            _stats.value = if (measured.isNotEmpty()) computeStats(measured) else null
        }
    }

    /**
     * Starts the benchmark harness: injects synthetic windows through
     * the full inference pipeline (10 warm-up + 100 measured by default).
     */
    fun runBenchmark(count: Int = 110) {
        if (_isBenchmarkRunning.value) return
        viewModelScope.launch {
            _isBenchmarkRunning.value = true
            _statusMessage.value = "Running benchmark ($count windows)…"
            withContext(Dispatchers.Default) {
                db.latencyDao().deleteAll()
            }
            StressInferenceRepository.runBenchmark(count)
            // Poll until all windows have been processed
            var waited = 0
            while (waited < 120_000) {
                kotlinx.coroutines.delay(2_000)
                waited += 2_000
                val n = withContext(Dispatchers.IO) { db.latencyDao().getMeasuredCount() }
                _measuredCount.value = n
                _statusMessage.value = "Benchmark: $n / ${count - LatencyRecord.WARMUP_COUNT} measured…"
                if (n >= count - LatencyRecord.WARMUP_COUNT) break
            }
            refresh()
            _isBenchmarkRunning.value = false
            _statusMessage.value = "Benchmark complete — ${_measuredCount.value} windows measured."
        }
    }

    /** Force-starts one FL round immediately using synthetic data if needed. */
    fun forceStartFl() {
        if (_isFlRunning.value) return
        viewModelScope.launch {
            _isFlRunning.value = true
            _statusMessage.value = "FL round starting (force mode)…"
            FLTrainingWorker.enqueueForce(getApplication())
            // Wait for the worker to complete (WorkManager status polling)
            kotlinx.coroutines.delay(5_000)
            _isFlRunning.value = false
            _statusMessage.value = "FL round enqueued (force mode)"
        }
    }

    /** Clear all recorded latency data. */
    fun clearRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            db.latencyDao().deleteAll()
            _records.value = emptyList()
            _stats.value = null
            _measuredCount.value = 0
            _statusMessage.value = "Records cleared."
        }
    }

    /** Export records to a CSV string suitable for saving or sharing. */
    fun exportCsv(): String {
        val header = "windowId,featureUs,normalizeUs,inferenceUs,xaiUs,roomUs,alertUs,totalUs,isWarmup\n"
        val rows = _records.value.joinToString("\n") { r ->
            "${r.windowId},${r.featureUs},${r.normalizeUs},${r.inferenceUs},${r.xaiUs},${r.roomUs},${r.alertUs},${r.totalUs},${if (r.isWarmup) 1 else 0}"
        }
        return header + rows
    }

    private fun computeStats(records: List<LatencyRecord>): LatencyStats {
        fun longs(selector: (LatencyRecord) -> Long): LongArray =
            records.map(selector).filter { it > 0 }.toLongArray().also { it.sort() }

        fun percentile(sorted: LongArray, p: Double): Long {
            if (sorted.isEmpty()) return 0L
            val idx = ((sorted.size - 1) * p / 100.0).roundToLong().coerceIn(0, sorted.size.toLong() - 1)
            return sorted[idx.toInt()]
        }

        fun stats(sorted: LongArray) = StageStat(
            mean   = if (sorted.isEmpty()) 0L else sorted.average().roundToLong(),
            median = percentile(sorted, 50.0),
            p95    = percentile(sorted, 95.0),
            p99    = percentile(sorted, 99.0),
            max    = sorted.lastOrNull() ?: 0L,
            count  = sorted.size,
        )

        // Statistics computed in µs for precision; BenchmarkScreen displays in µs too.
        return LatencyStats(
            total     = stats(longs { it.totalUs }),
            feature   = stats(longs { it.featureUs }),
            normalize = stats(longs { it.normalizeUs }),
            inference = stats(longs { it.inferenceUs }),
            xai       = stats(longs { it.xaiUs }),
            room      = stats(longs { it.roomUs }),
            alert     = stats(longs { it.alertUs }),
        )
    }

    @Suppress("DEPRECATION")
    private fun getRamMb(context: Context): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024 * 1024)
    } catch (_: Exception) { 0L }
}

data class StageStat(
    val mean: Long,
    val median: Long,
    val p95: Long,
    val p99: Long,
    val max: Long,
    val count: Int,
)

data class LatencyStats(
    val total: StageStat,
    val feature: StageStat,
    val normalize: StageStat,
    val inference: StageStat,
    val xai: StageStat,
    val room: StageStat,
    val alert: StageStat,
)

