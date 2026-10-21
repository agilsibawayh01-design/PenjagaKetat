package com.mas.strictguard

import android.content.Context
import android.os.SystemClock

/**
 * Mendeteksi jam sistem yang diubah (maju/mundur) saat Mode Ketat aktif.
 * Hukuman: selama 30 menit, jadwal diabaikan dan aplikasi terblokir tetap diblokir.
 */
object TimeGuard {
    private const val TOLERANCE_MS = 3L * 60L * 1000L
    private const val PENALTY_MS = 30L * 60L * 1000L

    private var lastWall = 0L
    private var lastElapsed = 0L

    @Synchronized
    fun check(c: Context) {
        val wall = System.currentTimeMillis()
        val el = SystemClock.elapsedRealtime()
        if (lastElapsed != 0L && el >= lastElapsed) {
            val drift = (wall - lastWall) - (el - lastElapsed)
            if (Math.abs(drift) > TOLERANCE_MS && Store.strictOn(c)) {
                SafeTimer.start(c, "tamper")
                Store.log(c, "Jam sistem berubah ${drift / 60000L} menit. Jadwal diabaikan 30 menit.")
            }
        }
        lastWall = wall
        lastElapsed = el
    }

    fun penaltyActive(c: Context): Boolean {
        if (!SafeTimer.isRunning(c, "tamper")) return false
        if (SafeTimer.elapsed(c, "tamper") < PENALTY_MS) return true
        SafeTimer.cancel(c, "tamper")
        return false
    }
}
