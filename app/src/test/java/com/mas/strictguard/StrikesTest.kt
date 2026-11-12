package com.mas.strictguard

import org.junit.Assert.assertEquals
import org.junit.Test

class StrikesTest {
    @Test
    fun pruneKeepsOnlyRecent() {
        val now = 1_000_000_000L
        val times = listOf(now - Strikes.WINDOW_MS - 1, now - Strikes.WINDOW_MS, now - 1000L, now)
        assertEquals(listOf(now - Strikes.WINDOW_MS, now - 1000L, now), Strikes.prune(times, now))
    }

    @Test
    fun pruneDropsFuture() {
        val now = 5_000L
        assertEquals(emptyList<Long>(), Strikes.prune(listOf(now + 10L), now))
    }
}
