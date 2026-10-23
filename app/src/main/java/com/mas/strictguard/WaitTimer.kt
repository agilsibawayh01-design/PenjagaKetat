package com.mas.strictguard

import android.content.Context

/**
 * Timer tunggu ala StayFocused: setelah aplikasi dipakai [twUse] menit, aplikasi diblokir selama [twWait] menit.
 * Timer adaptif: bila selama separuh masa tunggu kamu tidak mencoba membuka aplikasi, masa tunggu berakhir lebih cepat (separuh).
 */
object WaitTimer {
    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    /** Sisa waktu tunggu (ms); 0 bila tidak sedang menunggu. Memulai masa tunggu bila batas pemakaian siklus tercapai. */
    fun remaining(c: Context, pkg: String, g: AppCfg, now: Long): Long {
        if (g.twUse <= 0 || g.twWait <= 0) return 0L
        if (!Rules.dayActive(c, g.days)) return 0L
        val p = sp(c)
        val used = UsageTracker.todayMs(c, pkg)
        var until = p.getLong("tw_until_$pkg", 0L)
        var base = p.getLong("tw_base_$pkg", 0L)
        if (until > 0L) {
            val start = p.getLong("tw_start_$pkg", now)
            val att = p.getInt("tw_att_$pkg", 0)
            val half = start + (until - start) / 2
            val ended = now >= until || (g.twAdaptive && att == 0 && now >= half)
            if (!ended) return until - now
            p.edit().putLong("tw_until_$pkg", 0L).putLong("tw_base_$pkg", used).commit()
            return 0L
        }
        if (used < base) {
            base = 0L
            p.edit().putLong("tw_base_$pkg", 0L).commit()
        }
        if (used - base >= g.twUse * 60000L) {
            val end = now + g.twWait * 60000L
            p.edit()
                .putLong("tw_until_$pkg", end)
                .putLong("tw_start_$pkg", now)
                .putInt("tw_att_$pkg", 0)
                .putLong("tw_base_$pkg", used)
                .commit()
            return end - now
        }
        return 0L
    }

    fun noteAttempt(c: Context, pkg: String) {
        val p = sp(c)
        if (System.currentTimeMillis() - p.getLong("tw_start_$pkg", 0L) < 10000L) return
        p.edit().putInt("tw_att_$pkg", p.getInt("tw_att_$pkg", 0) + 1).commit()
    }

    /** Timer aplikasi yang masih aktif; dipakai agar notifikasi tetap hidup meski BlockActivity sedang di depan. */
    fun activeWaiting(c: Context, now: Long = System.currentTimeMillis()): Pair<String, Long>? {
        val p = sp(c)
        val pkgs = LinkedHashSet<String>()
        pkgs.addAll(Store.apps(c))
        for (profile in ProfileStore.load(c)) pkgs.addAll(profile.apps)
        var best: Pair<String, Long>? = null
        for (pkg in pkgs) {
            val until = p.getLong("tw_until_$pkg", 0L)
            if (until > now) {
                val left = until - now
                if (best == null || left > best!!.second) best = pkg to left
            }
        }
        return best
    }

    /** Sisa pemakaian sebelum masa tunggu dimulai (menit), untuk tampilan. */
    fun usedInCycleMin(c: Context, pkg: String): Long {
        val base = sp(c).getLong("tw_base_$pkg", 0L)
        val used = UsageTracker.todayMs(c, pkg)
        return Math.max(0L, used - Math.min(base, used)) / 60000L
    }
}
