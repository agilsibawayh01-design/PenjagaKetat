package com.mas.strictguard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/** Pemakaian aplikasi (hari ini dan 60 menit terakhir) dari event UsageStats, termasuk sesi yang sedang berjalan. */
object UsageTracker {
    class DayUsage(
        val ms: Map<String, Long>,
        val last: Map<String, Long>,
        val launches: Map<String, Int>
    )

    private val EMPTY = DayUsage(emptyMap(), emptyMap(), emptyMap())
    private var dayAt = 0L
    private var dayV = EMPTY
    private var hourAt = 0L
    private var hourV = EMPTY
    private var statsAt = 0L

    fun startOfDay(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Synchronized
    fun get(c: Context): DayUsage {
        val now = System.currentTimeMillis()
        if (now - dayAt < 3000L) return dayV
        dayAt = now
        dayV = try {
            monotonic(compute(c, startOfDay(), startOfDay(), now))
        } catch (t: Throwable) {
            dayV
        }
        if (now - statsAt > 60000L && dayV.ms.isNotEmpty()) {
            statsAt = now
            try {
                StatsDb.upsertDay(c, StatsDb.dayKey(now), dayV)
            } catch (t: Throwable) {
                // abaikan
            }
        }
        return dayV
    }

    @Synchronized
    fun hour(c: Context): DayUsage {
        val now = System.currentTimeMillis()
        if (now - hourAt < 3000L) return hourV
        hourAt = now
        hourV = try {
            compute(c, now - 3600000L, now - 3 * 3600000L, now)
        } catch (t: Throwable) {
            EMPTY
        }
        return hourV
    }

    private var maxDay = 0L
    private val maxMs = HashMap<String, Long>()

    /** Pemakaian hari ini tidak pernah berkurang dalam hari yang sama: cegah angka mundur akibat event yang terlambat atau hilang. */
    private fun monotonic(v: DayUsage): DayUsage {
        val d = startOfDay()
        if (d != maxDay) {
            maxDay = d
            maxMs.clear()
        }
        val merged = HashMap<String, Long>(v.ms)
        for ((k, old) in maxMs) {
            val cur = merged[k] ?: 0L
            if (old > cur) merged[k] = old
        }
        for ((k, x) in merged) maxMs[k] = x
        return DayUsage(merged, v.last, v.launches)
    }

    fun todayMs(c: Context, pkg: String): Long = get(c).ms[pkg] ?: 0L
    fun hourMs(c: Context, pkg: String): Long = hour(c).ms[pkg] ?: 0L
    fun launchesToday(c: Context, pkg: String): Int = get(c).launches[pkg] ?: 0
    fun launchesHour(c: Context, pkg: String): Int = hour(c).launches[pkg] ?: 0

    /** Hitung pemakaian pada jendela [winStart, now]; event dibaca sejak queryStart agar sesi yang mulai lebih awal ikut terhitung. */
    private fun compute(c: Context, winStart: Long, queryStart: Long, now: Long): DayUsage {
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(queryStart, now)
        val e = UsageEvents.Event()
        // Sebuah aplikasi dianggap terbuka selama masih ada SATU saja activity-nya yang aktif.
        // (Melacak per aplikasi saja membuat hitungan mundur dan macet saat berpindah layar dalam aplikasi.)
        val openCls = HashMap<String, HashSet<String>>()
        val startAt = HashMap<String, Long>()
        val closedAt = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        val last = HashMap<String, Long>()
        val launches = HashMap<String, Int>()

        fun addSeg(p: String, s: Long, t: Long) {
            val from = Math.max(s, winStart)
            if (t > from) {
                total[p] = (total[p] ?: 0L) + (t - from)
                last[p] = t
            }
        }

        fun closeCls(p: String, cls: String, t: Long) {
            val set = openCls[p] ?: return
            if (set.remove(cls) && set.isEmpty()) {
                val s = startAt.remove(p)
                if (s != null) {
                    addSeg(p, s, t)
                    closedAt[p] = t
                }
            }
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val p: String = e.packageName ?: continue
            val t = e.timeStamp
            val cls = e.className ?: ""
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val set = openCls.getOrPut(p) { HashSet() }
                    if (set.isEmpty()) {
                        startAt[p] = t
                        val ca = closedAt[p]
                        // pindah layar dalam satu aplikasi (jeda < 5 dtk) bukan peluncuran baru
                        if (t >= winStart && (ca == null || t - ca > 5000L)) {
                            launches[p] = (launches[p] ?: 0) + 1
                        }
                    }
                    set.add(cls)
                    if (t >= winStart) last[p] = t
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED, 24 -> closeCls(p, cls, t)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    for ((k, s) in startAt) {
                        addSeg(k, s, t)
                        closedAt[k] = t
                    }
                    startAt.clear()
                    openCls.clear()
                }
            }
        }
        for ((k, s) in startAt) addSeg(k, s, now)
        return DayUsage(total, last, launches)
    }
}
