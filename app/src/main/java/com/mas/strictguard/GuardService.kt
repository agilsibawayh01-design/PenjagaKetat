package com.mas.strictguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GuardService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastBlockAt = 0L
    private var lastScanAt = 0L
    private var lastFeedAt = 0L
    private var lastBackAt = 0L
    private var lastProtectAt = 0L

    private var cacheAt = 0L
    private var apps: Set<String> = emptySet()
    private var keywords: Set<String> = emptySet()
    private var domains: Set<String> = emptySet()
    private var protectedNames: List<String> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.seed(this)
    }

    override fun onInterrupt() {}

    private fun refreshCache(now: Long) {
        if (now - cacheAt < 1500L) return
        cacheAt = now
        apps = Store.apps(this)
        keywords = Store.keywords(this)
        domains = Store.domains(this)
        val names = ArrayList<String>()
        names.add(Config.APP_LABEL)
        for (p in Store.protectedApps(this)) {
            names.add(p)
            try {
                val label = packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString().lowercase()
                if (label.length >= 4) names.add(label)
            } catch (e: Exception) {
                // tidak terpasang
            }
        }
        protectedNames = names
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in Config.IGNORED) return
        try {
            handle(event, pkg)
        } catch (t: Throwable) {
            // jangan pernah membuat service crash
        }
    }

    private fun handle(event: AccessibilityEvent, pkg: String) {
        val now = System.currentTimeMillis()
        refreshCache(now)

        // 1. Blokir aplikasi
        if (pkg in apps && Rules.blockingActive(this) && Store.passUntil(this, pkg) <= now) {
            blockApp(pkg)
            return
        }

        // 2. Kata kunci saat mengetik (kolom pencarian, URL, dll.)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val typed = event.text?.joinToString(" ") ?: ""
            if (typed.isNotEmpty() &&
                (Rules.matchesKeyword(typed, keywords) != null || Rules.matchesDomain(typed, domains) != null)
            ) {
                blockContent("content")
            }
            return
        }

        // 3. Lindungi pengaturan saat Mode Ketat aktif
        if (Store.strictOn(this) && pkg in Config.PROTECTED && now - lastProtectAt > 500L) {
            lastProtectAt = now
            val root = rootInActiveWindow
            if (root != null && showsOurApp(root)) {
                blockContent("protect")
                return
            }
        }

        // 4. Browser: periksa kolom alamat dan teks halaman
        if (pkg in Config.BROWSERS && now - lastScanAt > 500L) {
            lastScanAt = now
            val root = rootInActiveWindow ?: return
            for (n in collect(root, 400)) {
                val txt = n.text?.toString() ?: continue
                if (txt.isEmpty()) continue
                val id = (n.viewIdResourceName ?: "").lowercase()
                val isUrlBar = id.contains("url") || id.contains("location_bar") || id.contains("omnibox")
                if (isUrlBar && Rules.matchesDomain(txt, domains) != null) {
                    blockContent("content")
                    return
                }
                if (Rules.matchesKeyword(txt, keywords) != null) {
                    blockContent("content")
                    return
                }
            }
            return
        }

        // 5. Feed video pendek
        val feedWanted = (pkg == Config.YOUTUBE && Store.blockShorts(this)) ||
            (pkg == Config.INSTAGRAM && Store.blockReels(this))
        if (feedWanted && now - lastFeedAt > 600L) {
            lastFeedAt = now
            val root = rootInActiveWindow ?: return
            for (n in collect(root, 500)) {
                val id = (n.viewIdResourceName ?: "").lowercase()
                if (id.isEmpty()) continue
                if (Config.FEED_IDS.any { id.contains(it) }) {
                    if (now - lastBackAt > 800L) {
                        lastBackAt = now
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    return
                }
            }
        }
    }

    private fun collect(root: AccessibilityNodeInfo, limit: Int): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < limit) {
            val n = queue.removeFirst()
            out.add(n)
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                queue.add(c)
            }
        }
        return out
    }

    private fun showsOurApp(root: AccessibilityNodeInfo): Boolean {
        for (n in collect(root, 600)) {
            val t = n.text?.toString()?.lowercase() ?: ""
            val d = n.contentDescription?.toString()?.lowercase() ?: ""
            if (t.isEmpty() && d.isEmpty()) continue
            for (name in protectedNames) {
                if (t.contains(name) || d.contains(name)) return true
            }
        }
        return false
    }

    private fun blockApp(pkg: String) {
        launchBlock("app", pkg)
    }

    private fun blockContent(mode: String) {
        launchBlock(mode, "")
    }

    private fun launchBlock(mode: String, pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockAt < 800L) return
        lastBlockAt = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        val i = Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra("mode", mode)
            .putExtra("pkg", pkg)
        handler.postDelayed({
            try {
                startActivity(i)
            } catch (e: Exception) {
                // abaikan
            }
        }, 150L)
    }
}
