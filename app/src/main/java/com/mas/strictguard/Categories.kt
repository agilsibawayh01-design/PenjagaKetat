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
            "dewasa2", "Dewasa (tambahan)", "Tambahan kata kunci dan situs dewasa (aktif secara bawaan).",
            listOf(
                "youjizz.com", "thumbzilla.com", "pornone.com", "drtuber.com", "hclips.com", "4tube.com", "nuvid.com",
                "spankwire.com", "camsoda.com", "bongacams.com", "livejasmin.com", "myfreecams.com", "cam4.com",
                "fansly.com", "hanime.tv"
            ),
            listOf(
                "bokep indo", "bokep viral", "nonton bokep", "download bokep", "link bokep", "video porno", "film porno",
                "film semi", "video bugil", "sex video", "sex tape", "ngentot", "ngewe", "colmek", "tante girang",
                "vcs telegram", "link 18+", "grup 18+"
            )
        ),
        Cat(
            "judi2", "Judi (tambahan)", "Tambahan kata kunci dan situs judi online (aktif secara bawaan).",
            listOf(
                "betway.com", "22bet.com", "melbet.com", "parimatch.com", "bwin.com", "unibet.com", "stake.com",
                "188bet.com", "w88.com", "fun88.com", "dafabet.com", "m88.com"
            ),
            listOf(
                "slot online", "slot maxwin", "slot88", "slot demo", "rtp slot", "bocoran slot", "pola slot",
                "gacor hari ini", "togel online", "togel hongkong", "togel singapore", "toto macau", "judi bola",
                "mix parlay", "taruhan bola", "poker online", "domino qq", "dominoqq", "sabung ayam", "live casino",
                "baccarat", "situs judi", "agen judi", "bonus new member", "freebet", "daftar slot"
            )
        ),
        Cat(
            "dewasa3", "Dewasa (daftar Blocking Sites)", "Nama situs dan istilah dewasa berbahasa Inggris, diambil dari daftar aplikasi Blocking Sites (aktif secara bawaan).",
            listOf(
                "3movs.com", "alohatube.com", "anybunny.com", "badjojo.com", "bangbros.com", "camhub.cc", "empflix.com",
                "fakku.net", "freeones.com", "gelbooru.com", "jerkmate.com", "nudevista.com", "realitykings.com",
                "slutload.com", "tube8.com", "xtube.com", "xxxbunker.com", "watchmygf.xxx", "wowgirls.com", "kindgirls.com"
            ),
            listOf(
                "blowjob", "handjob", "creampie", "gangbang", "gang bang", "deepthroat", "doggystyle", "cumshot",
                "facesitting", "facefuck", "masturbating", "mastrubat", "threesome", "pussy", "dildo", "lapdance",
                "nsfw", "bdsm", "orgasm", "kamasutra", "kama sutra", "titties", "bangbros", "realitykings",
                "alohatube", "anybunny", "badjojo", "empflix", "freeones", "gelbooru", "jerkmate", "nudevista",
                "xxxbunker", "watchmygf", "wowgirls", "slutload", "downblouse", "scissoring",
                "anal", "tits", "cock", "clit", "sexy", "milf", "naked", "penis", "vagina", "nipple", "erotic", "seduction", "condom"
            )
        ),
        Cat(
            "judi3", "Judi (daftar Blocking Sites)", "Situs taruhan internasional tambahan dari daftar aplikasi Blocking Sites (aktif secara bawaan).",
            listOf(
                "sportybet.com", "stake.bet", "stake.games", "gamdom.com", "betano.com", "bovada.lv", "partypoker.com",
                "888poker.com", "pulszcasino.com", "sixty6casino.com", "4rabet.com", "casumo.com", "pinupcasino.com",
                "1win.com", "bc.game", "pari-matchin.com", "batery-bet.in", "1rajbet.in", "hollywoodbets.net",
                "betway.co.za", "caliente.mx"
            ),
            emptyList()
        ),
        Cat(
            "bypass", "Celah bypass", "Proxy, VPN gratis, dan situs pembuka blokir (aktif secara bawaan).",
            listOf(
                "croxyproxy.com", "proxysite.com", "hidemy.name", "kproxy.com", "hide.me", "vpnbook.com",
                "browsec.com", "hola.org", "psiphon.ca", "torproject.org", "translate.goog"
            ),
            listOf("vpn gratis", "free proxy", "web proxy", "proxy site", "unblock site", "cara buka situs diblokir", "bypass blokir")
        ),
        Cat(
            "film", "Film bajakan dan film dewasa", "Situs streaming film ilegal (domain sering berganti angka) dan film dewasa (aktif secara bawaan).",
            emptyList(),
            listOf(
                "rebahin", "indoxxi", "lk21", "layarkaca21", "dunia21", "pusatfilm", "filmapik", "juraganfilm",
                "nonton film semi", "film plus", "film dewasa", "semi korea", "semi jepang"
            )
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
    fun keywords(c: Context): Set<String> = enabled(c).flatMap { it.keywords }.map { it.lowercase() }.filter { it !in Store.disabledBuiltinKeywords(c) }.toSet()
}
