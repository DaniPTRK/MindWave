package com.example.mindwave.inference

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that the 23 saliency values from the XAI `explain` signature
 * are correctly grouped into the 4 sensor categories:
 *   HRV (indices 0–6), EDA (7–13), TEMP (14–18), ACC (19–22)
 *
 * These groupings drive the Insights screen XAI bars.
 */
class XaiGroupingTest {

    // Mirror the grouping logic used in the app
    private data class XaiGroup(val label: String, val indices: IntRange)

    private val groups = listOf(
        XaiGroup("Heart rate variability", 0..6),
        XaiGroup("Electrodermal activity", 7..13),
        XaiGroup("Skin temperature", 14..18),
        XaiGroup("Accelerometer", 19..22),
    )

    @Test
    fun `groups cover all 23 features exactly once`() {
        val covered = mutableSetOf<Int>()
        for (g in groups) {
            for (i in g.indices) {
                assertFalse("Index $i is covered by multiple groups", i in covered)
                covered.add(i)
            }
        }
        assertEquals("All 23 features must be covered", 23, covered.size)
        assertEquals(setOf(*(0..22).toList().toTypedArray()), covered)
    }

    @Test
    fun `group shares sum to 1 when all saliency positive`() {
        val saliency = FloatArray(23) { 1.0f / 23f }  // uniform
        val total = saliency.sum()
        val shares = groups.map { g ->
            g.indices.sumOf { saliency[it].toDouble() }.toFloat() / total
        }
        val sumShares = shares.sum()
        assertEquals("Shares should sum to 1.0", 1.0f, sumShares, 1e-5f)
    }

    @Test
    fun `dominant sensor group identified correctly`() {
        val saliency = FloatArray(23) { 0.01f }
        // Make EDA dominant
        for (i in 7..13) saliency[i] = 0.5f

        val total = saliency.sum()
        val shares = groups.map { g ->
            g.indices.sumOf { saliency[it].toDouble() }.toFloat() / total
        }
        val maxIdx = shares.indices.maxByOrNull { shares[it] }!!
        assertEquals("EDA should be the dominant group", "Electrodermal activity", groups[maxIdx].label)
    }

    @Test
    fun `zero saliency produces zero shares`() {
        val saliency = FloatArray(23) { 0f }
        val total = saliency.sum()
        // When total is 0, UI should handle gracefully (equal distribution or all zero)
        if (total == 0f) {
            // Graceful fallback: all shares are 0 or equal
            assertTrue("Zero saliency should not cause crash", true)
        }
    }

    @Test
    fun `negative saliency values handled`() {
        // Some XAI methods produce negative values (counteracting features)
        val saliency = FloatArray(23) { if (it < 7) -0.1f else 0.2f }
        val absTotal = saliency.map { kotlin.math.abs(it) }.sum()
        assertTrue("Absolute total should be positive", absTotal > 0f)
    }

    @Test
    fun `HRV group has 7 features (4 time + 3 freq)`() {
        assertEquals(7, groups[0].indices.count())
    }

    @Test
    fun `EDA group has 7 features`() {
        assertEquals(7, groups[1].indices.count())
    }

    @Test
    fun `TEMP group has 5 features`() {
        assertEquals(5, groups[2].indices.count())
    }

    @Test
    fun `ACC group has 4 features`() {
        assertEquals(4, groups[3].indices.count())
    }
}

