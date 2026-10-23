package com.mas.strictguard

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SiteTracker {
    private const val PREF = "strictguard_site_usage"
    private const val STATE = "site_tracker_state"
    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun day(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    private fun norm(d: String) = Rules.normalizeDomain(d)
    private fun usageKey(d: String) = "u_${day()}_${norm(d)}"
    private fun baseKey(d: String) = "base_${norm(d)}"
    private fun untilKey(d: String) = "until_${norm(d)}"
    private fun startKey(d: String) = "start_${norm(d)}"
    private fun attKey(d: String) = "att_${norm(d)}"

    @Synchronized fun tick(c: Context, domainRaw: String) = observe(c, domainRaw, System.currentTimeMillis())
    @Synchronized fun observe(c: Context, domainRaw: String, now: Long) {
        val domain = norm(domainRaw); if (domain.isEmpty()) return
        val p = c.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        val lastDomain = p.getString("last_domain", "") ?: ""
        val lastAt = p.getLong("last_at", 0L)
        if (lastDomain.isNotEmpty() && now > lastAt && now - lastAt <= 10000L) {
            val delta = now - lastAt; val key = usageKey(lastDomain)
            sp(c).edit().putLong(key, sp(c).getLong(key, 0L) + delta).apply()
        }
        p.edit().putString("last_domain", domain).putLong("last_at", now).apply()
    }
    fun stop(c: Context) { c.getSharedPreferences(STATE, Context.MODE_PRIVATE).edit().remove("last_domain").remove("last_at").apply() }
    /**
     * Riwayat pemakaian situs [days] hari terakhir (baca saja): hari (yyyyMMdd) -> domain -> ms.
     * Sekaligus membuang catatan yang lebih tua dari 35 hari.
     */
    fun dayTotals(c: Context, days: Int): Map<String, Map<String, Long>> {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val now = System.currentTimeMillis()
        val keep = HashSet<String>()
        for (i in 0 until days) keep.add(fmt.format(Date(now - i * 86400000L)))
        val cutoff = fmt.format(Date(now - 35L * 86400000L))
        val out = HashMap<String, HashMap<String, Long>>()
        val old = ArrayList<String>()
        val p = sp(c)
        for ((k, v) in p.all) {
            if (!k.startsWith("u_") || k.length < 11 || k[10] != '_') continue
            val d = k.substring(2, 10)
            if (d < cutoff) {
                old.add(k)
            } else if (d in keep) {
                out.getOrPut(d) { HashMap() }[k.substring(11)] = (v as? Long) ?: 0L
            }
        }
        if (old.isNotEmpty()) {
            val e = p.edit()
            for (k in old) e.remove(k)
            e.apply()
        }
        return out
    }

    fun todayMs(c: Context, domain: String): Long = sp(c).getLong(usageKey(domain), 0L)
    fun onConfigSaved(c: Context, domainRaw: String, old: SiteCfg, new: SiteCfg) {
        val d = norm(domainRaw); if (d.isEmpty()) return
        if (new.twUse <= 0 || new.twWait <= 0) { clearCycle(c, d); return }
        val changed = old.twUse != new.twUse || old.twWait != new.twWait || old.days != new.days
        if (changed || !sp(c).contains(baseKey(d))) resetCycle(c, d)
    }
    fun startNewCycle(c: Context, d: String) = resetCycle(c, norm(d))
    private fun resetCycle(c: Context, d: String) {
        if (d.isEmpty()) return
        sp(c).edit().putLong(baseKey(d), todayMs(c, d)).putLong(untilKey(d), 0L).putLong(startKey(d), 0L).putInt(attKey(d), 0).apply()
    }
    @Synchronized fun remaining(c: Context, domainRaw: String, r: SiteCfg, now: Long): Long {
        val d = norm(domainRaw)
        if (d.isEmpty() || r.twUse <= 0 || r.twWait <= 0 || !Rules.dayActive(c, r.days)) return 0L
        val p = sp(c); var base = p.getLong(baseKey(d), -1L); val used = todayMs(c, d)
        if (base < 0L) { resetCycle(c, d); base = used }
        val until = p.getLong(untilKey(d), 0L)
        if (until > 0L) {
            val start = p.getLong(startKey(d), now); val att = p.getInt(attKey(d), 0)
            val half = start + (until - start) / 2
            val ended = now >= until || (r.twAdaptive && att == 0 && now >= half)
            if (!ended) return until - now
            resetCycle(c, d); return 0L
        }
        if (used < base) { p.edit().putLong(baseKey(d), used).apply(); base = used }
        if (used - base >= r.twUse * 60000L) {
            val end = now + r.twWait * 60000L
            p.edit().putLong(untilKey(d), end).putLong(startKey(d), now).putInt(attKey(d), 0).apply()
            return end - now
        }
        return 0L
    }
    fun verdict(c: Context, domainRaw: String, r: SiteCfg, now: Long): String? {
        val d = norm(domainRaw); if (d.isEmpty()) return null
        if (LocationRules.blocked(c, "site_$d")) return "site_loc"
        if (r.schOn && !Rules.scheduleActive(r.start, r.end, r.days)) return null
        if (remaining(c, d, r, now) > 0L) return "site_wait"
        if (r.limitMin > 0 && todayMs(c, d) >= r.limitMin * 60000L) return "site_limit"
        if (r.blocksFully) return "site_full"
        return null
    }
    fun shouldDnsBlock(c: Context, domain: String): Boolean {
        val d = norm(domain); if (d.isEmpty()) return false
        val cfg = Store.siteCfgOrNull(c, d) ?: return true
        return verdict(c, d, cfg, System.currentTimeMillis()) != null
    }
    fun noteAttempt(c: Context, domainRaw: String) {
        val d = norm(domainRaw); val p = sp(c); val now = System.currentTimeMillis()
        if (now - p.getLong(startKey(d), 0L) < 10000L) return
        p.edit().putInt(attKey(d), p.getInt(attKey(d), 0) + 1).apply()
    }
    fun clearCycle(c: Context, domainRaw: String) {
        val d = norm(domainRaw); sp(c).edit().remove(baseKey(d)).remove(untilKey(d)).remove(startKey(d)).remove(attKey(d)).apply()
    }
    /** Sisa waktu penggunaan pada siklus aktif (ms); 0 bila sedang masa tunggu/timer mati. */
    fun remainingUseMs(c: Context, domainRaw: String, r: SiteCfg): Long {
        val d = norm(domainRaw)
        if (d.isEmpty() || r.twUse <= 0 || r.twWait <= 0 || !Rules.dayActive(c, r.days)) return 0L
        val p = sp(c)
        val base = p.getLong(baseKey(d), todayMs(c, d))
        if (p.getLong(untilKey(d), 0L) > 0L) return 0L
        val used = todayMs(c, d)
        val elapsed = Math.max(0L, used - Math.min(base, used))
        return Math.max(0L, r.twUse * 60000L - elapsed)
    }

    fun activeWait(c: Context, now: Long): Pair<String, Long>? {
        for (d in Store.domains(c)) {
            val r = Store.siteCfgOrNull(c, d) ?: continue
            val left = remaining(c, d, r, now); if (left > 0L) return d to left
        }
        return null
    }
    fun activeWaiting(c: Context, now: Long): Pair<String, Long>? = activeWait(c, now)
    fun currentDomain(root: AccessibilityNodeInfo?, configured: Set<String>): String? {
        if (root == null || configured.isEmpty()) return null
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root); var count = 0
        while (q.isNotEmpty() && count++ < 500) {
            val n = q.removeFirst(); val id = (n.viewIdResourceName ?: "").lowercase(Locale.US)
            val txt = n.text?.toString()?.trim() ?: ""; val desc = n.contentDescription?.toString()?.trim() ?: ""
            val isUrl = id.contains("url") || id.contains("location_bar") || id.contains("omnibox") || id.contains("address")
            if (isUrl) { matchConfigured(txt, configured)?.let { return it }; matchConfigured(desc, configured)?.let { return it } }
            for (i in 0 until n.childCount) n.getChild(i)?.let(q::add)
        }
        return null
    }
    private fun matchConfigured(raw: String, configured: Set<String>): String? {
        if (raw.isBlank()) return null
        val host = try { val candidate = if (raw.contains("://")) raw else "https://$raw"; URI(candidate).host?.lowercase(Locale.US)?.removePrefix("www.") ?: raw.lowercase(Locale.US) } catch (_: Throwable) { raw.lowercase(Locale.US) }
        return configured.map(::norm).firstOrNull { host == it || host.endsWith(".$it") }
    }
}
