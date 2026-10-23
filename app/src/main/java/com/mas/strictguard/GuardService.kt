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
    private var blockDomain = ""
    private var locCheckAt = 0L
    private var lastScanAt = 0L
    private var lastFeedAt = 0L
    private var lastBackAt = 0L
    private var lastProtectAt = 0L

    private var cacheAt = 0L
    private var apps: Set<String> = emptySet()
    private var keywords: Set<String> = emptySet()
    private var domains: Set<String> = emptySet()
    private var protectedNames: List<String> = emptyList()
    private var browsers: Set<String> = Config.BROWSERS
    private var watcher: PackageWatcher? = null
    private var profiles: List<Profile> = emptyList()
    private var limitPkg = ""
    private var backing = false
    private var strictNow = false
    private var pausedNow = false
    private var blockedBrowsers: Set<String> = emptySet()
    private var browsersAt = 0L

    /** Pemeriksaan berkala untuk batas pemakaian/timer tunggu. Tidak bergantung pada event Accessibility. */
    private val timerRunnable = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()
                refreshCache(now)
                if (now - locCheckAt > 60000L) {
                    locCheckAt = now
                    LocationTracker.ensure(this@GuardService)
                }
                // Notifikasi menunjukkan SISA WAKTU PENGGUNAAN, bukan sisa masa tunggu.
                // Masa tunggu tetap dihitung oleh WaitTimer/SiteTracker dan dipakai untuk memblokir.
                if (!backing && !pausedNow && Foreground.hasUsageAccess(this@GuardService)) {
                    val p = Foreground.current(this@GuardService)
                    if (p.isNotEmpty() && p != packageName && p !in Config.IGNORED) {
                        var siteShown = false
                        if (p in browsers) {
                            if (checkWebsiteRule(p, rootInActiveWindow, now)) {
                                TimerNotifier.cancel(this@GuardService)
                                handler.postDelayed(this, 2000L)
                                return
                            }
                            val configured = Store.domains(this@GuardService) +
                                ProfileStore.load(this@GuardService).filter { ProfileEngine.isActive(this@GuardService, it) }.flatMap { it.domains }
                            val domain = SiteTracker.currentDomain(rootInActiveWindow, configured)
                            if (domain != null) {
                                SiteTracker.tick(this@GuardService, domain)
                                val scfg = Store.siteCfgOrNull(this@GuardService, domain)
                                if (scfg != null && scfg.twUse > 0 && scfg.twWait > 0) {
                                    val leftUse = SiteTracker.remainingUseMs(this@GuardService, domain, scfg)
                                    if (leftUse > 0L) {
                                        TimerNotifier.show(this@GuardService, domain, leftUse)
                                        siteShown = true
                                    } else {
                                        TimerNotifier.cancel(this@GuardService)
                                    }
                                } else {
                                    TimerNotifier.cancel(this@GuardService)
                                }
                            } else {
                                TimerNotifier.cancel(this@GuardService)
                            }
                        }
                        if (p in blockedBrowsers) {
                            startBlock("browser", p)
                        } else {
                            val verdict = BlockEngine.check(this@GuardService, p, now, apps, profiles)
                            val cfg = Store.appCfg(this@GuardService, p)
                            val label = try {
                                packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
                            } catch (_: Exception) { p }
                            if (verdict != null) {
                                TimerNotifier.cancel(this@GuardService)
                                startBlock(verdict, p)
                            } else if (cfg.twUse > 0 && cfg.twWait > 0) {
                                val leftUse = WaitTimer.remainingUseMs(this@GuardService, p, cfg, now)
                                if (leftUse > 0L) TimerNotifier.show(this@GuardService, label, leftUse)
                                else if (!siteShown) TimerNotifier.cancel(this@GuardService)
                            } else if (!siteShown) {
                                TimerNotifier.cancel(this@GuardService)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Jangan pernah membuat AccessibilityService crash karena pemeriksaan timer.
            }
            handler.postDelayed(this, 2000L)
        }
    }

    private val limitRunnable = object : Runnable {
        override fun run() {
            val p = limitPkg
            if (p.isEmpty()) return
            if (pausedNow) {
                limitPkg = ""
                return
            }
            try {
                if (Foreground.hasUsageAccess(this@GuardService) && Foreground.current(this@GuardService) == p) {
                    val v = BlockEngine.check(this@GuardService, p, System.currentTimeMillis(), apps, profiles)
                    if (v != null) {
                        limitPkg = ""
                        startBlock(v, p)
                        return
                    }
                    handler.postDelayed(this, 20000L)
                } else {
                    limitPkg = ""
                }
            } catch (t: Throwable) {
                limitPkg = ""
            }
        }
    }

    private fun scheduleLimitCheck(pkg: String) {
        if (limitPkg == pkg) return
        limitPkg = pkg
        handler.removeCallbacks(limitRunnable)
        handler.postDelayed(limitRunnable, 20000L)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.seed(this)
        try {
            if (watcher == null) watcher = PackageWatcher.register(this)
        } catch (t: Throwable) {
            // abaikan
        }
        BrowserScanner.scan(this)
        TimeGuard.check(this)
        if (Store.strictRaw(this)) WatchdogService.start(this)
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
        VpnManager.ensure(this)
    }

    override fun onDestroy() {
        try {
            watcher?.let { unregisterReceiver(it) }
        } catch (t: Throwable) {
            // abaikan
        }
        watcher = null
        handler.removeCallbacks(timerRunnable)
        handler.removeCallbacks(limitRunnable)
        TimerNotifier.cancel(this)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun refreshCache(now: Long) {
        if (now - cacheAt < 1500L) return
        cacheAt = now
        strictNow = Store.strictOn(this)
        pausedNow = Store.pausedNow(this)
        if (now - browsersAt > 30000L || Store.pkgStamp > browsersAt) {
            browsersAt = now
            blockedBrowsers = if (Store.blockOtherBrowsers(this)) Store.unsupportedBrowsers(this) else emptySet()
        }
        apps = Store.apps(this)
        profiles = ProfileStore.load(this)
        val active = profiles.filter { ProfileEngine.isActive(this, it) }
        keywords = Store.keywords(this) + Categories.keywords(this) + active.flatMap { it.keywords }
        domains = Store.domains(this) + Categories.domains(this) + active.flatMap { it.domains }
        browsers = Config.BROWSERS + Store.extraBrowsers(this)
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
        TimeGuard.check(this)
        if (backing || pausedNow) return

        // Klik pada aplikasi terlindung di Pengaturan (mis. daftar layanan Aksesibilitas): langsung kembali,
        // sebelum halaman detail dan tombol nonaktifkan sempat dipakai.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (strictNow && isSettingsLike(pkg) && clickedProtected(event)) {
                startBlock("protect_click", "")
            }
            return
        }

        // 0. Browser selain Chrome tidak didukung
        if (pkg in blockedBrowsers) {
            startBlock("browser", pkg)
            return
        }

        // 1. Blokir aplikasi
        val verdict = BlockEngine.check(this, pkg, now, apps, profiles)
        if (verdict != null) {
            startBlock(verdict, pkg)
            return
        }
        if (BlockEngine.hasLimit(this, pkg, apps, profiles)) scheduleLimitCheck(pkg)

        // 2. Kata kunci saat mengetik (kolom pencarian, URL, dll.)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val typed = event.text?.joinToString(" ") ?: ""
            if (typed.isNotEmpty()) {
                val kwHit = Rules.matchesKeyword(typed, keywords) != null
                val dmHit = Rules.matchesDomain(typed, domains)
                val domainBlocked = dmHit != null && SiteTracker.shouldDnsBlock(this, dmHit)
                if (kwHit || domainBlocked) {
                    startBlock("typed", "")
                }
            }
            return
        }

        // 3. Lindungi pengaturan saat Mode Ketat aktif
        if (strictNow && pkg in Config.PROTECTED && now - lastProtectAt > 500L) {
            lastProtectAt = now
            val root = rootInActiveWindow
            if (root != null && showsProtected(root)) {
                startBlock("protect", "")
                return
            }
        }

        // 4. Website: aturan per-domain (timer tunggu, batas harian, jadwal, blokir penuh).
        if (pkg in browsers) {
            val root = rootInActiveWindow
            if (checkWebsiteRule(pkg, root, now)) return
        }

        // 5. Browser: periksa kolom alamat dan teks halaman
        if (pkg in browsers && now - lastScanAt > 500L) {
            lastScanAt = now
            val root = rootInActiveWindow ?: return
            if (browserHasBlocked(root)) startBlock("content", "")
            return
        }

        // 5. Feed video pendek (Shorts YouTube, Reels Instagram tanpa Stories)
        val ytWanted = pkg == Config.YOUTUBE && Store.blockShorts(this)
        val igWanted = pkg == Config.INSTAGRAM && Store.blockReels(this)
        if ((ytWanted || igWanted) && now - lastFeedAt > 600L) {
            lastFeedAt = now
            val root = rootInActiveWindow ?: return
            var hit = false
            for (n in collect(root, 500)) {
                val id = (n.viewIdResourceName ?: "").lowercase()
                if (id.isEmpty()) continue
                if (ytWanted) {
                    if (Config.YT_IDS.any { id.contains(it) }) {
                        hit = true
                        break
                    }
                } else {
                    if (id.contains("reel_viewer") || id.contains("story")) continue
                    if (Config.IG_IDS.any { id.contains(it) }) {
                        hit = true
                        break
                    }
                    if (id.contains(Config.IG_REELS_TAB) && n.isSelected) {
                        hit = true
                        break
                    }
                }
            }
            if (hit && now - lastBackAt > 800L) {
                lastBackAt = now
                performGlobalAction(GLOBAL_ACTION_BACK)
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

    /**
     * True bila layar menampilkan halaman yang berbahaya bagi aplikasi terlindungi:
     * halaman detail (nama aplikasi + tombol copot/paksa berhenti/nonaktifkan),
     * atau halaman detail layanan aksesibilitas Penjaga Ketat.
     * Daftar aplikasi biasa (hanya memuat nama) tetap boleh dibuka.
     */
    private fun showsProtected(root: AccessibilityNodeInfo): Boolean {
        var nameHit = false
        var actionHit = false
        for (n in collect(root, 600)) {
            val t = n.text?.toString()?.lowercase()?.trim() ?: ""
            val d = n.contentDescription?.toString()?.lowercase()?.trim() ?: ""
            if (t.isEmpty() && d.isEmpty()) continue
            if (t.contains(Config.SERVICE_MARKER) || d.contains(Config.SERVICE_MARKER)) return true
            if (!nameHit) {
                for (name in protectedNames) {
                    if (t.contains(name) || d.contains(name)) {
                        nameHit = true
                        break
                    }
                }
            }
            if (!actionHit) {
                if (isActionText(t) || isActionText(d)) actionHit = true
            }
            if (nameHit && actionHit) return true
        }
        return false
    }

    /** Hanya aplikasi Pengaturan/pemasang/izin. Layar utama (launcher) tidak termasuk, supaya membuka aplikasi biasa tidak ikut terblokir. */
    private fun isSettingsLike(pkg: String): Boolean =
        pkg in Config.PROTECTED && (pkg.contains("settings") || pkg.contains("packageinstaller") ||
            pkg.contains("permissioncontroller") || pkg.contains("vending"))

    /** True bila yang diklik adalah baris/tombol yang memuat nama aplikasi terlindung. */
    private fun clickedProtected(event: AccessibilityEvent): Boolean {
        fun hit(s: String?): Boolean {
            val t = s?.lowercase()?.trim() ?: return false
            if (t.isEmpty() || t.length > 80) return false
            return protectedNames.any { it.length >= 4 && !it.contains('.') && t.contains(it) }
        }
        for (x in event.text) if (hit(x?.toString())) return true
        if (hit(event.contentDescription?.toString())) return true
        val src = event.source ?: return false
        var depth = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(src)
        var count = 0
        while (queue.isNotEmpty() && count++ < 30 && depth < 4) {
            val n = queue.removeFirst()
            if (hit(n.text?.toString()) || hit(n.contentDescription?.toString())) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            depth++
        }
        return false
    }

    private fun isActionText(s: String): Boolean {
        if (s.isEmpty() || s.length > 60) return false
        for (w in Config.ACTION_WORDS) {
            if (s == w || s.startsWith(w)) return true
        }
        return false
    }

    private fun checkWebsiteRule(pkg: String, root: AccessibilityNodeInfo?, now: Long): Boolean {
        val configured = Store.domains(this) + ProfileStore.load(this).filter { ProfileEngine.isActive(this, it) }.flatMap { it.domains }
        val domain = SiteTracker.currentDomain(root, configured) ?: run {
            SiteTracker.stop(this)
            return false
        }
        SiteTracker.tick(this, domain)
        val cfg = Store.siteCfgOrNull(this, domain) ?: return false
        if (LocationRules.blocked(this, "site_" + Rules.normalizeDomain(domain))) {
            blockDomain = domain
            startBlock("site", pkg)
            return true
        }
        if (cfg.schOn && !Rules.scheduleActive(cfg.start, cfg.end, cfg.days)) return false
        if (cfg.blocksFully) {
            blockDomain = domain
            startBlock("site", pkg)
            return true
        }
        if (cfg.limitMin > 0 && SiteTracker.todayMs(this, domain) >= cfg.limitMin * 60000L) {
            blockDomain = domain
            startBlock("site", pkg)
            return true
        }
        val left = SiteTracker.remaining(this, domain, cfg, now)
        if (left > 0L) {
            blockDomain = domain
            startBlock("site_wait", pkg)
            return true
        }
        return false
    }

    private fun browserHasBlocked(root: AccessibilityNodeInfo): Boolean {
        for (n in collect(root, 400)) {
            val txt = n.text?.toString() ?: continue
            if (txt.isEmpty()) continue
            val id = (n.viewIdResourceName ?: "").lowercase()
            val isUrlBar = id.contains("url") || id.contains("location_bar") || id.contains("omnibox")
            if (isUrlBar) {
                val hit = Rules.matchesDomain(txt, domains)
                if (hit != null && SiteTracker.shouldDnsBlock(this, hit)) return true
            }
            if (Rules.matchesKeyword(txt, keywords) != null) return true
        }
        return false
    }

    /** True bila layar masih menampilkan hal yang harus ditutup, sesuai jenis blokir. */
    private fun stillBad(mode: String, pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val cur = root.packageName?.toString() ?: return false
        return when (mode) {
            "content" -> cur in browsers && browserHasBlocked(root)
            "protect" -> cur in Config.PROTECTED && showsProtected(root)
            else -> cur == pkg
        }
    }

    private fun maxSteps(mode: String): Int = when (mode) {
        "typed" -> 2
        "protect_click" -> 2
        "content" -> 6
        "protect" -> 5
        else -> 4
    }

    /**
     * Mode back: tekan Kembali berulang sampai konten terblokir hilang dari layar
     * (halaman situs sebelumnya, dialog copot, atau keluar dari aplikasi), baru tampilkan layar blokir.
     */
    private fun startBlock(mode: String, pkg: String) {
        val now = System.currentTimeMillis()
        if (backing || now - lastBlockAt < 800L) return
        lastBlockAt = now
        backing = true
        StatsDb.addBlock(this, if (mode == "typed") "content" else if (mode == "protect_click") "protect" else mode, pkg)
        // Klik di Pengaturan: beri 120 ms agar halaman tujuan sempat terbuka, lalu kembali.
        if (mode == "protect_click") handler.postDelayed({ stepBack(mode, pkg, 0) }, 120L) else stepBack(mode, pkg, 0)
    }

    private fun stepBack(mode: String, pkg: String, n: Int) {
        try {
            val bad = when (mode) {
                "typed" -> n < maxSteps(mode)
                "protect_click" -> n == 0 || (n < maxSteps(mode) && stillBad("protect", pkg))
                else -> stillBad(mode, pkg)
            }
            if (!bad || n >= maxSteps(mode)) {
                finishBack(mode, pkg)
                return
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({ stepBack(mode, pkg, n + 1) }, 400L)
        } catch (t: Throwable) {
            backing = false
        }
    }

    private fun finishBack(mode: String, pkg: String) {
        try {
            if (mode != "typed" && mode != "protect_click" && stillBad(mode, pkg)) performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (t: Throwable) {
            // lanjut menampilkan layar blokir
        }
        val shown = if (mode == "typed") "content" else if (mode == "protect_click") "protect" else mode
        val domain = if (mode == "site" || mode == "site_wait") {
            blockDomain.ifEmpty { SiteTracker.currentDomain(rootInActiveWindow, Store.domains(this)) ?: "" }
        } else ""
        val i = Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra("mode", shown)
            .putExtra("pkg", pkg)
            .putExtra("domain", domain)
        handler.postDelayed({
            try {
                startActivity(i)
            } catch (e: Exception) {
                // abaikan
            }
            lastBlockAt = System.currentTimeMillis()
            backing = false
        }, 250L)
    }
}
