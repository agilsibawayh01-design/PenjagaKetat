package com.mas.strictguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.provider.Settings

/**
 * Mode Fokus: lapisan blokir SEMENTARA di atas aturan permanen.
 * - tidak pernah mengubah konfigurasi permanen (AppCfg, SiteCfg, profil)
 * - pengecualian (ALLOW) hanya membebaskan dari aturan Fokus; aturan permanen tetap berlaku
 * - Mode Off/jeda menghentikan hitungan sementara; sisa waktu tersimpan dan dilanjutkan
 * - saat Mode Ketat aktif, Fokus tidak bisa dihentikan lebih awal
 * - waktu dihitung dengan elapsedRealtime (kebal ubah jam); reboot tidak menambah waktu terhitung
 */
object Focus {
    class Cfg(
        val blockApps: Set<String>,
        val blockSites: Set<String>,
        val blockCats: Set<String>,
        val allowApps: Set<String>,
        val allowSites: Set<String>
    ) {
        val isEmpty: Boolean get() = blockApps.isEmpty() && blockSites.isEmpty() && blockCats.isEmpty()
    }

    /** Kategori yang bisa dipilih: kunci -> (nama, kategori aplikasi Android, id kategori situs Penjaga Ketat atau null). */
    class CatDef(val key: String, val name: String, val appCategory: Int, val siteCat: String?)

    val CATS = listOf(
        CatDef("social", "Media sosial", ApplicationInfo.CATEGORY_SOCIAL, "sosmed"),
        CatDef("video", "Video dan streaming", ApplicationInfo.CATEGORY_VIDEO, "video"),
        CatDef("game", "Game", ApplicationInfo.CATEGORY_GAME, null),
        CatDef("news", "Berita", ApplicationInfo.CATEGORY_NEWS, null)
    )

    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    // ---------- konfigurasi (templat; tidak berubah saat sesi berjalan kecuali diedit) ----------
    @Volatile
    private var cfgCache: Cfg? = null

    private fun set(c: Context, k: String): Set<String> = HashSet(sp(c).getStringSet(k, emptySet()) ?: emptySet())

    fun cfg(c: Context): Cfg {
        cfgCache?.let { return it }
        val x = Cfg(set(c, "focus_ba"), set(c, "focus_bs"), set(c, "focus_bc"), set(c, "focus_aa"), set(c, "focus_as"))
        cfgCache = x
        return x
    }

    fun setCfg(c: Context, x: Cfg) {
        sp(c).edit()
            .putStringSet("focus_ba", HashSet(x.blockApps))
            .putStringSet("focus_bs", HashSet(x.blockSites))
            .putStringSet("focus_bc", HashSet(x.blockCats))
            .putStringSet("focus_aa", HashSet(x.allowApps))
            .putStringSet("focus_as", HashSet(x.allowSites))
            .commit()
        cfgCache = x
        effCache = null
    }

    // ---------- sesi ----------
    private var loaded = false
    private var on = false
    private var total = 0L
    private var acc = 0L
    private var lastEl = 0L
    private var lastBoot = -1
    private var lastSave = 0L

    private fun bootCount(c: Context): Int = try {
        Settings.Global.getInt(c.contentResolver, "boot_count", -1)
    } catch (e: Exception) {
        -1
    }

    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = sp(c)
        on = p.getBoolean("focus_on", false)
        total = p.getLong("focus_total", 0L)
        acc = p.getLong("focus_acc", 0L)
        lastEl = p.getLong("focus_el", 0L)
        lastBoot = p.getInt("focus_boot", -1)
    }

    private fun save(c: Context) {
        sp(c).edit()
            .putBoolean("focus_on", on)
            .putLong("focus_total", total)
            .putLong("focus_acc", acc)
            .putLong("focus_el", lastEl)
            .putInt("focus_boot", lastBoot)
            .apply()
        lastSave = SystemClock.elapsedRealtime()
    }

    /** Memajukan hitungan. Aman dipanggil sering: hanya membaca memori, menulis paling sering tiap 5 detik. */
    @Synchronized
    private fun tick(c: Context) {
        load(c)
        if (!on) return
        val now = SystemClock.elapsedRealtime()
        val boot = bootCount(c)
        if (boot != lastBoot || now < lastEl) {
            // reboot atau penghitung mundur: waktu mati tidak dihitung (lebih ketat, bukan lebih longgar)
            lastBoot = boot
            lastEl = now
            save(c)
            return
        }
        val d = now - lastEl
        lastEl = now
        if (!Store.pausedNow(c)) acc += d
        if (acc >= total) {
            on = false
            effCache = null
            save(c)
            Store.log(c, "Mode Fokus selesai, aturan normal kembali")
            return
        }
        if (now - lastSave > 5000L) save(c)
    }

    /** Ada sesi Fokus (termasuk yang sedang dijeda Mode Off). */
    fun running(c: Context): Boolean {
        tick(c)
        return on
    }

    /** Fokus sedang memberlakukan aturan (sesi ada dan tidak dijeda). */
    fun active(c: Context): Boolean {
        tick(c)
        return on && !Store.pausedNow(c)
    }

    fun leftMs(c: Context): Long {
        tick(c)
        return if (on) Math.max(0L, total - acc) else 0L
    }

    @Synchronized
    fun start(c: Context, minutes: Int): Boolean {
        load(c)
        if (on || minutes <= 0) return false
        on = true
        total = minutes * 60000L
        acc = 0L
        lastEl = SystemClock.elapsedRealtime()
        lastBoot = bootCount(c)
        effCache = null
        save(c)
        Store.log(c, "Mode Fokus mulai ($minutes menit)")
        return true
    }

    /** Menambah waktu selalu boleh (hanya memperketat). */
    @Synchronized
    fun extend(c: Context, minutes: Int) {
        tick(c)
        if (!on || minutes <= 0) return
        total += minutes * 60000L
        save(c)
    }

    /** Menghentikan lebih awal. Ditolak saat Mode Ketat aktif. */
    @Synchronized
    fun stop(c: Context): Boolean {
        load(c)
        if (!on) return true
        if (Store.strictOn(c)) return false
        on = false
        effCache = null
        save(c)
        Store.log(c, "Mode Fokus dihentikan lebih awal")
        return true
    }

    // ---------- penerapan ----------
    private val appCat = HashMap<String, Int>()

    private fun excluded(c: Context, pkg: String): Boolean {
        if (pkg == c.packageName || pkg in Config.IGNORED || pkg in Config.PROTECTED || pkg in Config.BANK_HINT) return true
        val l = pkg.lowercase()
        return l.contains("dialer") || l.contains("incallui") || l.contains("emergency")
    }

    private fun categoryOf(c: Context, pkg: String): Int {
        synchronized(appCat) {
            appCat[pkg]?.let { return it }
        }
        val v = try {
            c.packageManager.getApplicationInfo(pkg, 0).category
        } catch (e: Exception) {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
        synchronized(appCat) { appCat[pkg] = v }
        return v
    }

    fun blocksApp(c: Context, pkg: String): Boolean {
        if (!active(c)) return false
        if (excluded(c, pkg)) return false
        val x = cfg(c)
        if (pkg in x.allowApps) return false
        if (pkg in x.blockApps) return true
        if (x.blockCats.isNotEmpty()) {
            val cat = categoryOf(c, pkg)
            for (d in CATS) if (d.key in x.blockCats && d.appCategory == cat) return true
        }
        return false
    }

    @Volatile
    private var effCache: Set<String>? = null

    /** Domain yang diblokir Fokus saat ini (kosong bila Fokus tidak berlaku). */
    fun activeDomains(c: Context): Set<String> {
        if (!active(c)) return emptySet()
        effCache?.let { return it }
        val x = cfg(c)
        val s = HashSet<String>(x.blockSites)
        for (d in CATS) if (d.key in x.blockCats && d.siteCat != null) {
            Categories.ALL.firstOrNull { it.id == d.siteCat }?.let { s.addAll(it.domains) }
        }
        s.removeAll(x.allowSites)
        effCache = s
        return s
    }

    fun blocksDomain(c: Context, domain: String): Boolean {
        val d = Rules.normalizeDomain(domain)
        if (d.isEmpty()) return false
        val set = activeDomains(c)
        if (set.isEmpty()) return false
        return set.any { d == it || d.endsWith(".$it") }
    }
}
