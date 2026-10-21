package com.mas.strictguard

import android.content.Context
import java.security.SecureRandom
import java.util.Calendar

object Rules {
    /** True bila blokir aplikasi sedang berlaku (selalu, atau sesuai jadwal). */
    fun blockingActive(c: Context): Boolean {
        if (!Store.scheduleEnabled(c)) return true
        val now = Calendar.getInstance()
        val today = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Senin=0 .. Minggu=6
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val s = Store.startMin(c)
        val e = Store.endMin(c)
        val inWindow: Boolean
        var dayIdx = today
        if (s <= e) {
            inWindow = minute >= s && minute < e
        } else {
            inWindow = minute >= s || minute < e
            if (minute < e) dayIdx = (today + 6) % 7
        }
        return inWindow && (Store.days(c) and (1 shl dayIdx)) != 0
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

    fun matchesDomain(text: String, domains: Collection<String>): String? {
        val t = text.lowercase()
        for (d in domains) {
            val dd = d.trim().lowercase()
            if (dd.isNotEmpty() && t.contains(dd)) return d
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
