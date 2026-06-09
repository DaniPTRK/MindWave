package com.example.mindwave.inference

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit test for ScalerNormalizer, verifies standardisation logic.
 */
class ScalerNormalizerTest {

    /**
     * Create a ScalerNormalizer via reflection since constructor is private.
     */
    private fun createScaler(mean: FloatArray, scale: FloatArray): Any {
        val clazz = Class.forName("com.example.mindwave.inference.ScalerNormalizer")
        val ctor = clazz.getDeclaredConstructor(FloatArray::class.java, FloatArray::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(mean, scale)
    }

    private fun invokeNormalize(scaler: Any, features: FloatArray): FloatArray {
        val method = scaler.javaClass.getMethod("normalize", FloatArray::class.java)
        return method.invoke(scaler, features) as FloatArray
    }

    @Test
    fun `normalize produces correct z-scores`() {
        val mean = floatArrayOf(1f, 2f, 3f)
        val scale = floatArrayOf(1f, 2f, 0.5f)
        val scaler = createScaler(mean, scale)

        // Input: one sub-window with 3 features
        val input = floatArrayOf(2f, 4f, 3.5f)
        val result = invokeNormalize(scaler, input)

        assertEquals(1.0f, result[0], 1e-6f)
        assertEquals(1.0f, result[1], 1e-6f)
        assertEquals(1.0f, result[2], 1e-6f)
    }

    @Test
    fun `normalize handles zero scale gracefully`() {
        val mean = floatArrayOf(5f, 10f)
        val scale = floatArrayOf(0f, 2f)
        val scaler = createScaler(mean, scale)

        val input = floatArrayOf(7f, 14f)
        val result = invokeNormalize(scaler, input)

        assertEquals(2.0f, result[0], 1e-6f)
        assertEquals(2.0f, result[1], 1e-6f)
    }

    @Test
    fun `normalize wraps across sub-windows`() {
        // 2 features, 2 sub-windows (4 total elements)
        val mean = floatArrayOf(0f, 10f)
        val scale = floatArrayOf(2f, 5f)
        val scaler = createScaler(mean, scale)

        val input = floatArrayOf(4f, 20f, 6f, 15f)
        val result = invokeNormalize(scaler, input)

        // Sub-window 0: (4-0)/2=2, (20-10)/5=2
        // Sub-window 1: (6-0)/2=3, (15-10)/5=1
        assertEquals(2.0f, result[0], 1e-6f)
        assertEquals(2.0f, result[1], 1e-6f)
        assertEquals(3.0f, result[2], 1e-6f)
        assertEquals(1.0f, result[3], 1e-6f)
    }

    @Test
    fun `normalize returns input unchanged when dimensions mismatch`() {
        val mean = floatArrayOf(1f, 2f, 3f)
        val scale = floatArrayOf(1f, 1f, 1f)
        val scaler = createScaler(mean, scale)

        val input = floatArrayOf(10f, 20f, 30f, 40f, 50f)
        val result = invokeNormalize(scaler, input)

        assertArrayEquals("Mismatched dims should return input unchanged", input, result, 0f)
    }

    @Test
    fun `normalize output has same size as input`() {
        val n = 23
        val mean = FloatArray(n) { it.toFloat() }
        val scale = FloatArray(n) { 1f + it.toFloat() }
        val scaler = createScaler(mean, scale)

        val input = FloatArray(12 * n) { it.toFloat() * 0.1f }
        val result = invokeNormalize(scaler, input)

        assertEquals(input.size, result.size)
    }
}

