package com.mas.strictguard

import android.content.Context

data class SiteRule(
    val full: Boolean = true,
    val schOn: Boolean = false,
    val start: Int = 21 * 60,
    val end: Int = 5 * 60,
    val days: Int = 127,
    val limitMin: Int = 0,
    val twUse: Int = 0,
    val twWait: Int = 0,
    val twAdaptive: Boolean = true
)

object SiteCfg {
    private fun sp(c: Context) = c.getSharedPreferences("strictguard_sites", Context.MODE_PRIVATE)
    private fun key(domain: String) = "site_${Rules.normalizeDomain(domain)}"

    fun get(c: Context, domain: String): SiteRule {
        val d = Rules.normalizeDomain(domain)
        if (d.isEmpty()) return SiteRule()
        val raw = sp(c).getString(key(d), null) ?: return SiteRule()
        return try {
            val p = raw.split('|')
            fun i(k: Int, def: Int) = p.getOrNull(k)?.toIntOrNull() ?: def
            SiteRule(
                full = p.getOrNull(0) != "0",
                schOn = p.getOrNull(1) == "1",
                start = i(2, 21 * 60), end = i(3, 5 * 60), days = i(4, 127),
                limitMin = i(5, 0), twUse = i(6, 0), twWait = i(7, 0),
                twAdaptive = p.getOrNull(8) != "0"
            )
        } catch (_: Throwable) { SiteRule() }
    }

    fun has(c: Context, domain: String): Boolean = sp(c).contains(key(domain))

    fun set(c: Context, domain: String, r: SiteRule) {
        val d = Rules.normalizeDomain(domain)
        if (d.isEmpty()) return
        val raw = "${if (r.full) 1 else 0}|${if (r.schOn) 1 else 0}|${r.start}|${r.end}|${r.days}|${r.limitMin}|${r.twUse}|${r.twWait}|${if (r.twAdaptive) 1 else 0}"
        sp(c).edit().putString(key(d), raw).apply()
    }

    fun remove(c: Context, domain: String) = sp(c).edit().remove(key(domain)).apply()

    fun domains(c: Context): Set<String> = sp(c).all.keys
        .filter { it.startsWith("site_") }
        .map { it.removePrefix("site_") }
        .toSet()
}
