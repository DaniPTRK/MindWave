package com.example.mindwave.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate

@RunWith(AndroidJUnit4::class)
class TfliteSignatureContractInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun openTrainable(): Pair<Interpreter, FlexDelegate> {
        val fd = context.assets.openFd("mindwave_stress_trainable.tflite")
        val buf = java.io.FileInputStream(fd.fileDescriptor).channel
            .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val flex = FlexDelegate()
        val interp = Interpreter(buf, Interpreter.Options().apply { addDelegate(flex) })
        return interp to flex
    }

    @Test
    fun trainableModelExposesExpectedSignatures() {
        val (interp, flex) = openTrainable()
        try {
            val signatures = interp.signatureKeys.toSet()
            assertTrue(signatures.containsAll(setOf("infer", "train", "parameters", "restore", "explain")))

            assertEquals(setOf("x"), interp.getSignatureInputs("infer").toSet())
            assertEquals(setOf("logits"), interp.getSignatureOutputs("infer").toSet())
            assertEquals(setOf("x", "y"), interp.getSignatureInputs("train").toSet())
            assertEquals(setOf("loss"), interp.getSignatureOutputs("train").toSet())
            assertEquals(setOf("x"), interp.getSignatureInputs("explain").toSet())
            assertEquals(setOf("importances"), interp.getSignatureOutputs("explain").toSet())

            val restoreInputs = interp.getSignatureInputs("restore").toList()
            val parameterOutputs = interp.getSignatureOutputs("parameters").toList()
            assertTrue(restoreInputs.isNotEmpty())
            assertEquals(parameterOutputs.size, restoreInputs.size)
            assertTrue(restoreInputs.all { it.startsWith("vals_") })
            assertTrue(parameterOutputs.all { it.startsWith("var_") })
        } finally {
            interp.close()
            flex.close()
        }
    }

    @Test
    fun signatureCallsAcceptTypedArrays() {
        val (interp, flex) = openTrainable()
        try {
            val x = Array(1) { Array(FeatureExtractor.N_SUBWINDOWS) { FloatArray(FeatureExtractor.N_FEATURES) { 0f } } }
            val y = longArrayOf(0L)

            val logits = Array(1) { FloatArray(2) }
            interp.runSignature(mapOf("x" to x), mutableMapOf<String, Any>("logits" to logits), "infer")
            assertEquals(2, logits[0].size)

            val loss = FloatArray(1)
            interp.runSignature(mapOf("x" to x, "y" to y), mutableMapOf<String, Any>("loss" to loss), "train")
            assertTrue(loss[0].isFinite())

            val importances = Array(1) { FloatArray(FeatureExtractor.N_FEATURES) }
            interp.runSignature(mapOf("x" to x), mutableMapOf<String, Any>("importances" to importances), "explain")
            assertEquals(FeatureExtractor.N_FEATURES, importances[0].size)
        } finally {
            interp.close()
            flex.close()
        }
    }
}
