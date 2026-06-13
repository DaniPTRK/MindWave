package com.example.mindwave.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Instrumented tests for the ACC feature pipeline.
 *
 * Run on an emulator or device with:
 *   ./gradlew mobile:connectedAndroidTest
 *   (or right-click this file in Android Studio → Run)
 *
 * What is covered:
 *   1. FeatureExtractor — shape, bounds, ACC population, backward compat
 *   2. Band-pass filter — DC removal, frequency attenuation
 *   3. ScalerNormalizer — loads from assets, correctly normalises
 *   4. TFLite inference  — loads mindwave_stress.tflite, output is valid probability
 *   5. Full pipeline     — synthetic SensorWindow → feature tensor → TFLite → stress score
 */
@RunWith(AndroidJUnit4::class)
class FeatureExtractorInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Generate a realistic 60 s HR buffer (60 samples @ 1 Hz). */
    private fun syntheticHr(bpm: Float = 72f, noiseSd: Float = 3f) =
        FloatArray(60) { bpm + (Math.random().toFloat() * 2 - 1f) * noiseSd }

    /** Generate 60 s TEMP buffer (~4 Hz → 240 samples). */
    private fun syntheticTemp(mean: Float = 36.5f) =
        FloatArray(240) { mean + (Math.random().toFloat() * 0.2f - 0.1f) }

    /** Generate 60 s EDA buffer (~4 Hz → 240 samples). */
    private fun syntheticEda(mean: Float = 2.0f) =
        FloatArray(240) { mean + (Math.random().toFloat() * 0.5f) }

    /**
     * Generate 60 s ACC buffer (~25 Hz → 1500 samples).
     * [freqHz] is the dominant motion frequency (within 0.5–10 Hz band-pass).
     */
    private fun syntheticAcc(
        freqHz: Double = 1.0,
        amplitude: Float = 100f,
        n: Int = 1500,
    ) = FloatArray(n) { i -> (sin(2 * PI * freqHz * i / 25.0) * amplitude).toFloat() }

    /** Zero ACC (simulates missing sensor — backward-compat scenario). */
    private fun emptyAcc() = FloatArray(0)

    // -------------------------------------------------------------------------
    // 1. Shape & size
    // -------------------------------------------------------------------------

    @Test
    fun outputHasCorrectSize() {
        val features = FeatureExtractor.extract(
            hrValues   = syntheticHr(),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX       = syntheticAcc(),
            accY       = syntheticAcc(freqHz = 0.8),
            accZ       = syntheticAcc(freqHz = 1.2, amplitude = 50f),
        )
        assertNotNull("extract() must not return null for valid input", features)
        assertEquals(
            "Output must be N_SUBWINDOWS × N_FEATURES = ${FeatureExtractor.N_SUBWINDOWS * FeatureExtractor.N_FEATURES}",
            FeatureExtractor.N_SUBWINDOWS * FeatureExtractor.N_FEATURES,
            features!!.size,
        )
    }

    @Test
    fun returnsNullForTooFewHrSamples() {
        val features = FeatureExtractor.extract(
            hrValues   = FloatArray(3) { 70f }, // < 5 required
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX       = syntheticAcc(),
            accY       = syntheticAcc(),
            accZ       = syntheticAcc(),
        )
        assertNull("extract() must return null when HR samples < 5", features)
    }

    // -------------------------------------------------------------------------
    // 2. ACC features are populated when ACC data is provided
    // -------------------------------------------------------------------------

    @Test
    fun accFeaturesAreNonZeroWhenDataPresent() {
        val features = FeatureExtractor.extract(
            hrValues   = syntheticHr(),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX       = syntheticAcc(freqHz = 2.0, amplitude = 200f),
            accY       = syntheticAcc(freqHz = 2.0, amplitude = 200f),
            accZ       = FloatArray(1500) { 980f }, // static gravity-like offset
        )!!

        // Check all 12 sub-windows have non-zero ACC features
        for (sw in 0 until FeatureExtractor.N_SUBWINDOWS) {
            val base = sw * FeatureExtractor.N_FEATURES
            val magMean   = features[base + 19]
            val magStd    = features[base + 20]
            val magEnergy = features[base + 21]

            assertTrue(
                "Sub-window $sw: acc_mag_mean ($magMean) should be > 0 with motion signal",
                magMean > 0f,
            )
            assertTrue(
                "Sub-window $sw: acc_mag_energy ($magEnergy) should be > 0",
                magEnergy > 0f,
            )
            // std may be near 0 after DC removal if signal is nearly constant per sub-window,
            // but energy must be non-zero
            assertFalse(
                "Sub-window $sw: acc features must not all be NaN",
                magMean.isNaN() || magStd.isNaN() || magEnergy.isNaN(),
            )
        }
    }

    @Test
    fun accFeaturesAreZeroWhenNoDataProvided() {
        // Default empty ACC → backward compat, indices 19–22 should be 0
        val features = FeatureExtractor.extract(
            hrValues   = syntheticHr(),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            // accX/Y/Z omitted — default to FloatArray(0)
        )!!

        for (sw in 0 until FeatureExtractor.N_SUBWINDOWS) {
            val base = sw * FeatureExtractor.N_FEATURES
            assertEquals("acc_mag_mean should be 0 when no ACC data",   0f, features[base + 19])
            assertEquals("acc_mag_std should be 0 when no ACC data",    0f, features[base + 20])
            assertEquals("acc_mag_energy should be 0 when no ACC data", 0f, features[base + 21])
            assertEquals("acc_mag_zcr should be 0 when no ACC data",    0f, features[base + 22])
        }
    }

    // -------------------------------------------------------------------------
    // 3. Band-pass filter behaviour
    // -------------------------------------------------------------------------

    @Test
    fun bandPassAttenuatesDcOffset() {
        // A large constant offset (DC) should be removed by the 0.5 Hz high-pass component
        val dcOffset = 10_000f
        val accDc = FloatArray(1500) { dcOffset }

        val features = FeatureExtractor.extract(
            hrValues   = syntheticHr(),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX       = accDc,
            accY       = accDc,
            accZ       = accDc,
        )!!

        // After band-pass, mag_mean of filtered signal should be near zero
        // (DC removed). Allow ±5% of original DC
        val magMean = features[19]
        assertTrue(
            "Band-pass should remove DC: mag_mean=$magMean, original DC magnitude≈${sqrt(3f) * dcOffset}",
            abs(magMean) < 0.05f * sqrt(3f) * dcOffset,
        )
    }

    @Test
    fun bandPassPassesMotionFrequency() {
        // A 2 Hz sine (well inside 0.5–10 Hz passband) should survive the filter
        val amplitude = 1000f
        val acc2Hz = syntheticAcc(freqHz = 2.0, amplitude = amplitude, n = 1500)

        val features = FeatureExtractor.extract(
            hrValues   = syntheticHr(),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX       = acc2Hz,
            accY       = FloatArray(1500) { 0f },
            accZ       = FloatArray(1500) { 0f },
        )!!

        // Energy should be substantial — at least 10% of input energy
        val magEnergy = features[21]
        val inputEnergy = acc2Hz.map { it * it }.average().toFloat()
        assertTrue(
            "2 Hz signal should pass the band-pass (energy=$magEnergy, input=$inputEnergy)",
            magEnergy > 0.1f * inputEnergy,
        )
    }

    @Test
    fun bandPassAttenuatesAboveCutoff() {
        // 11 Hz sine (above 10 Hz cutoff at fs=25 Hz) should be strongly attenuated.
        // At fs=25 Hz, 11 Hz is above the 10 Hz cutoff but below Nyquist (12.5 Hz).
        val amplitude = 1000f
        val acc11Hz = syntheticAcc(freqHz = 11.0, amplitude = amplitude, n = 1500)
        val acc2Hz  = syntheticAcc(freqHz = 2.0,  amplitude = amplitude, n = 1500)

        val features11 = FeatureExtractor.extract(
            hrValues   = syntheticHr(),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX = acc11Hz, accY = FloatArray(1500), accZ = FloatArray(1500),
        )!!
        val features2 = FeatureExtractor.extract(
            hrValues   = syntheticHr(),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX = acc2Hz, accY = FloatArray(1500), accZ = FloatArray(1500),
        )!!

        val energy11 = features11[21]
        val energy2  = features2[21]

        assertTrue(
            "11 Hz signal energy ($energy11) should be less than 2 Hz signal energy ($energy2)",
            energy11 < energy2,
        )
    }

    // -------------------------------------------------------------------------
    // 4. HRV, EDA, TEMP features are not disturbed by adding ACC
    // -------------------------------------------------------------------------

    @Test
    fun addingAccDoesNotChangeOtherFeatures() {
        val hr   = syntheticHr()
        val temp = syntheticTemp()
        val eda  = syntheticEda()

        val withoutAcc = FeatureExtractor.extract(hr, temp, eda)!!
        val withAcc    = FeatureExtractor.extract(
            hr, temp, eda,
            accX = syntheticAcc(),
            accY = syntheticAcc(),
            accZ = syntheticAcc(),
        )!!

        // Indices 0–18 must be identical (same HR, EDA, TEMP computation path)
        for (sw in 0 until FeatureExtractor.N_SUBWINDOWS) {
            val base = sw * FeatureExtractor.N_FEATURES
            for (fi in 0..18) {
                assertEquals(
                    "Sub-window $sw, feature $fi should be unchanged by adding ACC",
                    withoutAcc[base + fi],
                    withAcc[base + fi],
                    0.0001f,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // 5. No NaN or Inf in output
    // -------------------------------------------------------------------------

    @Test
    fun noNaNOrInfInOutput() {
        val features = FeatureExtractor.extract(
            hrValues   = syntheticHr(),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX       = syntheticAcc(),
            accY       = syntheticAcc(),
            accZ       = syntheticAcc(),
        )!!

        features.forEachIndexed { i, v ->
            val sw = i / FeatureExtractor.N_FEATURES
            val fi = i % FeatureExtractor.N_FEATURES
            assertFalse("Feature [$sw][$fi] is NaN",  v.isNaN())
            assertFalse("Feature [$sw][$fi] is Inf",  v.isInfinite())
        }
    }

    // -------------------------------------------------------------------------
    // 6. ScalerNormalizer loads and works
    // -------------------------------------------------------------------------

    @Test
    fun scalerLoadsFromAssets() {
        val scaler = ScalerNormalizer.fromAssets(context)
        assertNotNull("ScalerNormalizer must load from assets/scaler_params.json", scaler)
    }

    @Test
    fun scalerOutputHasCorrectSize() {
        val scaler = ScalerNormalizer.fromAssets(context) ?: return
        val features = FeatureExtractor.extract(
            hrValues   = syntheticHr(),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX       = syntheticAcc(),
            accY       = syntheticAcc(),
            accZ       = syntheticAcc(),
        )!!
        val normalized = scaler.normalize(features)
        assertEquals("Scaler output must have same size as input", features.size, normalized.size)
    }

    @Test
    fun scalerDoesNotProduceNaNOrInf() {
        val scaler = ScalerNormalizer.fromAssets(context) ?: return
        val features = FeatureExtractor.extract(
            hrValues   = syntheticHr(),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX       = syntheticAcc(),
            accY       = syntheticAcc(),
            accZ       = syntheticAcc(),
        )!!
        val normalized = scaler.normalize(features)
        normalized.forEachIndexed { i, v ->
            val sw = i / FeatureExtractor.N_FEATURES
            val fi = i % FeatureExtractor.N_FEATURES
            assertFalse("Normalized feature [$sw][$fi] is NaN", v.isNaN())
            assertFalse("Normalized feature [$sw][$fi] is Inf", v.isInfinite())
        }
    }

    // -------------------------------------------------------------------------
    // 7. TFLite model loads and produces valid output
    // -------------------------------------------------------------------------

    @Test
    fun tfliteModelLoadsFromAssets() {
        val fd = context.assets.openFd("mindwave_stress.tflite")
        val buf = java.io.FileInputStream(fd.fileDescriptor).channel
            .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val interp = Interpreter(buf)
        assertNotNull("TFLite interpreter must not be null", interp)
        interp.close()
    }

    @Test
    fun tfliteOutputIsTwoProbabilities() {
        val fd = context.assets.openFd("mindwave_stress.tflite")
        val buf = java.io.FileInputStream(fd.fileDescriptor).channel
            .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val interp = Interpreter(buf, Interpreter.Options().apply { numThreads = 2 })

        val features = FeatureExtractor.extract(
            hrValues   = syntheticHr(),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX       = syntheticAcc(),
            accY       = syntheticAcc(),
            accZ       = syntheticAcc(),
        )!!
        val scaler = ScalerNormalizer.fromAssets(context)
        val normalized = scaler?.normalize(features) ?: features

        val inputBuf = ByteBuffer.allocateDirect(
            FeatureExtractor.N_SUBWINDOWS * FeatureExtractor.N_FEATURES * 4
        ).order(ByteOrder.nativeOrder())
        normalized.forEach { inputBuf.putFloat(it) }
        inputBuf.rewind()

        val outputBuf = ByteBuffer.allocateDirect(2 * 4).order(ByteOrder.nativeOrder())

        try {
            val inputs  = mapOf("x" to inputBuf)
            val outputs = mutableMapOf<String, Any>("logits" to outputBuf)
            interp.runSignature(inputs, outputs, "infer")
        } catch (_: Exception) {
            inputBuf.rewind(); outputBuf.rewind()
            interp.run(inputBuf, outputBuf)
        }

        outputBuf.rewind()
        val nonStress = outputBuf.float
        val stress    = outputBuf.float

        assertFalse("P(non_stress) must not be NaN", nonStress.isNaN())
        assertFalse("P(stress) must not be NaN",     stress.isNaN())
        assertTrue("P(non_stress) must be finite",   nonStress.isFinite())
        assertTrue("P(stress) must be finite",       stress.isFinite())

        interp.close()
    }

    // -------------------------------------------------------------------------
    // 8. Full end-to-end pipeline: SensorWindow → features → scaler → TFLite
    // -------------------------------------------------------------------------

    @Test
    fun fullPipelineWithAccDataReturnsValidStressScore() {
        // Simulate a calm session: low HR variability, low EDA, moderate movement
        val stressScore = runPipeline(
            hrBpm        = 65f,
            accAmplitude = 50f,
            freqHz       = 1.0,
        )
        assertNotNull("Pipeline must produce a stress score", stressScore)
        assertTrue("Stress score must be 0 or 1", stressScore in 0..1)
    }

    @Test
    fun fullPipelineWithZeroAccMatchesNonZeroAccStructurally() {
        // Both calls must complete without crashing. The *score* may differ
        // (that's the expected effect of this fix), but both must return valid outputs.
        val scoreWithAcc = runPipeline(accAmplitude = 200f, freqHz = 2.0)
        val scoreWithout = runPipeline(accAmplitude = 0f,   freqHz = 0.0)

        assertNotNull("Score WITH ACC must not be null",    scoreWithAcc)
        assertNotNull("Score WITHOUT ACC must not be null", scoreWithout)

        // Log the difference for manual inspection in the test output
        println("Score WITH ACC:    $scoreWithAcc")
        println("Score WITHOUT ACC: $scoreWithout")
    }

    @Test
    fun pipelineOutputsDifferForHighVsLowMovement() {
        // Run 10 trials each and collect logit values to see if movement
        // actually influences the model output (confirms ACC wiring is live)
        val fd = context.assets.openFd("mindwave_stress.tflite")
        val buf = java.io.FileInputStream(fd.fileDescriptor).channel
            .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val scaler = ScalerNormalizer.fromAssets(context)

        val highMovementLogits = mutableListOf<Float>()
        val lowMovementLogits  = mutableListOf<Float>()

        repeat(5) {
            highMovementLogits += getStressLogit(
                makeInterpreter(buf),
                scaler,
                accAmplitude = 500f, freqHz = 3.0,
            )
            lowMovementLogits += getStressLogit(
                makeInterpreter(buf),
                scaler,
                accAmplitude = 0f, freqHz = 0.0,
            )
        }

        val highMean = highMovementLogits.average()
        val lowMean  = lowMovementLogits.average()

        println("High-movement stress logit mean: $highMean  (values: $highMovementLogits)")
        println("Low-movement  stress logit mean: $lowMean  (values: $lowMovementLogits)")

        assertFalse("High-movement logits contain NaN — inference failed", highMovementLogits.any { it.isNaN() })
        assertFalse("Low-movement logits contain NaN — inference failed",  lowMovementLogits.any  { it.isNaN() })

        // We cannot assert direction (that depends on the model), but they must differ
        // if ACC is correctly wired. Allow only ≤ 1e-4 tolerance before flagging as identical.
        val diff = abs(highMean - lowMean)
        assertTrue(
            "High vs low movement logits should differ (diff=$diff) — " +
            "if this fails ACC features are not reaching the model",
            diff > 1e-4,
        )
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun makeInterpreter(buf: java.nio.MappedByteBuffer): Interpreter {
        val interp = Interpreter(buf.duplicate(), Interpreter.Options().apply { numThreads = 1 })
        // The trainable TFLite model uses LSTM state variables that must be
        // initialised before the first inference call; without this READ_VARIABLE fails.
        try { interp.runSignature(emptyMap(), mutableMapOf(), "initialize") } catch (_: Exception) {}
        return interp
    }

    private fun runPipeline(
        hrBpm: Float        = 72f,
        accAmplitude: Float = 100f,
        freqHz: Double      = 1.0,
    ): Int? {
        val features = FeatureExtractor.extract(
            hrValues   = syntheticHr(bpm = hrBpm),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX       = if (accAmplitude > 0f) syntheticAcc(freqHz, accAmplitude) else emptyAcc(),
            accY       = if (accAmplitude > 0f) syntheticAcc(freqHz, accAmplitude) else emptyAcc(),
            accZ       = if (accAmplitude > 0f) syntheticAcc(freqHz, accAmplitude / 2) else emptyAcc(),
        ) ?: return null

        val scaler     = ScalerNormalizer.fromAssets(context)
        val normalized = scaler?.normalize(features) ?: features

        val fd = context.assets.openFd("mindwave_stress.tflite")
        val modelBuf = java.io.FileInputStream(fd.fileDescriptor).channel
            .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val interp = makeInterpreter(modelBuf)

        return try {
            val inputBuf = ByteBuffer.allocateDirect(
                FeatureExtractor.N_SUBWINDOWS * FeatureExtractor.N_FEATURES * 4
            ).order(ByteOrder.nativeOrder())
            normalized.forEach { inputBuf.putFloat(it) }
            inputBuf.rewind()
            val outputBuf = ByteBuffer.allocateDirect(2 * 4).order(ByteOrder.nativeOrder())

            try {
                interp.runSignature(
                    mapOf("x" to inputBuf),
                    mutableMapOf<String, Any>("logits" to outputBuf),
                    "infer",
                )
            } catch (_: Exception) {
                inputBuf.rewind(); outputBuf.rewind()
                interp.run(inputBuf, outputBuf)
            }

            outputBuf.rewind()
            val probs = FloatArray(2) { outputBuf.float }
            probs.indices.maxByOrNull { probs[it] }
        } catch (_: Exception) {
            null
        } finally {
            interp.close()
        }
    }

    private fun getStressLogit(
        interp: Interpreter,
        scaler: ScalerNormalizer?,
        accAmplitude: Float,
        freqHz: Double,
    ): Float {
        val features = FeatureExtractor.extract(
            hrValues   = syntheticHr(),
            tempValues = syntheticTemp(),
            edaValues  = syntheticEda(),
            accX       = if (accAmplitude > 0f) syntheticAcc(freqHz, accAmplitude) else emptyAcc(),
            accY       = if (accAmplitude > 0f) syntheticAcc(freqHz, accAmplitude) else emptyAcc(),
            accZ       = if (accAmplitude > 0f) syntheticAcc(freqHz, accAmplitude / 2) else emptyAcc(),
        ) ?: return 0f

        val normalized = scaler?.normalize(features) ?: features

        // Use a FloatArray output — avoids ByteBuffer position bugs entirely
        val input  = Array(1) { Array(FeatureExtractor.N_SUBWINDOWS) {
            FloatArray(FeatureExtractor.N_FEATURES)
        }}
        var idx = 0
        for (sw in 0 until FeatureExtractor.N_SUBWINDOWS)
            for (f in 0 until FeatureExtractor.N_FEATURES)
                input[0][sw][f] = normalized[idx++]

        val output = Array(1) { FloatArray(2) }

        return try {
            interp.run(input, output)
            output[0][1]   // stress logit (index 1)
        } catch (e: Exception) {
            println("getStressLogit inference error: ${e.message}")
            Float.NaN
        } finally {
            interp.close()
        }
    }
}





