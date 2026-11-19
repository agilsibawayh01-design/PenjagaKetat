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

    /** Kata wajar yang mengandung potongan kata kunci (mis. "condominium" memuat "condom"). Dibuang sebelum pemeriksaan ulang. */
    val WHITELIST = listOf(
        "condominium", "condominiums", "essex", "sussex", "middlesex", "wessex", "sextant", "sexton", "sexagenarian",
        "invagination", "analisis", "analisa", "analis", "analitik", "analog", "analgesik", "cockpit", "cocktail",
        "peacock", "hancock", "babcock", "hitchcock", "scunthorpe", "penistone", "kontraseptif", "nudibranch"
    )

    /** typing=true: kata pendek di ujung teks dianggap belum selesai diketik (mis. "anal" saat mengetik "analisis"). */
    fun matchesKeyword(text: String, keywords: Collection<String>, typing: Boolean = false): String? {
        val t = text.lowercase()
        val hit = scanKeywords(t, keywords, typing) ?: return null
        var cleaned = t
        var changed = false
        for (w in WHITELIST) {
            if (cleaned.contains(w)) { cleaned = cleaned.replace(w, " "); changed = true }
        }
        return if (!changed) hit else scanKeywords(cleaned, keywords, typing)
    }

    private fun scanKeywords(t: String, keywords: Collection<String>, typing: Boolean): String? {
        for (k in keywords) {
            val kk = k.trim().lowercase()
            if (kk.isEmpty()) continue
            if (kk.contains(' ')) {
                // Frasa beberapa kata: harus diawali batas kata, supaya "Te|gang bang|et" tidak cocok dengan "gang bang".
                var idx = t.indexOf(kk)
                while (idx >= 0) {
                    if (idx == 0 || !t[idx - 1].isLetterOrDigit()) return k
                    idx = t.indexOf(kk, idx + 1)
                }
            } else if (kk.length >= 5) {
                if (t.contains(kk)) return k
            } else {
                var idx = t.indexOf(kk)
                while (idx >= 0) {
                    val before = idx == 0 || !t[idx - 1].isLetterOrDigit()
                    val end = idx + kk.length
                    if (typing && end >= t.length) break
                    val after = end >= t.length || !t[end].isLetterOrDigit()
                    if (before && after) return k
                    idx = t.indexOf(kk, idx + 1)
                }
            }
        }
        return null
    }

    private val IPV4_URL = Regex("^(?:[a-z][a-z0-9+.-]*://)?(?:[^/@\\s]*@)?(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?::\\d+)?(?:[/?#].*)?$")

    /**
     * True bila teks di address bar berupa alamat IP publik (mis. 178.62.96.212/halaman). Alamat IP
     * melewati filter DNS berbasis nama, jadi diblokir. IP lokal/jaringan rumah (192.168.x.x, 10.x, dll.) boleh.
     */
    fun isPublicIpUrl(text: String): Boolean {
        val m = IPV4_URL.find(text.trim().lowercase()) ?: return false
        val o = m.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
        if (o.any { it > 255 }) return false
        val a = o[0]
        val b = o[1]
        if (a == 10 || a == 127 || a == 0) return false
        if (a == 192 && b == 168) return false
        if (a == 172 && b in 16..31) return false
        if (a == 169 && b == 254) return false
        return true
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
