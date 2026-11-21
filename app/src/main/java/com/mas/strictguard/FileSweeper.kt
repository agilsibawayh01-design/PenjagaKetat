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
import java.util.concurrent.TimeUnit

/**
 * Pembersih berkas: memindai foto/video di penyimpanan HP (unduhan Telegram, galeri, folder Download, dll.)
 * dengan model AI yang sama, lalu MENGHAPUS PERMANEN berkas yang terdeteksi konten dewasa.
 * Butuh izin "Akses semua berkas". Folder data aplikasi lain (Android/data) tidak bisa dijangkau.
 */
object FileSweeper {
    private val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    private val VID = setOf("mp4", "mkv", "3gp", "webm", "mov", "avi", "m4v", "ts", "flv")
    // Kolam utas: satu berkas yang macet (dekode video bermasalah) tidak boleh menahan seluruh sapuan.
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private const val SEEN_MAX = 40000
    @Volatile private var runningSince = 0L
    @Volatile private var pendingDeep = false
    @Volatile private var pendingScope: String? = null

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

    /**
     * Pindai di latar. recentMs > 0: hanya berkas yang berubah dalam rentang itu. maxFiles membatasi beban.
     * deep = true: dipicu deteksi layar; berkas 48 jam terakhir dinilai ulang (abaikan catatan "aman"), bingkai lebih rapat.
     */
    fun sweepAsync(c: Context, recentMs: Long, maxFiles: Int, deep: Boolean = false, scopePkg: String? = null) {
        val app = c.applicationContext
        if (!enabled(app) || !hasAccess()) return
        val now = System.currentTimeMillis()
        // Penjaga: sapuan yang macet lebih dari 15 menit dianggap mati, sapuan baru boleh jalan.
        if (running && now - runningSince > 15 * 60_000L) running = false
        if (running) {
            // Sapuan dalam tidak dibuang: dijalankan begitu sapuan yang sedang berjalan selesai.
            if (deep) { pendingDeep = true; pendingScope = scopePkg }
            return
        }
        // Sapuan otomatis kecil ditunda hanya saat HP benar-benar panas; tombol manual dan sapuan dalam tetap jalan.
        if (!deep && maxFiles < 100 && Thermal.level >= 2) return
        running = true
        runningSince = now
        lastInfo = "berjalan sejak " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        pool.execute {
            try {
                sweep(app, recentMs, maxFiles, deep, scopePkg)
            } catch (t: Throwable) {
                lastInfo = "gagal: " + (t.message ?: t.javaClass.simpleName)
            } finally {
                running = false
                if (pendingDeep) {
                    pendingDeep = false
                    val sc = pendingScope
                    sweepAsync(app, 0L, 20, true, sc)
                }
            }
        }
    }

    private fun <T> runWithTimeout(sec: Long, block: () -> T?): T? {
        val fut = pool.submit<T?> { block() }
        return try {
            fut.get(sec, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            try { fut.cancel(true) } catch (e: Throwable) { }
            null
        }
    }

    // ---------- Catatan "sudah dipindai" (berkas, tanpa batas reset) ----------
    private fun h(key: String): Long {
        val crc = java.util.zip.CRC32()
        crc.update(key.toByteArray())
        return (key.hashCode().toLong() shl 32) xor crc.value
    }

    private fun seenFile(c: Context) = File(c.filesDir, "sweep_seen.txt")

    private fun loadSeen(c: Context): LinkedHashSet<Long> {
        val s = LinkedHashSet<Long>()
        try {
            val file = seenFile(c)
            if (file.exists()) file.forEachLine { line -> line.trim().takeIf { it.isNotEmpty() }?.let { try { s.add(java.lang.Long.parseUnsignedLong(it, 16)) } catch (t: Throwable) { } } }
        } catch (t: Throwable) {
            // abaikan
        }
        return s
    }

    private fun saveSeen(c: Context, s: LinkedHashSet<Long>) {
        try {
            val it = s.iterator()
            while (s.size > SEEN_MAX && it.hasNext()) { it.next(); it.remove() }
            seenFile(c).writeText(s.joinToString("\n") { java.lang.Long.toHexString(it) })
        } catch (t: Throwable) {
            // abaikan
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

    /** Cakupan sapuan dalam: aplikasi chat hanya memeriksa foldernya sendiri; lainnya (galeri, pemutar) semua folder. */
    private fun inScope(f: File, pkg: String?): Boolean {
        if (pkg == null) return true
        val low = f.absolutePath.lowercase()
        return when {
            pkg.startsWith("com.whatsapp") -> low.contains("whatsapp")
            pkg.startsWith("org.telegram") || pkg == "org.thunderdog.challegram" -> low.contains("telegram")
            else -> true
        }
    }

    private fun sweep(c: Context, recentMs: Long, maxFiles: Int, deep: Boolean, scopePkg: String?) {
        if (!NsfwAi.ready(c)) { lastInfo = "model AI belum siap"; return }
        val p = sp(c)
        purgeQuarantine(c)
        val seen = loadSeen(c)
        val all = ArrayList<File>()
        walk(Environment.getExternalStorageDirectory(), all, 0)
        val now = System.currentTimeMillis()
        // Berkas yang BARU MASUK ke indeks media (DATE_ADDED) didahulukan, walau waktu ubahnya lama
        // (salinan dari WhatsApp ke galeri sering membawa waktu ubah berkas asli).
        val horizon = if (deep) 48 * 3_600_000L else 60 * 60_000L
        val fresh = recentAdded(c, horizon).filter { (deep && inScope(it, scopePkg)) || (!deep && h(key(it)) !in seen) }
        val rest = all.filter {
            val m = it.lastModified()
            if (deep) (now - m <= horizon && inScope(it, scopePkg)) else ((recentMs <= 0L || now - m <= recentMs) && h(key(it)) !in seen)
        }.sortedByDescending { it.lastModified() }
        val byPath = LinkedHashMap<String, File>()
        for (x in fresh) byPath[x.absolutePath] = x
        for (x in rest) byPath.putIfAbsent(x.absolutePath, x)
        val chatScope = deep && scopePkg != null && (scopePkg.startsWith("com.whatsapp") || scopePkg.startsWith("org.telegram") || scopePkg == "org.thunderdog.challegram")
        // Sapuan dalam di aplikasi chat: video didahulukan (foto tidak boleh menutup antrian video), batas lebih longgar.
        val ordered = if (chatScope) byPath.values.sortedBy { if (it.name.substringAfterLast('.', "").lowercase() in VID) 0 else 1 } else byPath.values.toList()
        val todo = ordered.take(if (chatScope) 40 else maxFiles)
        var scanned = 0
        var deleted = 0
        var safe = 0
        var unread = 0
        val near = ArrayList<Pair<Float, String>>()
        val newLog = ArrayList<String>()
        val cands = ArrayList<Triple<Float, File, Boolean>>()
        for (f in todo) {
            if (!enabled(c)) break
            val m = f.lastModified()
            // Masih diunduh/ditulis: coba lagi nanti.
            if (now - m < 6000L) continue
            val ext = f.name.substringAfterLast('.', "").lowercase()
            val thr = DELETE_THRESHOLD
            lastScore = 0f
            lastDurSec = -1
            val res = runWithTimeout(if (ext in VID) 60L else 25L) {
                if (ext in VID) videoFlag(f, thr, deep) else imageFlag(f, thr)
            }
            scanned++
            if (deep && res != null) {
                cands.add(Triple(lastScore, f, ext in VID))
                newLog.add(logLine(f, ext in VID, "sapuan layar, skor " + String.format(java.util.Locale.US, "%.2f", lastScore)))
                if (lastScore >= 0.5f) near.add(lastScore to (folderOf(f.absolutePath) + " · " + (if (ext in VID) "video" else "foto") + " · skor " + String.format(java.util.Locale.US, "%.2f", lastScore)))
                continue
            }
            if (res == null) {
                unread++
                newLog.add(logLine(f, ext in VID, "tak terbaca"))
                near.add(0f to (folderOf(f.absolutePath) + " · " + (if (ext in VID) "video" else "foto") + " · tidak terbaca"))
                // Tidak terbaca: bila berkas masih baru, coba lagi nanti; bila lama, tandai selesai.
                if (now - m > 120_000L) seen.add(h(key(f)))
                continue
            }
            if (res) {
                val path = f.absolutePath
                val sz = f.length()
                addHistory(c, f, ext in VID, sz)
                newLog.add(logLine(f, ext in VID, "skor " + String.format(java.util.Locale.US, "%.2f", lastScore) + " DIKARANTINA"))
                if (quarantine(c, f)) {
                    deleted++
                    try { MediaScannerConnection.scanFile(c, arrayOf(path), null, null) } catch (t: Throwable) { }
                    Store.log(c, "Berkas dewasa dikarantina (${if (ext in VID) "video" else "foto"}, ${sz / 1024} KB)")
                } else {
                    Store.log(c, "Berkas dewasa terdeteksi tapi gagal dikarantina")
                }
            } else {
                safe++
                newLog.add(logLine(f, ext in VID, "skor " + String.format(java.util.Locale.US, "%.2f", lastScore) + " aman"))
                if (lastScore >= 0.5f) near.add(lastScore to (folderOf(f.absolutePath) + " · " + (if (ext in VID) "video" else "foto") + " · skor " + String.format(java.util.Locale.US, "%.2f", lastScore)))
                seen.add(h(key(f)))
            }
        }
        if (deep && cands.isNotEmpty()) {
            // Layar baru saja memblokir konten dewasa: karantina hanya berkas yang skornya >= ambang (0,95). Tanpa tebakan.
            // Di aplikasi chat yang layarnya baru terblokir, video dengan skor >= 0,90 (salinan WhatsApp dikompres ulang, skor turun) juga dikarantina.
            val pick = cands.filter { it.first >= DELETE_THRESHOLD || (chatScope && it.third && it.first >= 0.90f) }
            for ((sc, file, isV) in pick) {
                val sz = file.length()
                val path = file.absolutePath
                lastScore = sc
                addHistory(c, file, isV, sz)
                if (quarantine(c, file)) {
                    deleted++
                    try { MediaScannerConnection.scanFile(c, arrayOf(path), null, null) } catch (t: Throwable) { }
                    Store.log(c, "Berkas dikarantina setelah deteksi layar (${if (isV) "video" else "foto"}, ${sz / 1024} KB)")
                }
            }
        }
        if (newLog.isNotEmpty()) p.edit().putString("sweep_log", (scanLog(c).reversed() + newLog).takeLast(80).joinToString("\n")).apply()
        if (near.isNotEmpty()) p.edit().putString("sweep_near", near.sortedByDescending { it.first }.take(12).joinToString("\n") { it.second }).apply()
        saveSeen(c, seen)
        p.edit().putInt("sweep_deleted", deletedTotal(c) + deleted).apply()
        lastInfo = "dipindai $scanned, aman $safe, tak terbaca $unread, dikarantina $deleted, antrian ${Math.max(0, byPath.size - todo.size)} (" + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()) + ")"
    }

    @Volatile private var lastScore = 0f
    @Volatile private var lastDurSec = -1
    @Volatile private var lastFrames = 0

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
        val seen = loadSeen(c)
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
                    seen.add(h(key(dest)))
                    try { MediaScannerConnection.scanFile(c, arrayOf(dest.absolutePath), null, null) } catch (t: Throwable) { }
                    n++
                } else left.add(e)
            } catch (t: Throwable) {
                left.add(e)
            }
        }
        qSave(c, left)
        saveSeen(c, seen)
        return n
    }

    fun nearList(c: Context): List<String> = (sp(c).getString("sweep_near", "") ?: "").split('\n').filter { it.isNotBlank() }

    fun history(c: Context): List<String> =
        (sp(c).getString("sweep_hist", "") ?: "").split('\n').filter { it.isNotBlank() }.reversed()

    private fun addHistory(c: Context, f: File, isVideo: Boolean, size: Long, tag: String? = null) {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val dir = (f.parent ?: "").removePrefix(root).trim('/').ifEmpty { "(penyimpanan utama)" }
        val mb = if (size >= 1_048_576L) String.format(java.util.Locale.US, "%.1f MB", size / 1_048_576.0) else "${size / 1024} KB"
        val stamp = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "$stamp · karantina · ${if (isVideo) "video" else "foto"} · $mb · $dir · ${tag ?: "skor " + String.format(java.util.Locale.US, "%.2f", lastScore)}"
        val lines = ArrayList(sp(c).getString("sweep_hist", "")!!.split('\n').filter { it.isNotBlank() })
        lines.add(line.replace('\n', ' '))
        sp(c).edit().putString("sweep_hist", lines.takeLast(60).joinToString("\n")).apply()
    }

    /** Berkas foto/video yang baru terdaftar di MediaStore dalam rentang waktu itu (menurut DATE_ADDED). */
    @Suppress("DEPRECATION")
    private fun recentAdded(c: Context, windowMs: Long): List<File> {
        val out = ArrayList<File>()
        try {
            val since = (System.currentTimeMillis() - windowMs) / 1000L
            c.contentResolver.query(
                android.provider.MediaStore.Files.getContentUri("external"),
                arrayOf(android.provider.MediaStore.Files.FileColumns.DATA),
                android.provider.MediaStore.Files.FileColumns.DATE_ADDED + " >= ?",
                arrayOf(since.toString()), null
            )?.use { cur ->
                while (cur.moveToNext() && out.size < 300) {
                    val path = cur.getString(0) ?: continue
                    val ext = path.substringAfterLast('.', "").lowercase()
                    if (ext !in IMG && ext !in VID) continue
                    if (path.contains("/.")) continue
                    val file = File(path)
                    if (file.isFile) out.add(file)
                }
            }
        } catch (t: Throwable) {
            // abaikan
        }
        return out
    }


    private fun logLine(f: File, isVideo: Boolean, status: String): String {
        val sz = f.length()
        val mb = if (sz >= 1_048_576L) String.format(java.util.Locale.US, "%.1f MB", sz / 1_048_576.0) else "${sz / 1024} KB"
        val dur = if (isVideo && lastDurSec >= 0) " · ${lastDurSec / 60}:" + String.format("%02d", lastDurSec % 60) else ""
        val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        return "$t · ${folderOf(f.absolutePath)} · ${if (isVideo) "video" else "foto"}$dur · $mb · $status"
    }

    fun scanLog(c: Context): List<String> =
        (sp(c).getString("sweep_log", "") ?: "").split('\n').filter { it.isNotBlank() }.reversed()

    /**
     * Tindakan manual: karantina n video yang paling baru ditambahkan ke galeri (menurut MediaStore), tanpa dinilai AI.
     * Untuk video yang jelas-jelas baru kamu simpan dan lolos pindai. Berkas bisa dipulihkan dari karantina.
     */
    @Suppress("DEPRECATION")
    fun quarantineRecent(c: Context, n: Int): Int {
        if (!hasAccess()) return 0
        var done = 0
        try {
            val q = android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE + " = " + android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(android.provider.MediaStore.Files.FileColumns.DATE_ADDED))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, n)
            }
            val files = ArrayList<File>()
            c.contentResolver.query(android.provider.MediaStore.Files.getContentUri("external"), arrayOf(android.provider.MediaStore.Files.FileColumns.DATA), q, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val p = cur.getString(0) ?: continue
                    val file = File(p)
                    if (file.isFile) files.add(file)
                }
            }
            for (file in files) {
                val sz = file.length()
                val path = file.absolutePath
                addHistory(c, file, true, sz, "manual")
                if (quarantine(c, file)) {
                    done++
                    try { MediaScannerConnection.scanFile(c, arrayOf(path), null, null) } catch (t: Throwable) { }
                    Store.log(c, "Video dikarantina manual (${sz / 1024} KB)")
                }
            }
            if (done > 0) sp(c).edit().putInt("sweep_deleted", deletedTotal(c) + done).apply()
        } catch (t: Throwable) {
            lastInfo = "karantina manual gagal: " + (t.message ?: t.javaClass.simpleName)
        }
        return done
    }

    /** Naikkan angka ini SETIAP kali cara menilai berubah, supaya berkas yang dulu dinilai "aman" dinilai ulang. */
    private const val SCORE_VER = 4

    private fun testOne(file: File): String {
        lastScore = 0f; lastDurSec = -1; lastFrames = 0
        val res = runWithTimeout(120L) { videoFlag(file, 2f, true) }
        val sz = file.length()
        val mb = if (sz >= 1_048_576L) String.format(java.util.Locale.US, "%.1f MB", sz / 1_048_576.0) else "${sz / 1024} KB"
        val dur = if (lastDurSec >= 0) "${lastDurSec / 60}:" + String.format("%02d", lastDurSec % 60) else "?"
        val t = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))
        return if (res == null) "${folderOf(file.absolutePath)} · $t · $dur · $mb · TIDAK TERBACA (bingkai terbaca ${lastFrames})"
        else "${folderOf(file.absolutePath)} · $t · $dur · $mb · skor tertinggi ${String.format(java.util.Locale.US, "%.2f", lastScore)} dari ${lastFrames} bingkai"
    }

    /** Uji: video terbaru (menurut waktu ubah) di folder media WhatsApp dan WhatsApp Business, n per aplikasi. */
    fun testChatApps(c: Context, n: Int): List<String> {
        val out = ArrayList<String>()
        if (!hasAccess()) return listOf("Izin akses semua berkas belum diberikan.")
        if (!NsfwAi.ready(c)) return listOf("Model AI belum siap.")
        for (pk in listOf("com.whatsapp.w4b", "com.whatsapp", "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta", "org.thunderdog.challegram")) {
            try {
                val all = ArrayList<File>()
                val d = File(Environment.getExternalStorageDirectory(), "Android/media/$pk")
                if (!d.isDirectory) { if (pk.startsWith("com.whatsapp")) out.add("$pk: folder media tidak ditemukan / tidak terjangkau"); continue }
                walk(d, all, 2)
                val vids = all.filter { it.name.substringAfterLast('.', "").lowercase() in VID }.sortedByDescending { it.lastModified() }.take(n)
                if (vids.isEmpty()) out.add("$pk: tidak ada video di foldernya")
                for (v in vids) out.add(testOne(v))
            } catch (t: Throwable) {
                out.add("$pk: uji gagal: " + (t.message ?: t.javaClass.simpleName))
            }
        }
        return out
    }

    /** Uji satu kali: nilai n video terbaru di galeri dengan bingkai rapat, tampilkan skor tertinggi (tanpa nama, tanpa karantina). */
    @Suppress("DEPRECATION")
    fun testRecent(c: Context, n: Int): List<String> {
        val out = ArrayList<String>()
        if (!hasAccess()) return listOf("Izin akses semua berkas belum diberikan.")
        try {
            if (!NsfwAi.ready(c)) return listOf("Model AI belum siap.")
            val q = android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE + " = " + android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(android.provider.MediaStore.Files.FileColumns.DATE_ADDED))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, n)
            }
            val files = ArrayList<File>()
            c.contentResolver.query(android.provider.MediaStore.Files.getContentUri("external"), arrayOf(android.provider.MediaStore.Files.FileColumns.DATA), q, null)?.use { cur ->
                while (cur.moveToNext()) { val p = cur.getString(0) ?: continue; val file = File(p); if (file.isFile) files.add(file) }
            }
            if (files.isEmpty()) return listOf("Tidak ada video di indeks media.")
            for (file in files) out.add(testOne(file))
        } catch (t: Throwable) {
            out.add("Uji gagal: " + (t.message ?: t.javaClass.simpleName))
        }
        return out
    }

    private fun key(f: File) = "v$SCORE_VER|" + f.absolutePath + "|" + f.lastModified() + "|" + f.length()

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

    private fun videoFlag(f: File, thr: Float, deep: Boolean = false): Boolean? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0").toLongOrNull() ?: 0L
            lastDurSec = (dur / 1000L).toInt()
            // Lebih banyak bingkai: satu bingkai eksplisit sudah cukup, jadi video pendek pun diperiksa rapat (4-8 bingkai).
            val cnt = if (dur <= 0L) 1 else (dur / 2000L).toInt().coerceIn(4, if (deep) 16 else 10)
            val pts = if (dur <= 0L) listOf(0L) else (0 until cnt).map { (dur * 1000L * (0.04 + 0.92 * it / Math.max(1, cnt - 1))).toLong() }
            val shotVid = f.absolutePath.lowercase().let { it.contains("screenshot") || it.contains("screen_rec") || it.contains("screenrec") }
            // Video pendek: ambil bingkai persis (bukan hanya keyframe). Video kecil sering hanya punya 1-2 keyframe,
            // sehingga semua bingkai terpilih sama dan adegan yang berbeda tidak pernah dinilai.
            val opt = if (dur in 1..180_000L) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            var got = 0
            var hi = 0
            var max = 0f
            for (t in pts) {
                val b: Bitmap = (if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t, opt, 640, 640)
                else r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) ?: continue
                got++
                val sc = try { val full = NsfwAi.scoreRaw(b); val tl = if (shotVid) NsfwAi.scoreTiles(b) else 0f; if (tl >= TILE_THRESHOLD) Math.max(full, tl) else full } finally { b.recycle() }
                if (sc > max) max = sc
                if (sc >= 0.9f) hi++
                if (max >= thr) break
            }
            lastFrames = got
            if (got == 0) return null
            lastScore = max
            return max >= thr
        } finally {
            try { r.release() } catch (t: Throwable) { }
        }
    }
}
