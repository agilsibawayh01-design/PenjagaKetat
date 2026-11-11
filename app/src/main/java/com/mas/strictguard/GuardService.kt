package com.mas.strictguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager

class GuardService : AccessibilityService() {
    companion object {
        @Volatile var instance: GuardService? = null
    }

    @Volatile private var selfTesting = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastBlockAt = 0L
    private var blockDomain = ""
    private var locCheckAt = 0L
    private var lastScanAt = 0L
    private var lastFeedAt = 0L
    private var lastStoreAt = 0L
    private var ipBlock = true
    private var lastScreenNodes = 0

    // Penyebab blokir terakhir (untuk ditampilkan di layar blokir).
    private var hitWord = ""
    private var hitText = ""
    private var hitWhere = ""
    private var blockWord = ""
    private var blockText = ""
    private var blockWhere = ""

    private fun noteHit(word: String, text: String, where: String) {
        hitWord = word
        hitText = text.replace('\n', ' ').trim().take(70)
        hitWhere = where
    }

    /** Cocokkan [text] dengan kata kunci layar dan catat kata penyebabnya. */
    private fun screenHit(text: String, editable: Boolean, where: String): Boolean {
        val k = Rules.matchesKeyword(text, screenKeywords, editable) ?: return false
        noteHit(k, text, where)
        return true
    }
    private var lastScreenTexts = 0
    private var lastBackAt = 0L
    private var lastProtectAt = 0L

    private var cacheAt = 0L
    private var apps: Set<String> = emptySet()
    private var urlKeywords: Set<String> = emptySet()
    private var screenKeywords: Set<String> = emptySet()
    private var domains: Set<String> = emptySet()
    private var protectedNames: List<String> = emptyList()
    private var browsers: Set<String> = Config.BROWSERS
    private var inputMethodPackages: Set<String> = emptySet()
    private var watcher: PackageWatcher? = null
    private var profiles: List<Profile> = emptyList()
    private var limitPkg = ""
    private var backing = false
    private var strictNow = false
    private var blockUnknownNow = false
    private var nightLocked: Set<String> = emptySet()
    private var pauseAppsNow: Set<String> = emptySet()
    private var lastPauseAt = 0L
    private var pausedNow = false
    private var selfGuardNow = false

    // ---- Penutup jendela Picture-in-Picture (PiP) setelah gambar tidak pantas terdeteksi ----
    @Volatile private var pipArmed = false
    private var pipMiss = 0
    private var pipCover: android.view.View? = null
    private val pipRunnable = object : Runnable {
        override fun run() {
            var again = false
            try {
                again = pipCheck()
            } catch (t: Throwable) {
                // abaikan
            }
            if (again) handler.postDelayed(this, 400L) else removePipCover()
        }
    }

    private fun armPip() {
        pipArmed = true
        pipMiss = 0
        handler.removeCallbacks(pipRunnable)
        handler.post(pipRunnable)
    }

    /** true = terus periksa. */
    private fun pipCheck(): Boolean {
        if (!pipArmed) return false
        var pip: android.view.accessibility.AccessibilityWindowInfo? = null
        try {
            for (w in windows) {
                if (w.isInPictureInPictureMode) { pip = w; break }
            }
        } catch (t: Throwable) {
            return false
        }
        if (pip == null) {
            pipMiss++
            removePipCover()
            if (pipMiss >= 8) { pipArmed = false; return false }
            return true
        }
        pipMiss = 0
        val r = android.graphics.Rect()
        pip.getBoundsInScreen(r)
        showPipCover(r)
        tryClosePip(pip)
        return true
    }

    private fun tryClosePip(w: android.view.accessibility.AccessibilityWindowInfo) {
        try {
            val root = w.root ?: return
            val q = listOf("close", "tutup", "dismiss", "exit", "keluar")
            val stack = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            stack.add(root)
            var n = 0
            while (stack.isNotEmpty() && n < 60) {
                val node = stack.removeFirst()
                n++
                val d = ((node.contentDescription?.toString() ?: "") + " " + (node.text?.toString() ?: "")).lowercase()
                if (node.isClickable && q.any { d.contains(it) }) {
                    node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
            }
        } catch (t: Throwable) {
            // abaikan
        }
    }

    private fun showPipCover(r: android.graphics.Rect) {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val pad = 12
            val lp = android.view.WindowManager.LayoutParams(
                r.width() + pad * 2,
                r.height() + pad * 2,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.OPAQUE
            )
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.x = r.left - pad
            lp.y = r.top - pad
            val v = pipCover
            if (v == null) {
                val nv = android.view.View(this)
                nv.setBackgroundColor(android.graphics.Color.BLACK)
                wm.addView(nv, lp)
                pipCover = nv
            } else {
                wm.updateViewLayout(v, lp)
            }
        } catch (t: Throwable) {
            pipCover = null
        }
    }

    private fun removePipCover() {
        val v = pipCover ?: return
        pipCover = null
        try {
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(v)
        } catch (t: Throwable) {
            // sudah terlepas
        }
    }

    // ---- Deteksi gambar AI (tangkapan layar berkala, hanya Android 11+) ----
    @Volatile private var fgPkg = ""
    @Volatile private var nsfwBusy = false
    private val nsfwExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val nsfwRunnable = object : Runnable {
        override fun run() {
            try {
                nsfwTick()
            } catch (t: Throwable) {
                // abaikan
            }
            handler.postDelayed(this, 1200L)
        }
    }

    private fun nsfwTick() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        if (!Store.nsfwAi(this) || selfTesting || backing || nsfwBusy) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isInteractive) return
        val p = fgPkg
        if (p.isEmpty() || p == packageName || p == "com.android.systemui") return
        if (!NsfwAi.ready(this)) return
        shootAndScore(p)
    }

    @android.annotation.TargetApi(30)
    private fun shootAndScore(p: String) {
        nsfwBusy = true
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, nsfwExec, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = screenshot.hardwareBuffer
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        val soft = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        hb.close()
                        if (soft != null) {
                            val s = NsfwAi.score(this@GuardService, soft)
                            soft.recycle()
                            NsfwAi.lastFail = ""
                            if (s >= NsfwAi.THRESHOLD) handler.post { onNsfwHit(p, s) }
                        }
                    } catch (t: Throwable) {
                        NsfwAi.lastFail = "gagal memproses: " + (t.message ?: t.javaClass.simpleName)
                    } finally {
                        nsfwBusy = false
                    }
                }

                override fun onFailure(errorCode: Int) {
                    NsfwAi.lastFail = "tangkapan layar gagal (kode $errorCode)"
                    nsfwBusy = false
                }
            })
        } catch (t: Throwable) {
            NsfwAi.lastFail = "tangkapan layar tidak tersedia: " + (t.message ?: t.javaClass.simpleName)
            nsfwBusy = false
        }
    }

    private fun onNsfwHit(p: String, score: Float) {
        armPip()
        if (backing) return
        NsfwAi.hits++
        Store.log(this, "Gambar AI: skor " + String.format("%.2f", score) + " di " + p)
        startBlock("nsfw", p)
    }

    private var blockedBrowsers: Set<String> = emptySet()
    private var browsersAt = 0L

    /** Pemeriksaan berkala untuk batas pemakaian/timer tunggu. Tidak bergantung pada event Accessibility. */
    private val timerRunnable = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()
                refreshCache(now)
                AutoBackup.tick(this@GuardService)
                Focus.active(this@GuardService)
                if (now - locCheckAt > 60000L) {
                    locCheckAt = now
                    LocationTracker.ensure(this@GuardService)
                }
                // Notifikasi menunjukkan SISA WAKTU PENGGUNAAN, bukan sisa masa tunggu.
                // Masa tunggu tetap dihitung oleh WaitTimer/SiteTracker dan dipakai untuk memblokir.
                if (!backing && Foreground.hasUsageAccess(this@GuardService)) {
                    val p = Foreground.current(this@GuardService)
                    if (p.isNotEmpty() && p != packageName && p !in Config.IGNORED) {
                        var siteShown = false
                        if (p in browsers) {
                            if (checkWebsiteRule(p, rootInActiveWindow, now)) {
                                TimerNotifier.cancel(this@GuardService)
                                handler.postDelayed(this, 2000L)
                                return
                            }
                            val configured = Store.domains(this@GuardService) + Focus.activeDomains(this@GuardService) +
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
        instance = this
        Store.seed(this)
        try {
            if (watcher == null) watcher = PackageWatcher.register(this)
        } catch (t: Throwable) {
            // abaikan
        }
        BrowserScanner.scan(this)
        TgClients.sweep(this)
        handler.removeCallbacks(nsfwRunnable)
        handler.postDelayed(nsfwRunnable, 3000L)
        TimeGuard.check(this)
        if (Store.strictRaw(this)) WatchdogService.start(this)
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
        VpnManager.ensure(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        try {
            watcher?.let { unregisterReceiver(it) }
        } catch (t: Throwable) {
            // abaikan
        }
        watcher = null
        handler.removeCallbacks(timerRunnable)
        handler.removeCallbacks(nsfwRunnable)
        handler.removeCallbacks(pipRunnable)
        removePipCover()
        handler.removeCallbacks(limitRunnable)
        TimerNotifier.cancel(this)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun refreshCache(now: Long) {
        if (now - cacheAt < 1500L) return
        cacheAt = now
        Zone.update(this)
        strictNow = Store.strictOn(this)
        blockUnknownNow = Store.blockUnknownInstall(this)
        nightLocked = Night.lockedPackages(this)
        DnsGuard.check(this, strictNow)
        pauseAppsNow = Store.pauseApps(this)
        pausedNow = Store.pausedNow(this)
        selfGuardNow = !strictNow && !Store.uninstallWindowOpen(this)
        ipBlock = Store.blockIpUrls(this)
        if (now - browsersAt > 30000L || Store.pkgStamp > browsersAt) {
            browsersAt = now
            blockedBrowsers = if (Store.blockOtherBrowsers(this)) Store.unsupportedBrowsers(this) else emptySet()
        }
        apps = Store.apps(this)
        profiles = ProfileStore.load(this)
        val active = profiles.filter { ProfileEngine.isActive(this, it) }
        val profileKeywords = active.flatMap { it.keywords }.toSet()
        val categoryKeywords = Categories.keywords(this)
        urlKeywords = Store.urlKeywords(this) + categoryKeywords + profileKeywords
        screenKeywords = Store.screenKeywords(this) + categoryKeywords + profileKeywords
        domains = Store.domains(this) + Categories.domains(this) + Focus.activeDomains(this) + active.flatMap { it.domains }
        browsers = Config.BROWSERS + Store.extraBrowsers(this)
        inputMethodPackages = try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
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
        // Keyboard/IME mengirim event saat pengguna mengetik. Jangan memindai UI keyboard
        // sebagai layar aplikasi, supaya input keyword di PenjagaKetat tidak memicu blokir.
        if (selfTesting) return
        if (pkg == packageName || pkg in Config.IGNORED || pkg in inputMethodPackages) return
        fgPkg = pkg
        if (pkg == BrowserStoreGuard.STORE) {
            BrowserStoreGuard.seenCount++
            BrowserStoreGuard.seenInfo = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                " jenis=" + AccessibilityEvent.eventTypeToString(event.eventType) + " total=" + BrowserStoreGuard.seenCount
        }
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
        // Mode Off tidak lagi melewati blokir: aplikasi, kata kunci, situs, dan fitur sosmed tetap aktif.
        // Mematikannya satu per satu wajib lewat gerbang (teks acak/tunggu/kode teman) di aplikasi.
        if (backing) {
            if (pkg == BrowserStoreGuard.STORE) BrowserStoreGuard.gateInfo = "ditahan: backing=$backing"
            return
        }
        if (pkg == BrowserStoreGuard.STORE) BrowserStoreGuard.gateInfo = "lolos"

        // Jangan pernah memindai layar internal PenjagaKetat sendiri.
        // Event dari keyboard/IME saat mengetik di pengaturan bisa membuat service
        // membaca kembali kolom input PenjagaKetat dan memblokirnya sendiri.
        val foregroundRoot = rootInActiveWindow
        if (foregroundRoot?.packageName?.toString() == packageName) return

        // Jam rawan: Play Store / Pengaturan ditutup total bila saklarnya aktif.
        if (pkg in nightLocked && !selfTesting) {
            startBlock("night", pkg)
            return
        }

        // Folder Aman Samsung dikunci saat Mode Ketat.
        if (strictNow && pkg in SecureGuard.SECURE_FOLDER_PACKAGES) {
            if (now - lastBackAt > 800L) {
                lastBackAt = now
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }
        if (FeatureDump.active()) FeatureDump.capture(pkg, foregroundRoot)

        // Klik pada aplikasi terlindung di Pengaturan (mis. daftar layanan Aksesibilitas): langsung kembali,
        // sebelum halaman detail dan tombol nonaktifkan sempat dipakai.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // Hanya klik pada halaman sistem pengaturan/installer yang memicu proteksi.
            // Jangan memblokir klik launcher: menu Sembunyikan/Tampilkan aplikasi juga
            // mengirim nama aplikasi terlindung, tetapi bukan tindakan uninstall/disable.
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

        // 0b. Cegah memasang browser selain Chrome lewat Play Store
        if (pkg == BrowserStoreGuard.STORE && BrowserStoreGuard.enabled(this) && Store.blockOtherBrowsers(this) &&
            now - lastStoreAt > 300L
        ) {
            lastStoreAt = now
            val sroot = foregroundRoot
            if (BrowserStoreGuard.shouldBack(this, event, sroot, resources.displayMetrics.heightPixels) && now - lastBackAt > 800L) {
                lastBackAt = now
                performGlobalAction(GLOBAL_ACTION_BACK)
                android.widget.Toast.makeText(this, "Browser selain Chrome tidak diizinkan", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 1. Blokir aplikasi
        val verdict = BlockEngine.check(this, pkg, now, apps, profiles)
        if (verdict != null) {
            startBlock(verdict, pkg)
            return
        }
        if (BlockEngine.hasLimit(this, pkg, apps, profiles)) scheduleLimitCheck(pkg)

        // 1b. Jeda napas + niat sebelum aplikasi tertentu dibuka (hanya gesekan, tidak memblokir).
        if (pkg in pauseAppsNow && now > Store.pauseOkUntil(this, pkg) && now - lastPauseAt > 2500L) {
            lastPauseAt = now
            val pi = Intent(this, BlockActivity::class.java)
                .putExtra("mode", "pause")
                .putExtra("pkg", pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(pi)
            return
        }

        // 2. Riwayat situs browser.
        // Ambil domain utama dari address bar sebelum pemeriksaan keyword.
        // Tidak perlu situs tersebut sudah masuk daftar blokir.
        if (pkg in browsers) {
            val historyRoot = rootInActiveWindow
            if (historyRoot?.packageName?.toString() == pkg) {
                SiteHistory.recordFromRoot(this, historyRoot)
            }
        }

        // 3. Keyword URL / layar saat teks berubah.
        // URL keyword hanya boleh dipakai pada address bar browser.
        // Screen keyword berlaku pada teks umum yang tersedia melalui Accessibility.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            var typed = event.text?.joinToString(" ") ?: ""
            if (typed.isEmpty()) {
                // Beberapa aplikasi (mis. kolom pencarian Play Store) tidak mengisi event.text.
                typed = try { event.source?.text?.toString() ?: "" } catch (t: Throwable) { "" }
            }
            if (typed.isNotEmpty()) {
                val source = event.source
                val isUrlBar = source != null && isUrlBarNode(source)
                val urlKw = if (isUrlBar && pkg in browsers) Rules.matchesKeyword(typed, urlKeywords, true) else null
                val screenKw = Rules.matchesKeyword(typed, screenKeywords, true)
                val kwHit = urlKw != null || screenKw != null
                val dmHit = if (isUrlBar && pkg in browsers) Rules.matchesDomain(typed, domains) else null
                val domainBlocked = dmHit != null && SiteTracker.shouldDnsBlock(this, dmHit)
                val ipHit = ipBlock && isUrlBar && pkg in browsers && Rules.isPublicIpUrl(typed)
                if (kwHit || domainBlocked || ipHit) {
                    when {
                        urlKw != null -> noteHit(urlKw, typed, "yang kamu ketik di kolom alamat")
                        screenKw != null -> noteHit(screenKw, typed, "yang kamu ketik")
                        domainBlocked -> noteHit(dmHit ?: "", typed, "situs terblokir di kolom alamat")
                        else -> noteHit("alamat IP publik", typed, "kolom alamat")
                    }
                    startBlock("typed", "")
                }
            }
            return
        }

        // 3b. Layar pemasang APK memuat teksnya bertahap: periksa ulang beberapa kali.
        if (strictNow && blockUnknownNow && pkg.contains("packageinstaller")) scheduleInstallerCheck()

        // 4. Lindungi pengaturan saat Mode Ketat aktif
        if (strictNow && isSettingsLike(pkg) && now - lastProtectAt > 500L) {
            lastProtectAt = now
            val root = rootInActiveWindow
            if (root != null && showsProtected(root)) {
                startBlock("protect", "")
                return
            }
        }

        // 4b. Di luar Mode Ketat: halaman uninstall/nonaktifkan Penjaga Ketat sendiri tetap ditutup,
        // kecuali jendela uninstall dibuka lewat gerbang di aplikasi.
        if (selfGuardNow && isSettingsLike(pkg) && now - lastProtectAt > 500L) {
            lastProtectAt = now
            val root = rootInActiveWindow
            if (root != null && showsOwnPage(root)) {
                startBlock("protect", "")
                return
            }
        }

        // 5. Website: aturan per-domain (timer tunggu, batas harian, jadwal, blokir penuh).
        if (pkg in browsers) {
            val root = rootInActiveWindow
            if (checkWebsiteRule(pkg, root, now)) return
        }

        // 6. Periksa URL browser dan seluruh layar secara terpisah.
        if (now - lastScanAt > 200L) {
            lastScanAt = now
            val root = rootInActiveWindow ?: return
            val rootPkg = root.packageName?.toString() ?: return
            // Abaikan event dari keyboard/IME bila root aktif bukan aplikasi sumber
            // event. Selain itu, PenjagaKetat tidak boleh memeriksa layarnya sendiri.
            if (rootPkg == packageName || rootPkg != pkg) return
            if (pkg in browsers && browserHasBlocked(root)) {
                startBlock("content", "")
                return
            }
            val sHit = screenHasBlocked(root)
            if (pkg == BrowserStoreGuard.STORE) {
                BrowserStoreGuard.screenDiag = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                    " layar: node=" + lastScreenNodes + " teks=" + lastScreenTexts + " kata kunci=" + screenKeywords.size +
                    " hasil=" + (if (sHit) "BLOKIR" else "aman")
            }
            if (sHit) {
                startBlock("screen_keyword", "")
                return
            }
        }

        // 7. Bagian dalam aplikasi sosmed (Shorts, Reels, Cerita, Pencarian, ...)
        checkFeeds(pkg, now)
    }

    /** Menutup bagian sosmed yang dinyalakan. Dipakai juga saat Mode Off (jeda). */
    private fun checkFeeds(pkg: String, now: Long) {
        val feats = Features.activeFor(this, pkg)
        if (feats.isNotEmpty() && now - lastFeedAt > 600L) {
            lastFeedAt = now
            val root = rootInActiveWindow ?: return
            val nodes = collect(root, 500)
            val hit = feats.any { Features.hit(it, nodes) }
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
    private fun screenTexts(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val out = ArrayList<String>()
        for (n in collect(root, 600)) {
            val t = n.text?.toString()?.lowercase()?.trim() ?: ""
            val d = n.contentDescription?.toString()?.lowercase()?.trim() ?: ""
            if (t.isNotEmpty()) out.add(t)
            if (d.isNotEmpty() && d != t) out.add(d)
        }
        return out
    }

    private fun openPk() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (t: Throwable) {
            // abaikan
        }
    }

    /** Uji otomatis: membuka halaman Pengaturan satu per satu dan memeriksa penandanya. */
    fun startSelfTest() {
        if (selfTesting) return
        selfTesting = true
        SelfTest.results.clear()
        handler.postDelayed({ selfTesting = false }, 90000L)
        val targets = SelfTest.targets()
        var idx = 0
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                if (!selfTesting || idx >= targets.size) {
                    selfTesting = false
                    SelfTest.finishedAt = System.currentTimeMillis()
                    openPk()
                    return
                }
                val t = targets[idx]
                if (tries == 0) {
                    try {
                        val i = t.intent() ?: throw IllegalStateException("tanpa perintah")
                        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Throwable) {
                        SelfTest.results.add(SelfTest.Result(t.name, false, "halaman tidak bisa dibuka lewat perintah sistem"))
                        idx++
                        handler.postDelayed(this, 300L)
                        return
                    }
                }
                tries++
                val root = rootInActiveWindow
                val onOther = root != null && root.packageName?.toString() != packageName
                val texts = if (onOther) screenTexts(root) else emptyList()
                val hit = texts.firstOrNull { it in t.markers }
                if (hit != null || tries >= 10) {
                    val detail = when {
                        hit != null -> "terdeteksi lewat teks \"$hit\""
                        texts.isEmpty() -> "halaman tidak terbuka (paket aktif: " + (root?.packageName ?: "tidak ada") + ")"
                        else -> "tidak ada penanda. Teks di layar: " + texts.distinct().take(25).joinToString(" | ")
                    }
                    SelfTest.results.add(SelfTest.Result(t.name, hit != null, detail))
                    if (onOther) performGlobalAction(GLOBAL_ACTION_BACK)
                    idx++
                    tries = 0
                    handler.postDelayed(this, 900L)
                } else {
                    handler.postDelayed(this, 600L)
                }
            }
        }
        handler.post(r)
    }

    /** Pindai layar yang sedang terbuka (mis. DNS Pribadi) setelah [delaySec] detik. */
    fun startManualScan(delaySec: Int) {
        if (selfTesting) return
        selfTesting = true
        SelfTest.results.clear()
        handler.postDelayed({
            try {
                val root = rootInActiveWindow
                val texts = screenTexts(root)
                val hit = texts.firstOrNull { it in SelfTest.ALL_MARKERS }
                val detail = when {
                    hit != null -> "terdeteksi lewat teks \"$hit\""
                    texts.isEmpty() -> "tidak ada teks terbaca"
                    else -> "tidak ada penanda. Teks di layar: " + texts.distinct().take(25).joinToString(" | ")
                }
                SelfTest.results.add(SelfTest.Result("Layar yang kamu buka", hit != null, detail))
            } catch (t: Throwable) {
                SelfTest.results.add(SelfTest.Result("Layar yang kamu buka", false, "gagal memindai"))
            }
            selfTesting = false
            SelfTest.finishedAt = System.currentTimeMillis()
            openPk()
        }, delaySec * 1000L)
    }

    private var installerCheckRunning = false

    private fun scheduleInstallerCheck() {
        if (installerCheckRunning) return
        installerCheckRunning = true
        var n = 0
        val r = object : Runnable {
            override fun run() {
                try {
                    val root = rootInActiveWindow
                    val cur = root?.packageName?.toString() ?: ""
                    if (root != null && cur.contains("packageinstaller") && strictNow && blockUnknownNow) {
                        if (showsProtected(root)) {
                            installerCheckRunning = false
                            startBlock("protect", "")
                            return
                        }
                        n++
                        if (n < 12) {
                            handler.postDelayed(this, 500L)
                            return
                        }
                    }
                } catch (t: Throwable) {
                    // abaikan
                }
                installerCheckRunning = false
            }
        }
        handler.postDelayed(r, 400L)
    }

    private var lastProtectWhy = ""
    private fun why(s: String): Boolean { lastProtectWhy = s; return true }

    /** Nama aplikasi pelindung yang uninstall-nya ikut dijaga di luar Mode Ketat. */
    private val GUARD_LABELS = listOf(
        "blockp", "block p", "stay focused", "stayfocused", "channel blocker", "telegram blocker",
        "safe telegram", "safetelegram", "bankswitch", "bank switch"
    )

    /** Halaman uninstall/nonaktifkan Penjaga Ketat dan aplikasi pelindung lain (daftar Lindungi + bawaan). */
    private fun showsOwnPage(root: AccessibilityNodeInfo): Boolean {
        val guardNames = (listOf(Config.APP_LABEL) + GUARD_LABELS + protectedNames.filter { it.length >= 4 && !it.contains('.') }).distinct()
        var nameHit = false
        var actionHit = false
        for (n in collect(root, 600)) {
            val t = n.text?.toString()?.lowercase()?.trim() ?: ""
            val d = n.contentDescription?.toString()?.lowercase()?.trim() ?: ""
            if (t.isEmpty() && d.isEmpty()) continue
            if (t.contains(Config.SERVICE_MARKER) || d.contains(Config.SERVICE_MARKER)) return why("layanan aksesibilitas (di luar Mode Ketat)")
            if (!nameHit && guardNames.any { t.contains(it) || d.contains(it) }) nameHit = true
            if (isActionText(t) || isActionText(d)) actionHit = true
            if (nameHit && actionHit) return why("uninstal/nonaktifkan aplikasi pelindung (di luar Mode Ketat)")
        }
        return false
    }

    private fun showsProtected(root: AccessibilityNodeInfo): Boolean {
        // Pemasang APK: saat saklar aktif, hanya izinkan pemasangan aplikasi kita sendiri.
        if (blockUnknownNow && (root.packageName?.toString() ?: "").contains("packageinstaller")) {
            val texts = collect(root, 600).mapNotNull {
                (it.text?.toString() ?: it.contentDescription?.toString())?.lowercase()?.trim()?.takeIf { s -> s.isNotEmpty() }
            }
            // Hanya layar PEMASANGAN. Layar copot/uninstall tidak disentuh.
            val isUninstall = texts.any { it.contains("uninstal") || it.contains("copot") || it.contains("hapus") }
            val isInstall = texts.any { it.contains("instal") || it.contains("pasang") || it.contains("perbarui") || it.contains("update") }
            if (!isUninstall && isInstall && texts.size >= 2 &&
                texts.none { t -> SecureGuard.OWN_APP_LABELS.any { t.contains(it) } }
            ) return why("pemasang APK")
        }
        var nameHit = false
        var hitName = ""
        var hitAction = ""
        var actionHit = false
        for (n in collect(root, 600)) {
            val t = n.text?.toString()?.lowercase()?.trim() ?: ""
            val d = n.contentDescription?.toString()?.lowercase()?.trim() ?: ""
            if (t.isEmpty() && d.isEmpty()) continue
            if (t in SecureGuard.SECURE_PAGE_TEXTS || d in SecureGuard.SECURE_PAGE_TEXTS) return why("folder aman: " + t + d)
            if (t in SecureGuard.BYPASS_PAGE_TEXTS || d in SecureGuard.BYPASS_PAGE_TEXTS) return why("halaman celah: " + t + d)
            if (blockUnknownNow && (t in SecureGuard.UNKNOWN_INSTALL_TEXTS || d in SecureGuard.UNKNOWN_INSTALL_TEXTS)) return why("izin instal: " + t + d)
            if (t.contains(Config.SERVICE_MARKER) || d.contains(Config.SERVICE_MARKER)) return why("layanan aksesibilitas")
            if (!nameHit) {
                for (name in protectedNames) {
                    if (t.contains(name) || d.contains(name)) {
                        nameHit = true
                        hitName = name + " di '" + (t + d).take(40) + "'"
                        break
                    }
                }
            }
            if (!actionHit) {
                if (isActionText(t) || isActionText(d)) { actionHit = true; hitAction = (t + d).take(30) }
            }
            if (nameHit && actionHit) return why("nama " + hitName + ", tombol: " + hitAction)
        }
        return false
    }

    /**
     * Permukaan yang memang bisa membawa pengguna ke halaman uninstall/disable.
     * Launcher sengaja tidak masuk pemeriksaan showsProtected(), karena seluruh pohon
     * Accessibility launcher dapat berisi nama aplikasi dan action generik seperti
     * "hapus" meskipun pengguna hanya kembali dari notification shade.
     */
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
        val configured = Store.domains(this) + Focus.activeDomains(this) + ProfileStore.load(this).filter { ProfileEngine.isActive(this, it) }.flatMap { it.domains }
        val domain = SiteTracker.currentDomain(root, configured) ?: run {
            SiteTracker.stop(this)
            return false
        }
        SiteTracker.tick(this, domain)
        if (Focus.blocksDomain(this, domain)) {
            blockDomain = domain
            startBlock("site", pkg)
            return true
        }
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

    private fun isUrlBarNode(n: AccessibilityNodeInfo): Boolean {
        val id = (n.viewIdResourceName ?: "").lowercase()
        return id.contains("url") || id.contains("location_bar") || id.contains("omnibox") ||
            id.contains("address_bar") || id.contains("url_bar")
    }

    /** Pemeriksaan khusus URL/address bar untuk semua browser yang dipantau. */
    private fun browserHasBlocked(root: AccessibilityNodeInfo): Boolean {
        for (n in collect(root, 400)) {
            if (!isUrlBarNode(n)) continue
            val txt = n.text?.toString() ?: n.contentDescription?.toString() ?: continue
            if (txt.isEmpty()) continue
            val hit = Rules.matchesDomain(txt, domains)
            if (hit != null && SiteTracker.shouldDnsBlock(this, hit)) { noteHit(hit, txt, "situs di kolom alamat"); return true }
            val uk = Rules.matchesKeyword(txt, urlKeywords)
            if (uk != null) { noteHit(uk, txt, "di kolom alamat"); return true }
            if (ipBlock && Rules.isPublicIpUrl(txt)) { noteHit("alamat IP publik", txt, "kolom alamat"); return true }
        }
        return false
    }

    /**
     * Pemeriksaan keyword layar. Tidak dibatasi browser: semua aplikasi yang mengirim
     * AccessibilityNodeInfo dapat diperiksa. URL keyword tidak ikut diperiksa di sini.
     */
    private fun screenHasBlocked(root: AccessibilityNodeInfo): Boolean {
        // Pengaman tambahan: jangan pernah memblokir berdasarkan teks di UI PenjagaKetat.
        if (root.packageName?.toString() == packageName) return false
        // Kolom input yang sedang aktif (mis. kotak pencarian) diperiksa lebih dulu. Murah dan tidak
        // bergantung pada urutan node di pohon tampilan yang besar.
        try {
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && !isUrlBarNode(f)) {
                val ft = f.text?.toString()?.trim() ?: ""
                if (ft.isNotEmpty() && screenHit(ft, true, "di kolom input")) return true
            }
        } catch (t: Throwable) {
            // abaikan
        }
        val limit = if (root.packageName?.toString() == BrowserStoreGuard.STORE) 1200 else 600
        val all = collect(root, limit)
        lastScreenNodes = all.size
        lastScreenTexts = all.count { !it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty() }
        for (n in all) {
            // Jangan anggap kolom URL sebagai "layar". URL punya jalur pemeriksaan
            // sendiri agar keyword URL tidak tercampur dengan keyword layar.
            if (isUrlBarNode(n)) continue

            // Accessibility tree kadang masih berisi node tersembunyi/off-screen
            // (termasuk konten lama/placeholder). Node seperti ini tidak boleh
            // menyebabkan blokir ketika pengguna baru membuka kolom input.
            if (!n.isVisibleToUser && !n.isEditable && !n.isFocused) continue

            val txt = n.text?.toString()?.trim() ?: ""
            if (txt.isNotEmpty() && screenHit(txt, n.isEditable, "di layar")) return true

            val desc = n.contentDescription?.toString()?.trim() ?: ""
            if (desc.isNotEmpty() && screenHit(desc, false, "di layar")) return true
        }
        return false
    }

    /** True bila layar masih menampilkan hal yang harus ditutup, sesuai jenis blokir. */
    private fun stillBad(mode: String, pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val cur = root.packageName?.toString() ?: return false
        return when (mode) {
            "content" -> cur != packageName && cur in browsers && browserHasBlocked(root)
            "screen_keyword" -> cur != packageName && screenHasBlocked(root)
            "protect" -> isSettingsLike(cur) && (if (strictNow) showsProtected(root) else showsOwnPage(root))
            else -> cur == pkg
        }
    }

    private fun maxSteps(mode: String): Int = when (mode) {
        "typed" -> 2
        "protect_click" -> 2
        "content" -> 6
        "screen_keyword" -> 6
        "protect" -> 5
        else -> 4
    }

    /**
     * Mode back: tekan Kembali berulang sampai konten terblokir hilang dari layar
     * (halaman situs sebelumnya, dialog copot, atau keluar dari aplikasi), baru tampilkan layar blokir.
     */
    // Layar penutup instan: menutup isi aplikasi sejak deteksi sampai layar blokir tampil, supaya tidak sempat diklik.
    private var shield: android.view.View? = null
    private val hideShieldRunnable = Runnable { hideShield() }

    private fun showShield() {
        if (shield != null) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val v = android.view.View(this)
            v.setBackgroundColor(android.graphics.Color.BLACK)
            val lp = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.OPAQUE
            )
            wm.addView(v, lp)
            shield = v
            handler.removeCallbacks(hideShieldRunnable)
            handler.postDelayed(hideShieldRunnable, 3500L) // pengaman: tidak boleh menempel selamanya
        } catch (t: Throwable) {
            shield = null
        }
    }

    private fun hideShield() {
        handler.removeCallbacks(hideShieldRunnable)
        val v = shield ?: return
        shield = null
        try {
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(v)
        } catch (t: Throwable) {
            // sudah terlepas
        }
    }

    private fun startBlock(mode: String, pkg: String) {
        val now = System.currentTimeMillis()
        if (backing || now - lastBlockAt < 800L) return
        lastBlockAt = now
        backing = true
        if (mode == "screen_keyword" || mode == "content" || mode == "typed" || mode == "nsfw") showShield()
        blockWord = if (mode == "typed" || mode == "content" || mode == "screen_keyword") hitWord else ""
        blockText = hitText
        blockWhere = hitWhere
        hitWord = ""
        hitText = ""
        hitWhere = ""
        val statTarget = if ((mode == "site" || mode == "site_wait") && blockDomain.isNotEmpty()) blockDomain else pkg
        StatsDb.addBlock(this, if (mode == "typed") "content" else if (mode == "protect_click") "protect" else mode, statTarget)
        if (mode == "protect" || mode == "protect_click") Store.log(this, "Proteksi ($mode, $pkg): " + lastProtectWhy.ifEmpty { "klik pada nama terlindung" })
        // Klik di Pengaturan: beri 120 ms agar halaman tujuan sempat terbuka, lalu kembali.
        if (mode == "protect_click") handler.postDelayed({ stepBack(mode, pkg, 0) }, 120L) else stepBack(mode, pkg, 0)
    }

    private fun stepBack(mode: String, pkg: String, n: Int) {
        try {
            val bad = when (mode) {
                "typed" -> n < maxSteps(mode)
                "nsfw" -> false
                "protect_click" -> n == 0 || (n < maxSteps(mode) && stillBad("protect", pkg))
                else -> stillBad(mode, pkg)
            }
            if (!bad || n >= maxSteps(mode)) {
                finishBack(mode, pkg)
                return
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({ stepBack(mode, pkg, n + 1) }, 300L)
        } catch (t: Throwable) {
            backing = false
            hideShield()
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
            .putExtra("word", blockWord)
            .putExtra("wordText", blockText)
            .putExtra("wordWhere", blockWhere)
        handler.postDelayed({
            try {
                startActivity(i)
            } catch (e: Exception) {
                // abaikan
            }
            lastBlockAt = System.currentTimeMillis()
            backing = false
            handler.postDelayed({ hideShield() }, 350L)
        }, 250L)
    }
}
