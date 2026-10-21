package com.mas.strictguard

object Config {
    const val APP_LABEL = "penjaga ketat"
    const val DISABLE_WAIT_MS = 60L * 60L * 1000L

    val IGNORED: Set<String> = setOf("com.android.systemui")

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
        "com.samsung.android.lool",
        "com.samsung.android.sm"
    )

    val BROWSERS: Set<String> = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
        "com.sec.android.app.sbrowser", "org.mozilla.firefox", "org.mozilla.fenix",
        "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
        "com.duckduckgo.mobile.android", "com.kiwibrowser.browser", "com.vivaldi.browser",
        "com.google.android.googlequicksearchbox", "mark.via.gp", "com.uc.browser.en"
    )

    const val YOUTUBE = "com.google.android.youtube"
    const val INSTAGRAM = "com.instagram.android"

    val FEED_IDS: List<String> = listOf(
        "reel_player", "shorts_player", "reel_viewer", "clips_viewer", "reels_viewer", "shorts_container"
    )

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
