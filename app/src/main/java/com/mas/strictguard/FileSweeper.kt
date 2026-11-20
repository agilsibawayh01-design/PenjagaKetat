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

    /** Aplikasi chat yang medianya disimpan di Android/media dan ikut dipindai. */
    private val MEDIA_PKGS = listOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
        "org.telegram.messenger.beta", "org.thunderdog.challegram"
    )

    /** Hapus permanen hanya bila skor tertinggi >= 0,95 (semua folder, foto dan video). */
    const val DELETE_THRESHOLD = 0.95f

    /** Petak tangkapan layar harus lebih tinggi lagi, karena potongan kecil mudah salah dinilai. */
    const val TILE_THRESHOLD = 0.97f

    /** Berkas terdeteksi dipindah ke karantina dulu; baru dihapus permanen setelah sekian hari. */
    const val QUARANTINE_DAYS = 2

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
                if (n == "Android" && depth == 0) {
                    // Hanya media WhatsApp dan Telegram di Android/media (Android/data tidak terjangkau).
                    for (pk in MEDIA_PKGS) {
                        val d = File(f, "media/$pk")
                        if (d.isDirectory) walk(d, out, depth + 2)
                    }
                    continue
                }
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
        purgeQuarantine(c)
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
            val thr = DELETE_THRESHOLD
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
                addHistory(c, f, ext in VID, sz)
                if (quarantine(c, f)) {
                    deleted++
                    try { MediaScannerConnection.scanFile(c, arrayOf(path), null, null) } catch (t: Throwable) { }
                    Store.log(c, "Berkas dewasa dikarantina (${if (ext in VID) "video" else "foto"}, ${sz / 1024} KB)")
                } else {
                    Store.log(c, "Berkas dewasa terdeteksi tapi gagal dikarantina")
                }
            } else {
                seen.add(key(f))
            }
        }
        p.edit().putStringSet("sweep_seen", seen).putInt("sweep_deleted", deletedTotal(c) + deleted).apply()
        lastInfo = "dipindai $scanned, dihapus $deleted (" + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()) + ")"
    }

    @Volatile private var lastScore = 0f

    // ---------- Karantina ----------
    private fun qDir(c: Context): File {
        val base = c.getExternalFilesDir(null) ?: c.filesDir
        return File(base, "karantina").apply { mkdirs() }
    }

    private fun qEntries(c: Context): List<List<String>> =
        (sp(c).getString("sweep_q", "") ?: "").split('\n').filter { it.isNotBlank() }.map { it.split('|') }.filter { it.size >= 3 }

    private fun qSave(c: Context, l: List<List<String>>) {
        sp(c).edit().putString("sweep_q", l.joinToString("\n") { it.joinToString("|") }).commit()
    }

    fun quarantineCount(c: Context): Int = qEntries(c).size

    private fun quarantine(c: Context, f: File): Boolean {
        val name = System.currentTimeMillis().toString() + "_" + Math.abs(f.absolutePath.hashCode()) + "." + f.name.substringAfterLast('.', "bin")
        val dest = File(qDir(c), name)
        val ok = try {
            if (f.renameTo(dest)) true else { f.copyTo(dest, true); f.delete() }
        } catch (t: Throwable) {
            false
        }
        if (ok) qSave(c, qEntries(c) + listOf(listOf(name, f.absolutePath.replace('|', '_'), System.currentTimeMillis().toString())))
        return ok
    }

    private fun purgeQuarantine(c: Context) {
        val now = System.currentTimeMillis()
        val keep = ArrayList<List<String>>()
        for (e in qEntries(c)) {
            val at = e[2].toLongOrNull() ?: 0L
            if (now - at > QUARANTINE_DAYS * 86_400_000L) {
                try { File(qDir(c), e[0]).delete() } catch (t: Throwable) { }
            } else keep.add(e)
        }
        if (keep.size != qEntries(c).size) qSave(c, keep)
    }

    /** Pulihkan semua berkas karantina ke lokasi asalnya dan tandai sebagai sudah dipindai (tidak dipindai ulang). */
    private fun folderOf(path: String): String {
        val root = Environment.getExternalStorageDirectory().absolutePath
        return (File(path).parent ?: "").removePrefix(root).trim('/').ifEmpty { "(penyimpanan utama)" }
    }

    /** Jumlah berkas di karantina per folder asal (tanpa nama berkas). */
    fun quarantineFolders(c: Context): List<Pair<String, Int>> =
        qEntries(c).groupBy { folderOf(it[1]) }.map { it.key to it.value.size }.sortedBy { it.first }

    fun restoreAll(c: Context): Int = restore(c, null)

    /** folder = null: semua; selain itu hanya berkas yang berasal dari folder tersebut. */
    fun restore(c: Context, folder: String?): Int {
        var n = 0
        val seen = HashSet(sp(c).getStringSet("sweep_seen", emptySet()) ?: emptySet())
        val left = ArrayList<List<String>>()
        for (e in qEntries(c)) {
            if (folder != null && folderOf(e[1]) != folder) { left.add(e); continue }
            val src = File(qDir(c), e[0])
            var dest = File(e[1])
            try {
                dest.parentFile?.mkdirs()
                if (dest.exists()) dest = File(dest.parentFile, "pulih_" + dest.name)
                val ok = if (src.renameTo(dest)) true else { src.copyTo(dest, true); src.delete() }
                if (ok) {
                    seen.add(key(dest))
                    try { MediaScannerConnection.scanFile(c, arrayOf(dest.absolutePath), null, null) } catch (t: Throwable) { }
                    n++
                } else left.add(e)
            } catch (t: Throwable) {
                left.add(e)
            }
        }
        qSave(c, left)
        sp(c).edit().putStringSet("sweep_seen", seen).apply()
        return n
    }

    fun history(c: Context): List<String> =
        (sp(c).getString("sweep_hist", "") ?: "").split('\n').filter { it.isNotBlank() }.reversed()

    private fun addHistory(c: Context, f: File, isVideo: Boolean, size: Long) {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val dir = (f.parent ?: "").removePrefix(root).trim('/').ifEmpty { "(penyimpanan utama)" }
        val mb = if (size >= 1_048_576L) String.format(java.util.Locale.US, "%.1f MB", size / 1_048_576.0) else "${size / 1024} KB"
        val stamp = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "$stamp · karantina · ${if (isVideo) "video" else "foto"} · $mb · $dir · skor ${String.format(java.util.Locale.US, "%.2f", lastScore)}"
        val lines = ArrayList(sp(c).getString("sweep_hist", "")!!.split('\n').filter { it.isNotBlank() })
        lines.add(line.replace('\n', ' '))
        sp(c).edit().putString("sweep_hist", lines.takeLast(60).joinToString("\n")).apply()
    }

    private fun key(f: File) = "v2|" + f.absolutePath + "|" + f.lastModified() + "|" + f.length() + "|v2"

    private fun imageFlag(f: File, thr: Float): Boolean? {
        if (f.length() < 15_000L) return false
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0) return null
        // Tangkapan layar (atau gambar sangat tinggi): konten bisa hanya mengisi sebagian kecil (jendela PiP, split),
        // jadi dipindai juga per petak agar tidak "encer".
        val shot = f.absolutePath.lowercase().let { it.contains("screenshot") || it.contains("screen_rec") || it.contains("screenrec") }
        val limit = if (shot) 2600 else 800
        var s = 1
        while (Math.max(o.outWidth, o.outHeight) / s > limit) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = BitmapFactory.decodeFile(f.absolutePath, o2) ?: return null
        try {
            var sc = NsfwAi.scoreRaw(bmp)
            var tileMax = 0f
            if (sc < DELETE_THRESHOLD && shot) {
                val w = bmp.width
                val h = bmp.height
                val side = Math.max(Math.min(w, h) / 2, 160)
                val step = Math.max(side / 2, 80)
                var y = 0
                while (y < h && tileMax < TILE_THRESHOLD) {
                    val hh = Math.min(side, h - y)
                    var x = 0
                    while (x < w && tileMax < TILE_THRESHOLD) {
                        val ww = Math.min(side, w - x)
                        if (ww >= 120 && hh >= 120) {
                            val t = Bitmap.createBitmap(bmp, x, y, ww, hh)
                            val v = try { NsfwAi.scoreRaw(t) } finally { if (t !== bmp) t.recycle() }
                            if (v > tileMax) tileMax = v
                        }
                        x += step
                    }
                    y += step
                }
            }
            if (tileMax >= TILE_THRESHOLD) sc = Math.max(sc, tileMax)
            lastScore = sc
            return sc >= DELETE_THRESHOLD
        } finally {
            bmp.recycle()
        }
    }

    private fun videoFlag(f: File, thr: Float): Boolean? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0").toLongOrNull() ?: 0L
            val pts = if (dur <= 0L) listOf(0L) else listOf(0.12, 0.35, 0.6, 0.85).map { (dur * it * 1000).toLong() }
            val shotVid = f.absolutePath.lowercase().let { it.contains("screenshot") || it.contains("screen_rec") || it.contains("screenrec") }
            var got = 0
            var hi = 0
            var max = 0f
            for (t in pts) {
                val b: Bitmap = (if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                else r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) ?: continue
                got++
                val sc = try { val full = NsfwAi.scoreRaw(b); val tl = if (shotVid) NsfwAi.scoreTiles(b) else 0f; if (tl >= TILE_THRESHOLD) Math.max(full, tl) else full } finally { b.recycle() }
                if (sc > max) max = sc
                if (sc >= 0.9f) hi++
            }
            if (got == 0) return null
            lastScore = max
            return max >= thr
        } finally {
            try { r.release() } catch (t: Throwable) { }
        }
    }
}
