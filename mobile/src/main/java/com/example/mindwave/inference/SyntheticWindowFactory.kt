package com.example.mindwave.inference

import com.example.mindwave.sync.MobileDataListenerService
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates synthetic SensorWindows drawn from WESAD wrist-sensor distribution estimates.
 */
object SyntheticWindowFactory {

    /** Approximate WESAD wrist resting HR (BPM) */
    private const val HR_MEAN_BASELINE  = 72f
    private const val HR_STD_BASELINE   = 8f
    /** Approximate WESAD wrist stress HR (BPM) */
    private const val HR_MEAN_STRESS    = 85f
    private const val HR_STD_STRESS     = 12f

    /** EDA resting (µS) */
    private const val EDA_MEAN_BASELINE = 5f
    private const val EDA_STD_BASELINE  = 2f
    /** EDA stress (µS) */
    private const val EDA_MEAN_STRESS   = 12f
    private const val EDA_STD_STRESS    = 5f

    /** Skin temperature (°C) — same for baseline and stress */
    private const val TEMP_MEAN         = 33f
    private const val TEMP_STD          = 0.8f

    /**
     * Generate [count] synthetic windows.
     */
    fun generate(
        count: Int = 100,
        stressFraction: Float = 0.5f,
        seed: Long = 42L,
    ): List<MobileDataListenerService.SensorWindow> {
        val rng = if (seed >= 0) Random(seed) else Random.Default
        val now = System.currentTimeMillis()
        return (0 until count).map { i ->
            val isStress = rng.nextFloat() < stressFraction
            // Stagger timestamps 60 s apart going backwards in time
            val ts = now - (count - i) * 60_000L
            buildWindow(ts, isStress, rng)
        }
    }

    /**
     * Build a single sensor window with synthetic data.
     */
    fun buildWindow(
        timestamp: Long,
        isStress: Boolean,
        rng: Random = Random(timestamp),
    ): MobileDataListenerService.SensorWindow {
        val hrMean  = if (isStress) HR_MEAN_STRESS  else HR_MEAN_BASELINE
        val hrStd   = if (isStress) HR_STD_STRESS   else HR_STD_BASELINE
        val edaMean = if (isStress) EDA_MEAN_STRESS  else EDA_MEAN_BASELINE
        val edaStd  = if (isStress) EDA_STD_STRESS  else EDA_STD_BASELINE

        val hrCount   = 60
        val edaCount  = 240
        val tmpCount  = 6
        val accCount  = 1500

        val hrValues = FloatArray(hrCount) {
            (hrMean + rng.nextGaussian() * hrStd).toFloat().coerceIn(40f, 200f)
        }

        val edaBase  = edaMean + rng.nextGaussian() * edaStd * 0.5
        val edaValues = FloatArray(edaCount) { idx ->
            val drift = edaBase + 0.3 * sin(idx.toDouble() / edaCount * Math.PI)
            (drift + rng.nextGaussian() * edaStd * 0.1).toFloat().coerceAtLeast(0f)
        }

        val tempBase  = TEMP_MEAN + rng.nextGaussian() * TEMP_STD
        val tempValues = FloatArray(tmpCount) { i ->
            (tempBase + i * 0.02f + rng.nextGaussian() * 0.05).toFloat()
        }

        val hasBurst   = isStress && rng.nextFloat() > 0.7f
        val burstStart = rng.nextInt(accCount / 2)
        val burstLen   = 150

        val accXValues = FloatArray(accCount) { i ->
            val burst = if (hasBurst && i in burstStart until (burstStart + burstLen)) (rng.nextGaussian() * 1.5).toFloat() else 0f
            (0.05f * sin(i * 0.1).toFloat() + (rng.nextGaussian() * 0.02).toFloat() + burst)
        }
        val accYValues = FloatArray(accCount) { i ->
            val burst = if (hasBurst && i in burstStart until (burstStart + burstLen)) (rng.nextGaussian() * 1.5).toFloat() else 0f
            (0.03f * cos(i * 0.1).toFloat() + (rng.nextGaussian() * 0.02).toFloat() + burst)
        }
        val accZValues = FloatArray(accCount) { i ->
            val burst = if (hasBurst && i in burstStart until (burstStart + burstLen)) (rng.nextGaussian() * 0.8).toFloat() else 0f
            (9.81f + 0.02f * (i % 5 - 2) + (rng.nextGaussian() * 0.05).toFloat() + burst)
        }

        return MobileDataListenerService.SensorWindow(
            timestamp  = timestamp,
            hrTimes    = LongArray(hrCount)  { timestamp - (hrCount  - it) * 1_000L },
            hrValues   = hrValues,
            tempTimes  = LongArray(tmpCount) { timestamp - (tmpCount - it) * 10_000L },
            tempValues = tempValues,
            edaTimes   = LongArray(edaCount) { timestamp - (edaCount  - it) * 250L },
            edaValues  = edaValues,
            accXValues = accXValues,
            accYValues = accYValues,
            accZValues = accZValues,
            mood       = 0,
        )
    }

    // Use Box-muller as Gaussian generator.
    private fun Random.nextGaussian(): Double {
        val u = nextDouble()
        val v = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u + 1e-12)) * cos(2.0 * Math.PI * v)
    }
}


