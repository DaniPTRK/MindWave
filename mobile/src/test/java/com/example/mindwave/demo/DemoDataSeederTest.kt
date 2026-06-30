package com.example.mindwave.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDataSeederTest {

    @Test
    fun `single demo reading receives a complete normalized explanation`() {
        val readingId = 73L
        val explanations = DemoDataSeeder.buildXaiForReading(
            readingId = readingId,
            isStress = true,
            seed = 95,
        )

        assertEquals(23, explanations.size)
        assertTrue(explanations.all { it.readingId == readingId })
        assertTrue(explanations.zipWithNext().all { (a, b) -> a.importance >= b.importance })
        assertEquals(1f, explanations.sumOf { it.importance.toDouble() }.toFloat(), 1e-5f)
    }
}
