package com.mas.strictguard

object Config {
    const val APP_LABEL = "penjaga ketat"
    const val DISABLE_WAIT_MS = 60L * 60L * 1000L

    val IGNORED: Set<String> = setOf("com.android.systemui")

    /**
     * Aplikasi pendamping buatan sendiri yang layarnya memuat daftar kata/channel terlarang (milikmu sendiri).
     * Tidak dipindai kata kunci, situs, maupun AI gambar, supaya membukanya tidak memicu blokir. Aturan blokir
     * aplikasi, batas waktu, dan perlindungan uninstall untuk paket ini tetap berlaku seperti biasa.
     */
    val SCAN_EXEMPT: Set<String> = setOf("com.mas.tgblocker")

    /** Aplikasi kamera: pratinjau langsung (cermin) tidak dipindai AI. PiP/layar terbagi di atasnya tetap dipindai. */
    val CAMERA_APPS: Set<String> = setOf("com.sec.android.app.camera", "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera")

    /** Layar sistem tempat orang biasanya mencopot / mematikan aplikasi. Dijaga saat Mode Ketat aktif. */
    val PROTECTED: Set<String> = setOf(
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.samsung.android.lool",
        "com.samsung.android.sm",
        // layar utama (uninstall lewat tekan lama atau seret ke "Hapus instalan")
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.miui.home",
        "com.oppo.launcher",
        "com.transsion.hilauncher"
    )

    val BROWSERS: Set<String> = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
        "com.sec.android.app.sbrowser", "org.mozilla.firefox", "org.mozilla.fenix",
        "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
        "com.duckduckgo.mobile.android", "com.kiwibrowser.browser", "com.vivaldi.browser",
        "com.google.android.googlequicksearchbox", "mark.via.gp", "com.uc.browser.en"
    )

    /** Paket bank yang dikenal (sama dengan BankSwitch). Dipakai agar pengawas tidak menyalakan aksesibilitas saat bank terbuka. */
    val BANK_HINT: Set<String> = setOf(
        "id.bni.wondr", "com.bibit.bibitid", "com.bca", "com.bca.mybca.omni.android", "id.co.bri.brimo",
        "id.bmri.livin", "com.jago.digitalBanking", "com.bcadigital.blu", "com.btpn.dc",
        "id.co.cimbniaga.mobile.android", "com.bsm.activity2", "com.id.bankbsi.superapp"
    )

    /** Kata tombol yang menandai halaman detail aplikasi / dialog copot (awal teks). */
    val ACTION_WORDS = listOf(
        "paksa berhenti", "force stop", "copot", "uninstall", "hapus data", "clear data",
        "nonaktifkan", "deactivate", "disable", "uninstal", "hapus", "putus", "disconnect", "lupakan", "forget", "hapus vpn", "remove vpn"
    )

    /** Potongan deskripsi layanan aksesibilitas kita (muncul di halaman detail layanan). */
    const val SERVICE_MARKER = "memblokir aplikasi, situs, dan kata kunci"

    const val YOUTUBE = "com.google.android.youtube"
    const val INSTAGRAM = "com.instagram.android"

    /** ID tampilan Shorts YouTube. */
    val YT_IDS: List<String> = listOf("reel_player", "shorts_player", "shorts_container", "reel_watch")

    /** ID tampilan Reels Instagram. Stories memakai nama "reel_viewer", jadi TIDAK dipakai di sini. */
    val IG_IDS: List<String> = listOf("clips_viewer", "clips_video_container", "clips_swipe_refresh")

    /** ID tab Reels di bilah bawah Instagram: dianggap Reels bila tab itu sedang terpilih. */
    const val IG_REELS_TAB = "clips_tab"

    /** Satu-satunya browser yang didukung. */
    const val ALLOWED_BROWSER = "com.android.chrome"

    /** Paket yang tidak pernah diblokir walau terdeteksi sebagai browser. */
    val BROWSER_NEVER_BLOCK: Set<String> = setOf("com.google.android.googlequicksearchbox")

    val EXIT_TEXT_LENGTHS = listOf(10, 50, 100, 200, 500, 600, 2000)

    val EXCLUDED_FROM_LIST: Set<String> = setOf(
        "com.android.settings", "com.android.systemui", "com.sec.android.app.launcher",
        "com.google.android.dialer", "com.samsung.android.dialer", "com.android.phone",
        "com.samsung.android.messaging", "com.google.android.apps.messaging"
    )

    val SEED_KEYWORDS = listOf("porn", "bokep", "xnxx", "xvideos", "xhamster", "pornhub", "hentai", "nude", "telanjang")
    val SEED_DOMAINS = listOf("pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com")
    /** Aplikasi yang dilindungi dari uninstall/force stop/matikan aksesibilitas saat Mode Ketat aktif. */
    val SEED_PROTECTED = listOf(
        "com.stayfocused", "com.blocking.sites", "dev.pranav.applock",
        "com.mas.tgblocker", "com.safetelegram.guard", "com.mas.bankswitch"
    )
    val SEED_APPS = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
}
