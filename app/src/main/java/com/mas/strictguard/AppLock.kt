package com.mas.strictguard

import android.content.Context
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Kunci untuk membuka aplikasi Penjaga Ketat ini (bukan kunci aplikasi lain).
 * Mode 0 = mati, 1 = PIN sendiri (4 sampai 8 angka), 2 = kunci layar HP (PIN/pola/sidik jari).
 */
object AppLock {
    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    /** Setelah berhasil membuka, aplikasi tidak meminta lagi selama masa ini. */
    private const val GRACE_MS = 60_000L

    @Volatile
    private var unlockedAt = 0L

    fun mode(c: Context): Int = sp(c).getInt("lock_mode", 0)
    fun setMode(c: Context, m: Int) { sp(c).edit().putInt("lock_mode", m).commit() }
    fun hasPin(c: Context): Boolean = sp(c).getString("lock_hash", null) != null

    fun needsAuth(c: Context): Boolean {
        if (mode(c) == 0) return false
        if (unlockedAt == 0L) return true
        return SystemClock.elapsedRealtime() - unlockedAt > GRACE_MS
    }

    fun markUnlocked() { unlockedAt = SystemClock.elapsedRealtime() }
    fun relock() { unlockedAt = 0L }

    private fun hash(salt: String, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest((salt + ":" + pin).toByteArray(Charsets.UTF_8)).joinToString("") { String.format("%02x", it) }
    }

    /** Mengatur PIN baru. Mengembalikan pesan galat atau null bila sukses. */
    fun setPin(c: Context, pin: String): String? {
        if (pin.length !in 4..8 || !pin.all { it.isDigit() }) return "PIN harus 4 sampai 8 angka"
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        val salt = b.joinToString("") { String.format("%02x", it) }
        sp(c).edit().putString("lock_salt", salt).putString("lock_hash", hash(salt, pin)).putInt("lock_mode", 1).commit()
        return null
    }

    fun lockedMinutes(c: Context): Long {
        if (!SafeTimer.isRunning(c, "locklock")) return 0L
        val left = 5L * 60000L - SafeTimer.elapsed(c, "locklock")
        if (left <= 0L) {
            SafeTimer.cancel(c, "locklock")
            return 0L
        }
        return left / 60000L + 1L
    }

    fun verifyPin(c: Context, pin: String): Boolean {
        val h = sp(c).getString("lock_hash", null) ?: return true
        if (lockedMinutes(c) > 0L) return false
        val salt = sp(c).getString("lock_salt", "") ?: ""
        val ok = hash(salt, pin.trim()) == h
        val p = sp(c)
        if (ok) {
            p.edit().putInt("lock_fails", 0).commit()
            markUnlocked()
        } else {
            val f = p.getInt("lock_fails", 0) + 1
            if (f >= 5) {
                p.edit().putInt("lock_fails", 0).commit()
                SafeTimer.start(c, "locklock")
                Store.log(c, "PIN aplikasi salah 5 kali: dikunci 5 menit")
            } else {
                p.edit().putInt("lock_fails", f).commit()
            }
        }
        return ok
    }

    fun turnOff(c: Context) {
        sp(c).edit().putInt("lock_mode", 0).remove("lock_hash").remove("lock_salt").commit()
    }
}
