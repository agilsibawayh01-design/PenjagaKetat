package com.mas.strictguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NsfwAutoBlockTest {
    private val min = 60_000L

    @Test
    fun fiveSeparateEventsInWindowReach() {
        var ev = emptyList<NsfwAutoBlock.Ev>()
        var last: NsfwAutoBlock.Outcome? = null
        for (i in 0 until 5) {
            last = NsfwAutoBlock.apply(ev, "d|x.com", i * 2 * min)
            ev = last.events
        }
        assertTrue(last!!.reached)
        assertTrue(last.events.isEmpty())
    }

    @Test
    fun eventsTooCloseAreNotCounted() {
        val a = NsfwAutoBlock.apply(emptyList(), "d|x.com", 0L)
        val b = NsfwAutoBlock.apply(a.events, "d|x.com", 10_000L)
        assertFalse(b.accepted)
        assertEquals(1, b.count)
    }

    @Test
    fun outsideWindowDoesNotAccumulate() {
        var ev = emptyList<NsfwAutoBlock.Ev>()
        for (i in 0 until 4) ev = NsfwAutoBlock.apply(ev, "d|x.com", i * 4 * min).events
        val o = NsfwAutoBlock.apply(ev, "d|x.com", 16 * min)
        assertFalse(o.reached)
    }

    @Test
    fun differentSourcesCountSeparately() {
        var ev = emptyList<NsfwAutoBlock.Ev>()
        for (i in 0 until 4) ev = NsfwAutoBlock.apply(ev, "d|a.com", i * 2 * min).events
        val o = NsfwAutoBlock.apply(ev, "d|b.com", 9 * min)
        assertFalse(o.reached)
        assertEquals(1, o.count)
    }

    @Test
    fun sourceParsing() {
        assertEquals("contoh.co.id", NsfwSource.registrable("video.contoh.co.id"))
        assertEquals("contoh.com", NsfwSource.registrable("a.b.contoh.com"))
        assertEquals("contoh.com", NsfwSource.hostOf("https://www.contoh.com/path?q=1"))
        assertNull(NsfwSource.hostOf("Search or type URL"))
        assertEquals("Nama Channel", NsfwSource.titleFromCombined("Nama Channel, 12,3K subscribers"))
        assertEquals("Berita Kita", NsfwSource.titleFromCombined("Berita Kita, 1.234 pelanggan"))
        assertTrue(NsfwSource.isSubtitle("12 345 subscribers"))
        assertFalse(NsfwSource.isSubtitle("12 members"))
        assertFalse(NsfwSource.domainAllowed("google.com"))
        assertFalse(NsfwSource.domainAllowed("192.168.1.1"))
    }
}

class NsfwChatTest {
    @org.junit.Test
    fun subtitleKinds() {
        org.junit.Assert.assertEquals("c", NsfwSource.subtitleKind("12,3K subscribers"))
        org.junit.Assert.assertEquals("g", NsfwSource.subtitleKind("45 anggota, 3 online"))
        org.junit.Assert.assertEquals("b", NsfwSource.subtitleKind("bot"))
        org.junit.Assert.assertEquals("u", NsfwSource.subtitleKind("terakhir dilihat baru saja"))
        org.junit.Assert.assertNull(NsfwSource.subtitleKind("Pesan"))
    }

    @org.junit.Test
    fun allowMatching() {
        val allow = setOf("kajian dt", "mas pram")
        org.junit.Assert.assertTrue(NsfwSource.titleAllowed("Grup Kajian DT Surabaya", allow))
        org.junit.Assert.assertTrue(NsfwSource.titleAllowed("Mas Pram", allow))
        org.junit.Assert.assertFalse(NsfwSource.titleAllowed("Channel Asing", allow))
        org.junit.Assert.assertFalse(NsfwSource.titleAllowed("Apa saja", emptySet()))
    }
}

class NsfwSplitTest {
    @org.junit.Test
    fun splitCombined() {
        org.junit.Assert.assertEquals("Admin DT Surabaya 7" to "g", NsfwSource.splitCombined("Admin DT Surabaya 7, 8 members"))
        org.junit.Assert.assertEquals("Mas Pram" to "u", NsfwSource.splitCombined("Mas Pram\nterakhir dilihat baru saja"))
        org.junit.Assert.assertNull(NsfwSource.splitCombined("Halo, apa kabar"))
    }
}
