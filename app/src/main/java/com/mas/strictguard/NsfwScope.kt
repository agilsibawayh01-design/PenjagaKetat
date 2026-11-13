package com.mas.strictguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Menentukan aplikasi mana yang layarnya dipindai pendeteksi gambar AI, supaya hemat baterai:
 * hanya aplikasi yang bisa memuat media (browser, sosmed, galeri, pemutar video, dll.) dan aplikasi yang
 * baru dipasang. Aplikasi lain (Ruang Tenang, kalender, bank, dsb.) tidak dipotret rutin.
 */
object NsfwScope {
    const val NEW_APP_DAYS = 14

    private val KNOWN = setOf(
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
        "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android", "com.instagram.barcelona",
        "com.facebook.katana", "com.facebook.lite", "com.twitter.android", "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill", "com.google.android.youtube", "com.google.android.apps.youtube.music",
        "com.reddit.frontpage", "com.snapchat.android", "com.pinterest", "com.discord", "com.tumblr",
        "com.sec.android.gallery3d", "com.google.android.apps.photos", "com.samsung.android.video",
        "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
        "com.android.chrome", "com.sec.android.app.sbrowser", "com.samsung.android.messaging",
        "com.google.android.apps.messaging", "com.sec.android.app.myfiles", "com.android.documentsui",
        "com.google.android.documentsui", "com.google.android.apps.nbu.files",
        // Aplikasi di HP M.A.S yang bisa menampilkan gambar/video dari luar: pencarian, asisten AI, surel, drive, pasar belanja, peta.
        "com.google.android.googlequicksearchbox", "com.google.android.apps.bard", "com.openai.chatgpt",
        "com.anthropic.claude", "com.google.android.apps.docs", "com.google.android.gm",
        "com.shopee.id", "com.tokopedia.tkpd", "com.google.android.apps.maps", "com.sec.android.app.camera"
    )

    /** Kategori aplikasi (ApplicationInfo.category) yang memuat media. */
    fun categoryHasMedia(category: Int): Boolean =
        category == ApplicationInfo.CATEGORY_VIDEO || category == ApplicationInfo.CATEGORY_IMAGE ||
            category == ApplicationInfo.CATEGORY_SOCIAL || category == ApplicationInfo.CATEGORY_NEWS

    /** Murni (bisa dites): baru dipasang? */
    fun isNew(firstInstall: Long, now: Long): Boolean = now - firstInstall in 0..(NEW_APP_DAYS * 86_400_000L)

    private val cache = HashMap<String, Pair<Boolean, Long>>()
    private const val TTL_MS = 10 * 60_000L

    @Volatile var skips = 0

    /** hasPip = ada jendela PiP di layar: tetap dipindai walau aplikasi di depan peluncur. */
    fun shouldScan(c: Context, pkg: String, browsers: Set<String>, hasPip: () -> Boolean): Boolean {
        val now = System.currentTimeMillis()
        val hit = synchronized(cache) { cache[pkg] }
        val base = if (hit != null && now - hit.second < TTL_MS) hit.first else {
            val v = compute(c, pkg, browsers, now)
            synchronized(cache) { cache[pkg] = v to now }
            v
        }
        if (base) return true
        if (hasPip()) return true
        skips++
        return false
    }

    private fun compute(c: Context, pkg: String, browsers: Set<String>, now: Long): Boolean {
        if (pkg in KNOWN || pkg in browsers) return true
        return try {
            val pm = c.packageManager
            val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            if (isNew(pi.firstInstallTime, now)) return true
            if (categoryHasMedia(pi.applicationInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED)) return true
            // Kategori tak diketahui: pindai bila aplikasi meminta akses foto/video/penyimpanan.
            val perms = pi.requestedPermissions ?: emptyArray()
            (pi.applicationInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED) == ApplicationInfo.CATEGORY_UNDEFINED &&
                perms.any {
                    it == "android.permission.READ_MEDIA_IMAGES" || it == "android.permission.READ_MEDIA_VIDEO" ||
                        it == "android.permission.READ_EXTERNAL_STORAGE" || it == "android.permission.MANAGE_EXTERNAL_STORAGE"
                }
        } catch (t: Throwable) {
            true // tidak bisa dipastikan: pindai (lebih ketat)
        }
    }
}
