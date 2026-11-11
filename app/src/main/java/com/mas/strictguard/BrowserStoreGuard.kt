package com.mas.strictguard

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Mencegah memasang browser selain Chrome lewat Play Store: bila halaman detail aplikasi browser terbuka
 * (bagian atas layar, ada tombol Instal) atau kolom pencarian berisi nama browser, layar dikembalikan.
 * Tidak mencegah APK langsung, toko lain, atau pemasangan lewat PC. Bergantung pada tampilan Play Store.
 */
object BrowserStoreGuard {
    const val STORE = "com.android.vending"

    private val DEFAULT_NAMES = listOf(
        "firefox", "brave", "opera", "microsoft edge", "duckduckgo", "kiwi browser", "vivaldi",
        "uc browser", "via browser", "tor browser", "samsung internet", "puffin", "dolphin",
        "ecosia", "aloha", "yandex browser", "mi browser", "bromite", "fennec", "waterfox",
        // Klien Telegram pihak ketiga (API Telegram) yang bisa menembus penjaga Telegram resmi.
        "telegram x", "nekogram", "nekox", "plus messenger", "telegraph", "graph messenger", "turrit",
        "exteragram", "forkgram", "nicegram", "ayugram", "colibri", "kotatogram", "unigram",
        "telegram lite", "cherrygram", "swiftgram", "vidogram", "mytelegram", "telegram fork",
        "messenger for telegram", "client for telegram", "unofficial telegram", "tg client"
    )
    private val GENERIC = listOf("browser", "peramban")
    private val INSTALL = setOf("instal", "install", "pasang")

    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = sp(c).getBoolean("store_block", true)

    fun setEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("store_block", v).commit()
    }

    fun customNames(c: Context): Set<String> = HashSet(sp(c).getStringSet("store_names", emptySet()) ?: emptySet())

    fun addName(c: Context, name: String) {
        val n = name.trim().lowercase()
        if (n.length < 3) return
        sp(c).edit().putStringSet("store_names", customNames(c) + n).commit()
    }

    fun removeName(c: Context, name: String) {
        sp(c).edit().putStringSet("store_names", customNames(c) - name).commit()
    }

    private fun names(c: Context): List<String> = DEFAULT_NAMES + customNames(c)

    private fun isBrowserText(t: String, names: List<String>): Boolean {
        if (t.length > 45 || t.contains("chrome")) return false
        return names.any { t.contains(it) } || GENERIC.any { t.contains(it) }
    }

    class Box(val top: Int, val cy: Int)

    /** Hasil pemindaian terakhir, ditampilkan di layar Browser untuk diagnosa. */
    @Volatile
    var diag: String = "belum ada pemindaian (buka Play Store dulu)"

    @Volatile
    var screenDiag: String = "belum ada"

    @Volatile
    var seenCount = 0

    @Volatile
    var seenInfo = "belum ada event dari Play Store"

    @Volatile
    var gateInfo = ""

    private val diagFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Volatile
    private var lastDeepAt = 0L

    @Volatile
    private var lastErrAt = 0L

    /**
     * Keputusan murni dari posisi layar: ada nama browser yang (a) sebaris dengan tombol Instal
     * (hasil pencarian / daftar), atau (b) berada di bagian judul atas layar sementara tombol Instal
     * ada di bagian atas-tengah (halaman detail aplikasi).
     */
    fun decide(names: List<Box>, installs: List<Box>, h: Int): Boolean {
        if (names.isEmpty() || installs.isEmpty() || h <= 0) return false
        val gap = Math.max(220, (h * 0.10).toInt())
        for (n in names) for (i in installs) {
            if (Math.abs(n.cy - i.cy) <= gap) return true
            if (n.top < h * 0.35 && i.cy < h * 0.65) return true
        }
        return false
    }

    private fun text(n: AccessibilityNodeInfo): String =
        (n.text?.toString() ?: n.contentDescription?.toString() ?: "").lowercase().trim()

    /** Cepat: pencarian teks dari sisi aplikasi Play Store (hanya belasan panggilan). */
    @Volatile
    private var cheapInstalls = 0

    private fun cheapScan(root: AccessibilityNodeInfo, names: List<String>, h: Int): Boolean {
        val r = Rect()
        val installs = ArrayList<Box>()
        for (q in listOf("instal", "install", "pasang")) {
            val found = root.findAccessibilityNodeInfosByText(q) ?: continue
            for (n in found) {
                if (text(n) in INSTALL) {
                    n.getBoundsInScreen(r)
                    installs.add(Box(r.top, r.centerY()))
                }
            }
        }
        cheapInstalls = installs.size
        if (installs.isEmpty()) return false
        val boxes = ArrayList<Box>()
        for (q in names + GENERIC) {
            val found = root.findAccessibilityNodeInfosByText(q) ?: continue
            for (n in found) {
                val t = text(n)
                if (t.isEmpty() || !isBrowserText(t, names)) continue
                n.getBoundsInScreen(r)
                boxes.add(Box(r.top, r.centerY()))
            }
        }
        return decide(boxes, installs, h)
    }

    /** Lambat tapi menyeluruh: menelusuri pohon tampilan. Dipakai bila pemeriksaan cepat tidak menemukan apa pun. */
    private fun deepScan(root: AccessibilityNodeInfo, names: List<String>, h: Int): Boolean {
        val installs = ArrayList<Box>()
        val boxes = ArrayList<Box>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        val r = Rect()
        val t0 = System.currentTimeMillis()
        while (queue.isNotEmpty() && count < 1500 && System.currentTimeMillis() - t0 < 450L) {
            val n = queue.removeFirst()
            count++
            val t = text(n)
            if (t.isNotEmpty()) {
                if (t in INSTALL) {
                    n.getBoundsInScreen(r)
                    installs.add(Box(r.top, r.centerY()))
                } else if (isBrowserText(t, names)) {
                    n.getBoundsInScreen(r)
                    boxes.add(Box(r.top, r.centerY()))
                }
            }
            for (i in 0 until n.childCount) {
                val ch = n.getChild(i) ?: continue
                queue.add(ch)
            }
        }
        val res = decide(boxes, installs, h)
        diag = diagFmt.format(java.util.Date()) + " dalam: node=" + count + " instal=" + installs.size +
            " nama=" + boxes.size + " waktu=" + (System.currentTimeMillis() - t0) + "ms hasil=" + (if (res) "BLOKIR" else "aman")
        return res
    }

    /** True bila event/layar Play Store ini harus dikembalikan. Tidak pernah melempar exception. */
    fun shouldBack(c: Context, event: AccessibilityEvent, root: AccessibilityNodeInfo?, screenHeight: Int): Boolean {
        try {
            val names = names(c)
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                var typed0 = event.text?.joinToString(" ") ?: ""
                if (typed0.isEmpty()) typed0 = try { event.source?.text?.toString() ?: "" } catch (t: Throwable) { "" }
                val typed = typed0.lowercase().trim()
                if (typed.length in 3..40 && !typed.contains("chrome") &&
                    (names.any { typed.contains(it) } || GENERIC.any { typed.contains(it) })
                ) return true
            }
            if (root == null) return false
            if (cheapScan(root, names, screenHeight)) {
                diag = diagFmt.format(java.util.Date()) + " cepat: instal=" + cheapInstalls + " hasil=BLOKIR"
                return true
            }
            val now = System.currentTimeMillis()
            if (now - lastDeepAt > 700L) {
                lastDeepAt = now
                if (deepScan(root, names, screenHeight)) return true
            } else if (!diag.contains("dalam")) {
                diag = diagFmt.format(java.util.Date()) + " cepat: instal=" + cheapInstalls + " (pindai dalam menunggu)"
            }
        } catch (t: Throwable) {
            val now = System.currentTimeMillis()
            if (now - lastErrAt > 600_000L) {
                lastErrAt = now
                Store.log(c, "Pemeriksa Play Store error: " + t.javaClass.simpleName)
            }
        }
        return false
    }
}
