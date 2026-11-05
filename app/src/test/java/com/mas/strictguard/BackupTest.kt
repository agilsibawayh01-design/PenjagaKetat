package com.mas.strictguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    @Test fun whitelistedKeysAreAllowed() {
        assertTrue(Backup.allowed("apps"))
        assertTrue(Backup.allowed("night_on"))
        assertTrue(Backup.allowed("cfg_com.example"))
    }

    @Test fun secretsAndStateAreNeverRestored() {
        assertFalse(Backup.allowed("strict_on"))
        assertFalse(Backup.allowed("pin_hash"))
        assertFalse(Backup.allowed("cfg_"))
        assertFalse(Backup.allowed("random_key"))
    }

    @Test fun parseRejectsForeignOrBrokenFiles() {
        assertFalse(Backup.parse("bukan json").ok)
        assertFalse(Backup.parse("{\"app\":\"Lain\",\"format\":1,\"prefs\":{}}").ok)
        assertFalse(Backup.parse("{\"app\":\"PenjagaKetat\",\"format\":99,\"prefs\":{}}").ok)
        assertFalse(Backup.parse("{\"app\":\"PenjagaKetat\",\"format\":1,\"prefs\":{}}").ok)
    }

    @Test fun parseAcceptsMinimalValidBackup() {
        val json = "{\"app\":\"PenjagaKetat\",\"format\":1,\"prefs\":{\"block_ip_urls\":{\"t\":\"b\",\"v\":true}}}"
        assertTrue(Backup.parse(json).ok)
    }
}
