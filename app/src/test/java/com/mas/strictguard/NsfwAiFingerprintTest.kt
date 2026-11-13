package com.mas.strictguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NsfwAiFingerprintTest {
    @Test
    fun identicalIsZero() {
        val a = IntArray(512) { it % 255 }
        assertEquals(0.0, NsfwAi.meanDiff(a, a.copyOf()), 0.0001)
    }

    @Test
    fun differentSizeIsMax() {
        assertTrue(NsfwAi.meanDiff(IntArray(3), IntArray(4)) >= 255.0)
    }

    @Test
    fun smallNoiseBelowThreshold() {
        val a = IntArray(512) { 100 }
        val b = IntArray(512) { 101 }
        assertTrue(NsfwAi.meanDiff(a, b) < 2.0)
    }
}
