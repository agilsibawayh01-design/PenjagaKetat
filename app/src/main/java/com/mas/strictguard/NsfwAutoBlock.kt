package com.mas.strictguard

import android.content.Context
import org.json.JSONArray

/**
 * Blokir permanen otomatis: sumber yang sama (domain di browser / channel Telegram) terdeteksi NSFW
 * LIMIT kali dalam WINDOW_MS masuk ke daftar blokir yang sudah ada (situs atau channel).
 * Yang dihitung adalah kejadian deteksi (sudah lolos 2-dari-3 pemindaian dan jeda antar-deteksi),
 * dan dua kejadian untuk sumber yang sama harus berjarak minimal MIN_GAP_MS.
 */
object NsfwAutoBlock {
    const val WINDOW_MS = 10 * 60_000L
    const val LIMIT = 5
    const val MIN_GAP_MS = 60_000L

    data class Ev(val key: String, val at: Long)
    data class Outcome(val events: List<Ev>, val count: Int, val accepted: Boolean, val reached: Boolean)

    /** Murni (bisa dites). */
    fun apply(events: List<Ev>, key: String, now: Long): Outcome {
        val recent = events.filter { now - it.at in 0..WINDOW_MS }
        val mine = recent.filter { it.key == key }
        if (mine.isNotEmpty() && now - mine.maxOf { it.at } < MIN_GAP_MS) {
            return Outcome(recent, mine.size, false, false)
        }
        val count = mine.size + 1
        if (count >= LIMIT) return Outcome(recent.filter { it.key != key }, count, true, true)
        return Outcome(recent + Ev(key, now), count, true, false)
    }

    private fun sp(c: Context) = c.getSharedPreferences("nsfw_auto", Context.MODE_PRIVATE)

    private fun load(c: Context): List<Ev> = try {
        val a = JSONArray(sp(c).getString("events", "[]") ?: "[]")
        (0 until a.length()).map { val o = a.getJSONArray(it); Ev(o.getString(0), o.getLong(1)) }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun save(c: Context, list: List<Ev>) {
        val a = JSONArray()
        list.forEach { a.put(JSONArray().put(it.key).put(it.at)) }
        sp(c).edit().putString("events", a.toString()).commit()
    }

    /** Catat satu kejadian NSFW untuk sumber ini. Mengembalikan keterangan bila baru saja diblokir permanen, selain itu null. */
    @Synchronized
    fun record(c: Context, src: NsfwSource.Src): String? {
        val o = apply(load(c), src.key, System.currentTimeMillis())
        save(c, o.events)
        if (!o.reached) return null
        return if (src.kind == "d") {
            if (!NsfwSource.domainAllowed(src.name)) return null
            Store.addDomain(c, src.name)
            "situs " + src.name + " diblokir permanen otomatis (" + LIMIT + " kali NSFW dalam " + (WINDOW_MS / 60000L) + " menit)"
        } else {
            Store.addChannel(c, src.name)
            "channel \"" + src.name + "\" diblokir permanen otomatis (" + LIMIT + " kali NSFW dalam " + (WINDOW_MS / 60000L) + " menit)"
        }
    }
}
