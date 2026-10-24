package com.mas.strictguard

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pengecualian darurat: SATU aplikasi, 2 menit, SATU kali per hari, dengan alasan yang dicatat.
 * Hanya melonggarkan blokir tingkat aplikasi (BlockEngine). Blokir situs, kata kunci, dan perlindungan
 * diri tidak terpengaruh. Tidak tersedia bila jam HP terdeteksi diubah. Reboot mengakhiri pengecualian.
 */
object Emergency {
    const val MINUTES = 2

    private fun sp(c: Context) = c.getSharedPreferences("strictguard_emg", Context.MODE_PRIVATE)

    private fun day(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun boot(c: Context): Int =
        try {
            Settings.Global.getInt(c.contentResolver, "boot_count", -1)
        } catch (e: Throwable) {
            -1
        }

    fun usedToday(c: Context): Boolean = sp(c).getString("day", "") == day()

    fun canUse(c: Context): Boolean = !usedToday(c) && !TimeGuard.penaltyActive(c)

    /** True bila pkg sedang dalam masa darurat. Murah: dipanggil di BlockEngine.check. */
    fun activeFor(c: Context, pkg: String): Boolean {
        val p = sp(c)
        if (p.getString("pkg", "") != pkg) return false
        val end = p.getLong("end", 0L)
        if (end == 0L) return false
        val now = SystemClock.elapsedRealtime()
        return now < end && end - now <= MINUTES * 60_000L && p.getInt("boot", -2) == boot(c)
    }

    fun grant(c: Context, pkg: String, reason: String): Boolean {
        if (!canUse(c)) return false
        val end = SystemClock.elapsedRealtime() + MINUTES * 60_000L
        sp(c).edit().putString("day", day()).putString("pkg", pkg).putLong("end", end).putInt("boot", boot(c)).commit()
        Store.log(c, "Darurat $MINUTES menit: $pkg. Alasan: $reason")
        return true
    }
}
