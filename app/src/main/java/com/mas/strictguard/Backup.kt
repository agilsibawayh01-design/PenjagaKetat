package com.mas.strictguard

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ekspor/impor konfigurasi sebagai JSON. Memakai DAFTAR PUTIH kunci: hanya aturan dan pengaturan yang
 * ikut. TIDAK ikut: PIN/kode teman (hash), bendera Mode Ketat dan kunci aplikasi, timer berjalan
 * (tw_*, pass_*, pend_*, safetimer), log, riwayat, dan sesi Fokus yang sedang berjalan.
 * Saat Mode Ketat aktif, restore hanya MENAMBAH/memperketat, tidak pernah melonggarkan.
 */
object Backup {
    const val APP = "PenjagaKetat"
    const val FORMAT = 1
    private const val MAX_BYTES = 4 * 1024 * 1024

    private val SET_KEYS = setOf(
        "apps", "browsers", "domains", "keywords", "keywords_url", "keywords_screen",
        "keywords_builtin_disabled", "newapps", "prot", "vpn_excl", "store_names",
        "focus_ba", "focus_bs", "focus_bc", "focus_aa", "focus_as", "pause_apps"
    )

    /** Himpunan yang aman digabung (union) saat Mode Ketat aktif: menambah = memperketat. */
    private val TIGHTEN_SETS = setOf("apps", "browsers", "domains", "keywords", "keywords_url", "keywords_screen", "prot", "store_names", "focus_ba", "focus_bs", "focus_bc", "pause_apps")

    private val SCALAR_KEYS = setOf(
        "auto_new", "block_browsers", "motivation", "reels", "shorts",
        "sch_on", "sch_days", "sch_start", "sch_end", "wait_s",
        "vpn_doh", "vpn_excl_bank", "vpn_on",
        "strict_sched", "exp_min", "exit_wait_on", "exit_delay", "exit_text_len",
        "profiles", "keyword_split_v21", "seeded", "sched_notify", "warn_before", "store_block", "block_ip_urls",
        "block_unknown_install", "night_on", "night_start", "night_end"
    )

    private val PREFIXES = listOf("cfg_", "sitecfg_", "cat_", "loc_", "feat_")

    fun allowed(key: String): Boolean =
        key in SET_KEYS || key in SCALAR_KEYS || PREFIXES.any { key.startsWith(it) && key.length > it.length }

    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    fun fileName(): String = "PenjagaKetat_Backup_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    // ---------------- ekspor ----------------
    fun export(c: Context): String {
        val prefs = JSONObject()
        for ((k, v) in sp(c).all) {
            if (!allowed(k) || v == null) continue
            val o = JSONObject()
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "set").put("v", JSONArray(v.filterIsInstance<String>().sorted()))
                else -> continue
            }
            prefs.put(k, o)
        }
        val root = JSONObject()
        root.put("app", APP)
        root.put("format", FORMAT)
        root.put("created", System.currentTimeMillis())
        root.put("device", Build.MODEL ?: "")
        root.put("strictWasOn", Store.strictRaw(c))
        root.put("prefs", prefs)
        return root.toString(1)
    }

    // ---------------- validasi ----------------
    class Parsed(val ok: Boolean, val error: String, val root: JSONObject?, val summary: String)

    fun parse(text: String): Parsed {
        try {
            if (text.length > MAX_BYTES) return Parsed(false, "File terlalu besar untuk backup Penjaga Ketat.", null, "")
            val root = JSONObject(text)
            if (root.optString("app") != APP) return Parsed(false, "Ini bukan file backup Penjaga Ketat.", null, "")
            val fmt = root.optInt("format", 0)
            if (fmt < 1) return Parsed(false, "Format backup tidak dikenal.", null, "")
            if (fmt > FORMAT) return Parsed(false, "Backup dibuat oleh versi yang lebih baru. Perbarui aplikasi dulu.", null, "")
            val prefs = root.optJSONObject("prefs") ?: return Parsed(false, "Isi backup rusak (tidak ada data).", null, "")
            val it = prefs.keys()
            var n = 0
            while (it.hasNext()) {
                val k = it.next()
                if (!allowed(k)) continue
                val o = prefs.optJSONObject(k) ?: return Parsed(false, "Isi backup rusak pada \"$k\".", null, "")
                val t = o.optString("t")
                val ok = when (t) {
                    "b" -> o.get("v") is Boolean
                    "i" -> o.get("v") is Int
                    "l" -> o.get("v") is Int || o.get("v") is Long
                    "f" -> o.get("v") is Number
                    "s" -> o.get("v") is String
                    "set" -> {
                        val a = o.optJSONArray("v")
                        a != null && (0 until a.length()).all { i -> a.get(i) is String }
                    }
                    else -> false
                }
                if (!ok) return Parsed(false, "Isi backup rusak pada \"$k\".", null, "")
                if (k == "profiles") {
                    val arr = JSONArray(o.getString("v"))
                    for (i in 0 until arr.length()) arr.getJSONObject(i)
                }
                n++
            }
            if (n == 0) return Parsed(false, "Backup kosong, tidak ada aturan yang bisa dipulihkan.", null, "")
            return Parsed(true, "", root, summary(root))
        } catch (t: Throwable) {
            return Parsed(false, "File tidak bisa dibaca atau rusak.", null, "")
        }
    }

    private fun setSize(prefs: JSONObject, k: String): Int = prefs.optJSONObject(k)?.optJSONArray("v")?.length() ?: 0

    private fun summary(root: JSONObject): String {
        val prefs = root.getJSONObject("prefs")
        val whenText = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")).format(Date(root.optLong("created", 0L)))
        val apps = setSize(prefs, "apps")
        val sites = HashSet<String>()
        prefs.optJSONObject("domains")?.optJSONArray("v")?.let { a -> for (i in 0 until a.length()) sites.add(a.getString(i)) }
        val keys = prefs.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith("sitecfg_")) sites.add(k.removePrefix("sitecfg_"))
        }
        var profiles = 0
        var schedules = 0
        try {
            val arr = JSONArray(prefs.optJSONObject("profiles")?.optString("v", "[]") ?: "[]")
            profiles = arr.length()
            for (i in 0 until arr.length()) schedules += arr.getJSONObject(i).optJSONArray("s")?.length() ?: 0
        } catch (t: Throwable) {
            // abaikan
        }
        return "Backup dibuat pada $whenText\nBerisi $apps aturan aplikasi, ${sites.size} aturan website, $profiles profile, $schedules jadwal"
    }

    // ---------------- restore ----------------
    private fun dir(c: Context) = File(c.filesDir, "backups")

    /** Simpan konfigurasi sekarang sebelum restore. Menyimpan 3 terakhir. */
    fun saveSafety(c: Context): Boolean =
        try {
            val d = dir(c)
            d.mkdirs()
            val f = File(d, "pre_restore_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json")
            f.writeText(export(c))
            val all = (d.listFiles() ?: emptyArray()).filter { it.name.startsWith("pre_restore_") }.sortedBy { it.name }
            for (old in all.dropLast(3)) old.delete()
            true
        } catch (t: Throwable) {
            false
        }

    fun lastSafety(c: Context): File? =
        (dir(c).listFiles() ?: emptyArray()).filter { it.name.startsWith("pre_restore_") }.maxByOrNull { it.name }

    /** Terapkan backup yang SUDAH lolos parse(). Mengembalikan pesan hasil. */
    fun apply(c: Context, root: JSONObject): String {
        if (!saveSafety(c)) return "Restore dibatalkan: gagal menyimpan cadangan konfigurasi saat ini."
        val prefs = root.getJSONObject("prefs")
        val p = sp(c)
        val e = p.edit()
        val strict = Store.strictRaw(c)
        var added = 0
        val keys = ArrayList<String>()
        val it = prefs.keys()
        while (it.hasNext()) keys.add(it.next())

        if (!strict) {
            for (k in p.all.keys) if (allowed(k)) e.remove(k)
            for (k in keys) if (allowed(k)) {
                put(e, k, prefs.getJSONObject(k))
                added++
            }
        } else {
            for (k in keys) {
                if (!allowed(k)) continue
                val o = prefs.getJSONObject(k)
                when {
                    k in TIGHTEN_SETS -> {
                        val cur = HashSet(p.getStringSet(k, emptySet()) ?: emptySet())
                        val a = o.getJSONArray("v")
                        val before = cur.size
                        for (i in 0 until a.length()) cur.add(a.getString(i))
                        if (cur.size > before) {
                            e.putStringSet(k, cur)
                            added += cur.size - before
                        }
                    }
                    k.startsWith("cfg_") || k.startsWith("sitecfg_") || k.startsWith("loc_") -> {
                        if (!p.contains(k)) {
                            put(e, k, o)
                            added++
                        }
                    }
                    k == "block_unknown_install" || k == "night_on" -> {
                        // hanya boleh menyalakan (memperketat) saat Mode Ketat aktif
                        if (o.opt("v") == true && !p.getBoolean(k, k == "night_on")) {
                            e.putBoolean(k, true)
                            added++
                        }
                    }
                    k.startsWith("cat_") || k.startsWith("feat_") -> {
                        if (o.opt("v") == true && !p.getBoolean(k, false)) {
                            e.putBoolean(k, true)
                            added++
                        }
                    }
                    k == "profiles" -> {
                        val cur = try { JSONArray(p.getString("profiles", "[]") ?: "[]") } catch (t: Throwable) { JSONArray() }
                        val ids = HashSet<String>()
                        for (i in 0 until cur.length()) ids.add(cur.getJSONObject(i).optString("id"))
                        val inc = JSONArray(o.getString("v"))
                        var n = 0
                        for (i in 0 until inc.length()) {
                            val po = inc.getJSONObject(i)
                            if (po.optString("id") !in ids) {
                                cur.put(po)
                                n++
                            }
                        }
                        if (n > 0) {
                            e.putString("profiles", cur.toString())
                            added += n
                        }
                    }
                    else -> {
                        // pengaturan lain tidak diubah saat Mode Ketat aktif (bisa melonggarkan)
                    }
                }
            }
        }
        if (!e.commit()) return "Restore gagal ditulis. Konfigurasi lama tetap ada."
        Focus.reload()
        ScheduleNotifier.rearm(c)
        Store.log(c, if (strict) "Restore (Mode Ketat aktif): hanya menambah $added aturan" else "Restore dari backup: $added pengaturan dipulihkan")
        return if (strict) "Mode Ketat aktif: hanya menambah aturan ($added ditambahkan). Pengaturan yang bisa melonggarkan tidak diubah."
        else "Restore selesai ($added pengaturan dipulihkan). Cadangan konfigurasi sebelumnya disimpan di aplikasi."
    }

    private fun put(e: android.content.SharedPreferences.Editor, k: String, o: JSONObject) {
        when (o.getString("t")) {
            "b" -> e.putBoolean(k, o.getBoolean("v"))
            "i" -> e.putInt(k, o.getInt("v"))
            "l" -> e.putLong(k, o.getLong("v"))
            "f" -> e.putFloat(k, o.getDouble("v").toFloat())
            "s" -> e.putString(k, o.getString("v"))
            "set" -> {
                val a = o.getJSONArray("v")
                val s = HashSet<String>()
                for (i in 0 until a.length()) s.add(a.getString(i))
                e.putStringSet(k, s)
            }
        }
    }
}
