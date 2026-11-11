package com.mas.strictguard

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Klien Telegram pihak ketiga (Colibri, Telegram X, Nekogram, dst.) memakai API Telegram dan lolos dari
 * penjaga Telegram resmi. Mereka semua mendaftar untuk membuka tautan tg://, jadi cukup satu pertanyaan ke
 * Android ("siapa yang menangani tg://?") untuk menemukan semuanya, tanpa memindai seluruh aplikasi.
 */
object TgClients {
    /** Telegram resmi dan Channel Blocker kita sendiri tidak diblokir. */
    val ALLOWED = setOf(
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
        "com.mas.tgblocker"
    )

    /** Rentang IP server Telegram (AS62041/AS59930/AS62014/AS211157). Diperiksa terhadap daftar publik Telegram. */
    val NET_V4 = listOf(
        "91.108.4.0" to 22, "91.108.8.0" to 22, "91.108.12.0" to 22, "91.108.16.0" to 22,
        "91.108.20.0" to 22, "91.108.56.0" to 22, "91.105.192.0" to 23,
        "149.154.160.0" to 20, "185.76.151.0" to 24
    )
    val NET_V6 = listOf(
        "2001:67c:4e8::" to 48, "2001:b28:f23c::" to 48, "2001:b28:f23d::" to 48,
        "2001:b28:f23f::" to 48, "2a0a:f280::" to 32
    )

    /** Aplikasi non-sistem yang menangani tg://, di luar [ALLOWED] dan aplikasi ini sendiri. */
    fun find(c: Context): Set<String> {
        val out = HashSet<String>()
        try {
            val pm = c.packageManager
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=telegram"))
            for (ri in pm.queryIntentActivities(i, PackageManager.MATCH_ALL)) {
                val ai = ri.activityInfo?.applicationInfo ?: continue
                val p = ai.packageName
                if (p == c.packageName || p in ALLOWED) continue
                if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
                out.add(p)
            }
        } catch (t: Throwable) {
            // abaikan
        }
        return out
    }

    /** Blokir otomatis semua klien yang ditemukan. Mengembalikan jumlah yang baru ditambahkan. */
    fun sweep(c: Context): Int {
        var added = 0
        try {
            val have = Store.apps(c)
            for (p in find(c)) {
                if (p in have) continue
                Store.addApp(c, p)
                added++
                val label = try {
                    c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(p, 0)).toString()
                } catch (e: Exception) {
                    p
                }
                Store.log(c, "Klien Telegram diblokir otomatis: $label")
            }
        } catch (t: Throwable) {
            // abaikan
        }
        return added
    }
}
