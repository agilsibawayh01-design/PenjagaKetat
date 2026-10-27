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
}
