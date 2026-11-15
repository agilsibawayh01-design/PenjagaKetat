package com.mas.strictguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** Kesehatan proteksi: semua pemeriksaan penting dalam satu daftar, dengan kode perbaikan. */
object Health {
    class Item(val id: String, val ok: Boolean, val critical: Boolean, val label: String, val fixLabel: String?)

    fun items(c: Context): List<Item> {
        val out = ArrayList<Item>()
        out.add(Item("acc", SelfProtector.accessibilityOn(c), true, "Aksesibilitas Penjaga Ketat belum aktif", "Buka pengaturan Aksesibilitas"))
        out.add(Item("admin", SelfProtector.adminOn(c), true, "Perlindungan uninstall belum aktif", "Aktifkan perlindungan uninstall"))
        out.add(Item("usage", Foreground.hasUsageAccess(c), false, "Akses Penggunaan belum diberikan", "Buka Akses Penggunaan"))
        out.add(Item("battery", AntiBypass.batteryExempt(c), false, "Masih dibatasi penghemat baterai: bisa dimatikan sistem", "Kecualikan dari penghemat baterai"))
        if (Store.vpnOn(c) && !VpnStatus.running) {
            out.add(Item("vpn", false, false, "VPN DNS tidak berjalan (" + VpnStatus.state.label + ")", null))
        }
        val pdns = try { Settings.Global.getString(c.contentResolver, "private_dns_mode") ?: "" } catch (t: Throwable) { "" }
        if (pdns == "hostname") {
            out.add(Item("pdns", false, false, "Private DNS HP diatur ke nama host tertentu: melewati filter DNS", "Buka pengaturan jaringan"))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            out.add(Item("notif", false, false, "Notifikasi peringatan belum diizinkan", "Izinkan notifikasi"))
        }
        if (Store.anyNotifRule(c) && !SelfProtector.notifListenerOn(c)) {
            out.add(Item("listener", false, false, "Akses Notifikasi belum aktif (aturan notifikasi tidak berjalan)", "Nyalakan Akses Notifikasi"))
        }
        if (!Store.blockOtherBrowsers(c)) {
            out.add(Item("browsers", false, false, "Blokir browser selain Chrome sedang mati", "Nyalakan kembali"))
        }
        if (Store.strictOn(c) && !WatchdogService.running) {
            out.add(Item("watchdog", false, false, "Pengawas Mode Ketat tidak berjalan", null))
        }
        return out
    }

    fun problems(c: Context): List<Item> = items(c).filter { !it.ok }
    fun criticalOk(c: Context): Boolean = items(c).none { !it.ok && it.critical }
}

/** Browser di dalam aplikasi lain (mis. tautan yang dibuka dalam Instagram/Facebook) dikenali dari nama kelas layarnya. */
object InAppBrowser {
    private val EXACT = listOf(
        "browserliteactivity", "crossplatformactivity", "inappbrowser", "webbrowseractivity",
        "minibrowser", "simplebrowser", "embeddedbrowser"
    )

    fun matches(pkg: String, className: String): Boolean {
        if (className.isEmpty() || pkg == Config.ALLOWED_BROWSER) return false
        val simple = className.substringAfterLast('.').lowercase()
        if (EXACT.any { simple.contains(it) }) return true
        return simple.endsWith("browseractivity") || simple.endsWith("browserfragment") ||
            (simple.contains("inapp") && simple.contains("browser"))
    }
}
