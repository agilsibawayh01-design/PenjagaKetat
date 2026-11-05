package com.mas.strictguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    @Test fun keywordLongMatchesInsideText() {
        assertEquals("bokep", Rules.matchesKeyword("cari video BOKEP indo", listOf("bokep")))
    }

    @Test fun keywordShortNeedsWordBoundary() {
        assertNotNull(Rules.matchesKeyword("nonton xxx gratis", listOf("xxx")))
        assertNull(Rules.matchesKeyword("abcxxxdef", listOf("xxx")))
    }

    @Test fun keywordWhitelistedWordsAreIgnored() {
        assertNull(Rules.matchesKeyword("jual condominium murah", listOf("condom")))
        assertNull(Rules.matchesKeyword("analisis data", listOf("anal")))
    }

    @Test fun keywordTypingIgnoresUnfinishedShortWordAtEnd() {
        assertNull(Rules.matchesKeyword("anal", listOf("anal"), typing = true))
        assertNotNull(Rules.matchesKeyword("anal", listOf("anal"), typing = false))
    }

    @Test fun keywordBlankEntriesAreSkipped() {
        assertNull(Rules.matchesKeyword("apa saja", listOf("", "  ")))
    }

    @Test fun publicIpUrlsAreDetected() {
        assertTrue(Rules.isPublicIpUrl("178.62.96.212/halaman"))
        assertTrue(Rules.isPublicIpUrl("http://8.8.8.8:8080/x"))
    }

    @Test fun privateIpUrlsAreAllowed() {
        assertFalse(Rules.isPublicIpUrl("192.168.1.1"))
        assertFalse(Rules.isPublicIpUrl("10.0.0.5/admin"))
        assertFalse(Rules.isPublicIpUrl("172.16.0.1"))
        assertFalse(Rules.isPublicIpUrl("127.0.0.1"))
        assertFalse(Rules.isPublicIpUrl("contoh.com"))
        assertFalse(Rules.isPublicIpUrl("999.1.1.1"))
    }

    @Test fun normalizeDomainStripsSchemeWwwAndPath() {
        assertEquals("xvideos.com", Rules.normalizeDomain("https://www.XVideos.com/path?q=1"))
        assertEquals("a.com", Rules.normalizeDomain("*.a.com"))
        assertEquals("a.com", Rules.normalizeDomain(" a.com. "))
    }

    @Test fun domainMatchIncludesSubdomainsButNotLookalikes() {
        assertNotNull(Rules.matchesDomain("m.xvideos.com", listOf("xvideos.com")))
        assertNotNull(Rules.matchesDomain("https://www.xvideos.com/video", listOf("xvideos.com")))
        assertNull(Rules.matchesDomain("netflix.com", listOf("x.com")))
        assertNull(Rules.matchesDomain("notxvideos.com", listOf("xvideos.com")))
    }

    @Test fun randomTextHasRequestedLengthAndSafeAlphabet() {
        val t = Rules.randomText(40)
        assertEquals(40, t.length)
        assertTrue(t.none { it in "0O1lI" })
        assertEquals(16, Rules.randomText().length)
    }
}
