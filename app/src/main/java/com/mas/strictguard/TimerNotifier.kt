package com.mas.strictguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Notifikasi countdown sisa waktu penggunaan. Masa tunggu tetap menjadi mekanisme internal. */
object TimerNotifier {
    private const val CHANNEL = "usage_timer"
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

    fun showUsage(c: Context, label: String, leftMs: Long) {
        if (leftMs <= 0L) { cancel(c); return }
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
            c, 4701,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(label)
            .setContentText("Sisa waktu penggunaan: $time")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$label masih bisa digunakan. Sisa waktu penggunaan $time."
                )
            )
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
