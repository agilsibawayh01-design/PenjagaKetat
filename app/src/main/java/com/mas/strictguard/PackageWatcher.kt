package com.mas.strictguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build

/** Mendeteksi aplikasi baru dipasang / dicopot. Didaftarkan secara dinamis oleh GuardService. */
class PackageWatcher : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pkg = i.data?.schemeSpecificPart ?: return
        if (pkg == c.packageName) return
        val replacing = i.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        try {
            when (i.action) {
                Intent.ACTION_PACKAGE_ADDED -> if (!replacing) onAdded(c, pkg)
                Intent.ACTION_PACKAGE_REMOVED -> if (!replacing) onRemoved(c, pkg)
            }
        } catch (t: Throwable) {
            // abaikan
        }
    }

    private fun onAdded(c: Context, pkg: String) {
        val pm = c.packageManager
        if (pm.getLaunchIntentForPackage(pkg) == null) return
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        Store.addNewApp(c, pkg)
        Store.log(c, "Aplikasi baru: $label")
        BrowserScanner.scan(c)
        if (Store.autoBlockNew(c) && pkg !in Config.EXCLUDED_FROM_LIST) {
            Store.addApp(c, pkg)
            Store.log(c, "Diblokir otomatis: $label")
        }
    }

    private fun onRemoved(c: Context, pkg: String) {
        if (Store.strictOn(c) && pkg in Store.protectedApps(c)) {
            Store.log(c, "Aplikasi terlindungi dicopot: $pkg")
        }
    }

    companion object {
        fun register(ctx: Context): PackageWatcher {
            val w = PackageWatcher()
            val f = IntentFilter()
            f.addAction(Intent.ACTION_PACKAGE_ADDED)
            f.addAction(Intent.ACTION_PACKAGE_REMOVED)
            f.addDataScheme("package")
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(w, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(w, f)
            }
            return w
        }
    }
}

object BrowserScanner {
    /** Mencari semua aplikasi yang bisa membuka tautan web (browser) dan mencatatnya. */
    fun scan(c: Context) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            for (r in c.packageManager.queryIntentActivities(i, 0)) {
                val p = r.activityInfo.packageName
                if (p == c.packageName || p in Config.PROTECTED || p in Config.IGNORED) continue
                if (p !in Config.BROWSERS) Store.addBrowser(c, p)
            }
        } catch (t: Throwable) {
            // abaikan
        }
    }
}
