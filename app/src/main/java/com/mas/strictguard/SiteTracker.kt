package com.mas.strictguard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mengukur waktu domain yang sedang terlihat di browser dan menerapkan aturan situs. */
object SiteTracker {
    private const val PREF = "strictguard_site_usage"
    private const val STATE = "site_tracker_state"
    private const val CYCLE = 2
    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun day(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    private fun usageKey(domain: String) = "u_${day()}_${Rules.normalizeDomain(domain)}"
    private fun baseKey(domain: String) = "base_${Rules.normalizeDomain(domain)}"
    private fun untilKey(domain: String) = "until_${Rules.normalizeDomain(domain)}"
    private fun startKey(domain: String) = "start_${Rules.normalizeDomain(domain)}"
    private fun attKey(domain: String) = "att_${Rules.normalizeDomain(domain)}"

    @Synchronized fun observe(c: Context, domainRaw: String, now: Long) {
        val domain = Rules.normalizeDomain(domainRaw)
        if (domain.isEmpty()) return
        val p = c.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        val lastDomain = p.getString("last_domain", "") ?: ""
        val lastAt = p.getLong("last_at", 0L)
        if (lastDomain.isNotEmpty() && now > lastAt && now - lastAt <= 10000L) {
            val delta = now - lastAt
            val key = usageKey(lastDomain)
            sp(c).edit().putLong(key, sp(c).getLong(key, 0L) + delta).apply()
        }
        p.edit().putString("last_domain", domain).putLong("last_at", now).apply()
    }

    fun todayMs(c: Context, domain: String): Long = sp(c).getLong(usageKey(domain), 0L)

    fun onConfigSaved(c: Context, domainRaw: String, old: SiteRule, new: SiteRule) {
        val d = Rules.normalizeDomain(domainRaw)
        if (d.isEmpty()) return
        if (new.twUse <= 0 || new.twWait <= 0) {
            clearCycle(c, d)
            return
        }
        val changed = old.twUse != new.twUse || old.twWait != new.twWait || old.days != new.days
        if (changed || !sp(c).contains(baseKey(d))) resetCycle(c, d)
    }

    private fun resetCycle(c: Context, d: String) {
        sp(c).edit()
            .putLong(baseKey(d), todayMs(c, d))
            .putLong(untilKey(d), 0L)
            .putLong(startKey(d), 0L)
            .putInt(attKey(d), 0)
            .apply()
    }

    fun remaining(c: Context, domainRaw: String, r: SiteRule, now: Long): Long {
        val d = Rules.normalizeDomain(domainRaw)
        if (d.isEmpty() || r.twUse <= 0 || r.twWait <= 0) return 0L
        if (!Rules.dayActive(c, r.days)) return 0L
        val p = sp(c)
        var base = p.getLong(baseKey(d), -1L)
        val used = todayMs(c, d)
        if (base < 0L) { resetCycle(c, d); base = used }
        var until = p.getLong(untilKey(d), 0L)
        if (until > 0L) {
            val start = p.getLong(startKey(d), now)
            val att = p.getInt(attKey(d), 0)
            val half = start + (until - start) / 2
            val ended = now >= until || (r.twAdaptive && att == 0 && now >= half)
            if (!ended) return until - now
            resetCycle(c, d)
            return 0L
        }
        if (used < base) { p.edit().putLong(baseKey(d), used).apply(); base = used }
        if (used - base >= r.twUse * 60000L) {
            val end = now + r.twWait * 60000L
            p.edit().putLong(untilKey(d), end).putLong(startKey(d), now).putInt(attKey(d), 0).apply()
            return end - now
        }
        return 0L
    }

    fun verdict(c: Context, domainRaw: String, r: SiteRule, now: Long): String? {
        val d = Rules.normalizeDomain(domainRaw)
        if (d.isEmpty()) return null
        if (remaining(c, d, r, now) > 0L) return "site_wait"
        if (r.schOn && !Rules.scheduleActive(r.start, r.end, r.days)) return null
        if (r.limitMin > 0 && todayMs(c, d) >= r.limitMin * 60000L) return "site_limit"
        if (r.full) return "site_full"
        return null
    }

    fun noteAttempt(c: Context, domainRaw: String) {
        val d = Rules.normalizeDomain(domainRaw)
        val p = sp(c)
        val now = System.currentTimeMillis()
        if (now - p.getLong(startKey(d), 0L) < 10000L) return
        p.edit().putInt(attKey(d), p.getInt(attKey(d), 0) + 1).apply()
    }

    fun clearCycle(c: Context, domainRaw: String) {
        val d = Rules.normalizeDomain(domainRaw)
        sp(c).edit().remove(baseKey(d)).remove(untilKey(d)).remove(startKey(d)).remove(attKey(d)).apply()
    }

    fun activeWait(c: Context, now: Long): Pair<String, Long>? {
        for (d in SiteCfg.domains(c)) {
            val r = SiteCfg.get(c, d)
            val left = remaining(c, d, r, now)
            if (left > 0L) return d to left
        }
        return null
    }
}
