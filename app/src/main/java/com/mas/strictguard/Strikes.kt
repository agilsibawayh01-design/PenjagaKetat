package com.mas.strictguard

import android.content.Context

/**
 * Hitungan "layar blokir muncul berulang". Kalau layar blokir muncul LOCK_AT kali dalam WINDOW_MS,
 * layar dikunci LOCK_MS. Peringatan mulai tampil di WARN_AT.
 */
object Strikes {
    const val WINDOW_MS = 10 * 60_000L
    const val WARN_AT = 3
    const val LOCK_AT = 5
    const val LOCK_MS = 5 * 60_000L

    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    /** Murni (bisa dites): buang catatan yang sudah di luar jendela waktu. */
    fun prune(times: List<Long>, now: Long): List<Long> = times.filter { now - it in 0..WINDOW_MS }

    private fun load(c: Context, now: Long): List<Long> {
        val raw = sp(c).getString("strikes", "") ?: ""
        val list = raw.split(",").mapNotNull { it.trim().toLongOrNull() }
        return prune(list, now)
    }

    private fun save(c: Context, list: List<Long>) {
        sp(c).edit().putString("strikes", list.joinToString(",")).commit()
    }

    /** Catat satu kemunculan layar blokir. Mengembalikan jumlah dalam jendela waktu (termasuk yang ini). */
    fun record(c: Context): Int {
        val now = System.currentTimeMillis()
        val list = load(c, now) + now
        if (list.size >= LOCK_AT) {
            sp(c).edit().putLong("strike_lock_until", now + LOCK_MS).putString("strikes", "").commit()
        } else {
            save(c, list)
        }
        return list.size
    }

    fun count(c: Context): Int = load(c, System.currentTimeMillis()).size

    fun lockLeftMs(c: Context): Long {
        val left = sp(c).getLong("strike_lock_until", 0L) - System.currentTimeMillis()
        return if (left > 0L) left else 0L
    }

    fun warning(c: Context): String {
        val n = count(c)
        if (n < WARN_AT || n >= LOCK_AT) return ""
        val sisa = LOCK_AT - n
        return "Peringatan: layar blokir sudah muncul $n kali dalam ${WINDOW_MS / 60000L} menit. " +
            (if (sisa == 1) "Satu kali lagi" else "Setelah $LOCK_AT kali") +
            ", layarmu akan terkunci ${LOCK_MS / 60000L} menit. Berhenti sekarang dan fokus pada tujuanmu."
    }
}
