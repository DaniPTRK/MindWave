package com.example.mindwave.sync

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests sensor buffer flush behaviour.
 *
 * The watch buffers 60s of data, then WearDataSender.sendWindow() flushes
 * all buffers and clears them. These tests verify the expected flush semantics.
 */
class SensorBufferFlushTest {

    /**
     * Simulates a ring-buffer that mimics SensorForegroundService behaviour.
     */
    private class FakeBuffer<T>(private val maxSize: Int = 3600) {
        private val data = mutableListOf<T>()

        fun add(value: T) {
            data.add(value)
            if (data.size > maxSize) data.removeAt(0)
        }

        fun snapshot(): List<T> = ArrayList(data)
        fun clear() = data.clear()
        val size get() = data.size
    }

    @Test
    fun `flush returns all buffered items`() {
        val buffer = FakeBuffer<Float>()
        repeat(60) { buffer.add(it.toFloat()) }

        val snapshot = buffer.snapshot()
        assertEquals(60, snapshot.size)
        assertEquals(0f, snapshot.first(), 0f)
        assertEquals(59f, snapshot.last(), 0f)
    }

    @Test
    fun `buffer is empty after clear`() {
        val buffer = FakeBuffer<Float>()
        repeat(100) { buffer.add(it.toFloat()) }
        buffer.clear()
        assertEquals(0, buffer.size)
        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun `empty buffer produces empty snapshot`() {
        val buffer = FakeBuffer<Float>()
        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun `flush with partial data does not crash`() {
        // Only HR has data, EDA and TEMP empty
        val hr = FakeBuffer<Float>()
        val eda = FakeBuffer<Float>()
        val temp = FakeBuffer<Float>()

        repeat(30) { hr.add(72f) }

        val hrSnap = hr.snapshot()
        val edaSnap = eda.snapshot()
        val tempSnap = temp.snapshot()

        hr.clear(); eda.clear(); temp.clear()

        assertEquals(30, hrSnap.size)
        assertEquals(0, edaSnap.size)
        assertEquals(0, tempSnap.size)
    }

    @Test
    fun `concurrent add and flush simulation`() {
        // Buffer receives data while flush is pending
        val buffer = FakeBuffer<Float>()
        repeat(60) { buffer.add(it.toFloat()) }

        // Flush
        val snapshot = buffer.snapshot()
        buffer.clear()

        // New data arrives after flush
        buffer.add(100f)
        buffer.add(101f)

        // Snapshot should have had 60 items
        assertEquals(60, snapshot.size)
        // Current buffer has only new items
        assertEquals(2, buffer.size)
    }

    @Test
    fun `buffer respects max size`() {
        val buffer = FakeBuffer<Float>(maxSize = 10)
        repeat(20) { buffer.add(it.toFloat()) }
        assertEquals(10, buffer.size)
        // Should contain most recent 10 items
        val snap = buffer.snapshot()
        assertEquals(10f, snap.first(), 0f)
        assertEquals(19f, snap.last(), 0f)
    }

    @Test
    fun `60s window produces expected sample counts`() {
        // HR ~1 Hz -> 60 samples
        // TEMP ~1 Hz -> 60 samples
        // EDA ~1 Hz (Samsung SDK) -> 60 samples
        // ACC ~25 Hz -> 1500 samples
        val hrBuffer = FakeBuffer<Float>()
        val tempBuffer = FakeBuffer<Float>()
        val edaBuffer = FakeBuffer<Float>()
        val accXBuffer = FakeBuffer<Float>()
        val accYBuffer = FakeBuffer<Float>()
        val accZBuffer = FakeBuffer<Float>()

        // Simulate 60s of data at expected rates
        repeat(60) { hrBuffer.add(72f) }
        repeat(60) { tempBuffer.add(32.5f) }
        repeat(60) { edaBuffer.add(2.5f) }
        repeat(1500) { accXBuffer.add(0.1f) }
        repeat(1500) { accYBuffer.add(0.2f) }
        repeat(1500) { accZBuffer.add(9.8f) }

        assertEquals("HR should have ~60 samples", 60, hrBuffer.size)
        assertEquals("TEMP should have ~60 samples", 60, tempBuffer.size)
        assertEquals("EDA should have ~60 samples", 60, edaBuffer.size)
        assertEquals("ACC X should have ~1500 samples", 1500, accXBuffer.size)
        assertEquals("ACC Y should have ~1500 samples", 1500, accYBuffer.size)
        assertEquals("ACC Z should have ~1500 samples", 1500, accZBuffer.size)
    }
}

