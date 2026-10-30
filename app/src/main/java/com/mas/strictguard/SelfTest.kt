package com.mas.strictguard

import android.content.Intent
import android.provider.Settings

/** Uji perlindungan: memeriksa apakah penanda halaman Pengaturan benar terdeteksi di HP ini. */
object SelfTest {
    class Target(val name: String, val markers: Set<String>, val intent: () -> Intent?)
    class Result(val name: String, val ok: Boolean, val detail: String)

    val results = ArrayList<Result>()
    @Volatile var finishedAt = 0L

    fun targets(): List<Target> = listOf(
        Target("Opsi pengembang (debugging USB/nirkabel)", SecureGuard.DEV_TEXTS) {
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        },
        Target("Pengaturan VPN", SecureGuard.VPN_TEXTS) { Intent(Settings.ACTION_VPN_SETTINGS) },
        Target("Izin instal aplikasi tidak dikenal", SecureGuard.UNKNOWN_INSTALL_TEXTS) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        },
        Target("Tambah pengguna / tamu", SecureGuard.USER_TEXTS) { Intent("android.settings.USER_SETTINGS") },
        Target("Aplikasi admin perangkat", SecureGuard.ADMIN_TEXTS) {
            Intent().setClassName("com.android.settings", "com.android.settings.DeviceAdminSettings")
        }
    )

    val ALL_MARKERS: Set<String>
        get() = SecureGuard.BYPASS_PAGE_TEXTS + SecureGuard.SECURE_PAGE_TEXTS + SecureGuard.UNKNOWN_INSTALL_TEXTS
}
