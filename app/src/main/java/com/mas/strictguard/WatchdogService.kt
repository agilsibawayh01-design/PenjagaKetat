package com.mas.strictguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * Pengawas ringan. Hanya berjalan selama Mode Ketat aktif, dan berhenti sendiri setelah itu.
 * - ContentObserver: bereaksi seketika saat daftar aksesibilitas berubah.
 * - Tick 60 detik: checkpoint timer, deteksi jam diubah, cek aksesibilitas dan admin.
 */
class WatchdogService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastAlertAt = 0L
    private var lastMarkerAlert = 0L

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            try {
                guard()
            } catch (t: Throwable) {
                // jangan crash
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!Store.strictRaw(this@WatchdogService)) {
                AntiBypass.syncMarker(this@WatchdogService)
                stopSelf()
                return
            }
            try {
                TimeGuard.check(this@WatchdogService)
                SafeTimer.elapsed(this@WatchdogService, "exit")
                SafeTimer.elapsed(this@WatchdogService, "tamper")
                guard()
            } catch (t: Throwable) {
                // jangan crash
            }
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        AntiBypass.serviceStarted(this)
        try {
            startAsForeground()
        } catch (t: Throwable) {
            stopSelf()
            return
        }
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer
            )
        } catch (t: Throwable) {
            // tetap jalan dengan tick saja
        }
        try {
            contentResolver.registerContentObserver(AntiBypass.markerUri(), false, observer)
        } catch (t: Throwable) {
            // abaikan
        }
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        AntiBypass.serviceStopped(this)
        handler.removeCallbacksAndMessages(null)
        try {
            contentResolver.unregisterContentObserver(observer)
        } catch (t: Throwable) {
            // abaikan
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun guard() {
        AntiBypass.syncMarker(this)
        if (!AntiBypass.markerOk(this) && SelfProtector.canWriteSecure(this)) {
            val t = SystemClock.elapsedRealtime()
            if (lastMarkerAlert == 0L || t - lastMarkerAlert > 1_800_000L) {
                lastMarkerAlert = t
                alert("Penanda perlindungan gagal dipulihkan. Cek status perlindungan.")
            }
        }
        if (!Store.strictOn(this)) return
        if (SelfProtector.bankPaused(this)) return
        var problem: String? = null
        if (!SelfProtector.accessibilityOn(this)) {
            if (SelfProtector.reenableAccessibility(this)) {
                Store.log(this, "Aksesibilitas dimatikan, dinyalakan kembali otomatis")
            } else {
                problem = "Aksesibilitas Penjaga Ketat mati. Nyalakan kembali sekarang."
            }
        }
        if (Store.anyNotifRule(this) && !SelfProtector.notifListenerOn(this)) {
            if (SelfProtector.enableNotifListener(this)) Store.log(this, "Akses notifikasi dimatikan, dinyalakan kembali otomatis")
        }
        if (!SelfProtector.adminOn(this)) {
            problem = "Perlindungan uninstall (Administrator perangkat) dicabut. Aktifkan kembali."
        }
        if (problem != null) alert(problem)
    }

    private fun alert(msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastAlertAt != 0L && now - lastAlertAt < 120_000L) return
        lastAlertAt = now
        Store.log(this, msg)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("alerts", "Peringatan", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "alerts")
            .setContentTitle("Penjaga Ketat")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(2, n)
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("watch", "Pengawas", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, "watch")
            .setContentTitle("Penjaga Ketat aktif")
            .setContentText("Mode Ketat sedang berjalan")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        @Volatile
        var running = false

        fun start(c: Context) {
            try {
                c.startForegroundService(Intent(c, WatchdogService::class.java))
            } catch (t: Throwable) {
                // mungkin ditolak di latar belakang; akan dicoba lagi saat boot / membuka aplikasi
            }
        }
    }
}
