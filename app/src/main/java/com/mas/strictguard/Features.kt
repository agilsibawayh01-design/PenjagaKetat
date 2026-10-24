package com.mas.strictguard

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Blokir bagian dalam aplikasi sosmed (Shorts, Reels, Cerita, Pencarian, ...). Aplikasinya tetap bisa
 * dipakai; saat bagian terlarang terdeteksi, layar dikembalikan (BACK).
 * Deteksi memakai tampilan aksesibilitas, jadi BERGANTUNG pada tampilan tiap aplikasi dan bisa meleset
 * setelah aplikasi diperbarui. ID di sini adalah perkiraan terbaik; gunakan "Rekam layar" untuk memperbaikinya.
 */
class Feature(
    val id: String,
    val group: String,
    val label: String,
    val pkgs: Set<String>,
    /** Potongan ID tampilan yang menandakan bagian itu sedang terbuka. */
    val ids: List<String> = emptyList(),
    /** Node yang ID-nya memuat salah satu ini diabaikan (mis. Stories pada deteksi Reels). */
    val skipIds: List<String> = emptyList(),
    /** Potongan ID tab bawah yang, bila SEDANG TERPILIH, berarti bagian itu terbuka. */
    val tabIds: List<String> = emptyList(),
    /** Label tab (awalan teks/deskripsi, huruf kecil) yang, bila sedang terpilih, berarti bagian itu terbuka. */
    val tabLabels: List<String> = emptyList(),
    /** True: terbuka bila kolom pencarian (EditText) sedang tampil. */
    val searchField: Boolean = false
)

object Features {
    private val WA = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val FB = setOf("com.facebook.katana", "com.facebook.lite")
    private val IG = setOf(Config.INSTAGRAM)
    private val YT = setOf(Config.YOUTUBE)
    private val SC = setOf("com.snapchat.android")
    private val TG = setOf("org.telegram.messenger", "org.telegram.messenger.web")
    private val X = setOf("com.twitter.android")

    val ALL: List<Feature> = listOf(
        Feature("yt_shorts", "YouTube", "Shorts", YT, ids = Config.YT_IDS),
        Feature("yt_search", "YouTube", "Pencarian", YT, searchField = true),
        Feature("ig_reels", "Instagram", "Reels", IG, ids = Config.IG_IDS, skipIds = listOf("reel_viewer", "story"), tabIds = listOf(Config.IG_REELS_TAB)),
        Feature("ig_stories", "Instagram", "Cerita (Stories)", IG, ids = listOf("reel_viewer")),
        Feature("ig_search", "Instagram", "Pencarian", IG, searchField = true),
        Feature("wa_updates", "WhatsApp", "Tab Pembaruan (Status dan Saluran)", WA, tabLabels = listOf("pembaruan", "updates")),
        Feature("wa_channels", "WhatsApp", "Saluran (halaman saluran)", WA, ids = listOf("newsletter")),
        Feature("fb_reels", "Facebook", "Reels / Video", FB, tabLabels = listOf("reels", "video")),
        Feature("fb_search", "Facebook", "Pencarian", FB, searchField = true),
        Feature("sc_spotlight", "Snapchat", "Spotlight", SC, tabLabels = listOf("spotlight")),
        Feature("sc_stories", "Snapchat", "Cerita", SC, tabLabels = listOf("stories", "cerita")),
        Feature("sc_search", "Snapchat", "Pencarian", SC, searchField = true),
        Feature("tg_search", "Telegram", "Pencarian", TG, searchField = true),
        Feature("x_search", "X (Twitter)", "Pencarian", X, searchField = true)
    )

    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    fun enabled(c: Context, f: Feature): Boolean = when (f.id) {
        "yt_shorts" -> Store.blockShorts(c)
        "ig_reels" -> Store.blockReels(c)
        else -> sp(c).getBoolean("feat_" + f.id, false)
    }

    fun setEnabled(c: Context, f: Feature, v: Boolean) {
        when (f.id) {
            "yt_shorts" -> Store.setFeeds(c, v, Store.blockReels(c))
            "ig_reels" -> Store.setFeeds(c, Store.blockShorts(c), v)
            else -> sp(c).edit().putBoolean("feat_" + f.id, v).commit()
        }
    }

    /** Fitur yang menyala untuk paket ini. */
    fun activeFor(c: Context, pkg: String): List<Feature> =
        ALL.filter { pkg in it.pkgs && enabled(c, it) }

    private fun short(s: CharSequence?): String {
        val t = s?.toString()?.lowercase()?.trim() ?: return ""
        return if (t.length > 40) "" else t
    }

    private fun selected(n: AccessibilityNodeInfo): Boolean =
        n.isSelected || (try { n.parent?.isSelected == true } catch (t: Throwable) { false })

    fun hit(f: Feature, nodes: List<AccessibilityNodeInfo>): Boolean {
        for (n in nodes) {
            val id = (n.viewIdResourceName ?: "").lowercase()
            if (id.isNotEmpty() && f.skipIds.any { id.contains(it) }) continue
            if (id.isNotEmpty()) {
                if (f.ids.any { id.contains(it) }) return true
                if (f.tabIds.any { id.contains(it) } && n.isSelected) return true
            }
            if (f.tabLabels.isNotEmpty() && selected(n)) {
                val t = short(n.text)
                val d = short(n.contentDescription)
                if (f.tabLabels.any { (t.isNotEmpty() && t.startsWith(it)) || (d.isNotEmpty() && d.startsWith(it)) }) return true
            }
            if (f.searchField && n.isEditable && n.className?.toString()?.contains("EditText") == true) {
                val hint = try { n.hintText?.toString()?.lowercase() ?: "" } catch (t: Throwable) { "" }
                val d = n.contentDescription?.toString()?.lowercase() ?: ""
                if (id.contains("search") || hint.contains("search") || hint.contains("cari") ||
                    d.contains("search") || d.contains("cari")
                ) return true
            }
        }
        return false
    }
}

/** Perekam layar sementara untuk memperbaiki deteksi. Hanya menyimpan ID dan teks pendek (maks 24 huruf). */
object FeatureDump {
    @Volatile
    private var until = 0L
    private var lastAt = 0L
    private val buf = StringBuilder()

    fun start(seconds: Int) {
        synchronized(buf) { buf.setLength(0) }
        until = System.currentTimeMillis() + seconds * 1000L
    }

    fun active(): Boolean = System.currentTimeMillis() < until

    fun text(): String = synchronized(buf) { buf.toString() }

    fun isEmpty(): Boolean = synchronized(buf) { buf.isEmpty() }

    fun capture(pkg: String, root: AccessibilityNodeInfo?) {
        if (root == null) return
        val now = System.currentTimeMillis()
        if (now - lastAt < 1500L) return
        lastAt = now
        synchronized(buf) {
            if (buf.length > 150_000) return
            buf.append("=== ").append(pkg).append(" @").append(now / 1000).append('\n')
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count < 400) {
                val n = queue.removeFirst()
                count++
                val id = n.viewIdResourceName ?: ""
                val t = clip(n.text)
                val d = clip(n.contentDescription)
                if (id.isNotEmpty() || t.isNotEmpty() || d.isNotEmpty()) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    buf.append(id.substringAfter(":id/")).append(" | ").append(n.className?.toString()?.substringAfterLast('.'))
                        .append(" | t=").append(t).append(" | d=").append(d)
                        .append(" | sel=").append(if (n.isSelected) 1 else 0)
                        .append(" edit=").append(if (n.isEditable) 1 else 0)
                        .append(" y=").append(r.top).append('\n')
                }
                for (i in 0 until n.childCount) {
                    val ch = n.getChild(i) ?: continue
                    queue.add(ch)
                }
            }
        }
    }

    private fun clip(s: CharSequence?): String {
        val t = s?.toString()?.replace('\n', ' ')?.trim() ?: return ""
        return if (t.length > 24) "<" + t.length + ">" else t
    }
}
