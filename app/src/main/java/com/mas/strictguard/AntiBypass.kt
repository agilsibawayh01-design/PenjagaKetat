package com.mas.strictguard

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings

/**
 * Anti-bypass yang jujur dan ringan (tanpa polling baru, tanpa penulisan Settings terus-menerus):
 * 1. Penanda Mode Ketat di Settings.Secure. Ditulis HANYA saat berbeda dari yang seharusnya, lalu dibaca
 *    ulang untuk memastikan benar-benar tersimpan. Percobaan dibatasi (maks 1 kali per 10 menit bila gagal).
 * 2. Deteksi data aplikasi terhapus/dipasang ulang saat Mode Ketat aktif (penanda masih "aktif" tetapi
 *    data lokal kosong).
 * 3. Deteksi pengawas mati tidak wajar (Force Stop, crash, update) lewat bendera "sedang berjalan".
 * 4. Kartu status perlindungan untuk layar.
 *
 * Batas yang TIDAK bisa ditutup tanpa root/device-owner: Force Stop lewat ADB, pm revoke, Safe Mode,
 * profil kerja, dan factory reset. Kelas ini hanya mendeteksi dan memberi tahu, tidak menjanjikan lebih.
 */
object AntiBypass {
    private const val MARKER_KEY = "penjagaketat_guard"
    private const val RETRY_MS = 10 * 60_000L

    private fun sp(c: Context) = c.getSharedPreferences("antibypass", Context.MODE_PRIVATE)

    private fun bootCount(c: Context): Int =
        try {
            Settings.Global.getInt(c.contentResolver, "boot_count", -1)
        } catch (e: Throwable) {
            -1
        }

    private fun desired(c: Context): String = if (Store.strictRaw(c)) "v1|1" else "v1|0"

    private fun readMarker(c: Context): String? =
        try {
            Settings.Secure.getString(c.contentResolver, MARKER_KEY)
        } catch (t: Throwable) {
            null
        }

    fun markerUri() = Settings.Secure.getUriFor(MARKER_KEY)

    /** Panggil SEBELUM syncMarker pada start aplikasi/boot. Mengembalikan true bila terdeteksi data terhapus. */
    fun checkWiped(c: Context): Boolean {
        try {
            val p = sp(c)
            if (p.getBoolean("init", false)) return false
            p.edit().putBoolean("init", true).commit()
            if (readMarker(c) == "v1|1" && !Store.strictRaw(c)) {
                p.edit().putLong("wipe_at", System.currentTimeMillis()).commit()
                Store.log(c, "PERINGATAN: data aplikasi tampak terhapus atau dipasang ulang saat Mode Ketat aktif")
                return true
            }
        } catch (t: Throwable) {
            // abaikan
        }
        return false
    }

    /** Menyamakan penanda dengan keadaan Mode Ketat. Menulis hanya bila berbeda, lalu memverifikasi. */
    fun syncMarker(c: Context): Boolean {
        try {
            val want = desired(c)
            val cur = readMarker(c)
            val p = sp(c)
            if (cur == want) {
                p.edit().putString("written", want).putBoolean("marker_ok", true).apply()
                return true
            }
            if (!SelfProtector.canWriteSecure(c)) {
                p.edit().putBoolean("marker_ok", false).putString("marker_why", "izin WRITE_SECURE_SETTINGS belum diberikan").apply()
                return false
            }
            val now = SystemClock.elapsedRealtime()
            val last = p.getLong("try_at", 0L)
            val failed = !p.getBoolean("marker_ok", true)
            if (failed && last != 0L && now >= last && now - last < RETRY_MS) return false
            val wasWritten = p.getString("written", null)
            val tamper = wasWritten != null && wasWritten == "v1|1" && want == "v1|1"
            Settings.Secure.putString(c.contentResolver, MARKER_KEY, want)
            val ok = readMarker(c) == want
            p.edit().putLong("try_at", now).putBoolean("marker_ok", ok)
                .putString("marker_why", if (ok) "" else "penulisan tidak tersimpan").apply()
            if (ok) {
                p.edit().putString("written", want).apply()
                if (tamper) Store.log(c, "Penanda perlindungan diubah/dihapus, sudah dipulihkan dan diverifikasi")
            } else {
                Store.log(c, "Penanda perlindungan gagal dipulihkan (tidak tersimpan setelah ditulis)")
            }
            return ok
        } catch (t: Throwable) {
            return false
        }
    }

    fun markerOk(c: Context): Boolean = sp(c).getBoolean("marker_ok", true)

    /** Pengawas dimulai. Bila bendera "berjalan" masih menyala pada boot yang sama, proses sebelumnya mati tak wajar. */
    fun serviceStarted(c: Context) {
        try {
            val p = sp(c)
            val boot = bootCount(c)
            if (p.getBoolean("svc_run", false) && p.getInt("svc_boot", -2) == boot) {
                val n = p.getInt("kill_n", 0) + 1
                p.edit().putInt("kill_n", n).putLong("kill_at", System.currentTimeMillis()).apply()
                Store.log(c, "Pengawas sempat mati tidak wajar (Force Stop, crash, atau update)")
            }
            p.edit().putBoolean("svc_run", true).putInt("svc_boot", boot).apply()
        } catch (t: Throwable) {
            // abaikan
        }
    }

    fun serviceStopped(c: Context) {
        try {
            sp(c).edit().putBoolean("svc_run", false).apply()
        } catch (t: Throwable) {
            // abaikan
        }
    }

    fun killCount(c: Context): Int = sp(c).getInt("kill_n", 0)
    fun killAt(c: Context): Long = sp(c).getLong("kill_at", 0L)
    fun wipeAt(c: Context): Long = sp(c).getLong("wipe_at", 0L)
    fun markerWhy(c: Context): String = sp(c).getString("marker_why", "") ?: ""

    fun batteryExempt(c: Context): Boolean =
        try {
            (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)
        } catch (t: Throwable) {
            true
        }

    /** Daftar pemeriksaan untuk kartu status: (baik?, keterangan). */
    fun checks(c: Context): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        out.add(Pair(SelfProtector.accessibilityOn(c), "Aksesibilitas menyala"))
        out.add(Pair(SelfProtector.adminOn(c), "Perlindungan uninstall (Administrator perangkat)"))
        out.add(Pair(Foreground.hasUsageAccess(c), "Akses Penggunaan"))
        if (Store.vpnOn(c)) out.add(Pair(VpnStatus.running, "VPN DNS berjalan" + (if (VpnStatus.running) "" else " (" + VpnStatus.state.label + ")")))
        out.add(Pair(batteryExempt(c), "Dikecualikan dari penghemat baterai"))
        if (SelfProtector.canWriteSecure(c)) out.add(Pair(markerOk(c), "Penanda perlindungan di Settings terverifikasi" + (if (markerOk(c)) "" else ": " + markerWhy(c))))
        else out.add(Pair(false, "Pemulih otomatis (izin WRITE_SECURE_SETTINGS) belum diberikan"))
        return out
    }
}
