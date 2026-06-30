package com.example.mindwave.inference

import kotlin.math.*

/**
 * On-device feature extraction that mirrors ml/src/features.py.
 *
 * Input: raw sensor arrays from a 60 s window.
 * Output: FloatArray of shape (12 * N_FEATURES) — 12 sub-windows × 23 features each.
 *
 * Feature order per sub-window (matches WESAD training):
 *   HRV time (4): meanNN, SDNN, RMSSD, pNN50
 *   HRV freq (3): LF, HF, LF/HF
 *   EDA      (7): scl_mean, scl_slope, scr_mean, scr_std, scr_auc, scr_peaks, scr_amp_mean
 *   TEMP     (5): mean, std, slope, min, max
 *   ACC      (4): mag_mean, mag_std, mag_energy, zcr
 */
object FeatureExtractor {

    const val N_SUBWINDOWS = 12
    const val N_FEATURES = 23
    const val WINDOW_SEC = 60
    const val SUBWINDOW_SEC = 5

    fun extract(
        hrValues: FloatArray,
        tempValues: FloatArray,
        edaValues: FloatArray,
        accX: FloatArray = FloatArray(0),
        accY: FloatArray = FloatArray(0),
        accZ: FloatArray = FloatArray(0),
    ): FloatArray? {
        if (hrValues.size < 5) return null

        // Band-pass ACC magnitude, then remove residual DC (gravity) via mean subtraction
        val accMag = removeDc(bandpassAccMag(accX, accY, accZ))

        // Forward-fill temperature; fall back to 36 °C if no samples arrive
        val tempFilled: FloatArray = when {
            tempValues.isEmpty() -> FloatArray(hrValues.size) { 36.0f }
            tempValues.size >= hrValues.size -> tempValues
            else -> FloatArray(hrValues.size) { i ->
                tempValues[minOf(i, tempValues.size - 1)]
            }
        }

        // Compute HRV over the full 60 s window and broadcast to all sub-windows
        val fullWindowHrv = hrvTimeFeatures(hrValues)

        // Low-pass filter the raw EDA buffer to remove Samsung quantization noise
        val edaFiltered = lowpassEda(edaValues)

        // Bail out to zeros if the signal is noisy/absent
        val edaQualityOk = edaSignalQualityOk(edaFiltered)

        // Count SCR peaks on the full 60 s z-scored signal
        val fullWindowEdaZ = if (edaQualityOk) zscoreWindow(edaFiltered) else FloatArray(edaFiltered.size)
        val fullWindowScrPeaks = if (edaQualityOk) countScrPeaksFullWindow(fullWindowEdaZ) else 0f
        val fullWindowScrAuc   = if (edaQualityOk) scrAucFullWindow(fullWindowEdaZ)        else 0f
        val fullWindowScrAmp   = if (edaQualityOk) scrAmpMeanFullWindow(fullWindowEdaZ)    else 0f

        val result = FloatArray(N_SUBWINDOWS * N_FEATURES)

        for (sw in 0 until N_SUBWINDOWS) {
            val edaSeg = subwindowSlice(edaFiltered, sw, N_SUBWINDOWS)
            val tmpSeg = subwindowSlice(tempFilled,  sw, N_SUBWINDOWS)
            val accSeg = subwindowSlice(accMag,      sw, N_SUBWINDOWS)

            val offset = sw * N_FEATURES
            fullWindowHrv.copyInto(result, offset)
            // HRV freq, zeroed out.

            if (edaQualityOk) {
                // z-score the 5s sub-window to match training
                val edaSegZ = zscoreWindow(edaSeg)
                val edaVec = edaFeaturesSubwindow(edaSegZ)
                // Overwrite indices 4–6 (scr_auc, scr_peaks, scr_amp_mean) with full-window values.
                edaVec[4] = fullWindowScrAuc
                edaVec[5] = fullWindowScrPeaks
                edaVec[6] = fullWindowScrAmp
                edaVec.copyInto(result, offset + 7)
            }

            tempFeatures(tmpSeg).copyInto(result, offset + 14)
            accFeatures(accSeg).copyInto(result, offset + 19)
        }
        return result
    }

    // eda helpers

    /**
     * Single-pole IIR low-pass at ~0.2 Hz for a ~1 Hz Samsung EDA stream, attenuating
     * the quantization noise that causes huge oscillations, SCR for example.
     */
    private fun lowpassEda(eda: FloatArray): FloatArray {
        if (eda.size < 2) return eda
        val alpha = 0.35f
        val out = FloatArray(eda.size)
        out[0] = eda[0]
        for (i in 1 until eda.size) {
            out[i] = alpha * eda[i] + (1f - alpha) * out[i - 1]
        }
        return out
    }

    /**
     * Returns false (bad quality) when:
     *  - fewer than 3 samples (cannot compute meaningful features)
     *  - full-window range < 0.05 µS (no info)
     *  - flip rate > 40% of consecutive pairs change direction (Samsung ADC noise)
     */
    private fun edaSignalQualityOk(eda: FloatArray): Boolean {
        if (eda.size < 3) return false
        val range = eda.max() - eda.min()
        if (range < 0.05f) return false       // flat / no skin contact
        val diffs = FloatArray(eda.size - 1) { eda[it + 1] - eda[it] }
        var flips = 0
        for (i in 1 until diffs.size) {
            if (diffs[i] * diffs[i - 1] < 0f) flips++  // sign change between consecutive diffs
        }
        val flipRate = flips.toFloat() / (diffs.size - 1).coerceAtLeast(1)
        return flipRate <= 0.40f   // allow up to 40% direction reversals (real SCR has ringing)
    }

    /**
     * Count SCR peaks on the full 60 s z-scored EDA signal, matching the pipeline
     */
    private fun countScrPeaksFullWindow(edaZ: FloatArray): Float {
        if (edaZ.size < 3) return 0f
        val sorted = edaZ.copyOf().also { it.sort() }
        val threshold = sorted[(sorted.size * 0.30f).toInt().coerceIn(0, sorted.size - 1)]
        var peaks = 0
        for (i in 1 until edaZ.size - 1) {
            if (edaZ[i] > edaZ[i - 1] && edaZ[i] > edaZ[i + 1] && edaZ[i] > threshold) {
                peaks++
            }
        }
        return peaks.toFloat()
    }

    /** AUC of phasic on the full 60 s window (trapezoid approximation). */
    private fun scrAucFullWindow(edaZ: FloatArray): Float {
        if (edaZ.size < 2) return 0f
        val mean = edaZ.average().toFloat()
        val scr = FloatArray(edaZ.size) { edaZ[it] - mean }
        // trapezoid: sum of (|y[i]| + |y[i+1]|) / 2 * dt, dt=1 sample => fs
        var auc = 0f
        for (i in 1 until scr.size) auc += (abs(scr[i - 1]) + abs(scr[i])) * 0.5f
        return auc / edaZ.size.coerceAtLeast(1)
    }

    /** Mean SCR peak amplitude on the full 60 s window. */
    private fun scrAmpMeanFullWindow(edaZ: FloatArray): Float {
        if (edaZ.size < 3) return 0f
        val mean = edaZ.average().toFloat()
        val scr = FloatArray(edaZ.size) { edaZ[it] - mean }
        val sorted = edaZ.copyOf().also { it.sort() }
        val threshold = sorted[(sorted.size * 0.30f).toInt().coerceIn(0, sorted.size - 1)]
        var ampSum = 0f; var count = 0
        for (i in 1 until scr.size - 1) {
            if (scr[i] > scr[i - 1] && scr[i] > scr[i + 1] && scr[i] > threshold) {
                ampSum += scr[i]; count++
            }
        }
        return if (count > 0) ampSum / count else 0f
    }

    /**
     * Per-sub-window EDA features — indices 0–3 only (scl_mean, scl_slope, scr_mean, scr_std).
     * Indices 4–6 (scr_auc, scr_peaks, scr_amp_mean) are overwritten by the caller with
     * full-window values, matching the training broadcast of scr_peak_features().
     */
    private fun edaFeaturesSubwindow(eda: FloatArray): FloatArray {
        val out = FloatArray(7)
        if (eda.size < 3) return out
        val mean = eda.average().toFloat()   // ≈ 0 after z-scoring
        out[0] = mean                        // scl_mean
        out[1] = linearSlope(eda)            // scl_slope
        val scr = FloatArray(eda.size) { eda[it] - mean }
        out[2] = scr.average().toFloat()     // scr_mean
        out[3] = scr.stdDev()               // scr_std
        // out[4..6] filled by caller with full-window broadcast values
        return out
    }

    // helpers

    private fun subwindowSlice(arr: FloatArray, sw: Int, total: Int): FloatArray {
        if (arr.isEmpty()) return FloatArray(0)
        val start = (arr.size.toLong() * sw / total).toInt()
        val end   = (arr.size.toLong() * (sw + 1) / total).toInt()
        return arr.copyOfRange(start.coerceAtLeast(0), end.coerceAtMost(arr.size))
    }

    /**
     * Z-score a 1-D signal to mean=0 std=1, matching nk.standardize() used in pipeline.
     */
    private fun zscoreWindow(arr: FloatArray): FloatArray {
        if (arr.size < 2) return FloatArray(arr.size)
        val m = arr.average().toFloat()
        val s = arr.stdDev()
        if (s == 0f) return FloatArray(arr.size)
        return FloatArray(arr.size) { (arr[it] - m) / s }
    }

    /**
     * Subtract the per-window mean from the band-passed ACC magnitude to eliminate
     * any residual DC offset.  Training acc_mag_mean is ~0 by construction.
     */
    private fun removeDc(arr: FloatArray): FloatArray {
        if (arr.isEmpty()) return arr
        val m = arr.average().toFloat()
        return FloatArray(arr.size) { arr[it] - m }
    }

    // HRV time features
    private fun hrvTimeFeatures(bpmSamples: FloatArray): FloatArray {
        val out = FloatArray(4)
        if (bpmSamples.size < 3) return out
        val rr = FloatArray(bpmSamples.size) { 60_000f / bpmSamples[it].coerceAtLeast(1f) }
        out[0] = rr.average().toFloat()
        out[1] = rr.stdDev()
        val diffs = FloatArray(rr.size - 1) { abs(rr[it + 1] - rr[it]) }
        out[2] = if (diffs.isEmpty()) 0f else sqrt(diffs.map { it * it }.average()).toFloat()
        out[3] = if (diffs.isEmpty()) 0f else diffs.count { it > 50f }.toFloat() / diffs.size * 100f
        return out
    }

    // Temp features
    private fun tempFeatures(temp: FloatArray): FloatArray {
        val out = FloatArray(5)
        if (temp.isEmpty()) return out
        out[0] = temp.average().toFloat()
        out[1] = temp.stdDev()
        out[2] = linearSlope(temp)
        out[3] = temp.min()
        out[4] = temp.max()
        return out
    }

    /**
     * Band-pass ACC magnitude 0.5–10 Hz at fs=25 Hz (recreates Butterworth filter).
     */
    private fun bandpassAccMag(xArr: FloatArray, yArr: FloatArray, zArr: FloatArray): FloatArray {
        val n = minOf(xArr.size, yArr.size, zArr.size)
        if (n == 0) return FloatArray(0)
        val mag = FloatArray(n) { i ->
            val x = xArr[i]; val y = yArr[i]; val z = zArr[i]
            sqrt(x * x + y * y + z * z)
        }
        if (n < 9) return mag
        val sos = arrayOf(
            floatArrayOf( 0.06745527f,  0.0f,        -0.06745527f, -1.56928973f,  0.72577947f),
            floatArrayOf( 1.0f,        -2.0f,          1.0f,        -1.87516732f,  0.91487519f),
            floatArrayOf( 0.06745527f,  0.0f,        -0.06745527f, -1.23305023f,  0.61847416f),
            floatArrayOf( 1.0f,         2.0f,          1.0f,        -1.65723018f,  0.76553175f)
        )
        var x = mag
        for (sec in sos) {
            val b0 = sec[0]; val b1 = sec[1]; val b2 = sec[2]
            val a1 = sec[3]; val a2 = sec[4]
            val y = FloatArray(n)
            var w1 = 0f; var w2 = 0f
            for (i in 0 until n) {
                val w0 = x[i] - a1 * w1 - a2 * w2
                y[i] = b0 * w0 + b1 * w1 + b2 * w2
                w2 = w1; w1 = w0
            }
            x = y
        }
        return x
    }

    private fun accFeatures(accMag: FloatArray): FloatArray {
        val out = FloatArray(4)
        if (accMag.isEmpty()) return out
        out[0] = accMag.average().toFloat()                         // mag_mean (≈0 after DC removal)
        out[1] = accMag.stdDev()                                    // mag_std
        out[2] = accMag.map { it * it }.average().toFloat()         // mag_energy
        val mean = out[0]
        var zc = 0
        for (i in 1 until accMag.size) {
            if ((accMag[i] - mean >= 0f) != (accMag[i - 1] - mean >= 0f)) zc++
        }
        out[3] = if (accMag.size > 1) zc.toFloat() / accMag.size else 0f  // zcr
        return out
    }

    // Math helpers
    private fun FloatArray.stdDev(): Float {
        if (size < 2) return 0f
        val m = average()
        return sqrt(map { (it - m) * (it - m) }.average()).toFloat()
    }

    private fun FloatArray.average(): Double = if (isEmpty()) 0.0 else sumOf { it.toDouble() } / size

    private fun linearSlope(arr: FloatArray): Float {
        if (arr.size < 2) return 0f
        val n = arr.size
        val xMean = (n - 1) / 2.0
        val yMean = arr.average()
        var num = 0.0; var den = 0.0
        for (i in arr.indices) {
            num += (i - xMean) * (arr[i] - yMean)
            den += (i - xMean) * (i - xMean)
        }
        return if (den == 0.0) 0f else (num / den).toFloat()
    }
}
