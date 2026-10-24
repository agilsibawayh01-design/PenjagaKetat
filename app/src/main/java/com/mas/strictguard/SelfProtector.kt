package com.mas.strictguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Pemeriksa dan pemulih perlindungan diri (aksesibilitas, admin, izin). */
object SelfProtector {
    private const val BANKSWITCH_KEY = "bankswitch_pause_until"

    fun canWriteSecure(c: Context): Boolean =
        c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun entries(c: Context): List<String> {
        val raw = Settings.Secure.getString(
            c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return raw.split(':').filter { it.isNotBlank() }
    }

    fun accessibilityOn(c: Context): Boolean =
        entries(c).any { ComponentName.unflattenFromString(it)?.packageName == c.packageName }

    fun adminOn(c: Context): Boolean {
        val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(c, AdminReceiver::class.java))
    }

    /** True bila BankSwitch sedang menjeda aksesibilitas karena aplikasi bank terbuka. */
    fun bankPaused(c: Context): Boolean {
        try {
            val v = Settings.Secure.getString(c.contentResolver, BANKSWITCH_KEY)?.toLongOrNull() ?: 0L
            if (v > System.currentTimeMillis()) return true
        } catch (t: Throwable) {
            // abaikan
        }
        if (Foreground.hasUsageAccess(c)) {
            val cur = Foreground.current(c)
            if (cur.isNotEmpty() && cur in Config.BANK_HINT) return true
        }
        return false
    }

    /** Menyalakan kembali aksesibilitas sendiri. Butuh WRITE_SECURE_SETTINGS. */
    fun reenableAccessibility(c: Context): Boolean {
        if (!canWriteSecure(c)) return false
        if (accessibilityOn(c)) return true
        // Batasi percobaan agar tidak saling tulis terus-menerus bila ada yang melawan.
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastTry != 0L && now >= lastTry && now - lastTry < 10_000L) return false
        lastTry = now
        return try {
            val list = entries(c).toMutableList()
            list.add(ComponentName(c, GuardService::class.java).flattenToString())
            Settings.Secure.putString(
                c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":")
            )
            Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            // Jangan percaya putString tanpa exception: baca ulang.
            accessibilityOn(c)
        } catch (t: Throwable) {
            false
        }
    }

    @Volatile
    private var lastTry = 0L

    private const val NOTIF_KEY = "enabled_notification_listeners"

    fun notifListenerOn(c: Context): Boolean {
        val raw = Settings.Secure.getString(c.contentResolver, NOTIF_KEY) ?: ""
        return raw.split(':').any { ComponentName.unflattenFromString(it)?.packageName == c.packageName }
    }

    /** Menyalakan akses notifikasi sendiri. Butuh WRITE_SECURE_SETTINGS. */
    fun enableNotifListener(c: Context): Boolean {
        if (!canWriteSecure(c)) return false
        if (notifListenerOn(c)) return true
        return try {
            val raw = Settings.Secure.getString(c.contentResolver, NOTIF_KEY) ?: ""
            val list = raw.split(':').filter { it.isNotBlank() }.toMutableList()
            list.add(ComponentName(c, NotifGuard::class.java).flattenToString())
            Settings.Secure.putString(c.contentResolver, NOTIF_KEY, list.joinToString(":"))
            notifListenerOn(c)
        } catch (t: Throwable) {
            false
        }
    }
}
