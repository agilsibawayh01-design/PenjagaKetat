package com.mas.strictguard

import android.content.Context

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    private fun getSet(c: Context, key: String): Set<String> =
        HashSet(sp(c).getStringSet(key, emptySet()) ?: emptySet())

    private fun putSet(c: Context, key: String, v: Set<String>) {
        sp(c).edit().putStringSet(key, HashSet(v)).commit()
    }

    fun apps(c: Context): Set<String> = getSet(c, "apps")
    fun keywords(c: Context): Set<String> = getSet(c, "keywords")
    fun domains(c: Context): Set<String> = getSet(c, "domains")

    fun protectedApps(c: Context): Set<String> = getSet(c, "prot") + Config.SEED_PROTECTED
    fun addProtected(c: Context, p: String) = putSet(c, "prot", getSet(c, "prot") + p)
    fun removeProtected(c: Context, p: String) = putSet(c, "prot", getSet(c, "prot") - p)

    fun addApp(c: Context, p: String) = putSet(c, "apps", apps(c) + p)
    fun removeApp(c: Context, p: String) = putSet(c, "apps", apps(c) - p)
    fun addKeyword(c: Context, k: String) = putSet(c, "keywords", keywords(c) + k.trim().lowercase())
    fun removeKeyword(c: Context, k: String) = putSet(c, "keywords", keywords(c) - k)
    fun addDomain(c: Context, d: String) = putSet(c, "domains", domains(c) + d.trim().lowercase())
    fun removeDomain(c: Context, d: String) = putSet(c, "domains", domains(c) - d)

    fun seed(c: Context) {
        if (sp(c).getBoolean("seeded", false)) return
        putSet(c, "keywords", keywords(c) + Config.SEED_KEYWORDS)
        putSet(c, "domains", domains(c) + Config.SEED_DOMAINS)
        putSet(c, "apps", apps(c) + Config.SEED_APPS)
        sp(c).edit().putBoolean("seeded", true).commit()
    }

    fun strictOn(c: Context): Boolean = sp(c).getBoolean("strict", false)
    fun setStrict(c: Context, v: Boolean) { sp(c).edit().putBoolean("strict", v).commit() }

    fun disableRequestedAt(c: Context): Long = sp(c).getLong("disable_req", 0L)
    fun setDisableRequestedAt(c: Context, v: Long) { sp(c).edit().putLong("disable_req", v).commit() }

    fun waitSeconds(c: Context): Int = sp(c).getInt("wait_s", 0)
    fun passMinutes(c: Context): Int = sp(c).getInt("pass_m", 5)
    fun setWait(c: Context, seconds: Int, passMin: Int) {
        sp(c).edit().putInt("wait_s", seconds).putInt("pass_m", passMin).commit()
    }

    fun passUntil(c: Context, pkg: String): Long = sp(c).getLong("pass_$pkg", 0L)
    fun setPass(c: Context, pkg: String, until: Long) { sp(c).edit().putLong("pass_$pkg", until).commit() }

    fun scheduleEnabled(c: Context): Boolean = sp(c).getBoolean("sch_on", false)
    fun startMin(c: Context): Int = sp(c).getInt("sch_start", 21 * 60)
    fun endMin(c: Context): Int = sp(c).getInt("sch_end", 5 * 60)
    fun days(c: Context): Int = sp(c).getInt("sch_days", 127)
    fun setSchedule(c: Context, on: Boolean, start: Int, end: Int, days: Int) {
        sp(c).edit().putBoolean("sch_on", on).putInt("sch_start", start).putInt("sch_end", end)
            .putInt("sch_days", days).commit()
    }

    fun blockShorts(c: Context): Boolean = sp(c).getBoolean("shorts", true)
    fun blockReels(c: Context): Boolean = sp(c).getBoolean("reels", true)
    fun setFeeds(c: Context, shorts: Boolean, reels: Boolean) {
        sp(c).edit().putBoolean("shorts", shorts).putBoolean("reels", reels).commit()
    }
}
