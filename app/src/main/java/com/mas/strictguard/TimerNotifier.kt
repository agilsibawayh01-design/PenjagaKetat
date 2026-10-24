package com.mas.strictguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Notifikasi sisa waktu penggunaan pada Timer Tunggu. Aktif saat aplikasi/situs masih boleh digunakan. */
object TimerNotifier {
    private const val CHANNEL = "wait_timer"
    private const val ID = 4701

    private fun nm(c: Context): NotificationManager =
        c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(c: Context) {
        nm(c).createNotificationChannel(
            NotificationChannel(CHANNEL, "Sisa waktu penggunaan", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Menampilkan sisa waktu penggunaan Penjaga Ketat"
                setShowBadge(false)
            }
        )
    }

    private const val WARN_CHANNEL = "wait_warn"
    private const val WARN_ID = 4702
    private var warned = false

    fun warnEnabled(c: Context): Boolean =
        c.getSharedPreferences("strictguard", Context.MODE_PRIVATE).getBoolean("warn_before", true)

    fun setWarnEnabled(c: Context, v: Boolean) {
        c.getSharedPreferences("strictguard", Context.MODE_PRIVATE).edit().putBoolean("warn_before", v).commit()
    }

    /** Peringatan sekali saat tersisa 1 menit; aktif lagi setelah siklus baru (sisa > 90 detik). */
    private fun warnIfNeeded(c: Context, label: String, leftMs: Long) {
        if (leftMs > 90_000L) {
            warned = false
            return
        }
        if (leftMs > 60_000L || warned || !warnEnabled(c)) return
        warned = true
        try {
            nm(c).createNotificationChannel(
                NotificationChannel(WARN_CHANNEL, "Pengingat waktu hampir habis", NotificationManager.IMPORTANCE_HIGH)
            )
            val n = NotificationCompat.Builder(c, WARN_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("1 menit lagi • $label")
                .setContentText("Simpan dulu pekerjaanmu. Waktu penggunaan hampir habis.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(30_000L)
                .build()
            nm(c).notify(WARN_ID, n)
        } catch (t: Throwable) {
            // abaikan
        }
    }

    fun show(c: Context, label: String, leftMs: Long) {
        if (leftMs <= 0L) { cancel(c); return }
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) warnIfNeeded(c, label, leftMs)
        if (!nm(c).areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(c)
        val left = leftMs / 1000L
        val min = left / 60L
        val sec = left % 60L
        val time = String.format(java.util.Locale.getDefault(), "%02d:%02d", min, sec)
        val pi = PendingIntent.getActivity(
            c, 4701, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Sisa waktu penggunaan • $label")
            .setContentText("Masih bisa digunakan: $time")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$label masih bisa digunakan selama $time."))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis() + leftMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(pi)
            .build()
        nm(c).notify(ID, n)
    }

    fun cancel(c: Context) {
        nm(c).cancel(ID)
    }
}
