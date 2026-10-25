package com.mas.strictguard

import android.content.Context
import android.location.Location

/**
 * Zona Ketat: di dalam zona (titik + radius), Mode Ketat menyala otomatis bila sedang tidak ketat,
 * dan bila sudah ketat menjadi lebih ketat: jadwal dan kedaluwarsa diabaikan, serta keluar dari
 * Mode Ketat mewajibkan teks acak minimal [MIN_TEXT] huruf (di atas metode yang sudah diatur).
 *
 * - Keluar dari zona: bila Mode Ketat dinyalakan oleh zona, ia dimatikan lagi; bila sudah ketat
 *   sebelumnya, ia kembali ke aturan penonaktifan semula.
 * - Bila pengguna berhasil keluar dari Mode Ketat di dalam zona (teks acak dipenuhi), zona dilewati
 *   sampai pengguna keluar dari radius, lalu aktif lagi saat masuk berikutnya.
 * - Zona disimpan sebagai aturan lokasi "loc_zone_N" (ikut Backup dan memakai pelacak lokasi yang ada).
 * - Posisi tidak tersedia: keadaan terakhir dipertahankan (tidak pernah otomatis keluar dari zona).
 */
object Zone {
    const val MIN_TEXT = 2000
    const val MAX_ZONES = 5
    private const val OUT_CONFIRM_MS = 60_000L

    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    private fun key(i: Int) = "zone_$i"

    fun zones(c: Context): List<Pair<Int, LocRule>> {
        val out = ArrayList<Pair<Int, LocRule>>()
        for (i in 1..MAX_ZONES) {
            val r = LocationRules.get(c, key(i)) ?: continue
            out.add(Pair(i, r))
        }
        return out
    }

    fun hasZones(c: Context): Boolean = (1..MAX_ZONES).any { sp(c).contains("loc_" + key(it)) }

    fun add(c: Context, lat: Double, lon: Double, radius: Int): Boolean {
        for (i in 1..MAX_ZONES) {
            if (LocationRules.get(c, key(i)) == null) {
                LocationRules.set(c, key(i), LocRule(lat, lon, radius, false, false))
                return true
            }
        }
        return false
    }

    fun remove(c: Context, i: Int) {
        LocationRules.set(c, key(i), null)
        update(c)
    }

    /** True bila Zona Ketat sedang berlaku (di dalam zona dan belum dilewati). Hanya membaca prefs. */
    fun active(c: Context): Boolean {
        val p = sp(c)
        return p.getBoolean("zone_in", false) && !p.getBoolean("zone_override", false) && hasZones(c)
    }

    fun inside(c: Context): Boolean = sp(c).getBoolean("zone_in", false)

    fun overridden(c: Context): Boolean = sp(c).getBoolean("zone_override", false)

    /** Panjang teks acak yang diwajibkan untuk keluar dari Mode Ketat saat ini. */
    fun requiredTextLen(c: Context, configured: Int): Int =
        if (active(c)) Math.max(configured, MIN_TEXT) else configured

    /** Jarak (m) ke tepi zona terdekat; negatif bila di dalam. Null bila posisi tidak diketahui. */
    fun distanceToEdge(c: Context): Int? {
        val fix = LocationTracker.current(c) ?: return null
        var best: Float? = null
        for ((_, r) in zones(c)) {
            val d = FloatArray(1)
            Location.distanceBetween(fix.latitude, fix.longitude, r.lat, r.lon, d)
            val e = d[0] - r.radius
            if (best == null || e < best) best = e
        }
        return best?.toInt()
    }

    /** Dipanggil setelah pengguna berhasil menonaktifkan Mode Ketat lewat layar keluar. */
    @Synchronized
    fun onUserExit(c: Context) {
        val p = sp(c)
        if (p.getBoolean("zone_in", false)) {
            p.edit().putBoolean("zone_override", true).putBoolean("zone_auto", false).commit()
            Store.log(c, "Mode Ketat dinonaktifkan di dalam Zona Ketat (zona dilewati sampai keluar radius)")
        }
    }

    /** Evaluasi posisi dan terapkan perpindahan masuk/keluar zona. Aman dipanggil sering. */
    @Synchronized
    fun update(c: Context) {
        try {
            val p = sp(c)
            val zs = zones(c)
            val was = p.getBoolean("zone_in", false)
            if (zs.isEmpty()) {
                if (was || p.getBoolean("zone_override", false)) leave(c)
                return
            }
            val fix = LocationTracker.current(c) ?: return
            var enter = false
            var allOut = true
            val acc = if (fix.hasAccuracy()) fix.accuracy else 0f
            for ((_, r) in zs) {
                val d = FloatArray(1)
                Location.distanceBetween(fix.latitude, fix.longitude, r.lat, r.lon, d)
                if (d[0] <= r.radius && acc <= 300f) enter = true
                if (d[0] <= r.radius + Math.max(50f, acc)) allOut = false
            }
            if (!was) {
                if (enter) {
                    p.edit().putLong("zone_out_since", 0L).commit()
                    enterZone(c)
                }
                return
            }
            if (allOut) {
                val now = System.currentTimeMillis()
                val since = p.getLong("zone_out_since", 0L)
                if (since == 0L) {
                    p.edit().putLong("zone_out_since", now).commit()
                } else if (now - since >= OUT_CONFIRM_MS) {
                    leave(c)
                }
            } else if (p.getLong("zone_out_since", 0L) != 0L) {
                p.edit().putLong("zone_out_since", 0L).commit()
            }
        } catch (t: Throwable) {
            // jangan sampai pelacakan zona menjatuhkan layanan
        }
    }

    private fun enterZone(c: Context) {
        val p = sp(c)
        val wasRaw = Store.strictRaw(c)
        p.edit().putBoolean("zone_in", true).putBoolean("zone_override", false).putBoolean("zone_auto", !wasRaw).commit()
        if (!wasRaw) {
            Store.setStrict(c, true)
            Store.log(c, "Masuk Zona Ketat: Mode Ketat dinyalakan otomatis")
        } else {
            Store.log(c, "Masuk Zona Ketat: Mode Ketat diperketat (teks acak $MIN_TEXT huruf)")
        }
        // permintaan keluar yang sedang berjalan dimulai ulang agar tidak memakai syarat lama
        SafeTimer.cancel(c, "exit")
        try { WatchdogService.start(c) } catch (t: Throwable) { }
    }

    private fun leave(c: Context) {
        val p = sp(c)
        val auto = p.getBoolean("zone_auto", false)
        p.edit().putBoolean("zone_in", false).putBoolean("zone_override", false).putBoolean("zone_auto", false)
            .putLong("zone_out_since", 0L).commit()
        if (auto && Store.strictRaw(c)) {
            Store.setStrict(c, false)
            SafeTimer.cancel(c, "exit")
            Store.log(c, "Keluar Zona Ketat: Mode Ketat otomatis dimatikan")
        } else {
            Store.log(c, "Keluar Zona Ketat: kembali ke aturan Mode Ketat semula")
        }
    }
}
