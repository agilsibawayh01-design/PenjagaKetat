package com.mas.strictguard

/**
 * Folder Aman dan Dual Messenger Samsung bisa dipakai sebagai jalan keluar dari
 * pemblokiran. Saat Mode Ketat, aplikasi Folder Aman ditutup ke Home dan halaman
 * Pengaturan yang memuatnya ditutup dengan BACK.
 */
object SecureGuard {
    val SECURE_FOLDER_PACKAGES: Set<String> = setOf(
        "com.samsung.knox.securefolder",
        "com.samsung.android.knox.containercore"
    )

    /** Teks halaman Pengaturan Samsung (dicocokkan persis, huruf kecil). */
    val SECURE_PAGE_TEXTS: Set<String> = setOf("dual messenger", "secure folder", "folder aman")

    /**
     * Penanda halaman Pengaturan yang bisa dipakai membuka celah saat Mode Ketat:
     * Opsi pengembang (debugging USB/nirkabel), DNS Pribadi, dan VPN (selalu aktif).
     * Dicocokkan persis (huruf kecil) agar daftar Pengaturan biasa tidak ikut tertutup.
     */
    val BYPASS_PAGE_TEXTS: Set<String> = setOf(
        "debugging usb", "usb debugging", "debugging nirkabel", "wireless debugging",
        "buka kunci oem", "oem unlocking",
        "nama host penyedia dns pribadi", "private dns provider hostname",
        "tambahkan vpn", "tambah vpn", "add vpn",
        "vpn selalu aktif", "always-on vpn", "blokir koneksi tanpa vpn", "block connections without vpn",
        // Menambah pengguna/tamu (ruang kosong tanpa pemblokiran)
        "tambahkan pengguna", "tambah pengguna", "add user", "tambahkan tamu", "tambah tamu", "add guest",
        // Aplikasi admin perangkat
        "aplikasi admin perangkat", "device admin apps", "device admin app"
    )

    /** Label aplikasi buatan sendiri yang boleh dipasang lewat APK saat saklar pemasangan aktif. */
    val OWN_APP_LABELS: List<String> = listOf("penjaga ketat", "channel blocker", "telegram blocker", "bankswitch", "bank switch")

    /** Hanya bila saklar "blokir pemasangan tidak dikenal" aktif. */
    val UNKNOWN_INSTALL_TEXTS: Set<String> = setOf(
        "instal aplikasi tidak dikenal", "instal aplikasi yang tidak dikenal", "install unknown apps",
        "izinkan dari sumber ini", "allow from this source"
    )
}
