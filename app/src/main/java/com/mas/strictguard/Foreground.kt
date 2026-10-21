package com.mas.strictguard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

object Foreground {
    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Paket aplikasi yang terakhir dibuka (10 menit terakhir). Kosong bila tidak diketahui. */
    fun current(c: Context): String {
        return try {
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val ev = usm.queryEvents(now - 10L * 60L * 1000L, now)
            val e = UsageEvents.Event()
            var cur = ""
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) cur = e.packageName ?: ""
                else if (e.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) cur = ""
            }
            cur
        } catch (t: Throwable) {
            ""
        }
    }
}
