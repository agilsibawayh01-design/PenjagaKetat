package com.mas.strictguard

import android.content.Context
import android.provider.Settings

/**
 * Saat Mode Ketat aktif, pengaturan DNS Pribadi dikembalikan bila diubah (jalan memutar filter VPN DNS).
 * Butuh izin WRITE_SECURE_SETTINGS (sama dengan pemulih aksesibilitas). Tanpa izin itu tidak melakukan apa pun.
 */
object DnsGuard {
    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    private fun eff(mode: String) = if (mode.isEmpty()) "opportunistic" else mode

    fun check(c: Context, strict: Boolean) {
        try {
            val p = sp(c)
            if (!strict) {
                if (p.contains("pdns_mode")) p.edit().remove("pdns_mode").remove("pdns_spec").apply()
                return
            }
            if (!SelfProtector.canWriteSecure(c)) return
            val cr = c.contentResolver
            val mode = Settings.Global.getString(cr, "private_dns_mode") ?: ""
            val spec = Settings.Global.getString(cr, "private_dns_specifier") ?: ""
            if (!p.contains("pdns_mode")) {
                p.edit().putString("pdns_mode", mode).putString("pdns_spec", spec).apply()
                return
            }
            val bm = p.getString("pdns_mode", "") ?: ""
            val bs = p.getString("pdns_spec", "") ?: ""
            val changed = eff(mode) != eff(bm) || (eff(bm) == "hostname" && spec != bs)
            if (changed) {
                Settings.Global.putString(cr, "private_dns_mode", eff(bm))
                if (eff(bm) == "hostname") Settings.Global.putString(cr, "private_dns_specifier", bs)
                Store.log(c, "DNS Pribadi dikembalikan ke pengaturan awal Mode Ketat")
            }
        } catch (t: Throwable) {
            // abaikan
        }
    }
}
