package com.mas.strictguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Pemulihan setelah reboot atau update aplikasi. Tidak pernah melempar exception. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val a = intent?.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED &&
            a != Intent.ACTION_MY_PACKAGE_REPLACED &&
            a != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        try {
            SafeTimer.elapsed(context, "exit")
            SafeTimer.elapsed(context, "tamper")
            if (Store.vpnOn(context)) DnsVpnService.start(context)
            if (Store.strictOn(context)) {
                Store.log(context, "Pemulihan setelah boot/update")
                SelfProtector.reenableAccessibility(context)
                WatchdogService.start(context)
            }
        } catch (t: Throwable) {
            // jangan pernah crash saat boot
        }
    }
}
