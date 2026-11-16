package com.mas.strictguard

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pemeriksa pembaruan: membaca Release terbaru di GitHub (termasuk pra-rilis).
 * Nomor build diambil dari tag "build-N" dan dibandingkan dengan versionCode terpasang.
 * Tidak mengunduh atau memasang apa pun sendiri; hanya menunjuk tautan unduhan.
 */
object Updater {
    class Result(val ok: Boolean, val error: String, val latest: Int, val current: Int, val url: String, val notes: String)

    fun currentBuild(c: Context): Int = try {
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode.toInt()
    } catch (t: Throwable) { 0 }

    fun validRepo(r: String): Boolean = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$").matches(r.trim())

    /** Dipanggil di thread latar. */
    fun check(c: Context): Result {
        val cur = currentBuild(c)
        val repo = Store.updateRepo(c).trim()
        if (!validRepo(repo)) return Result(false, "Isi dulu alamat repo dengan format pemilik/nama.", 0, cur, "", "")
        return try {
            val conn = URL("https://api.github.com/repos/$repo/releases?per_page=5").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "PenjagaKetat")
            val code = conn.responseCode
            if (code == 404) return Result(false, "Repo tidak ditemukan atau masih privat. Pemeriksaan hanya bisa untuk repo publik.", 0, cur, "", "")
            if (code != 200) return Result(false, "GitHub menjawab kode $code. Coba lagi nanti.", 0, cur, "", "")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            var best = 0
            var bestUrl = ""
            var bestNotes = ""
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optBoolean("draft")) continue
                val n = Regex("(\\d+)\\s*$").find(o.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (n <= best) continue
                var apk = ""
                val assets = o.optJSONArray("assets")
                if (assets != null) for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
                }
                best = n
                bestUrl = if (apk.isNotEmpty()) apk else o.optString("html_url")
                bestNotes = o.optString("body")
            }
            if (best == 0) return Result(false, "Belum ada rilis di repo itu.", 0, cur, "", "")
            Store.setUpdateResult(c, if (best > cur) best else 0, bestUrl)
            Result(true, "", best, cur, bestUrl, bestNotes)
        } catch (t: Throwable) {
            Result(false, "Tidak bisa terhubung. Periksa internet lalu coba lagi.", 0, cur, "", "")
        }
    }

    /** Pemeriksaan otomatis paling banyak sekali sehari, hanya bila dinyalakan. */
    fun autoCheckIfDue(c: Context) {
        if (!Store.updateAuto(c) || !validRepo(Store.updateRepo(c))) return
        if (System.currentTimeMillis() - Store.updateLast(c) < 24L * 3600_000L) return
        Thread { try { check(c.applicationContext) } catch (t: Throwable) { } }.start()
    }
}
