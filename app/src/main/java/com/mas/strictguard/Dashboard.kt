package com.mas.strictguard

import android.content.Context
import android.content.pm.ApplicationInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ringkasan penggunaan untuk Dashboard. Hanya MEMBACA data yang sudah dikumpulkan
 * (UsageTracker, StatsDb, SiteTracker); tidak ada pelacakan baru. Hasil di-cache 30 detik.
 */
object Dashboard {
    class Item(val key: String, val label: String, val ms: Long)
    class DayRow(val day: String, val label: String, val ms: Long, val blocks: Int)

    class Snap(
        val at: Long,
        val usageAccess: Boolean,
        val todayMs: Long,
        val yesterdayMs: Long,
        val blocksToday: Int,
        val attemptsToday: Int,
        val reasonsToday: List<Pair<String, Int>>,
        val topAppsToday: List<Item>,
        val topSitesToday: List<Item>,
        val appCategories: List<Pair<String, Long>>,
        val siteCategories: List<Pair<String, Long>>,
        val days: List<DayRow>,
        val weekMs: Long,
        val prevWeekMs: Long,
        val blocksWeek: Int,
        val topAppsWeek: List<Item>,
        val topSitesWeek: List<Item>
    )

    @Volatile
    private var cache: Snap? = null

    private val NOT_ATTEMPT = setOf("protect", "content", "dns")

    fun get(c: Context, force: Boolean = false): Snap {
        val old = cache
        val now = System.currentTimeMillis()
        if (!force && old != null && now - old.at < 30000L) return old
        val s = build(c.applicationContext, now)
        cache = s
        return s
    }

    fun dur(ms: Long): String {
        val min = ms / 60000L
        return when {
            ms <= 0L -> "0m"
            min < 1L -> "<1m"
            min < 60L -> "${min}m"
            else -> "${min / 60L}j ${min % 60L}m"
        }
    }

    private fun label(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun categoryName(cat: Int): String = when (cat) {
        ApplicationInfo.CATEGORY_GAME -> "Game"
        ApplicationInfo.CATEGORY_AUDIO -> "Musik dan audio"
        ApplicationInfo.CATEGORY_VIDEO -> "Video"
        ApplicationInfo.CATEGORY_IMAGE -> "Foto"
        ApplicationInfo.CATEGORY_SOCIAL -> "Media sosial"
        ApplicationInfo.CATEGORY_NEWS -> "Berita"
        ApplicationInfo.CATEGORY_MAPS -> "Peta dan navigasi"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Produktivitas"
        else -> "Lainnya"
    }

    private fun siteCategory(domain: String): String {
        for (cat in Categories.ALL) {
            if (cat.domains.any { domain == it || domain.endsWith(".$it") }) return cat.name
        }
        return "Lainnya"
    }

    private fun build(c: Context, now: Long): Snap {
        val pm = c.packageManager
        val usageAccess = Foreground.hasUsageAccess(c)
        val countable = HashMap<String, Boolean>()
        fun ok(pkg: String): Boolean = countable.getOrPut(pkg) {
            pkg != c.packageName && pkg !in Config.IGNORED && pkg !in Config.PROTECTED &&
                pm.getLaunchIntentForPackage(pkg) != null
        }

        val dayKeys = ArrayList<String>() // 14 hari, terbaru dulu
        for (i in 0 until 14) dayKeys.add(StatsDb.dayKey(now - i * 86400000L))
        val todayKey = dayKeys[0]

        // pemakaian aplikasi per hari (hari ini dari data langsung)
        val perDay = HashMap<String, HashMap<String, Long>>()
        try {
            for ((d, p, ms) in StatsDb.usageRows(c, 14)) {
                if (d != todayKey && ok(p)) perDay.getOrPut(d) { HashMap() }[p] = ms
            }
        } catch (t: Throwable) {
            // lanjut tanpa riwayat
        }
        val todayMap = HashMap<String, Long>()
        if (usageAccess) {
            try {
                for ((p, ms) in UsageTracker.get(c).ms) if (ok(p)) todayMap[p] = ms
            } catch (t: Throwable) {
                // abaikan
            }
        }
        perDay[todayKey] = todayMap

        fun total(day: String): Long = perDay[day]?.values?.sum() ?: 0L

        // blokir
        val blocksByDay = try { StatsDb.blocksPerDay(c, 14) } catch (t: Throwable) { emptyMap() }
        val reasons = try { StatsDb.blocksByReason(c, UsageTracker.startOfDay()) } catch (t: Throwable) { emptyList() }
        val blocksToday = reasons.sumOf { it.second }
        val attempts = reasons.filter { it.first !in NOT_ATTEMPT }.sumOf { it.second }

        // situs
        val siteDays = try { SiteTracker.dayTotals(c, 14) } catch (t: Throwable) { emptyMap() }
        fun siteKey(day: String) = day.replace("-", "")

        fun aggApps(daysList: List<String>): List<Item> {
            val m = HashMap<String, Long>()
            for (d in daysList) for ((p, ms) in perDay[d] ?: emptyMap()) m[p] = (m[p] ?: 0L) + ms
            return m.entries.sortedByDescending { it.value }.take(8).map { Item(it.key, label(c, it.key), it.value) }
        }

        fun aggSites(daysList: List<String>): List<Item> {
            val m = HashMap<String, Long>()
            for (d in daysList) for ((s, ms) in siteDays[siteKey(d)] ?: emptyMap()) m[s] = (m[s] ?: 0L) + ms
            return m.entries.filter { it.value > 0L }.sortedByDescending { it.value }.take(6).map { Item(it.key, it.key, it.value) }
        }

        val week = dayKeys.subList(0, 7)
        val prev = dayKeys.subList(7, 14)

        val appCats = HashMap<String, Long>()
        for ((p, ms) in todayMap) {
            val cat = try { categoryName(pm.getApplicationInfo(p, 0).category) } catch (e: Exception) { "Lainnya" }
            appCats[cat] = (appCats[cat] ?: 0L) + ms
        }
        val siteCats = HashMap<String, Long>()
        for ((s, ms) in siteDays[siteKey(todayKey)] ?: emptyMap()) {
            val cat = siteCategory(s)
            siteCats[cat] = (siteCats[cat] ?: 0L) + ms
        }

        val fmt = SimpleDateFormat("EEE d", Locale("id"))
        val rows = ArrayList<DayRow>()
        for (i in 6 downTo 0) {
            val d = dayKeys[i]
            val dt = Date(now - i * 86400000L)
            rows.add(DayRow(d, fmt.format(dt), total(d), blocksByDay[d] ?: 0))
        }

        return Snap(
            at = now,
            usageAccess = usageAccess,
            todayMs = total(todayKey),
            yesterdayMs = total(dayKeys[1]),
            blocksToday = blocksToday,
            attemptsToday = attempts,
            reasonsToday = reasons,
            topAppsToday = aggApps(listOf(todayKey)).take(6),
            topSitesToday = aggSites(listOf(todayKey)).take(5),
            appCategories = appCats.entries.sortedByDescending { it.value }.map { it.key to it.value },
            siteCategories = siteCats.entries.sortedByDescending { it.value }.map { it.key to it.value },
            days = rows,
            weekMs = week.sumOf { total(it) },
            prevWeekMs = prev.sumOf { total(it) },
            blocksWeek = week.sumOf { blocksByDay[it] ?: 0 },
            topAppsWeek = aggApps(week),
            topSitesWeek = aggSites(week)
        )
    }

    /** Riwayat 14 hari untuk satu aplikasi ([isSite] = false) atau situs: (hari, label, ms, blokir). */
    fun history(c: Context, key: String, isSite: Boolean): List<DayRow> {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("EEE d MMM", Locale("id"))
        val blocks = try { StatsDb.blocksPerDay(c, 14, key) } catch (t: Throwable) { emptyMap() }
        val ms = HashMap<String, Long>()
        if (isSite) {
            val sd = try { SiteTracker.dayTotals(c, 14) } catch (t: Throwable) { emptyMap() }
            for (i in 0 until 14) {
                val k = StatsDb.dayKey(now - i * 86400000L)
                ms[k] = sd[k.replace("-", "")]?.get(key) ?: 0L
            }
        } else {
            try {
                for ((d, p, v) in StatsDb.usageRows(c, 14)) if (p == key) ms[d] = v
            } catch (t: Throwable) {
                // abaikan
            }
            if (Foreground.hasUsageAccess(c)) ms[StatsDb.dayKey(now)] = UsageTracker.todayMs(c, key)
        }
        val out = ArrayList<DayRow>()
        for (i in 13 downTo 0) {
            val k = StatsDb.dayKey(now - i * 86400000L)
            out.add(DayRow(k, fmt.format(Date(now - i * 86400000L)), ms[k] ?: 0L, blocks[k] ?: 0))
        }
        return out
    }
}
