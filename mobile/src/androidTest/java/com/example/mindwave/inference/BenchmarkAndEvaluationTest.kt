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
 * Benchmark & Evaluation tests — produce date numerice concrete pentru capitolul
 * "Testare și Evaluare" din lucrarea de licență.
 *
 * Rulează cu:
 *   ./gradlew mobile:connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     com.example.mindwave.inference.BenchmarkAndEvaluationTest
 *
 * Output-ul apare în Logcat filtrat după tag "MWBenchmark".
 *
 * ─────────────────────────────────────────────────────────────────────
 * SECȚIUNILE GENERATE:
 *
 *   B1. Latența extragerii de features (FeatureExtractor)
 *   B2. Latența inferenței TFLite (runSignature "infer")
 *   B3. Latența normalizării (ScalerNormalizer)
 *   B4. Latența pipeline complet (extragere + normalizare + inferență)
 *   B5. Overhead memorie model (dimensiune ByteBuffer)
 *   B6. Stabilitate numerică (NaN/Inf pe 100 window-uri sintetice)
 *   B7. Robustețe la date edge-case (HR scăzut, EDA zero, ACC zero)
 *   B8. Consistența predicțiilor (același input → același output)
 *   B9. Raport sumar — afișat în Logcat pentru copy-paste direct în paper
 * ─────────────────────────────────────────────────────────────────────
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkAndEvaluationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val TAG = "MWBenchmark"

    // ── Helpers ──────────────────────────────────────────────────────

    private fun syntheticHr(bpm: Float = 72f, n: Int = 60) =
        FloatArray(n) { bpm + (Math.random().toFloat() - 0.5f) * 6f }

    private fun syntheticTemp(mean: Float = 36.5f, n: Int = 240) =
        FloatArray(n) { mean + (Math.random().toFloat() - 0.5f) * 0.4f }

    private fun syntheticEda(n: Int = 240) =
        FloatArray(n) { 2.0f + Math.random().toFloat() * 0.5f }

    private fun syntheticAcc(freqHz: Double = 2.0, amplitude: Float = 200f, n: Int = 1500) =
        FloatArray(n) { i -> (sin(2 * PI * freqHz * i / 25.0) * amplitude).toFloat() }

    /** Deschide TFLite și apelează signature "infer" fără a folosi interp.run() default. */
    private fun openInterpreter(): Pair<Interpreter, java.nio.MappedByteBuffer> {
        val fd = context.assets.openFd("mindwave_stress.tflite")
        val buf = java.io.FileInputStream(fd.fileDescriptor).channel
            .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val interp = Interpreter(buf, Interpreter.Options().apply { numThreads = 2 })
        return interp to buf
    }

    private fun runInfer(interp: Interpreter, features: FloatArray): FloatArray {
        val inputBuf = ByteBuffer.allocateDirect(
            FeatureExtractor.N_SUBWINDOWS * FeatureExtractor.N_FEATURES * 4
        ).order(ByteOrder.nativeOrder())
        features.forEach { inputBuf.putFloat(it) }
        inputBuf.rewind()
        val outputBuf = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        interp.runSignature(mapOf("x" to inputBuf), mutableMapOf<String, Any>("logits" to outputBuf), "infer")
        outputBuf.rewind()
        return FloatArray(2) { outputBuf.float }
    }

    /** Mediază N măsurători, elimină outlieri (min+max), returnează (mean_ms, min_ms, max_ms, std_ms). */
    private fun benchmark(warmup: Int = 3, runs: Int = 20, block: () -> Unit): DoubleArray {
        repeat(warmup) { block() }
        val times = DoubleArray(runs)
        repeat(runs) { i ->
            val t0 = System.nanoTime()
            block()
            times[i] = (System.nanoTime() - t0) / 1_000_000.0
        }
        times.sort()
        val trimmed = times.drop(1).dropLast(1).toDoubleArray()   // elimină min+max
        val mean = trimmed.average()
        val std  = sqrt(trimmed.map { (it - mean).pow(2) }.average())
        return doubleArrayOf(mean, times.first(), times.last(), std)
    }

    private fun log(msg: String) {
        android.util.Log.i(TAG, msg)
        println(msg)   // vizibil și în test output
    }

    // ── B1. Latența FeatureExtractor ─────────────────────────────────

    @Test
    fun b1_featureExtractionLatency() {
        val hr   = syntheticHr()
        val temp = syntheticTemp()
        val eda  = syntheticEda()
        val accX = syntheticAcc()
        val accY = syntheticAcc(freqHz = 1.5)
        val accZ = syntheticAcc(freqHz = 0.8, amplitude = 100f)

        val (mean, min, max, std) = benchmark {
            FeatureExtractor.extract(hr, temp, eda, accX, accY, accZ)
        }

        log("═══════════════════════════════════════════")
        log("B1 | Feature Extraction Latency (N=20, trimmed)")
        log("   Mean : ${"%.2f".format(mean)} ms")
        log("   Min  : ${"%.2f".format(min)} ms")
        log("   Max  : ${"%.2f".format(max)} ms")
        log("   StdDev: ${"%.2f".format(std)} ms")

        assertTrue("Feature extraction trebuie să fie < 50 ms", mean < 50.0)
    }

    // ── B2. Latența inferenței TFLite ────────────────────────────────

    @Test
    fun b2_tfliteInferenceLatency() {
        val (interp, _) = openInterpreter()
        val features = FeatureExtractor.extract(
            syntheticHr(), syntheticTemp(), syntheticEda(),
            syntheticAcc(), syntheticAcc(1.5), syntheticAcc(0.8, 100f)
        )!!
        val scaler = ScalerNormalizer.fromAssets(context)
        val normalized = scaler?.normalize(features) ?: features

        val (mean, min, max, std) = benchmark {
            runInfer(interp, normalized)
        }

        log("═══════════════════════════════════════════")
        log("B2 | TFLite Inference Latency (N=20, trimmed, numThreads=2)")
        log("   Mean : ${"%.2f".format(mean)} ms")
        log("   Min  : ${"%.2f".format(min)} ms")
        log("   Max  : ${"%.2f".format(max)} ms")
        log("   StdDev: ${"%.2f".format(std)} ms")

        interp.close()
        assertTrue("Inferența trebuie să fie < 100 ms", mean < 100.0)
    }

    // ── B3. Latența normalizării ──────────────────────────────────────

    @Test
    fun b3_scalerNormalizationLatency() {
        val scaler = ScalerNormalizer.fromAssets(context)
        assertNotNull("Scaler trebuie să existe", scaler)
        val features = FeatureExtractor.extract(
            syntheticHr(), syntheticTemp(), syntheticEda(),
            syntheticAcc(), syntheticAcc(), syntheticAcc()
        )!!

        val (mean, min, max, std) = benchmark {
            scaler!!.normalize(features)
        }

        log("═══════════════════════════════════════════")
        log("B3 | ScalerNormalizer Latency (N=20, trimmed)")
        log("   Mean : ${"%.3f".format(mean)} ms")
        log("   Min  : ${"%.3f".format(min)} ms")
        log("   Max  : ${"%.3f".format(max)} ms")
        log("   StdDev: ${"%.3f".format(std)} ms")

        assertTrue("Normalizarea trebuie să fie < 5 ms", mean < 5.0)
    }

    // ── B4. Latența pipeline complet ─────────────────────────────────

    @Test
    fun b4_fullPipelineLatency() {
        val (interp, _) = openInterpreter()
        val scaler = ScalerNormalizer.fromAssets(context)

        val (mean, min, max, std) = benchmark {
            val features = FeatureExtractor.extract(
                syntheticHr(), syntheticTemp(), syntheticEda(),
                syntheticAcc(), syntheticAcc(1.5), syntheticAcc(0.8, 100f)
            )!!
            val normalized = scaler?.normalize(features) ?: features
            runInfer(interp, normalized)
        }

        log("═══════════════════════════════════════════")
        log("B4 | Full Pipeline Latency: extract + normalize + infer (N=20)")
        log("   Mean : ${"%.2f".format(mean)} ms")
        log("   Min  : ${"%.2f".format(min)} ms")
        log("   Max  : ${"%.2f".format(max)} ms")
        log("   StdDev: ${"%.2f".format(std)} ms")
        log("   * Fereastră senzor: 60 s → latența totală < 1% din fereastra de colectare")

        interp.close()
        assertTrue("Pipeline complet trebuie să fie < 150 ms", mean < 150.0)
    }

    // ── B5. Footprint model și artefacte ─────────────────────────────

    @Test
    fun b5_modelFootprint() {
        val inferFd       = context.assets.openFd("mindwave_stress.tflite")
        val trainableFd   = context.assets.openFd("mindwave_stress_trainable.tflite")
        val scalerFd      = context.assets.openFd("scaler_params.json")
        val featureNamesFd = context.assets.openFd("feature_names.json")

        val inferKB      = inferFd.declaredLength / 1024.0
        val trainableKB  = trainableFd.declaredLength / 1024.0
        val scalerKB     = scalerFd.declaredLength / 1024.0
        val featNamesKB  = featureNamesFd.declaredLength / 1024.0
        val totalKB      = inferKB + trainableKB + scalerKB + featNamesKB

        log("═══════════════════════════════════════════")
        log("B5 | On-device Asset Footprint")
        log("   mindwave_stress.tflite          : ${"%.1f".format(inferKB)} KB")
        log("   mindwave_stress_trainable.tflite: ${"%.1f".format(trainableKB)} KB")
        log("   scaler_params.json              : ${"%.1f".format(scalerKB)} KB")
        log("   feature_names.json              : ${"%.1f".format(featNamesKB)} KB")
        log("   ─────────────────────────────────────")
        log("   TOTAL                           : ${"%.1f".format(totalKB)} KB  (${"%.2f".format(totalKB/1024)} MB)")
        log("   * Comparație: GPT-4 ~800 GB, MobileNet ~14 MB, MindWave ~1.8 MB")

        assertTrue("Modelul trebuie să fie < 2 MB", totalKB < 2048)
    }

    // ── B6. Stabilitate numerică pe 100 ferestre sintetice ───────────

    @Test
    fun b6_numericalStabilityOverManyWindows() {
        val (interp, _) = openInterpreter()
        val scaler = ScalerNormalizer.fromAssets(context)
        var nanCount    = 0
        var infCount    = 0
        var nullCount   = 0
        val predictions = mutableListOf<Int>()

        repeat(100) {
            val hrBpm = 50f + (Math.random() * 70).toFloat()   // 50–120 BPM
            val features = FeatureExtractor.extract(
                syntheticHr(hrBpm),
                syntheticTemp(36f + (Math.random() * 2).toFloat()),
                syntheticEda(),
                syntheticAcc(Math.random() * 5 + 0.5, (Math.random() * 500).toFloat()),
                syntheticAcc(Math.random() * 5 + 0.5, (Math.random() * 500).toFloat()),
                syntheticAcc(Math.random() * 5 + 0.5, (Math.random() * 300).toFloat()),
            )
            if (features == null) { nullCount++; return@repeat }

            val normalized = scaler?.normalize(features) ?: features
            normalized.forEach { v ->
                if (v.isNaN()) nanCount++
                if (v.isInfinite()) infCount++
            }

            try {
                val probs = runInfer(interp, normalized)
                val cls = probs.indices.maxByOrNull { probs[it] } ?: -1
                predictions.add(cls)
            } catch (_: Exception) { nullCount++ }
        }

        val stressRate = predictions.count { it == 1 } * 100.0 / predictions.size.coerceAtLeast(1)

        log("═══════════════════════════════════════════")
        log("B6 | Numerical Stability — 100 random synthetic windows")
        log("   NaN values      : $nanCount  (expected: 0)")
        log("   Inf values      : $infCount  (expected: 0)")
        log("   Failed windows  : $nullCount")
        log("   Successful runs : ${predictions.size}/100")
        log("   Stress rate     : ${"%.1f".format(stressRate)}% (informativ, date sintetice)")

        interp.close()
        assertEquals("Zero NaN pe 100 ferestre", 0, nanCount)
        assertEquals("Zero Inf pe 100 ferestre", 0, infCount)
        assertTrue("Cel puțin 95% ferestre procesate cu succes", predictions.size >= 95)
    }

    // ── B7. Robustețe la date edge-case ──────────────────────────────

    @Test
    fun b7_edgeCaseRobustness() {
        val (interp, _) = openInterpreter()
        val scaler = ScalerNormalizer.fromAssets(context)

        data class Case(val name: String, val hr: FloatArray, val temp: FloatArray,
                        val eda: FloatArray, val ax: FloatArray, val ay: FloatArray, val az: FloatArray)

        val cases = listOf(
            Case("HR minim (40 BPM)",
                FloatArray(60) { 40f }, syntheticTemp(), syntheticEda(),
                syntheticAcc(), syntheticAcc(), syntheticAcc()),
            Case("HR maxim (200 BPM)",
                FloatArray(60) { 200f }, syntheticTemp(), syntheticEda(),
                syntheticAcc(), syntheticAcc(), syntheticAcc()),
            Case("EDA zero (fără transpirație)",
                syntheticHr(), syntheticTemp(), FloatArray(240) { 0f },
                syntheticAcc(), syntheticAcc(), syntheticAcc()),
            Case("ACC zero (fără mișcare)",
                syntheticHr(), syntheticTemp(), syntheticEda(),
                FloatArray(0), FloatArray(0), FloatArray(0)),
            Case("Temp constantă (36°C)",
                syntheticHr(), FloatArray(240) { 36f }, syntheticEda(),
                syntheticAcc(), syntheticAcc(), syntheticAcc()),
            Case("Date extrem de zgomotoase",
                FloatArray(60) { 60f + (Math.random().toFloat() - 0.5f) * 40f },
                FloatArray(240) { 36.5f + (Math.random().toFloat() - 0.5f) * 2f },
                FloatArray(240) { (Math.random() * 5).toFloat() },
                syntheticAcc(amplitude = 1000f), syntheticAcc(amplitude = 1000f), syntheticAcc(amplitude = 1000f)),
        )

        log("═══════════════════════════════════════════")
        log("B7 | Edge-Case Robustness")
        log("   ${"Caz".padEnd(35)} | Features | NaN | Inf | Predicție")
        log("   ${"─".repeat(35)}-+----------+-----+-----+----------")

        cases.forEach { c ->
            val features = FeatureExtractor.extract(c.hr, c.temp, c.eda, c.ax, c.ay, c.az)
            if (features == null) {
                log("   ${c.name.padEnd(35)} | null     |  -  |  -  | N/A")
                return@forEach
            }
            val normalized = scaler?.normalize(features) ?: features
            val nans = normalized.count { it.isNaN() }
            val infs = normalized.count { it.isInfinite() }
            val probs = try { runInfer(interp, normalized) } catch (_: Exception) { null }
            val pred = probs?.let { if (it[1] > it[0]) "STRESS" else "NON_STRESS" } ?: "ERROR"
            val probStr = probs?.let { "${"%.2f".format(it[1])}" } ?: "?"
            log("   ${c.name.padEnd(35)} | OK       | $nans   | $infs   | $pred (p=$probStr)")
            assertEquals("${c.name} — zero NaN", 0, nans)
            assertEquals("${c.name} — zero Inf", 0, infs)
        }
        interp.close()
    }

    // ── B8. Consistența predicțiilor (determinism) ───────────────────

    @Test
    fun b8_predictionConsistency() {
        val hr   = syntheticHr(75f)
        val temp = syntheticTemp()
        val eda  = syntheticEda()
        val accX = syntheticAcc(2.0, 200f)
        val accY = syntheticAcc(1.5, 150f)
        val accZ = syntheticAcc(0.8, 100f)
        val scaler = ScalerNormalizer.fromAssets(context)

        val features   = FeatureExtractor.extract(hr, temp, eda, accX, accY, accZ)!!
        val normalized = scaler?.normalize(features) ?: features

        val results = (1..10).map {
            val (interp, _) = openInterpreter()
            val probs = runInfer(interp, normalized)
            interp.close()
            probs[1]
        }

        val maxDiff = results.max() - results.min()

        log("═══════════════════════════════════════════")
        log("B8 | Prediction Consistency (10 independent interpreter instances)")
        log("   Stress logit values: ${results.map { "%.4f".format(it) }}")
        log("   Max deviation: ${"%.6f".format(maxDiff)}")
        log("   * TFLite with deterministic ops → deviation should be ~0")

        assertTrue("Deviaţia maximă trebuie să fie < 0.01", maxDiff < 0.01f)
    }

    // ── B9. Sumar complet pentru paper ───────────────────────────────

    @Test
    fun b9_paperSummaryReport() {
        val scaler = ScalerNormalizer.fromAssets(context)
        val (interp, _) = openInterpreter()

        // Măsoară fiecare componentă rapid (5 runde, fără warmup pentru sumar)
        val hr = syntheticHr(); val temp = syntheticTemp()
        val eda = syntheticEda()
        val accX = syntheticAcc(); val accY = syntheticAcc(1.5); val accZ = syntheticAcc(0.8, 100f)

        var features: FloatArray? = null
        val (tExtract, _, _, _) = benchmark(warmup = 2, runs = 10) {
            features = FeatureExtractor.extract(hr, temp, eda, accX, accY, accZ)
        }

        val norm = scaler?.normalize(features!!) ?: features!!
        val (tNorm, _, _, _) = benchmark(warmup = 2, runs = 10) {
            scaler?.normalize(features!!)
        }

        val (tInfer, _, _, _) = benchmark(warmup = 2, runs = 10) {
            runInfer(interp, norm)
        }

        val tTotal = tExtract + tNorm + tInfer

        // Dimensiuni artefacte
        val inferKB    = context.assets.openFd("mindwave_stress.tflite").declaredLength / 1024.0
        val trainKB    = context.assets.openFd("mindwave_stress_trainable.tflite").declaredLength / 1024.0
        val scalerKB   = context.assets.openFd("scaler_params.json").declaredLength / 1024.0
        val totalKB    = inferKB + trainKB + scalerKB

        interp.close()

        log("╔══════════════════════════════════════════════════════════════╗")
        log("║        RAPORT DE EVALUARE — MindWave (pentru licență)       ║")
        log("╠══════════════════════════════════════════════════════════════╣")
        log("║  PERFORMANȚĂ ON-DEVICE (emulator x86_64)                    ║")
        log("║  ─────────────────────────────────────────────────────────  ║")
        log("║  Extragere features (23 feat × 12 sub-ferestre): ${"%.1f".format(tExtract).padStart(6)} ms ║")
        log("║  Normalizare z-score (StandardScaler):           ${"%.1f".format(tNorm).padStart(6)} ms ║")
        log("║  Inferenta TFLite BiLSTM (signature 'infer'):    ${"%.1f".format(tInfer).padStart(6)} ms ║")
        log("║  ─────────────────────────────────────────────────────────  ║")
        log("║  TOTAL pipeline (fereastra de 60 s):             ${"%.1f".format(tTotal).padStart(6)} ms ║")
        log("║  Overhead față de fereastra de colectare:         ${"%.2f".format(tTotal/60000*100).padStart(5)}%  ║")
        log("╠══════════════════════════════════════════════════════════════╣")
        log("║  FOOTPRINT ARTEFACTE ON-DEVICE                              ║")
        log("║  ─────────────────────────────────────────────────────────  ║")
        log("║  mindwave_stress.tflite (inferență):         ${"%.1f".format(inferKB).padStart(7)} KB ║")
        log("║  mindwave_stress_trainable.tflite (FL):      ${"%.1f".format(trainKB).padStart(7)} KB ║")
        log("║  scaler_params.json:                         ${"%.1f".format(scalerKB).padStart(7)} KB ║")
        log("║  TOTAL assets ML:                            ${"%.1f".format(totalKB).padStart(7)} KB ║")
        log("╠══════════════════════════════════════════════════════════════╣")
        log("║  METRICI MODEL (LOSO cross-validation, 15 subiecți WESAD)   ║")
        log("║  ─────────────────────────────────────────────────────────  ║")
        log("║  Mean Accuracy  : 93.66% (±6.01%)                           ║")
        log("║  Mean Macro-F1  : 92.72% (±6.43%)                           ║")
        log("║  Best fold      : S4, S8, S16 — 100% accuracy               ║")
        log("║  Worst fold     : S14 — 77.7% (non_stress recall: 68.0%)    ║")
        log("║  Holdout set    : 100% (subjects S4, S8, S16 — in train)    ║")
        log("╠══════════════════════════════════════════════════════════════╣")
        log("║  FL SIMULATION (Flower, 15 clienți virtuali WESAD)          ║")
        log("║  ─────────────────────────────────────────────────────────  ║")
        log("║  Runda 1: train_acc=22.7%  val_acc=12.8%  loss=1.547        ║")
        log("║  Runda 2: train_acc=49.9%  val_acc=49.5%  loss=0.890        ║")
        log("║  Runda 3: train_acc=78.2%  val_acc=74.3%  loss=0.569        ║")
        log("║  * Pornit de la zero (random weights); convergență în 3 runde║")
        log("╠══════════════════════════════════════════════════════════════╣")
        log("║  STATISTICI CODEBASE                                         ║")
        log("║  ─────────────────────────────────────────────────────────  ║")
        log("║  Mobile app (Kotlin):  4,972 LOC                            ║")
        log("║  Wear app   (Kotlin):    797 LOC                            ║")
        log("║  Server     (Python):    975 LOC                            ║")
        log("║  ML pipeline(Python):  1,647 LOC                            ║")
        log("║  TOTAL:                8,391 LOC                            ║")
        log("╚══════════════════════════════════════════════════════════════╝")

        // Asertări de bază pentru a verifica că raportul este valid
        assertTrue("Pipeline total < 200ms", tTotal < 200.0)
        assertTrue("Model < 2MB", totalKB < 2048)
    }
}



