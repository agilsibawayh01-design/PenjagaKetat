package com.mas.strictguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager

/**
 * Proteksi suhu. Memakai SUHU BATERAI (bukan suhu CPU) dari intent sticky ACTION_BATTERY_CHANGED:
 * dibaca sesuai permintaan tanpa mendaftarkan receiver dan tanpa wakeup tambahan.
 * Level: NORMAL (<40), WARM (40-<42), BLOCK (>=42, aplikasi game/video ditutup), DANGER (>45, tambah aplikasi pihak ketiga
 * non-esensial ditutup). Setelah level BLOCK/DANGER tercapai, penutupan bertahan sampai suhu di bawah 40 (histeresis).
 * Bila sensor tidak tersedia, tidak ada yang diblokir.
 * Tidak menyentuh bendera Mode Ketat, daftar blokir, maupun kunci NSFW.
 */
object Thermal {
    const val NORMAL = 0
    const val WARM = 1
    const val BLOCK = 2
    const val DANGER = 3

    @Volatile var level = NORMAL
        private set
    @Volatile var tempC = Float.NaN
        private set

    private var lastAt = 0L
    private var notified = NORMAL
    private val cls = HashMap<String, Int>() // 0 aman, 1 berisiko (game/video), 2 pihak ketiga umum

    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)
    fun enabled(c: Context): Boolean = sp(c).getBoolean("thermal_on", true)
    fun setEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("thermal_on", v).commit()
        if (!v) { level = NORMAL; notify(c, NORMAL) }
    }

    /** Suhu baterai dalam Celsius, atau NaN bila sensor tidak melaporkan. */
    fun read(c: Context): Float {
        return try {
            val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (t == Int.MIN_VALUE || t <= 0 || t > 1000) Float.NaN else t / 10f
        } catch (t: Throwable) {
            Float.NaN
        }
    }

    fun update(c: Context, now: Long, force: Boolean = false): Int {
        if (!enabled(c)) {
            if (level != NORMAL) { level = NORMAL; notify(c, NORMAL) }
            return NORMAL
        }
        if (!force && now - lastAt < 15_000L) return level
        lastAt = now
        val t = read(c)
        tempC = t
        val prev = level
        level = if (t.isNaN()) NORMAL
        else if (t > 45f) DANGER
        else if (t >= 42f) BLOCK
        else if (t >= 40f) (if (prev >= BLOCK) BLOCK else WARM)
        else NORMAL
        if (level != notified) notify(c, level)
        return level
    }

    fun shouldBlock(c: Context, pkg: String, lv: Int): Boolean {
        if (lv < BLOCK || pkg == c.packageName) return false
        if (pkg in Config.PROTECTED || pkg in Config.IGNORED || pkg in Config.BANK_HINT || pkg in ESSENTIAL) return false
        val k = synchronized(cls) { cls[pkg] } ?: classify(c, pkg).also { synchronized(cls) { cls[pkg] = it } }
        return k == 1 || (lv >= DANGER && k == 2)
    }

    private fun classify(c: Context, pkg: String): Int = try {
        val ai = c.packageManager.getApplicationInfo(pkg, 0)
        val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        if (system) 0
        else if (ai.category == ApplicationInfo.CATEGORY_GAME || ai.category == ApplicationInfo.CATEGORY_VIDEO) 1
        else 2
    } catch (t: Throwable) {
        0
    }

    /** Tetap bisa dibuka walau suhu tinggi: komunikasi dan navigasi. */
    private val ESSENTIAL = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.google.android.contacts", "com.samsung.android.app.contacts",
        "com.google.android.apps.maps", "com.waze", "com.gojek.app", "com.grabtaxi.passenger", "com.bluebird.mobile", "com.google.android.deskclock"
    )

    private fun notify(c: Context, lv: Int) {
        notified = lv
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (lv == NORMAL) { nm.cancel(9731); return }
            val hot = lv >= DANGER
            val ch = if (hot) "thermal_alert" else "thermal"
            nm.createNotificationChannel(NotificationChannel(ch, if (hot) "Suhu berbahaya" else "Suhu HP", if (hot) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW))
            val msg = when (lv) {
                WARM -> "Baterai hangat. Kurangi pemakaian berat."
                BLOCK -> "Baterai panas. Game dan video ditutup sementara sampai di bawah 40 derajat."
                else -> "Suhu berbahaya. Hentikan pemakaian dan pengisian, biarkan HP dingin."
            }
            val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(9731, Notification.Builder(c, ch).setContentTitle("Penjaga Ketat").setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentIntent(pi).setOnlyAlertOnce(true).setAutoCancel(lv < BLOCK).build())
        } catch (t: Throwable) {
            // notifikasi opsional
        }
    }
}
