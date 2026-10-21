package com.mas.strictguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return if (Store.strictOn(context)) {
            "Mode Ketat sedang aktif. Nonaktifkan Mode Ketat dulu lewat aplikasi Penjaga Ketat."
        } else {
            "Perlindungan uninstall akan dimatikan."
        }
    }
}
