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

    /**
     * Build the (12, 23) feature tensor from raw buffers.
     *
     * @param hrValues    BPM samples (~1 Hz from watch HR callback)
     * @param tempValues  Skin temperature samples (°C)
     * @param edaValues   Sweat-loss / EDA proxy samples
     * @param accX        Raw ACC X samples from Samsung SDK (integer ADC, ~25 Hz)
     * @param accY        Raw ACC Y samples
     * @param accZ        Raw ACC Z samples
     * @return FloatArray of size N_SUBWINDOWS × N_FEATURES in row-major order,
     *         or null if the input is too short to be meaningful.
     */
    fun extract(
        hrValues: FloatArray,
        tempValues: FloatArray,
        edaValues: FloatArray,
        accX: FloatArray = FloatArray(0),
        accY: FloatArray = FloatArray(0),
        accZ: FloatArray = FloatArray(0),
    ): FloatArray? {
        if (hrValues.size < 5) return null   // need at least 5 HR samples

        // Pre-compute band-passed ACC magnitude once for the whole window
        val accMag = bandpassAccMag(accX, accY, accZ)

        val result = FloatArray(N_SUBWINDOWS * N_FEATURES)

        // Distribute samples across 12 sub-windows proportionally
        for (sw in 0 until N_SUBWINDOWS) {
            val hrSeg  = subwindowSlice(hrValues,   sw, N_SUBWINDOWS)
            val edaSeg = subwindowSlice(edaValues,  sw, N_SUBWINDOWS)
            val tmpSeg = subwindowSlice(tempValues, sw, N_SUBWINDOWS)
            val accSeg = subwindowSlice(accMag,     sw, N_SUBWINDOWS)

            val offset = sw * N_FEATURES
            // HRV time (indices 0–3)
            val hrv = hrvTimeFeatures(hrSeg)
            hrv.copyInto(result, offset)
            // HRV freq (indices 4–6) — zero-filled (not enough data from 1 Hz HR)
            // EDA (indices 7–13)
            val eda = edaFeatures(edaSeg)
            eda.copyInto(result, offset + 7)
            // TEMP (indices 14–18)
            val tmp = tempFeatures(tmpSeg)
            tmp.copyInto(result, offset + 14)
            // ACC magnitude (indices 19–22)
            val acc = accFeatures(accSeg)
            acc.copyInto(result, offset + 19)
        }
        return result
    }

    // ---------- Sub-window slicing ------------------------------------------

    private fun subwindowSlice(arr: FloatArray, sw: Int, total: Int): FloatArray {
        if (arr.isEmpty()) return FloatArray(0)
        val start = (arr.size.toLong() * sw / total).toInt()
        val end   = (arr.size.toLong() * (sw + 1) / total).toInt()
        return arr.copyOfRange(start.coerceAtLeast(0), end.coerceAtMost(arr.size))
    }

    // ---------- HRV time-domain (from BPM samples → RR intervals) ----------

    private fun hrvTimeFeatures(bpmSamples: FloatArray): FloatArray {
        val out = FloatArray(4)
        if (bpmSamples.size < 3) return out
        // Convert BPM → RR intervals in milliseconds
        val rr = FloatArray(bpmSamples.size) { 60_000f / bpmSamples[it].coerceAtLeast(1f) }
        out[0] = rr.average().toFloat()                     // meanNN
        out[1] = rr.stdDev()                                // SDNN
        val diffs = FloatArray(rr.size - 1) { abs(rr[it + 1] - rr[it]) }
        out[2] = if (diffs.isEmpty()) 0f else               // RMSSD
            sqrt(diffs.map { it * it }.average()).toFloat()
        out[3] = if (diffs.isEmpty()) 0f else               // pNN50
            diffs.count { it > 50f }.toFloat() / diffs.size * 100f
        return out
    }

    // ---------- EDA features (simplified tonic/phasic) ----------------------

    private fun edaFeatures(eda: FloatArray): FloatArray {
        val out = FloatArray(7)
        if (eda.size < 3) return out
        val mean = eda.average().toFloat()
        // Tonic (SCL) ≈ low-pass: just use the mean and linear slope
        out[0] = mean                                        // scl_mean
        out[1] = linearSlope(eda)                           // scl_slope
        // Phasic (SCR) ≈ signal minus its mean
        val scr = FloatArray(eda.size) { eda[it] - mean }
        out[2] = scr.average().toFloat()                    // scr_mean
        out[3] = scr.stdDev()                               // scr_std
        out[4] = scr.map { abs(it) }.sum() / eda.size      // scr_auc (simplified)
        // SCR peaks = zero-crossings of derivative above zero
        var peaks = 0
        var ampSum = 0f
        for (i in 1 until scr.size - 1) {
            if (scr[i] > scr[i - 1] && scr[i] > scr[i + 1] && scr[i] > 0.01f) {
                peaks++
                ampSum += scr[i]
            }
        }
        out[5] = peaks.toFloat()                            // scr_peaks
        out[6] = if (peaks > 0) ampSum / peaks else 0f     // scr_amp_mean
        return out
    }

    // ---------- Temperature features ----------------------------------------

    private fun tempFeatures(temp: FloatArray): FloatArray {
        val out = FloatArray(5)
        if (temp.isEmpty()) return out
        out[0] = temp.average().toFloat()                   // mean
        out[1] = temp.stdDev()                              // std
        out[2] = linearSlope(temp)                          // slope
        out[3] = temp.min()                                 // min
        out[4] = temp.max()                                 // max
        return out
    }

    // ---------- ACC magnitude features --------------------------------------

    /**
     * Compute tri-axial magnitude, then apply a 4th-order Butterworth band-pass
     * (0.5–10 Hz, mirroring preprocessing.py:filter_acc_mag) using a cascade of
     * two biquad sections derived from the bilinear transform at fs = 25 Hz.
     *
     * Samsung SDK delivers ACC as raw integer ADC values; we cast to Float first.
     */
    private fun bandpassAccMag(
        xArr: FloatArray, yArr: FloatArray, zArr: FloatArray,
    ): FloatArray {
        val n = minOf(xArr.size, yArr.size, zArr.size)
        if (n == 0) return FloatArray(0)
        // Magnitude
        val mag = FloatArray(n) { i ->
            val x = xArr[i]; val y = yArr[i]; val z = zArr[i]
            sqrt(x * x + y * y + z * z)
        }
        if (n < 9) return mag  // too short to filter safely
        // 4th-order Butterworth band-pass 0.5–10 Hz at fs=25 Hz, SOS form.
        // Coefficients pre-computed with scipy.signal.butter(4,(0.5,10),btype='band',fs=25,output='sos')
        // Section 0: b0,b1,b2, a1,a2  (a0 = 1 normalised)
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

    /**
     * ACC magnitude features matching ml/src/features.py:acc_features().
     *   [0] mag_mean   — mean of band-passed magnitude
     *   [1] mag_std    — std-dev
     *   [2] mag_energy — mean squared value
     *   [3] zcr        — zero-crossing rate of mean-centred signal
     */
    private fun accFeatures(accMag: FloatArray): FloatArray {
        val out = FloatArray(4)
        if (accMag.isEmpty()) return out
        out[0] = accMag.average().toFloat()                        // mag_mean
        out[1] = accMag.stdDev()                                   // mag_std
        out[2] = accMag.map { it * it }.average().toFloat()        // mag_energy
        // zero-crossing rate of mean-centred signal
        val mean = out[0]
        var zc = 0
        for (i in 1 until accMag.size) {
            if ((accMag[i] - mean >= 0f) != (accMag[i - 1] - mean >= 0f)) zc++
        }
        out[3] = if (accMag.size > 1) zc.toFloat() / accMag.size else 0f  // zcr
        return out
    }

    // ---------- Math helpers ------------------------------------------------

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
        var num = 0.0
        var den = 0.0
        for (i in arr.indices) {
            num += (i - xMean) * (arr[i] - yMean)
            den += (i - xMean) * (i - xMean)
        }
        return if (den == 0.0) 0f else (num / den).toFloat()
    }
}


