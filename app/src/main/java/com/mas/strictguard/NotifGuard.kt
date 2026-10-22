package com.mas.strictguard

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Menghapus notifikasi aplikasi yang sedang diblokir (bila opsi notifikasi dicentang di aturan aplikasi). */
class NotifGuard : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        val pkg = s.packageName ?: return
        if (pkg == packageName) return
        try {
            val apps = Store.apps(this)
            if (pkg in apps && !Store.appCfg(this, pkg).notif) return
            val verdict = BlockEngine.check(this, pkg, System.currentTimeMillis(), apps, ProfileStore.load(this))
            if (verdict != null) cancelNotification(s.key)
        } catch (t: Throwable) {
            // jangan crash
        }
    }
}
