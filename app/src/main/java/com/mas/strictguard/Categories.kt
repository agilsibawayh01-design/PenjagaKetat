package com.mas.strictguard

import android.content.Context

/** Kategori situs/kata kunci siap pakai. Tiap kategori bisa dinyalakan; subdomain ikut terblokir. */
object Categories {
    class Cat(val id: String, val name: String, val desc: String, val domains: List<String>, val keywords: List<String>)

    val ALL: List<Cat> = listOf(
        Cat(
            "dewasa", "Konten dewasa", "Situs dewasa dan kata kunci terkait (aktif secara bawaan).",
            listOf(
                "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com", "youporn.com",
                "spankbang.com", "eporner.com", "chaturbate.com", "stripchat.com", "nhentai.net", "onlyfans.com",
                "tubegalore.com", "simontok.com", "xhamster.desi", "pornhat.com", "porntrex.com", "hqporner.com",
                "txxx.com", "tnaflix.com", "beeg.com", "motherless.com", "rule34.xxx", "e-hentai.org", "fapello.com", "javhd.com"
            ),
            listOf("porn", "bokep", "xnxx", "xvideos", "xhamster", "pornhub", "hentai", "nude", "telanjang", "bugil", "simontok", "tubegalore", "xxx")
        ),
        Cat(
            "judi", "Judi online", "Situs taruhan dan kata kunci judi online (aktif secara bawaan).",
            listOf("bet365.com", "1xbet.com", "sbobet.com", "pokerstars.com", "888casino.com"),
            listOf("judi online", "slot gacor", "togel", "judol", "casino online", "situs slot")
        ),
        Cat(
            "sosmed", "Media sosial", "Facebook, Instagram, TikTok, X/Twitter, Reddit, Snapchat (mati secara bawaan).",
            listOf("facebook.com", "instagram.com", "tiktok.com", "twitter.com", "x.com", "reddit.com", "snapchat.com"),
            emptyList()
        ),
        Cat(
            "video", "Video dan streaming", "Netflix, Twitch, Dailymotion, Vimeo (mati secara bawaan).",
            listOf("netflix.com", "twitch.tv", "dailymotion.com", "vimeo.com"),
            emptyList()
        )
    )

    fun enabled(c: Context): List<Cat> = ALL.filter { Store.categoryOn(c, it.id) }
    fun domains(c: Context): Set<String> = enabled(c).flatMap { it.domains }.toSet()
    fun keywords(c: Context): Set<String> = enabled(c).flatMap { it.keywords }.toSet()
}
