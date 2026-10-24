package com.mas.strictguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Sched(val start: Int, val end: Int, val days: Int)

data class Profile(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val apps: Set<String>,
    val keywords: Set<String>,
    val domains: Set<String>,
    val schedules: List<Sched>,
    val limitMin: Int
)

object ProfileStore {
    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    fun defaults(): List<Profile> = listOf(
        Profile("kerja", "Kerja", false, emptySet(), emptySet(), emptySet(), listOf(Sched(8 * 60, 17 * 60, 31)), 0),
        Profile("belajar", "Belajar", false, emptySet(), emptySet(), emptySet(), listOf(Sched(19 * 60, 21 * 60, 127)), 0),
        Profile("tidur", "Tidur", false, emptySet(), emptySet(), emptySet(), listOf(Sched(21 * 60, 5 * 60, 127)), 0),
        Profile("ketat", "Mode Ketat", false, emptySet(), emptySet(), emptySet(), emptyList(), 0),
        Profile("custom", "Custom", false, emptySet(), emptySet(), emptySet(), emptyList(), 0)
    )

    private fun toSet(a: JSONArray?): Set<String> {
        val out = HashSet<String>()
        if (a == null) return out
        for (i in 0 until a.length()) out.add(a.getString(i))
        return out
    }

    fun load(c: Context): List<Profile> {
        val raw = sp(c).getString("profiles", null) ?: return defaults()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Profile>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sch = ArrayList<Sched>()
                val sa = o.optJSONArray("sched")
                if (sa != null) {
                    for (j in 0 until sa.length()) {
                        val s = sa.getJSONObject(j)
                        sch.add(Sched(s.getInt("s"), s.getInt("e"), s.getInt("d")))
                    }
                }
                out.add(
                    Profile(
                        o.getString("id"), o.getString("name"), o.optBoolean("on", false),
                        toSet(o.optJSONArray("apps")), toSet(o.optJSONArray("kw")), toSet(o.optJSONArray("dm")),
                        sch, o.optInt("limit", 0)
                    )
                )
            }
            if (out.isEmpty()) defaults() else out
        } catch (t: Throwable) {
            defaults()
        }
    }

    fun save(c: Context, list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("on", p.enabled)
            o.put("apps", JSONArray(p.apps.toList()))
            o.put("kw", JSONArray(p.keywords.toList()))
            o.put("dm", JSONArray(p.domains.toList()))
            val sa = JSONArray()
            for (s in p.schedules) {
                val so = JSONObject()
                so.put("s", s.start)
                so.put("e", s.end)
                so.put("d", s.days)
                sa.put(so)
            }
            o.put("sched", sa)
            o.put("limit", p.limitMin)
            arr.put(o)
        }
        sp(c).edit().putString("profiles", arr.toString()).commit()
        ScheduleNotifier.rearm(c)
    }

    /** Tambah profil baru (dinyalakan, belum berisi aturan). */
    fun add(c: Context, name: String): Profile {
        val p = Profile("p" + System.currentTimeMillis(), name.trim(), true, emptySet(), emptySet(), emptySet(), emptyList(), 0)
        save(c, load(c) + p)
        return p
    }

    fun rename(c: Context, id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        save(c, load(c).map { if (it.id == id) it.copy(name = n) else it })
    }

    /** Hapus profil. Profil terakhir tidak boleh dihapus. */
    fun remove(c: Context, id: String): Boolean {
        val list = load(c)
        if (list.size <= 1) return false
        save(c, list.filter { it.id != id })
        return true
    }

    fun replace(c: Context, p: Profile) {
        val list = load(c).map { if (it.id == p.id) p else it }
        save(c, list)
    }

    /** Saat Mode Ketat aktif: perubahan hanya boleh memperketat (menambah), tidak mengurangi. */
    fun mergeStrict(old: Profile, new: Profile): Profile {
        val limit = if (old.limitMin <= 0) new.limitMin
        else if (new.limitMin <= 0) old.limitMin
        else Math.min(old.limitMin, new.limitMin)
        val sch = ArrayList(old.schedules)
        for (s in new.schedules) if (!sch.contains(s)) sch.add(s)
        return old.copy(
            enabled = old.enabled || new.enabled,
            apps = old.apps + new.apps,
            keywords = old.keywords + new.keywords,
            domains = old.domains + new.domains,
            schedules = sch,
            limitMin = limit
        )
    }
}

object ProfileEngine {
    fun isActive(c: Context, p: Profile): Boolean {
        if (!p.enabled) return false
        if (p.schedules.isEmpty()) return true
        if (TimeGuard.penaltyActive(c)) return true
        for (s in p.schedules) if (Rules.scheduleActive(s.start, s.end, s.days)) return true
        return false
    }
}

object BlockEngine {
    /**
     * Mengembalikan alasan blokir ("until", "app", "limit", "profile") atau null bila boleh dipakai.
     */
    fun check(c: Context, pkg: String, now: Long, apps: Set<String>, profiles: List<Profile>): String? {
        if (Emergency.activeFor(c, pkg)) return null
        if (pkg in apps) {
            val cfg = Store.appCfg(c, pkg)
            if (cfg.blockUntil > now) return "until"
            if (Rules.dayActive(c, cfg.days)) {
                if (LocationRules.blocked(c, "app_$pkg")) return "location"
                // kondisi keras: tidak bisa ditembus dengan pass
                if (cfg.twUse > 0 && cfg.twWait > 0 && WaitTimer.remaining(c, pkg, cfg, now) > 0L) return "wait"
                if (cfg.hourLimit > 0 && UsageTracker.hourMs(c, pkg) >= cfg.hourLimit * 60000L) return "hour"
                if (cfg.launchDay > 0 && UsageTracker.launchesToday(c, pkg) > cfg.launchDay) return "launch"
                if (cfg.launchHour > 0 && UsageTracker.launchesHour(c, pkg) > cfg.launchHour) return "launch"
                val passOk = Store.passUntil(c, pkg) > now
                if (!passOk) {
                    if (cfg.mode == 0 && Rules.blockingActive(c, cfg)) return "app"
                    if (cfg.limitMin > 0 && UsageTracker.todayMs(c, pkg) >= cfg.limitMin * 60000L) return "limit"
                }
            }
        }
        // Mode Fokus: lapisan sementara setelah aturan permanen (aturan permanen selalu didahulukan).
        if (Focus.blocksApp(c, pkg)) return "focus"
        for (p in profiles) {
            if (pkg !in p.apps || !ProfileEngine.isActive(c, p)) continue
            if (p.limitMin <= 0) return "profile"
            if (UsageTracker.todayMs(c, pkg) >= p.limitMin * 60000L) return "profile"
        }
        return null
    }

    /** True bila aplikasi punya batas harian (supaya perlu dicek berkala selama dipakai). */
    fun hasLimit(c: Context, pkg: String, apps: Set<String>, profiles: List<Profile>): Boolean {
        if (pkg in apps) {
            val g = Store.appCfg(c, pkg)
            if (g.limitMin > 0 || g.hourLimit > 0 || (g.twUse > 0 && g.twWait > 0)) return true
        }
        for (p in profiles) {
            if (pkg in p.apps && p.limitMin > 0 && ProfileEngine.isActive(c, p)) return true
        }
        return false
    }
}
