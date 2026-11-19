package com.mas.strictguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.concurrent.Executors

/**
 * Pembersih berkas: memindai foto/video di penyimpanan HP (unduhan Telegram, galeri, folder Download, dll.)
 * dengan model AI yang sama, lalu MENGHAPUS PERMANEN berkas yang terdeteksi konten dewasa.
 * Butuh izin "Akses semua berkas". Folder data aplikasi lain (Android/data) tidak bisa dijangkau.
 */
object FileSweeper {
    private val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    private val VID = setOf("mp4", "mkv", "3gp", "webm", "mov", "avi", "m4v", "ts", "flv")
    private val exec = Executors.newSingleThreadExecutor()
    private const val SEEN_MAX = 9000

    @Volatile var running = false
        private set
    @Volatile var lastInfo = ""
        private set

    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)
    fun enabled(c: Context): Boolean = sp(c).getBoolean("file_sweep", true)
    fun setEnabled(c: Context, v: Boolean) { sp(c).edit().putBoolean("file_sweep", v).commit() }
    fun deletedTotal(c: Context): Int = sp(c).getInt("sweep_deleted", 0)

    fun hasAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** Pindai di latar. recentMs > 0: hanya berkas yang berubah dalam rentang itu. maxFiles membatasi beban. */
    fun sweepAsync(c: Context, recentMs: Long, maxFiles: Int) {
        val app = c.applicationContext
        if (!enabled(app) || !hasAccess() || running) return
        running = true
        exec.execute {
            try {
                sweep(app, recentMs, maxFiles)
            } catch (t: Throwable) {
                lastInfo = "gagal: " + (t.message ?: t.javaClass.simpleName)
            } finally {
                running = false
            }
        }
    }

    private fun walk(dir: File, out: ArrayList<File>, depth: Int) {
        if (depth > 8 || out.size > 60000) return
        val list = dir.listFiles() ?: return
        for (f in list) {
            if (f.isDirectory) {
                val n = f.name
                if (n == "Android" && depth == 0) continue
                if (n.startsWith(".")) continue
                walk(f, out, depth + 1)
            } else {
                val ext = f.name.substringAfterLast('.', "").lowercase()
                if (ext in IMG || ext in VID) out.add(f)
            }
        }
    }

    private fun sweep(c: Context, recentMs: Long, maxFiles: Int) {
        if (!NsfwAi.ready(c)) { lastInfo = "model AI belum siap"; return }
        val p = sp(c)
        var seen = HashSet(p.getStringSet("sweep_seen", emptySet()) ?: emptySet())
        if (seen.size > SEEN_MAX) seen = HashSet()
        val all = ArrayList<File>()
        walk(Environment.getExternalStorageDirectory(), all, 0)
        val now = System.currentTimeMillis()
        val todo = all.filter {
            val m = it.lastModified()
            (recentMs <= 0L || now - m <= recentMs) && key(it) !in seen
        }.sortedByDescending { it.lastModified() }.take(maxFiles)
        var scanned = 0
        var deleted = 0
        for (f in todo) {
            if (!enabled(c)) break
            val m = f.lastModified()
            // Masih diunduh/ditulis: coba lagi nanti.
            if (now - m < 6000L) continue
            val ext = f.name.substringAfterLast('.', "").lowercase()
            val low = f.absolutePath.lowercase()
            val thr = if (low.contains("telegram")) NsfwAi.THRESHOLD else NsfwAi.GENERAL_THRESHOLD
            val res = try {
                if (ext in VID) videoFlag(f, thr) else imageFlag(f, thr)
            } catch (t: Throwable) {
                null
            }
            scanned++
            if (res == null) {
                // Tidak terbaca: bila berkas masih baru, coba lagi nanti; bila lama, tandai selesai.
                if (now - m > 120_000L) seen.add(key(f))
                continue
            }
            if (res) {
                val path = f.absolutePath
                val sz = f.length()
                if (f.delete()) {
                    addHistory(c, f, ext in VID, sz)
                    deleted++
                    try { MediaScannerConnection.scanFile(c, arrayOf(path), null, null) } catch (t: Throwable) { }
                    Store.log(c, "Berkas dewasa dihapus (${if (ext in VID) "video" else "foto"}, ${f.length() / 1024} KB)")
                } else {
                    Store.log(c, "Berkas dewasa terdeteksi tapi gagal dihapus")
                }
            } else {
                seen.add(key(f))
            }
        }
        p.edit().putStringSet("sweep_seen", seen).putInt("sweep_deleted", deletedTotal(c) + deleted).apply()
        lastInfo = "dipindai $scanned, dihapus $deleted (" + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()) + ")"
    }

    @Volatile private var lastScore = 0f

    fun history(c: Context): List<String> =
        (sp(c).getString("sweep_hist", "") ?: "").split('\n').filter { it.isNotBlank() }.reversed()

    private fun addHistory(c: Context, f: File, isVideo: Boolean, size: Long) {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val dir = (f.parent ?: "").removePrefix(root).trim('/').ifEmpty { "(penyimpanan utama)" }
        val mb = if (size >= 1_048_576L) String.format(java.util.Locale.US, "%.1f MB", size / 1_048_576.0) else "${size / 1024} KB"
        val stamp = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "$stamp · ${if (isVideo) "video" else "foto"} · $mb · $dir · skor ${String.format(java.util.Locale.US, "%.2f", lastScore)}"
        val lines = ArrayList(sp(c).getString("sweep_hist", "")!!.split('\n').filter { it.isNotBlank() })
        lines.add(line.replace('\n', ' '))
        sp(c).edit().putString("sweep_hist", lines.takeLast(60).joinToString("\n")).apply()
    }

    private fun key(f: File) = f.absolutePath + "|" + f.lastModified() + "|" + f.length()

    private fun imageFlag(f: File, thr: Float): Boolean? {
        if (f.length() < 15_000L) return false
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (Math.max(o.outWidth, o.outHeight) / s > 800) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = BitmapFactory.decodeFile(f.absolutePath, o2) ?: return null
        val sc = try { NsfwAi.scoreRaw(bmp) } finally { bmp.recycle() }
        lastScore = sc
        return sc >= thr
    }

    private fun videoFlag(f: File, thr: Float): Boolean? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0").toLongOrNull() ?: 0L
            val pts = if (dur <= 0L) listOf(0L) else listOf(0.12, 0.35, 0.6, 0.85).map { (dur * it * 1000).toLong() }
            var got = 0
            var hi = 0
            var max = 0f
            for (t in pts) {
                val b: Bitmap = (if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                else r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) ?: continue
                got++
                val sc = try { NsfwAi.scoreRaw(b) } finally { b.recycle() }
                if (sc > max) max = sc
                if (sc >= 0.9f) hi++
            }
            if (got == 0) return null
            lastScore = max
            return max >= thr || hi >= 2
        } finally {
            try { r.release() } catch (t: Throwable) { }
        }
    }
}
