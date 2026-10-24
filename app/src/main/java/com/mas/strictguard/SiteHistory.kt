package com.mas.strictguard

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SiteHistoryEntry(
    val domain: String,
    val lastOpened: Long,
    val visits: Int
)

object SiteHistory {
    private const val PREF = "strictguard_site_history"
    private const val INDEX = "domains"
    private const val MAX_ENTRIES = 100

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Synchronized
    fun record(c: Context, domainRaw: String) {
        val domain = mainDomain(domainRaw)
        if (domain.isEmpty()) return

        val p = sp(c)
        val old = p.getString("h_$domain", null)
        val parts = old?.split('|')
        val oldAt = parts?.getOrNull(0)?.toLongOrNull() ?: 0L
        val oldVisits = parts?.getOrNull(1)?.toIntOrNull() ?: 0
        val now = System.currentTimeMillis()
        // Accessibility mengirim banyak event untuk satu halaman. Jangan hitung
        // setiap event sebagai kunjungan baru.
        val visits = if (oldAt > 0L && now - oldAt < 30000L) {
            oldVisits.coerceAtLeast(1)
        } else {
            oldVisits + 1
        }

        val domains = HashSet(p.getStringSet(INDEX, emptySet()) ?: emptySet())
        domains.add(domain)

        p.edit()
            .putString("h_$domain", "$now|$visits")
            .putStringSet(INDEX, domains)
            .apply()

        trim(c)
    }

    fun fromRoot(c: Context, root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val raw = findUrlText(root) ?: return null
        return extractHost(raw)?.let(::mainDomain)
    }

    @Synchronized
    fun recordFromRoot(c: Context, root: AccessibilityNodeInfo?) {
        fromRoot(c, root)?.let { record(c, it) }
    }

    fun list(c: Context, limit: Int = 30): List<SiteHistoryEntry> {
        val p = sp(c)
        val domains = p.getStringSet(INDEX, emptySet()) ?: emptySet()
        val out = ArrayList<SiteHistoryEntry>()
        for (d in domains) {
            val parts = p.getString("h_$d", null)?.split('|') ?: continue
            val last = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val visits = parts.getOrNull(1)?.toIntOrNull() ?: 1
            out.add(SiteHistoryEntry(d, last, visits))
        }
        return out.sortedByDescending { it.lastOpened }.take(limit)
    }

    fun formatTime(time: Long): String =
        SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(time))

    private fun trim(c: Context) {
        val p = sp(c)
        val domains = p.getStringSet(INDEX, emptySet()) ?: return
        if (domains.size <= MAX_ENTRIES) return

        val keep = domains.mapNotNull { d ->
            val last = p.getString("h_$d", null)?.substringBefore('|')?.toLongOrNull()
            if (last != null) d to last else null
        }.sortedByDescending { it.second }.take(MAX_ENTRIES).map { it.first }.toSet()

        val e = p.edit().putStringSet(INDEX, keep)
        for (d in domains) {
            if (d !in keep) e.remove("h_$d")
        }
        e.apply()
    }

    private fun findUrlText(root: AccessibilityNodeInfo): String? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var count = 0
        while (q.isNotEmpty() && count++ < 600) {
            val n = q.removeFirst()
            val id = (n.viewIdResourceName ?: "").lowercase(Locale.US)
            if (isUrlId(id)) {
                val text = n.text?.toString()?.trim()
                val desc = n.contentDescription?.toString()?.trim()
                if (!text.isNullOrBlank()) return text
                if (!desc.isNullOrBlank()) return desc
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let(q::add)
            }
        }
        return null
    }

    private fun isUrlId(id: String): Boolean =
        id.contains("url") || id.contains("location_bar") || id.contains("omnibox") ||
            id.contains("address_bar") || id.contains("url_bar") || id.endsWith(":url")

    private fun extractHost(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        return try {
            val candidate = if (value.contains("://")) value else "https://$value"
            URI(candidate).host?.lowercase(Locale.US)?.removePrefix("www.")
                ?.takeIf { it.isNotEmpty() && !it.contains(" ") && !it.all { ch -> ch.isDigit() || ch == '.' } }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Ambil domain utama tanpa library tambahan.
     * Contoh:
     *   www.youtube.com -> youtube.com
     *   news.detik.com -> detik.com
     *   example.co.id -> example.co.id
     */
    private fun mainDomain(hostRaw: String): String {
        var host = Rules.normalizeDomain(hostRaw).removePrefix("www.")
        if (host.isEmpty()) return ""

        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return host

        val suffix2 = parts.takeLast(2).joinToString(".")
        val secondLevel = setOf(
            "co.id", "or.id", "go.id", "ac.id", "sch.id", "web.id",
            "co.uk", "org.uk", "me.uk", "ac.uk",
            "com.au", "net.au", "org.au",
            "co.nz", "org.nz",
            "co.jp", "ne.jp", "or.jp",
            "com.br", "com.tr", "com.cn", "com.sg", "com.my", "com.ph",
            "co.in", "firm.in", "net.in", "org.in"
        )
        return if (suffix2 in secondLevel && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }
}
