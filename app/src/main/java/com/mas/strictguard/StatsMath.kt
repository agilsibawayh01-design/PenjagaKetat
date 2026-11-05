package com.mas.strictguard

/** Hitungan murni untuk layar Statistik (bisa dites tanpa perangkat). */
object StatsMath {
    /** Perubahan persen dari [prev] ke [now]; null bila [prev] <= 0 (tidak ada pembanding). */
    fun percentChange(now: Long, prev: Long): Int? =
        if (prev <= 0L) null else ((now - prev) * 100L / prev).toInt()

    /** Panjang batang 0..[full]; nilai > 0 minimal 1 supaya tetap terlihat. */
    fun barLength(value: Long, max: Long, full: Int): Int {
        if (value <= 0L || max <= 0L || full <= 0) return 0
        return Math.max(1L, Math.min(full.toLong(), value * full / max)).toInt()
    }
}
