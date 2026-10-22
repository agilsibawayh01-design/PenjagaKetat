package com.mas.strictguard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/** Pemakaian aplikasi hari ini, dihitung dari event UsageStats (termasuk sesi yang sedang berjalan). */
object UsageTracker {
    class DayUsage(val ms: Map<String, Long>, val last: Map<String, Long>)

    private var cacheAt = 0L
    private var cache = DayUsage(emptyMap(), emptyMap())

    private fun startOfDay(): Long {
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
        if (now - cacheAt < 15000L) return cache
        cacheAt = now
        cache = try {
            compute(c, now)
        } catch (t: Throwable) {
            DayUsage(emptyMap(), emptyMap())
        }
        return cache
    }

    fun todayMs(c: Context, pkg: String): Long = get(c).ms[pkg] ?: 0L

    private fun compute(c: Context, now: Long): DayUsage {
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(startOfDay(), now)
        val e = UsageEvents.Event()
        val opened = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        val last = HashMap<String, Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val p: String = e.packageName ?: continue
            val t = e.timeStamp
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (!opened.containsKey(p)) opened[p] = t
                    last[p] = t
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val s = opened.remove(p)
                    if (s != null) {
                        total[p] = (total[p] ?: 0L) + (t - s)
                        last[p] = t
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    for ((k, s) in opened) {
                        total[k] = (total[k] ?: 0L) + (t - s)
                        last[k] = t
                    }
                    opened.clear()
                }
            }
        }
        for ((k, s) in opened) {
            total[k] = (total[k] ?: 0L) + (now - s)
            last[k] = now
        }
        return DayUsage(total, last)
    }
}
