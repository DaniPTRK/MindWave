package com.example.mindwave.inference

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for FeatureExtractor.
 *
 * Verifies output shape, feature count, and deterministic behaviour
 * given synthetic sensor buffers that mimic a 60s window from the watch.
 *
 */
class FeatureExtractorTest {

    @Test
    fun `extract returns correct shape for valid input`() {
        val hr = FloatArray(60) { 72f + (it % 5) }    // ~1 Hz HR for 60s
        val temp = FloatArray(60) { 32.0f + 0.01f * it }
        val eda = FloatArray(60) { 2.0f + 0.05f * (it % 10) }
        val accX = FloatArray(1500) { 0.1f * (it % 20) }  // ~25 Hz × 60s
        val accY = FloatArray(1500) { 0.05f * (it % 15) }
        val accZ = FloatArray(1500) { 9.8f + 0.01f * (it % 10) }

        val result = FeatureExtractor.extract(hr, temp, eda, accX, accY, accZ)

        assertNotNull("Extract should return non-null for valid data", result)
        assertEquals(
            "Feature tensor size should276 (12 windows * 23 features)",
            FeatureExtractor.N_SUBWINDOWS * FeatureExtractor.N_FEATURES,
            result!!.size
        )
    }

    @Test
    fun `extract returns null for very short HR`() {
        val hr = FloatArray(3) { 70f }  // < 5 samples
        val temp = FloatArray(60) { 32f }
        val eda = FloatArray(60) { 2f }

        val result = FeatureExtractor.extract(hr, temp, eda)
        assertNull("Should return null when HR has fewer than 5 samples", result)
    }

    @Test
    fun `constants match Python pipeline`() {
        assertEquals(12, FeatureExtractor.N_SUBWINDOWS)
        assertEquals(23, FeatureExtractor.N_FEATURES)
        assertEquals(60, FeatureExtractor.WINDOW_SEC)
        assertEquals(5, FeatureExtractor.SUBWINDOW_SEC)
    }

    @Test
    fun `output is deterministic`() {
        val hr = FloatArray(60) { 75f }
        val temp = FloatArray(60) { 33f }
        val eda = FloatArray(60) { 3f }
        val accX = FloatArray(1500) { 0.5f }
        val accY = FloatArray(1500) { 0.3f }
        val accZ = FloatArray(1500) { 9.8f }

        val r1 = FeatureExtractor.extract(hr, temp, eda, accX, accY, accZ)
        val r2 = FeatureExtractor.extract(hr, temp, eda, accX, accY, accZ)

        assertArrayEquals("Same input should produce identical output", r1, r2, 0f)
    }

    @Test
    fun `no NaN or Inf in output`() {
        val hr = FloatArray(60) { 72f + (it % 3) }
        val temp = FloatArray(60) { 32.5f }
        val eda = FloatArray(60) { 2.5f + 0.1f * (it % 5) }
        val accX = FloatArray(1500) { 0.2f * (it % 10) }
        val accY = FloatArray(1500) { 0.1f * (it % 8) }
        val accZ = FloatArray(1500) { 9.8f }

        val result = FeatureExtractor.extract(hr, temp, eda, accX, accY, accZ)!!
        for (i in result.indices) {
            assertFalse("Feature[$i] should not be NaN", result[i].isNaN())
            assertFalse("Feature[$i] should not be Inf", result[i].isInfinite())
        }
    }

    @Test
    fun `empty ACC still produces valid output`() {
        // Watch may not have ACC available
        val hr = FloatArray(60) { 70f }
        val temp = FloatArray(60) { 32f }
        val eda = FloatArray(60) { 2f }

        val result = FeatureExtractor.extract(hr, temp, eda)
        assertNotNull(result)
        assertEquals(276, result!!.size)
        // ACC features (indices 19-22 of each sub-window) should be zero
        for (sw in 0 until 12) {
            val offset = sw * 23
            assertEquals("ACC mean should be 0 when no ACC data", 0f, result[offset + 19], 0.001f)
            assertEquals("ACC std should be 0 when no ACC data", 0f, result[offset + 20], 0.001f)
        }
    }

    @Test
    fun `HRV features use correct RR interval formula`() {
        // Fixed 72 BPM → RR = 60000/72 ≈ 833.33ms
        val hr = FloatArray(60) { 72f }
        val temp = FloatArray(60) { 32f }
        val eda = FloatArray(60) { 2f }

        val result = FeatureExtractor.extract(hr, temp, eda)!!
        // First sub-window: meanNN (index 0) should be ~833
        val meanNN = result[0]
        assertEquals("meanNN for 72 BPM should be ~833ms", 833.33f, meanNN, 1f)
    }

    @Test
    fun `temp features contain correct mean for constant temp`() {
        val hr = FloatArray(60) { 72f }
        val temp = FloatArray(60) { 35.0f }
        val eda = FloatArray(60) { 2f }

        val result = FeatureExtractor.extract(hr, temp, eda)!!
        // Temp mean is at index 14 in each sub-window
        val tempMean = result[14]
        assertEquals("Temp mean should match input", 35.0f, tempMean, 0.01f)
    }
}

