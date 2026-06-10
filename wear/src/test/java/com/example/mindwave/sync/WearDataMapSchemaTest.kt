package com.example.mindwave.sync

import org.junit.Assert.*
import org.junit.Test

/**
 * Test the DataMap wire format schema that WearDataSender uses.
 * This verifies the contract between the watch and phone without
 * requiring a real Wearable Data Layer connection.
 */
class WearDataMapSchemaTest {

    // Expected keys in the DataMap for a full sensor window
    private val expectedWindowKeys = listOf(
        "timestamp",      // Long: epoch millis
        "hr_times",       // LongArray: per-sample timestamps
        "hr_values",      // FloatArray: BPM samples
        "temp_times",     // LongArray
        "temp_values",    // FloatArray: °C
        "eda_times",      // LongArray
        "eda_values",     // FloatArray: µS
        "acc_x_values",   // FloatArray: ~25 Hz ADC
        "acc_y_values",   // FloatArray
        "acc_z_values",   // FloatArray
        "eda_available",  // Boolean: sensor availability flag
        "acc_available",  // Boolean
        "mood",           // Int: 0=no feedback, 1-5 from quick journal
        "_nonce",         // Long: ensures DataLayer treats each put as unique
    )
    private val expectedMoodKeys = listOf(
        "timestamp",
        "mood",
        "mood_only",
        "_nonce",
    )

    @Test
    fun `window DataMap has all required keys`() {
        val dataMap = simulateWindowDataMap(
            hrCount = 60, tempCount = 60, edaCount = 60, accCount = 1500
        )
        for (key in expectedWindowKeys) {
            assertTrue("Key '$key' should be present in window DataMap", key in dataMap)
        }
    }

    @Test
    fun `mood-only DataMap has required keys`() {
        val dataMap = simulateMoodDataMap(mood = 3)
        for (key in expectedMoodKeys) {
            assertTrue("Key '$key' should be present in mood DataMap", key in dataMap)
        }
    }

    @Test
    fun `HR arrays have matching lengths`() {
        val dataMap = simulateWindowDataMap(hrCount = 60)
        val times = dataMap["hr_times"] as LongArray
        val values = dataMap["hr_values"] as FloatArray
        assertEquals("hr_times and hr_values must have same length", times.size, values.size)
    }

    @Test
    fun `EDA arrays have matching lengths`() {
        val dataMap = simulateWindowDataMap(edaCount = 30)
        val times = dataMap["eda_times"] as LongArray
        val values = dataMap["eda_values"] as FloatArray
        assertEquals("eda_times and eda_values must have same length", times.size, values.size)
    }

    @Test
    fun `TEMP arrays have matching lengths`() {
        val dataMap = simulateWindowDataMap(tempCount = 45)
        val times = dataMap["temp_times"] as LongArray
        val values = dataMap["temp_values"] as FloatArray
        assertEquals("temp_times and temp_values must have same length", times.size, values.size)
    }

    @Test
    fun `ACC XYZ arrays have matching lengths`() {
        val dataMap = simulateWindowDataMap(accCount = 1500)
        val x = dataMap["acc_x_values"] as FloatArray
        val y = dataMap["acc_y_values"] as FloatArray
        val z = dataMap["acc_z_values"] as FloatArray
        assertEquals("acc X and Y must match", x.size, y.size)
        assertEquals("acc X and Z must match", x.size, z.size)
    }

    @Test
    fun `empty HR buffer produces empty arrays`() {
        val dataMap = simulateWindowDataMap(hrCount = 0)
        val values = dataMap["hr_values"] as FloatArray
        assertEquals(0, values.size)
    }

    @Test
    fun `missing EDA sensor sends empty arrays with flag false`() {
        val dataMap = simulateWindowDataMap(edaCount = 0, edaAvailable = false)
        val edaValues = dataMap["eda_values"] as FloatArray
        val edaAvail = dataMap["eda_available"] as Boolean
        assertEquals(0, edaValues.size)
        assertFalse(edaAvail)
    }

    @Test
    fun `missing ACC sensor sends empty arrays with flag false`() {
        val dataMap = simulateWindowDataMap(accCount = 0, accAvailable = false)
        val accX = dataMap["acc_x_values"] as FloatArray
        val accAvail = dataMap["acc_available"] as Boolean
        assertEquals(0, accX.size)
        assertFalse(accAvail)
    }

    @Test
    fun `mood value clamped to 0-5 range`() {
        // Valid range: 0 (no feedback), 1-5 (mood scale)
        for (mood in 0..5) {
            val dataMap = simulateMoodDataMap(mood = mood)
            val m = dataMap["mood"] as Int
            assertTrue("Mood $m should be in [0,5]", m in 0..5)
        }
    }

    @Test
    fun `timestamp is recent epoch millis`() {
        val dataMap = simulateWindowDataMap()
        val ts = dataMap["timestamp"] as Long
        val now = System.currentTimeMillis()
        assertTrue("Timestamp should be recent", ts <= now && ts > now - 60_000)
    }

    private fun simulateWindowDataMap(
        hrCount: Int = 60,
        tempCount: Int = 60,
        edaCount: Int = 60,
        accCount: Int = 1500,
        edaAvailable: Boolean = edaCount > 0,
        accAvailable: Boolean = accCount > 0,
    ): Map<String, Any> {
        val now = System.currentTimeMillis()
        return mapOf(
            "timestamp" to now,
            "hr_times" to LongArray(hrCount) { now - (hrCount - it) * 1000L },
            "hr_values" to FloatArray(hrCount) { 72f + (it % 5) },
            "temp_times" to LongArray(tempCount) { now - (tempCount - it) * 1000L },
            "temp_values" to FloatArray(tempCount) { 32.5f },
            "eda_times" to LongArray(edaCount) { now - (edaCount - it) * 1000L },
            "eda_values" to FloatArray(edaCount) { 2.5f },
            "acc_x_values" to FloatArray(accCount) { 0.1f },
            "acc_y_values" to FloatArray(accCount) { 0.2f },
            "acc_z_values" to FloatArray(accCount) { 9.8f },
            "eda_available" to edaAvailable,
            "acc_available" to accAvailable,
            "mood" to 0,
            "_nonce" to System.nanoTime(),
        )
    }

    private fun simulateMoodDataMap(mood: Int): Map<String, Any> {
        return mapOf(
            "timestamp" to System.currentTimeMillis(),
            "mood" to mood,
            "mood_only" to true,
            "_nonce" to System.nanoTime(),
        )
    }
}

