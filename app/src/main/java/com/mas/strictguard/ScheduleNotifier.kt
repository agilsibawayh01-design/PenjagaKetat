package com.mas.strictguard

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Calendar

/**
 * Jadwal profil BERJALAN TANPA alarm: ProfileEngine.isActive dicek saat dibutuhkan, jadi setelah reboot
 * jadwal langsung berlaku. Kelas ini hanya untuk pemberitahuan opsional "profil mulai/selesai":
 * satu alarm tidak presisi (setAndAllowWhileIdle) menuju batas jadwal berikutnya, bukan polling.
 * Bila pemberitahuan dimatikan (bawaan), tidak ada alarm sama sekali.
 */
object ScheduleNotifier {
    private const val KEY_ON = "sched_notify"
    private const val KEY_LAST = "sched_last_active"
    private const val CHANNEL = "schedule"
    private const val REQ = 4801

    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = sp(c).getBoolean(KEY_ON, false)

    fun setEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean(KEY_ON, on).commit()
        if (on) {
            snapshot(c)
            rearm(c)
        } else {
            cancel(c)
        }
    }

    private fun pending(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, REQ, Intent(c, ScheduleReceiver::class.java).setAction("com.mas.strictguard.SCHEDULE_TICK"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun cancel(c: Context) {
        try {
            (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c))
        } catch (t: Throwable) {
            // abaikan
        }
    }

    /** Profil bernama yang jadwalnya sedang berlaku. */
    fun activeNow(c: Context): List<Profile> =
        ProfileStore.load(c).filter { it.enabled && it.schedules.isNotEmpty() && ProfileEngine.isActive(c, it) }

    /** Simpan keadaan sekarang sebagai acuan agar tidak memberi tahu perubahan lama. */
    fun snapshot(c: Context) {
        sp(c).edit().putString(KEY_LAST, activeNow(c).joinToString("\n") { it.id + "=" + it.name }).apply()
    }

    /** Waktu dan keterangan batas jadwal berikutnya (mulai/selesai) dalam 8 hari ke depan, atau null. */
    fun nextBoundary(c: Context, now: Long): Pair<Long, String>? {
        var best = Long.MAX_VALUE
        var label = ""
        for (p in ProfileStore.load(c)) {
            if (!p.enabled) continue
            for (s in p.schedules) {
                if (s.start == s.end) continue
                for (off in 0..8) {
                    val st = Calendar.getInstance()
                    st.timeInMillis = now
                    st.add(Calendar.DAY_OF_YEAR, off)
                    val dayIdx = (st.get(Calendar.DAY_OF_WEEK) + 5) % 7
                    if ((s.days and (1 shl dayIdx)) == 0) continue
                    st.set(Calendar.HOUR_OF_DAY, s.start / 60)
                    st.set(Calendar.MINUTE, s.start % 60)
                    st.set(Calendar.SECOND, 0)
                    st.set(Calendar.MILLISECOND, 0)
                    val en = st.clone() as Calendar
                    en.set(Calendar.HOUR_OF_DAY, s.end / 60)
                    en.set(Calendar.MINUTE, s.end % 60)
                    if (s.start > s.end) en.add(Calendar.DAY_OF_YEAR, 1)
                    val a = st.timeInMillis
                    val b = en.timeInMillis
                    if (a > now && a < best) {
                        best = a
                        label = p.name + " dimulai"
                    }
                    if (b > now && b < best) {
                        best = b
                        label = p.name + " selesai"
                    }
                }
            }
        }
        return if (best == Long.MAX_VALUE) null else Pair(best, label)
    }

    /** Pasang satu alarm menuju batas berikutnya. Tidak melakukan apa pun bila pemberitahuan mati. */
    fun rearm(c: Context) {
        try {
            val ctx = c.applicationContext
            cancel(ctx)
            if (!enabled(ctx)) return
            val nb = nextBoundary(ctx, System.currentTimeMillis()) ?: return
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nb.first + 2000L, pending(ctx))
        } catch (t: Throwable) {
            // jangan pernah crash karena alarm
        }
    }

    /** Dipanggil saat alarm berbunyi atau jam/zona waktu berubah. */
    fun onTick(c: Context) {
        val ctx = c.applicationContext
        try {
            if (!enabled(ctx)) return
            val nowList = activeNow(ctx)
            val last = (sp(ctx).getString(KEY_LAST, "") ?: "").split('\n').filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val now = nowList.associate { it.id to it.name }
            for ((id, name) in now) if (id !in last) notify(ctx, "$name dimulai", "Jadwal profil $name sekarang berlaku.")
            for ((id, name) in last) if (id !in now) notify(ctx, "$name selesai", "Jadwal profil $name sudah berakhir.")
            snapshot(ctx)
        } catch (t: Throwable) {
            // abaikan
        } finally {
            rearm(ctx)
        }
    }

    private fun notify(c: Context, title: String, body: String) {
        if (c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jadwal profil", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify((title.hashCode() and 0x7FFFFFF) + 5000, n)
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ScheduleNotifier.onTick(context)
    }
}
