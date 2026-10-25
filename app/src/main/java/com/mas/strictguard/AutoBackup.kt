package com.mas.strictguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cadangan otomatis: menulis ulang satu file pilihan pengguna (mis. di Google Drive) memakai izin
 * penyimpanan yang diberikan lewat pemilih file. Tanpa login di dalam aplikasi.
 * Isi sama dengan Backup.export (tanpa PIN, tanpa timer berjalan, tanpa bendera Mode Ketat).
 * Ditulis saat ada perubahan (paling cepat tiap 5 menit) dan paling lambat sehari sekali.
 */
object AutoBackup {
    private const val MIN_GAP_MS = 5 * 60_000L
    private const val MAX_GAP_MS = 24 * 3600_000L
    private const val RETRY_MS = 30 * 60_000L

    private val busy = AtomicBoolean(false)
    @Volatile
    private var checkedAt = 0L

    private fun sp(c: Context) = c.getSharedPreferences("autobackup", Context.MODE_PRIVATE)

    fun uri(c: Context): Uri? = sp(c).getString("uri", null)?.let { try { Uri.parse(it) } catch (t: Throwable) { null } }

    fun enabled(c: Context): Boolean = uri(c) != null

    fun enable(c: Context, u: Uri) {
        sp(c).edit().putString("uri", u.toString()).putLong("last_at", 0L).putString("last_hash", "").putString("err", "").commit()
    }

    fun disable(c: Context) {
        val u = uri(c)
        sp(c).edit().clear().commit()
        if (u != null) try {
            c.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (t: Throwable) { }
    }

    fun lastAt(c: Context): Long = sp(c).getLong("last_at", 0L)
    fun error(c: Context): String = sp(c).getString("err", "") ?: ""

    fun displayName(c: Context): String {
        val u = uri(c) ?: return ""
        return try {
            c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: u.lastPathSegment ?: ""
        } catch (t: Throwable) {
            u.lastPathSegment ?: ""
        }
    }

    private fun snapshot(c: Context): String = Backup.export(c)

    private val CREATED = Regex("\"created\": [0-9]+")
    private fun hashOf(json: String): String = json.replace(CREATED, "").hashCode().toString()

    /** Panggil berkala (aman sering): menulis bila ada perubahan atau sudah sehari. */
    fun tick(c: Context) {
        try {
            val now = System.currentTimeMillis()
            if (now - checkedAt < 60_000L) return
            checkedAt = now
            val u = uri(c) ?: return
            val p = sp(c)
            val last = p.getLong("last_at", 0L)
            val failedAt = p.getLong("fail_at", 0L)
            if (failedAt != 0L && now - failedAt < RETRY_MS) return
            val json = snapshot(c)
            val h = hashOf(json)
            val changed = h != p.getString("last_hash", "")
            val due = (changed && now - last >= MIN_GAP_MS) || now - last >= MAX_GAP_MS
            if (!due) return
            write(c, u, json, h)
        } catch (t: Throwable) {
            // jangan sampai cadangan menjatuhkan layanan
        }
    }

    /** Tulis sekarang (dipakai tombol "Cadangkan sekarang" dan saat baru diaktifkan). */
    fun now(c: Context, done: ((Boolean) -> Unit)? = null) {
        val u = uri(c)
        if (u == null) {
            done?.invoke(false)
            return
        }
        val json = snapshot(c)
        write(c, u, json, hashOf(json), done)
    }

    private fun write(c: Context, u: Uri, json: String, h: String, done: ((Boolean) -> Unit)? = null) {
        if (!busy.compareAndSet(false, true)) return
        val app = c.applicationContext
        Thread {
            var ok = false
            var err = ""
            try {
                app.contentResolver.openOutputStream(u, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ok = true
            } catch (t: Throwable) {
                err = (t.message ?: t.javaClass.simpleName).take(120)
            }
            val e = sp(app).edit()
            if (ok) e.putLong("last_at", System.currentTimeMillis()).putString("last_hash", h).putString("err", "").putLong("fail_at", 0L)
            else e.putString("err", err).putLong("fail_at", System.currentTimeMillis())
            e.commit()
            busy.set(false)
            done?.invoke(ok)
        }.start()
    }
}
