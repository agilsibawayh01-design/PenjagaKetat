package com.mas.strictguard

import android.content.Context
import java.security.SecureRandom
import java.util.Calendar

object Rules {
    /** True bila blokir aplikasi sedang berlaku (selalu, atau sesuai jadwal). */
    fun blockingActive(c: Context, g: AppCfg): Boolean {
        if (TimeGuard.penaltyActive(c)) return true
        if (!g.schOn) return dayActive(c, g.days)
        return scheduleActive(g.start, g.end, g.days)
    }

    /** True bila hari ini termasuk hari yang dipilih (Senin=bit 0 .. Minggu=bit 6). Penalti jam = selalu aktif. */
    fun dayActive(c: Context, days: Int): Boolean {
        if (TimeGuard.penaltyActive(c)) return true
        val today = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
        return (days and (1 shl today)) != 0
    }

    /** True bila sekarang berada dalam jendela jadwal (mendukung jadwal lewat tengah malam). */
    fun scheduleActive(s: Int, e: Int, days: Int): Boolean {
        val now = Calendar.getInstance()
        val today = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Senin=0 .. Minggu=6
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val inWindow: Boolean
        var dayIdx = today
        if (s <= e) {
            inWindow = minute >= s && minute < e
        } else {
            inWindow = minute >= s || minute < e
            if (minute < e) dayIdx = (today + 6) % 7
        }
        return inWindow && (days and (1 shl dayIdx)) != 0
    }

    fun matchesKeyword(text: String, keywords: Collection<String>): String? {
        val t = text.lowercase()
        for (k in keywords) {
            val kk = k.trim().lowercase()
            if (kk.isEmpty()) continue
            if (kk.length >= 5) {
                if (t.contains(kk)) return k
            } else {
                var idx = t.indexOf(kk)
                while (idx >= 0) {
                    val before = idx == 0 || !t[idx - 1].isLetterOrDigit()
                    val end = idx + kk.length
                    val after = end >= t.length || !t[end].isLetterOrDigit()
                    if (before && after) return k
                    idx = t.indexOf(kk, idx + 1)
                }
            }
        }
        return null
    }

    private val HOST = Regex("[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}")

    /** Rapikan input situs: huruf kecil, tanpa skema, tanpa "*." dan "www.", tanpa path. */
    fun normalizeDomain(raw: String): String {
        var d = raw.trim().lowercase()
        d = d.removePrefix("https://").removePrefix("http://")
        d = d.removePrefix("*.").removePrefix("www.")
        val slash = d.indexOf('/')
        if (slash >= 0) d = d.substring(0, slash)
        return d.trim().trim('.')
    }

    /**
     * Cocokkan situs beserta subdomainnya: "xvideos.com" cocok untuk "m.xvideos.com" dan "www.xvideos.com",
     * tetapi "x.com" tidak cocok untuk "netflix.com".
     */
    fun matchesDomain(text: String, domains: Collection<String>): String? {
        val t = text.lowercase()
        val hosts = HOST.findAll(t).map { it.value.removePrefix("www.") }.toList()
        for (d in domains) {
            val dd = normalizeDomain(d)
            if (dd.isEmpty()) continue
            for (h in hosts) {
                if (h == dd || h.endsWith(".$dd")) return d
            }
        }
        return null
    }

    fun randomText(length: Int = 16): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        val sb = StringBuilder()
        for (i in 0 until length) sb.append(chars[r.nextInt(chars.length)])
        return sb.toString()
    }
}
