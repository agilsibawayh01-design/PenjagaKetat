# Kebijakan Privasi Penjaga Ketat

Terakhir diperbarui: 17 November 2026

Penjaga Ketat adalah alat pengendali diri yang berjalan di HP kamu sendiri. Tidak ada akun, tidak ada server milik pengembang, dan tidak ada pelacak atau iklan.

## Data yang disimpan di HP
Aturan blokir, jadwal, profil, log, statistik, dan pengaturan disimpan hanya di perangkat. PIN dan kode teman disimpan sebagai hash. Data ini tidak dikirim ke pengembang. Backup berupa file yang kamu simpan sendiri (opsional dienkripsi dengan kata sandimu).

## Izin sensitif dan alasannya
- **Aksesibilitas**: membaca nama aplikasi yang terbuka serta teks di layar untuk mencocokkan kata kunci, situs, dan konten dewasa, lalu menutup halaman yang melanggar aturanmu. Isi layar diproses di HP dan tidak disimpan atau dikirim.
- **VPN lokal (filter DNS)**: menyaring nama situs di HP. Lalu lintas tidak lewat server pengembang. Saat filter aktif, nama situs yang dibuka diteruskan ke server DNS: Cloudflare Family (1.1.1.3), lalu DNS jaringanmu atau DNS publik lain sebagai cadangan. Penyedia DNS itu punya kebijakan privasinya sendiri.
- **Lokasi** (opsional): untuk aturan berbasis lokasi. Dicocokkan di HP dan tidak dikirim.
- **Akses pemakaian, notifikasi, pengelola perangkat, dan izin sistem lain**: untuk batas waktu, peringatan, dan perlindungan agar aplikasi tidak mudah dimatikan.

## Koneksi keluar
1. Filter DNS (di atas).
2. Memilih titik di peta: memuat pustaka peta dari unpkg.com dan peta dari OpenStreetMap.
3. Pemeriksaan pembaruan: menghubungi GitHub bila kamu menekannya atau menyalakan pemeriksaan otomatis. GitHub dapat melihat alamat IP-mu.

## Batas perlindungan
Penjaga Ketat tidak bisa mencegah semua cara melewati aturan, misalnya reset pabrik, perangkat lain, atau mode aman. Ini alat bantu, bukan pengganti dukungan dari orang yang kamu percaya.

## Kontak
Pertanyaan atau permintaan penghapusan: karena data hanya ada di HP-mu, cukup hapus data aplikasi atau copot pemasangannya. Pertanyaan lain: lewat halaman repositori ini.
