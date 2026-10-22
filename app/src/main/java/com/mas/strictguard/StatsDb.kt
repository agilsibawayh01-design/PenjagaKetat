package com.mas.strictguard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Statistik lokal (SQLite): pemakaian harian per aplikasi dan riwayat blokir. Tidak ada data yang keluar dari perangkat. */
object StatsDb {
    private class Helper(c: Context) : SQLiteOpenHelper(c.applicationContext, "stats.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE usage (day TEXT NOT NULL, pkg TEXT NOT NULL, ms INTEGER NOT NULL, launches INTEGER NOT NULL, PRIMARY KEY(day, pkg))")
            db.execSQL("CREATE TABLE blocks (ts INTEGER NOT NULL, reason TEXT NOT NULL, target TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    private var helper: Helper? = null

    @Synchronized
    private fun db(c: Context): SQLiteDatabase {
        if (helper == null) helper = Helper(c)
        return helper!!.writableDatabase
    }

    fun dayKey(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

    fun upsertDay(c: Context, day: String, u: UsageTracker.DayUsage) {
        val d = db(c)
        d.beginTransaction()
        try {
            for ((pkg, ms) in u.ms) {
                val v = ContentValues()
                v.put("day", day)
                v.put("pkg", pkg)
                v.put("ms", ms)
                v.put("launches", u.launches[pkg] ?: 0)
                d.insertWithOnConflict("usage", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        // simpan paling lama 35 hari
        d.delete("usage", "day < ?", arrayOf(dayKey(System.currentTimeMillis() - 35L * 86400000L)))
        d.delete("blocks", "ts < ?", arrayOf((System.currentTimeMillis() - 35L * 86400000L).toString()))
    }

    fun addBlock(c: Context, reason: String, target: String) {
        try {
            val v = ContentValues()
            v.put("ts", System.currentTimeMillis())
            v.put("reason", reason)
            v.put("target", target)
            db(c).insert("blocks", null, v)
        } catch (t: Throwable) {
            // abaikan
        }
    }

    /** Jumlah blokir per alasan sejak [since]. */
    fun blocksByReason(c: Context, since: Long): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val cur = db(c).rawQuery(
            "SELECT reason, COUNT(*) FROM blocks WHERE ts >= ? GROUP BY reason ORDER BY COUNT(*) DESC",
            arrayOf(since.toString())
        )
        cur.use { while (it.moveToNext()) out.add(Pair(it.getString(0), it.getInt(1))) }
        return out
    }

    /** Total pemakaian (ms) per hari untuk [days] hari terakhir, urut lama ke baru. */
    fun dailyTotals(c: Context, days: Int): List<Pair<String, Long>> {
        val map = HashMap<String, Long>()
        val from = dayKey(System.currentTimeMillis() - (days - 1).toLong() * 86400000L)
        val cur = db(c).rawQuery("SELECT day, SUM(ms) FROM usage WHERE day >= ? GROUP BY day", arrayOf(from))
        cur.use { while (it.moveToNext()) map[it.getString(0)] = it.getLong(1) }
        val out = ArrayList<Pair<String, Long>>()
        for (i in days - 1 downTo 0) {
            val k = dayKey(System.currentTimeMillis() - i.toLong() * 86400000L)
            out.add(Pair(k, map[k] ?: 0L))
        }
        return out
    }

    /** Aplikasi paling banyak dipakai dalam [days] hari terakhir: (pkg, ms, peluncuran). */
    fun topApps(c: Context, days: Int, limit: Int): List<Triple<String, Long, Int>> {
        val from = dayKey(System.currentTimeMillis() - (days - 1).toLong() * 86400000L)
        val out = ArrayList<Triple<String, Long, Int>>()
        val cur = db(c).rawQuery(
            "SELECT pkg, SUM(ms), SUM(launches) FROM usage WHERE day >= ? GROUP BY pkg ORDER BY SUM(ms) DESC LIMIT ?",
            arrayOf(from, limit.toString())
        )
        cur.use { while (it.moveToNext()) out.add(Triple(it.getString(0), it.getLong(1), it.getInt(2))) }
        return out
    }

    fun clear(c: Context) {
        val d = db(c)
        d.delete("usage", null, null)
        d.delete("blocks", null, null)
    }
}
