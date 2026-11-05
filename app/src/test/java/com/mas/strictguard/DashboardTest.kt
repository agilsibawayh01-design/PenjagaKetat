package com.mas.strictguard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardTest {
    @Test fun durationFormatting() {
        assertEquals("0m", Dashboard.dur(0))
        assertEquals("<1m", Dashboard.dur(59_000))
        assertEquals("5m", Dashboard.dur(5 * 60_000L))
        assertEquals("1j 5m", Dashboard.dur(65 * 60_000L))
    }

    @Test fun percentChange() {
        assertEquals(50, StatsMath.percentChange(150, 100))
        assertEquals(-50, StatsMath.percentChange(50, 100))
        assertEquals(null, StatsMath.percentChange(10, 0))
    }

    @Test fun barHeightScalesAndClamps() {
        assertEquals(0, StatsMath.barLength(0, 100, 20))
        assertEquals(20, StatsMath.barLength(100, 100, 20))
        assertEquals(10, StatsMath.barLength(50, 100, 20))
        assertEquals(0, StatsMath.barLength(5, 0, 20))
        assertEquals(1, StatsMath.barLength(1, 1000, 20))
    }
}
