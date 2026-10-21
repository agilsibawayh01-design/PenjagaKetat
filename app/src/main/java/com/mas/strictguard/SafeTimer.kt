package com.mas.strictguard

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Penghitung waktu yang TIDAK terpengaruh jam sistem (elapsedRealtime).
 * Reboot hanya membuat waktu yang terhitung lebih sedikit (lebih ketat), tidak pernah lebih banyak.
 */
object SafeTimer {
    private fun sp(c: Context) = c.getSharedPreferences("safetimer", Context.MODE_PRIVATE)

    private fun bootCount(c: Context): Int =
        try {
            Settings.Global.getInt(c.contentResolver, "boot_count", -1)
        } catch (e: Exception) {
            -1
        }

    fun start(c: Context, key: String) {
        sp(c).edit()
            .putBoolean(key + "_on", true)
            .putLong(key + "_acc", 0L)
            .putLong(key + "_base", SystemClock.elapsedRealtime())
            .putInt(key + "_boot", bootCount(c))
            .commit()
    }

    fun cancel(c: Context, key: String) {
        sp(c).edit().putBoolean(key + "_on", false).putLong(key + "_acc", 0L).commit()
    }

    fun isRunning(c: Context, key: String): Boolean = sp(c).getBoolean(key + "_on", false)

    /** Milidetik yang sudah terhitung sejak start(). Sekaligus menyimpan checkpoint. */
    @Synchronized
    fun elapsed(c: Context, key: String): Long {
        val p = sp(c)
        if (!p.getBoolean(key + "_on", false)) return 0L
        val now = SystemClock.elapsedRealtime()
        var acc = p.getLong(key + "_acc", 0L)
        val base = p.getLong(key + "_base", now)
        val boot = p.getInt(key + "_boot", -1)
        val curBoot = bootCount(c)
        val rebooted = now < base || curBoot != boot
        if (!rebooted) acc += now - base
        p.edit()
            .putLong(key + "_acc", acc)
            .putLong(key + "_base", now)
            .putInt(key + "_boot", curBoot)
            .apply()
        return acc
    }
}
