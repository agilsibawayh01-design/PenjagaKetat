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
        "ecosia", "aloha", "yandex browser", "mi browser", "bromite", "fennec", "waterfox"
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

    /** True bila event/layar Play Store ini harus dikembalikan. */
    fun shouldBack(c: Context, event: AccessibilityEvent, root: AccessibilityNodeInfo?, screenHeight: Int): Boolean {
        val names = names(c)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val typed = (event.text?.joinToString(" ") ?: "").lowercase().trim()
            if (typed.length in 3..40 && !typed.contains("chrome") &&
                (names.any { typed.contains(it) } || GENERIC.any { typed.contains(it) })
            ) return true
        }
        if (root == null) return false
        // Browser terlihat berdampingan dengan tombol Instal (halaman detail ATAU hasil pencarian):
        // tutup sebelum tombol sempat ditekan.
        val installY = ArrayList<Int>()
        val nameY = ArrayList<Int>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        val r = Rect()
        while (queue.isNotEmpty() && count < 500) {
            val n = queue.removeFirst()
            count++
            val s = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").lowercase().trim()
            if (s.isNotEmpty()) {
                if (s in INSTALL) {
                    n.getBoundsInScreen(r)
                    installY.add(r.centerY())
                } else if (isBrowserText(s, names)) {
                    n.getBoundsInScreen(r)
                    nameY.add(r.centerY())
                }
            }
            for (i in 0 until n.childCount) {
                val ch = n.getChild(i) ?: continue
                queue.add(ch)
            }
        }
        if (installY.isEmpty() || nameY.isEmpty()) return false
        val gap = Math.max(160, (screenHeight * 0.08).toInt())
        return installY.any { iy -> nameY.any { Math.abs(it - iy) <= gap } }
    }
}
