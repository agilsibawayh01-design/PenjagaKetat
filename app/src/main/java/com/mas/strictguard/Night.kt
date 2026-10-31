package com.mas.strictguard

import android.content.Context
import java.util.Calendar

/**
 * Jam rawan: di jam yang ditandai, gerbang keluar Mode Ketat dan gerbang mematikan fitur
 * menjadi dua kali lebih berat (teks acak dua kali lebih panjang, waktu tunggu dua kali lebih lama).
 */
object Night {
    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = sp(c).getBoolean("night_on", true)
    fun setEnabled(c: Context, v: Boolean) { sp(c).edit().putBoolean("night_on", v).commit() }
    fun start(c: Context): Int = sp(c).getInt("night_start", 23 * 60)
    fun end(c: Context): Int = sp(c).getInt("night_end", 5 * 60)
    fun setWindow(c: Context, s: Int, e: Int) { sp(c).edit().putInt("night_start", s).putInt("night_end", e).commit() }

    fun active(c: Context): Boolean {
        if (!enabled(c)) return false
        val s = start(c)
        val e = end(c)
        if (s == e) return false
        val cal = Calendar.getInstance()
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (s < e) m in s until e else (m >= s || m < e)
    }

    fun lockPlay(c: Context): Boolean = sp(c).getBoolean("night_lock_play", false)
    fun setLockPlay(c: Context, v: Boolean) { sp(c).edit().putBoolean("night_lock_play", v).commit() }
    fun lockSettings(c: Context): Boolean = sp(c).getBoolean("night_lock_settings", false)
    fun setLockSettings(c: Context, v: Boolean) { sp(c).edit().putBoolean("night_lock_settings", v).commit() }

    /** Paket yang ditutup total pada jam rawan sekarang. */
    fun lockedPackages(c: Context): Set<String> {
        if (!active(c)) return emptySet()
        val out = HashSet<String>()
        if (lockPlay(c)) out.add("com.android.vending")
        if (lockSettings(c)) out.add("com.android.settings")
        return out
    }

    fun scaleText(c: Context, base: Int): Int =
        if (base <= 0 || !active(c)) base else Math.min(base * 2, 2000)

    fun scaleWait(c: Context, base: Int): Int =
        if (base <= 0 || !active(c)) base else base * 2

    fun fmt(min: Int): String = String.format(java.util.Locale.US, "%02d:%02d", min / 60, min % 60)
}
