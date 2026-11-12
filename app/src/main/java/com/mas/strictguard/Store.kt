package com.mas.strictguard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class AppCfg(
    val wait: Int = 0,
    val pass: Int = 5,
    val schOn: Boolean = false,
    val start: Int = 21 * 60,
    val end: Int = 5 * 60,
    val days: Int = 127,
    val mode: Int = 0,
    val limitMin: Int = 0,
    val cooldown: Int = 0,
    val blockUntil: Long = 0L,
    val hourLimit: Int = 0,
    val launchDay: Int = 0,
    val launchHour: Int = 0,
    val twUse: Int = 0,
    val twWait: Int = 0,
    val twAdaptive: Boolean = true,
    val notif: Boolean = false
)

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    private fun getSet(c: Context, key: String): Set<String> =
        HashSet(sp(c).getStringSet(key, emptySet()) ?: emptySet())

    private fun putSet(c: Context, key: String, v: Set<String>) {
        sp(c).edit().putStringSet(key, HashSet(v)).commit()
    }

    fun apps(c: Context): Set<String> = getSet(c, "apps")

    /**
     * Keyword URL: hanya dimaksudkan untuk pemeriksaan URL/address bar browser.
     * Keyword lama tetap dimigrasikan ke sini agar tidak hilang saat upgrade.
     */
    fun urlKeywords(c: Context): Set<String> { ensureKeywordSplit(c); return getSet(c, "keywords_url") }

    /**
     * Keyword layar: diperiksa pada teks/contentDescription yang tersedia melalui Accessibility.
     * Keyword lama juga dimigrasikan ke sini agar perilaku V20 tidak tiba-tiba melemah.
     */
    fun screenKeywords(c: Context): Set<String> { ensureKeywordSplit(c); return getSet(c, "keywords_screen") }

    /** Union untuk kompatibilitas kode lama dan statistik. */
    fun keywords(c: Context): Set<String> = urlKeywords(c) + screenKeywords(c)
    fun domains(c: Context): Set<String> = getSet(c, "domains")

    fun siteCfgOrNull(c: Context, domain: String): SiteCfg? {
        val d = Rules.normalizeDomain(domain)
        if (d.isEmpty()) return null
        val raw = sp(c).getString("sitecfg_$d", null) ?: return null
        return try {
            val p = raw.split('|')
            fun i(k: Int, dflt: Int) = p.getOrNull(k)?.toIntOrNull() ?: dflt
            SiteCfg(mode=i(0,0), schOn=p.getOrNull(1)=="1", start=i(2,1260), end=i(3,300), days=i(4,127), limitMin=i(5,0), hourLimit=i(6,0), twUse=i(7,0), twWait=i(8,0), twAdaptive=p.getOrNull(9)!="0")
        } catch (_: Throwable) { null }
    }
    fun siteCfg(c: Context, domain: String): SiteCfg = siteCfgOrNull(c, domain) ?: SiteCfg()
    fun setSiteCfg(c: Context, domain: String, g: SiteCfg) {
        val d = Rules.normalizeDomain(domain); if (d.isEmpty()) return
        val old = siteCfg(c, d)
        val raw = "${g.mode}|${if(g.schOn) 1 else 0}|${g.start}|${g.end}|${g.days}|${g.limitMin}|${g.hourLimit}|${g.twUse}|${g.twWait}|${if(g.twAdaptive) 1 else 0}"
        sp(c).edit().putString("sitecfg_$d", raw).commit()
        SiteTracker.onConfigSaved(c, d, old, g)
    }

    fun protectedApps(c: Context): Set<String> = getSet(c, "prot") + Config.SEED_PROTECTED
    fun addProtected(c: Context, p: String) = putSet(c, "prot", getSet(c, "prot") + p)
    fun removeProtected(c: Context, p: String) = putSet(c, "prot", getSet(c, "prot") - p)

    fun appCfg(c: Context, pkg: String): AppCfg {
        val raw = sp(c).getString("cfg_$pkg", null) ?: return AppCfg()
        return try {
            val p = raw.split('|')
            fun i(k: Int, d: Int): Int = p.getOrNull(k)?.toIntOrNull() ?: d
            AppCfg(
                i(0, 0), i(1, 5), p.getOrNull(2) == "1", i(3, 21 * 60), i(4, 5 * 60), i(5, 127),
                i(6, 0), i(7, 0), i(8, 0), p.getOrNull(9)?.toLongOrNull() ?: 0L,
                i(10, 0), i(11, 0), i(12, 0), i(13, 0), i(14, 0),
                if (p.size > 15) p[15] == "1" else true,
                p.getOrNull(16) == "1"
            )
        } catch (e: Exception) {
            AppCfg()
        }
    }

    fun setAppCfg(c: Context, pkg: String, g: AppCfg) {
        val raw = "${g.wait}|${g.pass}|${if (g.schOn) "1" else "0"}|${g.start}|${g.end}|${g.days}|" +
            "${g.mode}|${g.limitMin}|${g.cooldown}|${g.blockUntil}|" +
            "${g.hourLimit}|${g.launchDay}|${g.launchHour}|${g.twUse}|${g.twWait}|${if (g.twAdaptive) "1" else "0"}|${if (g.notif) "1" else "0"}"
        sp(c).edit().putString("cfg_$pkg", raw).commit()
    }

    /** Memperpanjang blokir penuh (tidak bisa dibuka) sampai waktu tertentu. Hanya memperketat. */
    fun extendBlockUntil(c: Context, pkg: String, minutes: Int) {
        val cfg = appCfg(c, pkg)
        val until = Math.max(cfg.blockUntil, System.currentTimeMillis() + minutes * 60000L)
        setAppCfg(c, pkg, cfg.copy(blockUntil = until))
    }

    fun lastPassEnd(c: Context, pkg: String): Long = sp(c).getLong("pend_$pkg", 0L)
    fun setLastPassEnd(c: Context, pkg: String, v: Long) { sp(c).edit().putLong("pend_$pkg", v).commit() }

    fun passHistory(c: Context): List<String> {
        val raw = sp(c).getString("passlog", "") ?: ""
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun addPassHistory(c: Context, pkg: String, minutes: Int) {
        val stamp = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val lines = ArrayList(passHistory(c))
        lines.add("$stamp|$pkg|$minutes")
        sp(c).edit().putString("passlog", lines.takeLast(30).joinToString("\n")).apply()
    }

    fun motivation(c: Context): String = sp(c).getString("motivation", null)
        ?: "Ingat tujuanmu. Waktu yang kamu jaga hari ini adalah investasi untuk dirimu esok."
    fun setMotivation(c: Context, v: String) { sp(c).edit().putString("motivation", v.trim()).commit() }

    fun categoryOn(c: Context, id: String): Boolean =
        sp(c).getBoolean("cat_$id", id == "dewasa" || id == "judi" || id == "dewasa2" || id == "judi2" || id == "dewasa3" || id == "judi3" || id == "bypass" || id == "film")
    fun setCategory(c: Context, id: String, v: Boolean) { sp(c).edit().putBoolean("cat_$id", v).commit() }

    fun exitDelayMin(c: Context): Int = sp(c).getInt("exit_delay", 60)
    fun setExitDelayMin(c: Context, v: Int) { sp(c).edit().putInt("exit_delay", v).commit() }

    fun autoBlockNew(c: Context): Boolean = sp(c).getBoolean("auto_new", true)
    fun setAutoBlockNew(c: Context, v: Boolean) { sp(c).edit().putBoolean("auto_new", v).commit() }

    fun newApps(c: Context): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        for (e in getSet(c, "newapps")) {
            val i = e.lastIndexOf('|')
            if (i <= 0) continue
            val t = e.substring(i + 1).toLongOrNull() ?: continue
            out.add(Pair(e.substring(0, i), t))
        }
        return out.sortedByDescending { it.second }.take(15)
    }

    fun addNewApp(c: Context, pkg: String) {
        val all = getSet(c, "newapps").filter { !it.startsWith("$pkg|") }.toMutableSet()
        all.add(pkg + "|" + System.currentTimeMillis())
        putSet(c, "newapps", all)
    }

    fun extraBrowsers(c: Context): Set<String> = getSet(c, "browsers")
    fun addBrowser(c: Context, p: String) = putSet(c, "browsers", getSet(c, "browsers") + p)

    fun logs(c: Context): List<String> {
        val raw = sp(c).getString("log", "") ?: ""
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun log(c: Context, msg: String) {
        val stamp = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val lines = ArrayList(logs(c))
        lines.add("$stamp  $msg")
        val kept = lines.takeLast(40)
        sp(c).edit().putString("log", kept.joinToString("\n")).apply()
    }

    fun addApp(c: Context, p: String) = putSet(c, "apps", apps(c) + p)
    fun removeApp(c: Context, p: String) {
        putSet(c, "apps", apps(c) - p)
        LocationRules.set(c, "app_$p", null)
    }
    /** Kompatibilitas: keyword umum lama ditambahkan ke kedua jenis. */
    fun addKeyword(c: Context, k: String) {
        val v = k.trim().lowercase()
        if (v.isEmpty()) return
        enableBuiltinKeyword(c, v)
        putSet(c, "keywords", getSet(c, "keywords") + v)
        putSet(c, "keywords_url", urlKeywords(c) + v)
        putSet(c, "keywords_screen", screenKeywords(c) + v)
    }

    fun removeKeyword(c: Context, k: String) {
        val v = k.trim().lowercase()
        putSet(c, "keywords", getSet(c, "keywords") - v)
        if (isBuiltinKeyword(c, v)) disableBuiltinKeyword(c, v)
        putSet(c, "keywords_url", urlKeywords(c) - v)
        putSet(c, "keywords_screen", screenKeywords(c) - v)
    }

    fun addUrlKeyword(c: Context, k: String) {
        val v = k.trim().lowercase()
        if (v.isNotEmpty()) {
            enableBuiltinKeyword(c, v)
            putSet(c, "keywords_url", urlKeywords(c) + v)
        }
    }

    fun removeUrlKeyword(c: Context, k: String) {
        val v = k.trim().lowercase()
        putSet(c, "keywords_url", urlKeywords(c) - v)
    }

    fun addScreenKeyword(c: Context, k: String) {
        val v = k.trim().lowercase()
        if (v.isNotEmpty()) {
            enableBuiltinKeyword(c, v)
            putSet(c, "keywords_screen", screenKeywords(c) + v)
        }
    }

    fun removeScreenKeyword(c: Context, k: String) {
        val v = k.trim().lowercase()
        putSet(c, "keywords_screen", screenKeywords(c) - v)
    }

    /** Keyword bawaan yang sengaja dimatikan pengguna. */
    fun disabledBuiltinKeywords(c: Context): Set<String> = getSet(c, "keywords_builtin_disabled")

    fun builtinKeywords(c: Context): Set<String> =
        (Config.SEED_KEYWORDS + Categories.ALL.flatMap { it.keywords }).map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }.toSet()

    fun isBuiltinKeyword(c: Context, keyword: String): Boolean =
        keyword.trim().lowercase() in builtinKeywords(c)

    fun disableBuiltinKeyword(c: Context, keyword: String) {
        val v = keyword.trim().lowercase()
        if (!isBuiltinKeyword(c, v)) return
        putSet(c, "keywords_builtin_disabled", disabledBuiltinKeywords(c) + v)
        putSet(c, "keywords_url", urlKeywords(c) - v)
        putSet(c, "keywords_screen", screenKeywords(c) - v)
    }

    fun enableBuiltinKeyword(c: Context, keyword: String) {
        val v = keyword.trim().lowercase()
        if (!isBuiltinKeyword(c, v)) return
        putSet(c, "keywords_builtin_disabled", disabledBuiltinKeywords(c) - v)
    }

    /**
     * Migrasi sekali dari storage keyword V20 lama ke dua kategori baru.
     * Keyword lama dimasukkan ke KEDUA kategori agar upgrade tidak mengurangi perlindungan.
     */
    fun ensureKeywordSplit(c: Context) {
        val prefs = sp(c)
        if (prefs.getBoolean("keyword_split_v21", false)) return
        val legacy = getSet(c, "keywords")
        if (legacy.isNotEmpty()) {
            putSet(c, "keywords_url", getSet(c, "keywords_url") + legacy)
            putSet(c, "keywords_screen", getSet(c, "keywords_screen") + legacy)
        }
        prefs.edit().putBoolean("keyword_split_v21", true).commit()
    }
    fun addDomain(c: Context, d: String) {
        val n = Rules.normalizeDomain(d)
        if (n.isNotEmpty()) putSet(c, "domains", domains(c) + n)
    }
    /** Channel Telegram yang diblokir (nama channel, huruf kecil). Diisi blokir otomatis NSFW atau manual. */
    fun channels(c: Context): Set<String> = getSet(c, "channels")
    fun addChannel(c: Context, name: String) {
        val n = name.trim().replace(Regex("\\s+"), " ").lowercase()
        if (n.length >= 2) putSet(c, "channels", channels(c) + n)
    }
    fun removeChannel(c: Context, name: String) {
        putSet(c, "channels", channels(c) - name)
    }
    fun removeDomain(c: Context, d: String) {
        putSet(c, "domains", domains(c) - d)
        LocationRules.set(c, "site_" + Rules.normalizeDomain(d), null)
    }

    fun seed(c: Context) {
        ensureKeywordSplit(c)
        if (sp(c).getBoolean("seeded", false)) return
        val seedKeywords = Config.SEED_KEYWORDS.map { it.lowercase() }.toSet()
        putSet(c, "keywords", getSet(c, "keywords") + seedKeywords)
        putSet(c, "keywords_url", urlKeywords(c) + seedKeywords)
        putSet(c, "keywords_screen", screenKeywords(c) + seedKeywords)
        putSet(c, "domains", domains(c) + Config.SEED_DOMAINS)
        putSet(c, "apps", apps(c) + Config.SEED_APPS)
        sp(c).edit().putBoolean("seeded", true).commit()
    }

    /** Bendera Mode Ketat mentah (tanpa memperhitungkan jadwal dan kedaluwarsa). */
    fun strictRaw(c: Context): Boolean = sp(c).getBoolean("strict", false)

    /** Mode Ketat yang BERLAKU sekarang: bendera aktif, belum kedaluwarsa, dan sedang dalam jadwal (bila ada). */
    fun strictOn(c: Context): Boolean {
        if (!strictRaw(c)) return false
        // Di dalam Zona Ketat: jadwal dan kedaluwarsa diabaikan (selalu ketat).
        if (Zone.active(c)) return true
        val exp = expireMin(c)
        if (exp > 0 && SafeTimer.isRunning(c, "expire") && SafeTimer.elapsed(c, "expire") >= exp * 60000L) {
            setStrict(c, false)
            log(c, "Mode Ketat berakhir otomatis (waktu kedaluwarsa)")
            return false
        }
        val sch = strictSchedules(c)
        if (sch.isNotEmpty() && !TimeGuard.penaltyActive(c) &&
            sch.none { Rules.scheduleActive(it.start, it.end, it.days) }
        ) return false
        return true
    }

    // ---- Metode penonaktifan Mode Ketat ----
    fun expireMin(c: Context): Int = sp(c).getInt("exp_min", 0)
    fun setExpireMin(c: Context, v: Int) { sp(c).edit().putInt("exp_min", v).commit() }

    fun exitWaitOn(c: Context): Boolean = sp(c).getBoolean("exit_wait_on", true)
    fun setExitWaitOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("exit_wait_on", v).commit() }

    /** Panjang teks acak untuk keluar dari Mode Ketat; 0 = tidak dipakai. */
    fun exitTextLen(c: Context): Int = sp(c).getInt("exit_text_len", 16)
    fun setExitTextLen(c: Context, v: Int) { sp(c).edit().putInt("exit_text_len", v).commit() }

    fun strictSchedules(c: Context): List<Sched> {
        val raw = sp(c).getString("strict_sched", "") ?: ""
        val out = ArrayList<Sched>()
        for (part in raw.split(';')) {
            val f = part.split(',')
            if (f.size == 3) {
                val st = f[0].toIntOrNull()
                val en = f[1].toIntOrNull()
                val dy = f[2].toIntOrNull()
                if (st != null && en != null && dy != null) out.add(Sched(st, en, dy))
            }
        }
        return out
    }

    fun setStrictSchedules(c: Context, list: List<Sched>) {
        sp(c).edit().putString("strict_sched", list.joinToString(";") { "${it.start},${it.end},${it.days}" }).commit()
    }

    // ---- Mode Off (jeda) ----
    /** Menit jeda yang dipilih; 0 = sampai dinyalakan lagi. */
    fun pauseMinutes(c: Context): Int = sp(c).getInt("pause_min", 0)

    fun pausedNow(c: Context): Boolean {
        if (!SafeTimer.isRunning(c, "pause")) return false
        if (strictOn(c)) {
            SafeTimer.cancel(c, "pause")
            return false
        }
        val m = pauseMinutes(c)
        if (m > 0 && SafeTimer.elapsed(c, "pause") >= m * 60000L) {
            SafeTimer.cancel(c, "pause")
            log(c, "Jeda selesai, blokir bekerja lagi")
            return false
        }
        return true
    }

    fun pauseStart(c: Context, minutes: Int) {
        sp(c).edit().putInt("pause_min", minutes).commit()
        SafeTimer.start(c, "pause")
        log(c, if (minutes > 0) "Mode Off (jeda $minutes menit)" else "Mode Off (sampai dinyalakan lagi)")
    }

    /** Jendela 10 menit (dibuka lewat gerbang di aplikasi) saat uninstall/penonaktifan Penjaga Ketat diizinkan di luar Mode Ketat. */
    fun uninstallWindowOpen(c: Context): Boolean =
        SafeTimer.isRunning(c, "uninst") && SafeTimer.elapsed(c, "uninst") < 10 * 60000L

    fun uninstallLeftMin(c: Context): Long =
        if (!uninstallWindowOpen(c)) 0L else 10L - SafeTimer.elapsed(c, "uninst") / 60000L

    fun openUninstallWindow(c: Context) {
        SafeTimer.start(c, "uninst")
        log(c, "Jendela uninstall dibuka (10 menit)")
    }

    fun closeUninstallWindow(c: Context) {
        SafeTimer.cancel(c, "uninst")
        log(c, "Jendela uninstall ditutup")
    }

    fun pauseStop(c: Context) {
        if (SafeTimer.isRunning(c, "pause")) log(c, "Mode Off diakhiri, kembali ke Mode Normal")
        SafeTimer.cancel(c, "pause")
    }

    fun pauseLeftMin(c: Context): Long {
        val m = pauseMinutes(c)
        if (m <= 0) return -1L
        return Math.max(0L, m * 60000L - SafeTimer.elapsed(c, "pause")) / 60000L + 1L
    }

    // ---- Browser ----
    fun blockIpUrls(c: Context): Boolean = sp(c).getBoolean("block_ip_urls", true)
    fun setBlockIpUrls(c: Context, v: Boolean) { sp(c).edit().putBoolean("block_ip_urls", v).commit() }

    fun blockOtherBrowsers(c: Context): Boolean = sp(c).getBoolean("block_browsers", true)
    fun setBlockOtherBrowsers(c: Context, v: Boolean) { sp(c).edit().putBoolean("block_browsers", v).commit() }

    /** Saklar terpisah: saat Mode Ketat, tutup halaman izin "Instal aplikasi tidak dikenal". */
    fun nsfwAi(c: Context): Boolean = sp(c).getBoolean("nsfw_ai", true)
    fun setNsfwAi(c: Context, v: Boolean) { sp(c).edit().putBoolean("nsfw_ai", v).commit() }

    fun blockUnknownInstall(c: Context): Boolean = sp(c).getBoolean("block_unknown_install", false)
    fun setBlockUnknownInstall(c: Context, v: Boolean) { sp(c).edit().putBoolean("block_unknown_install", v).commit() }

    /** Surat untuk diri sendiri: ditampilkan di dialog gerbang saat mencoba keluar/mematikan fitur. */
    fun letter(c: Context): String = sp(c).getString("letter", null)
        ?: "Ingatlah Allah, Tuhanmu.\nAllah melihatmu dan mengawasimu setiap saat."
    fun setLetter(c: Context, v: String) { sp(c).edit().putString("letter", v.trim()).commit() }

    /** Aplikasi yang diberi jeda (napas + niat) sebelum dibuka. */
    fun pauseApps(c: Context): Set<String> = getSet(c, "pause_apps")
    fun addPauseApp(c: Context, p: String) = putSet(c, "pause_apps", getSet(c, "pause_apps") + p)
    fun removePauseApp(c: Context, p: String) = putSet(c, "pause_apps", getSet(c, "pause_apps") - p)
    fun pauseOkUntil(c: Context, pkg: String): Long = sp(c).getLong("pauseok_$pkg", 0L)
    fun setPauseOk(c: Context, pkg: String, v: Long) { sp(c).edit().putLong("pauseok_$pkg", v).commit() }

    /** Riwayat tombol Dorongan (waktu), disimpan maksimal 200. */
    fun urgeTimes(c: Context): List<Long> =
        (sp(c).getString("urge_times", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
    fun addUrge(c: Context) {
        val l = (urgeTimes(c) + System.currentTimeMillis()).takeLast(200)
        sp(c).edit().putString("urge_times", l.joinToString(",")).commit()
    }

    /** Browser terpasang selain Chrome yang akan diblokir (bila aturannya aktif). */
    @Volatile
    var pkgStamp = 0L

    private val BROWSER_WORDS = listOf("browser", "peramban", "simontok", "vpn browser", "private browser", "incognito")

    /** Semua aplikasi peramban terpasang: filter tautan web umum (tanpa host) atau nama yang mengandung kata peramban. */
    fun detectedBrowsers(c: Context): Set<String> {
        val out = HashSet<String>()
        val pm = c.packageManager
        try {
            for (u in listOf("http://example.com", "https://example.com")) {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addCategory(Intent.CATEGORY_BROWSABLE)
                for (r in pm.queryIntentActivities(i, PackageManager.GET_RESOLVED_FILTER)) {
                    val f = r.filter ?: continue
                    if (f.countDataAuthorities() == 0) out.add(r.activityInfo.packageName)
                }
            }
        } catch (t: Throwable) {
            // lanjut dengan heuristik nama
        }
        try {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (r in pm.queryIntentActivities(main, 0)) {
                val p = r.activityInfo.packageName
                val label = try { r.loadLabel(pm).toString().lowercase() } catch (e: Exception) { "" }
                val pl = p.lowercase()
                if (BROWSER_WORDS.any { label.contains(it) || pl.contains(it) }) out.add(p)
            }
        } catch (t: Throwable) {
            // abaikan
        }
        return out
    }

    fun unsupportedBrowsers(c: Context): Set<String> {
        val out = HashSet<String>()
        val pm = c.packageManager
        for (p in Config.BROWSERS + extraBrowsers(c) + detectedBrowsers(c)) {
            if (p == Config.ALLOWED_BROWSER || p in Config.BROWSER_NEVER_BLOCK) continue
            if (p == c.packageName || p in Config.BANK_HINT || p in Config.PROTECTED || p in Config.EXCLUDED_FROM_LIST || p in Config.IGNORED) continue
            if (pm.getLaunchIntentForPackage(p) == null) continue
            out.add(p)
        }
        return out
    }

    fun setStrict(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("strict", v).commit()
        if (v) {
            if (!sp(c).contains("locked")) putSet(c, "locked", apps(c))
            SafeTimer.cancel(c, "pause")
            if (expireMin(c) > 0) SafeTimer.start(c, "expire") else SafeTimer.cancel(c, "expire")
        } else {
            sp(c).edit().remove("locked").commit()
            SafeTimer.cancel(c, "expire")
        }
    }

    /** Aplikasi yang sudah diblokir dan diatur sebelum Mode Ketat: aturannya terkunci. Aplikasi baru belum terkunci sampai aturannya disimpan. */
    fun isLocked(c: Context, pkg: String): Boolean {
        if (!strictOn(c)) return false
        if (!sp(c).contains("locked")) putSet(c, "locked", apps(c))
        return pkg in getSet(c, "locked")
    }

    fun lockApp(c: Context, pkg: String) {
        if (strictOn(c)) putSet(c, "locked", getSet(c, "locked") + pkg)
    }

    fun anyNotifRule(c: Context): Boolean = apps(c).any { appCfg(c, it).notif }

    fun vpnOn(c: Context): Boolean = sp(c).getBoolean("vpn_on", false)
    fun setVpnOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("vpn_on", v).commit() }
    fun vpnExcluded(c: Context): Set<String> = getSet(c, "vpn_excl")
    fun setVpnExcluded(c: Context, v: Set<String>) = putSet(c, "vpn_excl", v)
    fun vpnExcludeBanks(c: Context): Boolean = sp(c).getBoolean("vpn_excl_bank", true)
    fun setVpnExcludeBanks(c: Context, v: Boolean) { sp(c).edit().putBoolean("vpn_excl_bank", v).commit() }
    fun vpnUpstream(c: Context): Int = sp(c).getInt("vpn_up", 0)
    fun setVpnUpstream(c: Context, v: Int) { sp(c).edit().putInt("vpn_up", v).commit() }
    fun vpnDoh(c: Context): Boolean = sp(c).getBoolean("vpn_doh", false)
    fun setVpnDoh(c: Context, v: Boolean) { sp(c).edit().putBoolean("vpn_doh", v).commit() }

    fun disableRequestedAt(c: Context): Long = sp(c).getLong("disable_req", 0L)
    fun setDisableRequestedAt(c: Context, v: Long) { sp(c).edit().putLong("disable_req", v).commit() }

    fun waitSeconds(c: Context): Int = sp(c).getInt("wait_s", 0)
    fun passMinutes(c: Context): Int = sp(c).getInt("pass_m", 5)
    fun setWait(c: Context, seconds: Int, passMin: Int) {
        sp(c).edit().putInt("wait_s", seconds).putInt("pass_m", passMin).commit()
    }

    fun passUntil(c: Context, pkg: String): Long = sp(c).getLong("pass_$pkg", 0L)
    fun setPass(c: Context, pkg: String, until: Long) { sp(c).edit().putLong("pass_$pkg", until).commit() }

    fun scheduleEnabled(c: Context): Boolean = sp(c).getBoolean("sch_on", false)
    fun startMin(c: Context): Int = sp(c).getInt("sch_start", 21 * 60)
    fun endMin(c: Context): Int = sp(c).getInt("sch_end", 5 * 60)
    fun days(c: Context): Int = sp(c).getInt("sch_days", 127)
    fun setSchedule(c: Context, on: Boolean, start: Int, end: Int, days: Int) {
        sp(c).edit().putBoolean("sch_on", on).putInt("sch_start", start).putInt("sch_end", end)
            .putInt("sch_days", days).commit()
    }

    fun blockShorts(c: Context): Boolean = sp(c).getBoolean("shorts", true)
    fun blockReels(c: Context): Boolean = sp(c).getBoolean("reels", true)
    fun setFeeds(c: Context, shorts: Boolean, reels: Boolean) {
        sp(c).edit().putBoolean("shorts", shorts).putBoolean("reels", reels).commit()
    }
}
