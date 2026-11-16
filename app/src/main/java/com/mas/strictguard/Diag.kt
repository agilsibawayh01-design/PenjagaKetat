package com.mas.strictguard

import android.content.Context
import android.os.Build

/** Laporan diagnosis untuk bug report. Hanya status dan jumlah; tanpa PIN, nama situs, kata kunci, atau isi layar. */
object Diag {
    fun text(c: Context): String {
        val sb = StringBuilder()
        sb.append("Penjaga Ketat - laporan diagnosis\n")
        sb.append("Build: ").append(Updater.currentBuild(c)).append('\n')
        sb.append("Perangkat: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Waktu: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())).append("\n\n")
        sb.append("Status perlindungan\n")
        try {
            for (i in Health.items(c)) sb.append(if (i.ok) "[OK]  " else "[GAGAL] ").append(i.label).append('\n')
        } catch (t: Throwable) {
            sb.append("(gagal membaca status)\n")
        }
        sb.append("\nPengaturan (jumlah saja)\n")
        try {
            sb.append("Mode Ketat: ").append(if (Store.strictRaw(c)) "aktif" else "mati").append('\n')
            sb.append("Aplikasi diblokir: ").append(Store.apps(c).size).append('\n')
            sb.append("Situs diblokir: ").append(Store.domains(c).size).append('\n')
            sb.append("Kata kunci: ").append(Store.keywords(c).size).append('\n')
            sb.append("Tema: ").append(Store.themeMode(c)).append('\n')
        } catch (t: Throwable) {
            sb.append("(gagal membaca pengaturan)\n")
        }
        return sb.toString()
    }
}
