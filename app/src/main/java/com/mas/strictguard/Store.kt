package com.mas.strictguard

import android.content.Context

class AppCfg(
    val wait: Int = 0,
    val pass: Int = 5,
    val schOn: Boolean = false,
    val start: Int = 21 * 60,
    val end: Int = 5 * 60,
    val days: Int = 127
)

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

    fun appCfg(c: Context, pkg: String): AppCfg {
        val raw = sp(c).getString("cfg_$pkg", null) ?: return AppCfg()
        return try {
            val p = raw.split('|')
            AppCfg(p[0].toInt(), p[1].toInt(), p[2] == "1", p[3].toInt(), p[4].toInt(), p[5].toInt())
        } catch (e: Exception) {
            AppCfg()
        }
    }

    fun setAppCfg(c: Context, pkg: String, g: AppCfg) {
        val raw = "${g.wait}|${g.pass}|${if (g.schOn) "1" else "0"}|${g.start}|${g.end}|${g.days}"
        sp(c).edit().putString("cfg_$pkg", raw).commit()
    }

    fun exitDelayMin(c: Context): Int = sp(c).getInt("exit_delay", 60)
    fun setExitDelayMin(c: Context, v: Int) { sp(c).edit().putInt("exit_delay", v).commit() }

    fun autoBlockNew(c: Context): Boolean = sp(c).getBoolean("auto_new", true)
    fun setAutoBlockNew(c: Context, v: Boolean) { sp(c).edit().putBoolean("auto_new", v).commit() }

    fun newApps(c: Context): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        for (e in getSet(c, "newapps")) {
            val i = e.lastIndexOf('|')
            if (i <= 0) continue
            val t = e.substring(i + 1).toLongOrNull() ?: continue
            out.add(Pair(e.substring(0, i), t))
        }
        return out.sortedByDescending { it.second }.take(15)
    }

    fun addNewApp(c: Context, pkg: String) {
        val all = getSet(c, "newapps").filter { !it.startsWith("$pkg|") }.toMutableSet()
        all.add(pkg + "|" + System.currentTimeMillis())
        putSet(c, "newapps", all)
    }

    fun extraBrowsers(c: Context): Set<String> = getSet(c, "browsers")
    fun addBrowser(c: Context, p: String) = putSet(c, "browsers", getSet(c, "browsers") + p)

    fun logs(c: Context): List<String> {
        val raw = sp(c).getString("log", "") ?: ""
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun log(c: Context, msg: String) {
        val stamp = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val lines = ArrayList(logs(c))
        lines.add("$stamp  $msg")
        val kept = lines.takeLast(40)
        sp(c).edit().putString("log", kept.joinToString("\n")).apply()
    }

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
