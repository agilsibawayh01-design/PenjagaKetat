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

    /** Aplikasi pendamping yang aksesibilitasnya juga dipulihkan (Telegram Channel Blocker). */
    private val COMPANIONS = listOf("com.mas.tgblocker")
    @Volatile
    private var lastCompanionTry = 0L

    /**
     * Menyalakan lagi aksesibilitas aplikasi pendamping bila mati (mis. setelah Safe Mode). Butuh WRITE_SECURE_SETTINGS.
     * Tidak dilakukan saat BankSwitch menjeda layanan karena aplikasi bank terbuka.
     */
    fun reenableCompanions(c: Context) {
        if (!canWriteSecure(c) || bankPaused(c)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastCompanionTry != 0L && now >= lastCompanionTry && now - lastCompanionTry < 10_000L) return
        lastCompanionTry = now
        try {
            val cur = entries(c).toMutableList()
            var changed = false
            for (pkg in COMPANIONS) {
                try { c.packageManager.getPackageInfo(pkg, 0) } catch (t: Throwable) { continue }
                val svcs = c.packageManager.queryIntentServices(
                    android.content.Intent("android.accessibilityservice.AccessibilityService").setPackage(pkg), 0
                )
                for (r in svcs) {
                    val si = r.serviceInfo ?: continue
                    val flat = ComponentName(si.packageName, si.name).flattenToString()
                    if (cur.none { ComponentName.unflattenFromString(it)?.packageName == pkg }) {
                        cur.add(flat)
                        changed = true
                        Store.log(c, "Aksesibilitas $pkg mati, dinyalakan kembali otomatis")
                    }
                }
            }
            if (changed) {
                Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, cur.joinToString(":"))
                Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        } catch (t: Throwable) {
            // abaikan
        }
    }

    /** Pemulihan umum (tanpa syarat Mode Ketat): aksesibilitas sendiri dan aplikasi pendamping. */
    fun restoreAll(c: Context) {
        if (!canWriteSecure(c) || bankPaused(c)) return
        reenableAccessibility(c)
        reenableCompanions(c)
    }

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
