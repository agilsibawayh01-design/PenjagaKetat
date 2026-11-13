package com.mas.strictguard

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.net.URI
import java.util.ArrayDeque
import java.util.Locale

/**
 * Mengenali "sumber" konten yang sedang tampil, untuk blokir otomatis setelah pelanggaran NSFW berulang:
 * - Browser (Chrome): domain dari kolom alamat.
 * - Telegram resmi: nama channel dari judul layar obrolan (hanya bila layar itu menampilkan jumlah pelanggan).
 * Bila identitas tidak bisa dipastikan, hasilnya null dan tidak ada yang diblokir otomatis.
 */
object NsfwSource {
    data class Src(val kind: String, val name: String) {
        val key: String get() = "$kind|$name"
    }

    val TG_PACKAGES = setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta")

    /** Domain gerbang yang tidak pernah diblokir otomatis (mesin pencari dan Telegram web). */
    private val NEVER_DOMAIN = setOf(
        "google.com", "bing.com", "duckduckgo.com", "yahoo.com", "ecosia.org", "brave.com",
        "web.telegram.org", "t.me", "telegram.org", "telegram.me"
    )
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "or", "go", "ac", "gov", "edu", "web", "sch")
    private val SUB_RE = Regex("(?i)\\d[\\d.,\\s]*(k|m|rb|jt|ribu|juta)?\\s*(subscribers?|pelanggan|pengikut)\\b")
    private val COMBINED_RE = Regex("(?i)^(.{2,80}?)\\s*[,\\n]\\s*\\d[\\d.,\\s]*(k|m|rb|jt|ribu|juta)?\\s*(subscribers?|pelanggan|pengikut)\\b")

    /** Murni: ambil host dari teks kolom alamat; null bila bukan alamat. */
    fun hostOf(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 300 || t.contains(' ')) return null
        return try {
            val u = URI(if (t.contains("://")) t else "https://$t")
            u.host?.lowercase(Locale.US)?.removePrefix("www.")?.takeIf { it.contains('.') }
        } catch (e: Throwable) {
            null
        }
    }

    /** Murni: domain utama (mis. video.contoh.co.id -> contoh.co.id). */
    fun registrable(host: String): String {
        val l = host.split('.').filter { it.isNotEmpty() }
        if (l.size <= 2) return host
        return if (l.size >= 3 && l[l.size - 1].length == 2 && l[l.size - 2] in SECOND_LEVEL) l.takeLast(3).joinToString(".")
        else l.takeLast(2).joinToString(".")
    }

    fun isIp(h: String): Boolean = h.all { it.isDigit() || it == '.' } || h.contains(':')

    fun isSubtitle(s: String): Boolean = s.length <= 40 && SUB_RE.containsMatchIn(s)

    /** Murni: "Nama Channel, 1,2K pelanggan" -> "Nama Channel". */
    fun titleFromCombined(s: String): String? = COMBINED_RE.find(s.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 }

    fun domainAllowed(reg: String): Boolean = reg.contains('.') && !isIp(reg) && reg !in NEVER_DOMAIN

    fun identify(root: AccessibilityNodeInfo?, pkg: String, browsers: Set<String>, screenHeight: Int): Src? {
        if (root == null) return null
        return try {
            when {
                pkg in browsers -> urlDomain(root)?.let { Src("d", it) }
                pkg in TG_PACKAGES -> channelTitle(root, screenHeight)?.let { Src("c", it) }
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun urlDomain(root: AccessibilityNodeInfo): String? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n++ < 500) {
            val node = q.removeFirst()
            val id = (node.viewIdResourceName ?: "").lowercase(Locale.US)
            if (id.contains("url") || id.contains("location_bar") || id.contains("omnibox") || id.contains("address")) {
                val h = hostOf(node.text?.toString() ?: "") ?: hostOf(node.contentDescription?.toString() ?: "")
                if (h != null) {
                    val reg = registrable(h)
                    return if (domainAllowed(reg)) reg else null
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private val GROUP_RE = Regex("(?i)\\d[\\d.,\\s]*(k|m|rb|jt|ribu|juta)?\\s*(members?|anggota|peserta)\\b")
    private val BOT_RE = Regex("(?i)^(bot|\\d[\\d.,\\s]*(k|m|rb|jt)?\\s*(monthly users|pengguna bulanan))\\b")
    private val USER_RE = Regex("(?i)(\\bonline\\b|terakhir dilihat|last seen|baru saja|sedang mengetik|is typing|dilihat)")

    /** Murni: jenis chat dari teks subjudul: "c" channel, "g" grup, "b" bot, "u" pengguna, null = bukan subjudul chat. */
    fun subtitleKind(s: String): String? {
        val t = s.trim()
        if (t.isEmpty() || t.length > 60) return null
        return when {
            SUB_RE.containsMatchIn(t) -> "c"
            GROUP_RE.containsMatchIn(t) -> "g"
            BOT_RE.containsMatchIn(t) -> "b"
            USER_RE.containsMatchIn(t) -> "u"
            else -> null
        }
    }

    /** Murni: apakah judul chat termasuk daftar boleh (cocok "mengandung", tanpa membedakan huruf besar kecil). */
    fun titleAllowed(title: String, allow: Set<String>): Boolean {
        val t = title.trim().lowercase()
        return allow.any { it.isNotBlank() && (t == it || t.contains(it)) }
    }

    /** Judul dan jenis chat dari header layar chat Telegram. null bila layar itu bukan layar chat atau judul tak terbaca. */
    fun chatHeader(root: AccessibilityNodeInfo, screenHeight: Int): Pair<String, String>? {
        val limit = (screenHeight * 0.22f).toInt()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        val texts = ArrayList<Pair<String, Rect>>()
        while (q.isNotEmpty() && n++ < 600) {
            val node = q.removeFirst()
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.top < limit) {
                val t = node.text?.toString()?.trim() ?: ""
                val d = node.contentDescription?.toString()?.trim() ?: ""
                titleFromCombined(t)?.let { return it to "c" }
                titleFromCombined(d)?.let { return it to "c" }
                if (t.isNotEmpty()) texts.add(t to Rect(r))
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        val sub = texts.firstOrNull { subtitleKind(it.first) != null } ?: return null
        val kind = subtitleKind(sub.first) ?: return null
        // Judul = teks terdekat tepat di atas subjudul.
        val title = texts.filter { it !== sub && subtitleKind(it.first) == null && it.first.length in 2..80 && !it.first.all { c -> c.isDigit() } &&
            it.second.bottom <= sub.second.top + 12 }
            .maxByOrNull { it.second.bottom }?.first ?: return null
        return title to kind
    }

    private fun channelTitle(root: AccessibilityNodeInfo, screenHeight: Int): String? =
        chatHeader(root, screenHeight)?.takeIf { it.second == "c" }?.first
}
