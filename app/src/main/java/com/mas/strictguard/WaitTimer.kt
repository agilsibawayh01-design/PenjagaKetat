package com.mas.strictguard

import android.content.Context

/**
 * Timer tunggu berbasis SIKLUS:
 * setelah aplikasi dipakai [twUse] menit sejak awal siklus, aplikasi diblokir
 * selama [twWait] menit. Setelah masa tunggu selesai, siklus baru dimulai dari 0.
 *
 * Pemakaian sebelum aturan Timer Tunggu dibuat/diubah tidak ikut dihitung.
 */
object WaitTimer {
    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    private fun key(pkg: String, suffix: String) = "tw_${suffix}_$pkg"
    private fun versionKey(pkg: String) = key(pkg, "cycle_v")
    private const val CYCLE_VERSION = 2

    private fun resetCycleInternal(c: Context, pkg: String, usedNow: Long) {
        sp(c).edit()
            .putInt(versionKey(pkg), CYCLE_VERSION)
            .putLong(key(pkg, "base"), usedNow)
            .putLong(key(pkg, "until"), 0L)
            .putLong(key(pkg, "start"), 0L)
            .putInt(key(pkg, "att"), 0)
            .remove("tw_active_pkg")
            .apply()
    }

    /**
     * Dipanggil setelah konfigurasi aplikasi disimpan.
     *
     * Jika Timer Tunggu baru diaktifkan atau nilai "pakai/tunggu/hari" berubah,
     * siklus dimulai dari pemakaian saat konfigurasi disimpan.
     */
    fun onConfigSaved(c: Context, pkg: String, oldCfg: AppCfg, newCfg: AppCfg) {
        val oldOn = oldCfg.twUse > 0 && oldCfg.twWait > 0
        val newOn = newCfg.twUse > 0 && newCfg.twWait > 0

        if (!newOn) {
            clear(c, pkg)
            return
        }

        val changed = !oldOn ||
            oldCfg.twUse != newCfg.twUse ||
            oldCfg.twWait != newCfg.twWait ||
            oldCfg.days != newCfg.days

        if (changed) {
            resetCycleInternal(c, pkg, UsageTracker.todayMs(c, pkg))
        } else if (!sp(c).contains(versionKey(pkg))) {
            // Migrasi dari implementasi lama yang memakai base=0.
            resetCycleInternal(c, pkg, UsageTracker.todayMs(c, pkg))
        }
    }

    /**
     * Migrasi aman untuk konfigurasi lama. Dipanggil sebelum perhitungan.
     */
    private fun ensureInitialized(c: Context, pkg: String): Long {
        val p = sp(c)
        val used = UsageTracker.todayMs(c, pkg)
        if (p.getInt(versionKey(pkg), 0) != CYCLE_VERSION) {
            resetCycleInternal(c, pkg, used)
        }
        return p.getLong(key(pkg, "base"), used)
    }

    /** Sisa waktu tunggu (ms); 0 bila tidak sedang menunggu. */
    fun remaining(c: Context, pkg: String, g: AppCfg, now: Long): Long {
        if (g.twUse <= 0 || g.twWait <= 0) {
            clear(c, pkg)
            return 0L
        }
        if (!Rules.dayActive(c, g.days)) return 0L

        val p = sp(c)
        var base = ensureInitialized(c, pkg)
        val used = UsageTracker.todayMs(c, pkg)

        var until = p.getLong(key(pkg, "until"), 0L)

        // Sedang dalam masa tunggu.
        if (until > 0L) {
            val start = p.getLong(key(pkg, "start"), now)
            val att = p.getInt(key(pkg, "att"), 0)
            val half = start + (until - start) / 2
            val ended = now >= until || (g.twAdaptive && att == 0 && now >= half)

            if (!ended) {
                p.edit().putString("tw_active_pkg", pkg).apply()
                return until - now
            }

            // Siklus selesai: titik awal siklus berikutnya = pemakaian saat ini.
            p.edit()
                .putLong(key(pkg, "until"), 0L)
                .putLong(key(pkg, "start"), 0L)
                .putInt(key(pkg, "att"), 0)
                .putLong(key(pkg, "base"), used)
                .remove("tw_active_pkg")
                .apply()
            return 0L
        }

        // Jika statistik UsageStats mundur/reset, jangan memicu timer palsu.
        if (used < base) {
            base = used
            p.edit().putLong(key(pkg, "base"), base).apply()
        }

        // Batas pemakaian SIKLUS tercapai -> mulai masa tunggu.
        if (used - base >= g.twUse * 60000L) {
            val end = now + g.twWait * 60000L
            p.edit()
                .putLong(key(pkg, "until"), end)
                .putLong(key(pkg, "start"), now)
                .putInt(key(pkg, "att"), 0)
                .putString("tw_active_pkg", pkg)
                .apply()
            return end - now
        }

        return 0L
    }

    /** Paket yang sedang berada dalam masa tunggu, jika ada. */
    fun active(c: Context, now: Long): Pair<String, Long>? {
        val p = sp(c)
        val pkg = p.getString("tw_active_pkg", null) ?: return null
        if (pkg.isBlank()) return null

        val g = Store.appCfg(c, pkg)
        val left = remaining(c, pkg, g, now)
        return if (left > 0L) pkg to left else null
    }

    fun noteAttempt(c: Context, pkg: String) {
        val p = sp(c)
        if (System.currentTimeMillis() - p.getLong(key(pkg, "start"), 0L) < 10000L) return
        p.edit().putInt(key(pkg, "att"), p.getInt(key(pkg, "att"), 0) + 1).apply()
    }

    /** Reset manual bila diperlukan oleh aplikasi. */
    fun resetCycle(c: Context, pkg: String) {
        resetCycleInternal(c, pkg, UsageTracker.todayMs(c, pkg))
    }

    fun clear(c: Context, pkg: String) {
        val p = sp(c)
        p.edit()
            .remove(versionKey(pkg))
            .remove(key(pkg, "base"))
            .remove(key(pkg, "until"))
            .remove(key(pkg, "start"))
            .remove(key(pkg, "att"))
            .remove("tw_active_pkg")
            .apply()
    }

    /** Sisa waktu penggunaan pada siklus aktif (ms). 0 bila sedang masa tunggu/timer mati. */
    fun remainingUseMs(c: Context, pkg: String, g: AppCfg, now: Long): Long {
        if (g.twUse <= 0 || g.twWait <= 0 || !Rules.dayActive(c, g.days)) return 0L
        val p = sp(c)
        val base = p.getLong(key(pkg, "base"), UsageTracker.todayMs(c, pkg))
        val used = UsageTracker.todayMs(c, pkg)
        if (p.getLong(key(pkg, "until"), 0L) > 0L) return 0L
        val elapsed = Math.max(0L, used - Math.min(base, used))
        return Math.max(0L, g.twUse * 60000L - elapsed)
    }

    /** Sisa pemakaian dalam siklus aktif (menit), untuk tampilan. */
    fun usedInCycleMin(c: Context, pkg: String): Long {
        val base = sp(c).getLong(key(pkg, "base"), UsageTracker.todayMs(c, pkg))
        val used = UsageTracker.todayMs(c, pkg)
        return Math.max(0L, used - Math.min(base, used)) / 60000L
    }
}
