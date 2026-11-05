package com.mas.strictguard

import org.junit.Assert.assertTrue
import org.junit.Test

class SecureGuardTest {
    @Test fun samsungAbbreviatedUnknownInstallTextIsCovered() {
        assertTrue("instal aplikasi yg tdk dikenal" in SecureGuard.UNKNOWN_INSTALL_TEXTS)
        assertTrue("instal aplikasi yang tdk dikenal" in SecureGuard.UNKNOWN_INSTALL_TEXTS)
    }

    @Test fun markersAreLowercase() {
        for (s in SecureGuard.BYPASS_PAGE_TEXTS + SecureGuard.UNKNOWN_INSTALL_TEXTS + SecureGuard.SECURE_PAGE_TEXTS) {
            assertTrue("penanda harus huruf kecil: $s", s == s.lowercase())
        }
    }

    @Test fun bypassUnionContainsEveryGroup() {
        val all = SecureGuard.DEV_TEXTS + SecureGuard.DNS_TEXTS + SecureGuard.VPN_TEXTS +
            SecureGuard.USER_TEXTS + SecureGuard.ADMIN_TEXTS
        assertTrue(SecureGuard.BYPASS_PAGE_TEXTS.containsAll(all))
    }

    @Test fun ownAppLabelsIncludeTheToolset() {
        assertTrue("penjaga ketat" in SecureGuard.OWN_APP_LABELS)
        assertTrue("channel blocker" in SecureGuard.OWN_APP_LABELS)
    }
}
