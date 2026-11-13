package com.mas.strictguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NsfwScopeTest {
    @Test
    fun newAppWindow() {
        val now = 100L * 86_400_000L
        assertTrue(NsfwScope.isNew(now - 3L * 86_400_000L, now))
        assertFalse(NsfwScope.isNew(now - 30L * 86_400_000L, now))
        assertFalse(NsfwScope.isNew(now + 1000L, now))
    }
}
