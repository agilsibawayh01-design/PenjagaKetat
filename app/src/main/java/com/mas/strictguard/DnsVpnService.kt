package com.mas.strictguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService

/**
 * Cangkang tipis VpnService. Semua logika ada di VpnManager (siklus hidup), DnsEngine (DNS),
 * VpnTunnel (paket) dan VpnNetworkMonitor (jaringan).
 */
class DnsVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground(VpnStatus.summary())
        VpnManager.onServiceStarted(this)
        return START_STICKY
    }

    fun newBuilder(): Builder = Builder()

    private fun startAsForeground(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("vpn", "VPN DNS", NotificationManager.IMPORTANCE_MIN))
            startForeground(3, build(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (t: Throwable) {
            // tetap berjalan tanpa notifikasi latar depan
        }
    }

    private fun build(text: String): Notification =
        Notification.Builder(this, "vpn")
            .setContentTitle("Penjaga Ketat")
            .setContentText("VPN DNS: $text")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()

    fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(3, build(text))
        } catch (t: Throwable) {
            // abaikan
        }
    }

    override fun onRevoke() {
        VpnManager.onRevoked(this)
        super.onRevoke()
    }

    override fun onDestroy() {
        VpnManager.onServiceDestroyed(this)
        super.onDestroy()
    }
}
