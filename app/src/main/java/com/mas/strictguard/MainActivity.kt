package com.mas.strictguard

import android.app.Activity
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var gateText: String? = null

    private class AppRow(val pkg: String, val label: String)

    private var appRows: List<AppRow>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        Store.seed(this)
        render()
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 14f, bold: Boolean = false, color: Int = Color.BLACK): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun header(s: String) {
        root.addView(text(s, 17f, true).apply { setPadding(0, dp(20), 0, dp(4)) })
    }

    private fun status(ok: Boolean, label: String) {
        root.addView(text((if (ok) "✔ " else "✘ ") + label, 14f, false,
            if (ok) Color.rgb(0, 120, 0) else Color.rgb(180, 0, 0)))
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun accessibilityOn(): Boolean {
        val raw = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return raw.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun adminComponent() = ComponentName(this, AdminReceiver::class.java)

    private fun adminOn(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(adminComponent())
    }

    private fun loadApps(): List<AppRow> {
        appRows?.let { return it }
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName && it !in Config.EXCLUDED_FROM_LIST }
            .map { p ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
                } catch (e: Exception) {
                    p
                }
                AppRow(p, label)
            }
            .sortedBy { it.label.lowercase() }
        appRows = list
        return list
    }

    private fun fmt(min: Int): String = String.format("%02d:%02d", min / 60, min % 60)

    private fun render() {
        val keepY = scroll.scrollY
        root.removeAllViews()
        val strict = Store.strictOn(this)

        root.addView(text("Penjaga Ketat", 26f, true))
        root.addView(text("Blokir aplikasi, situs, kata kunci, dan feed video pendek. Mode Ketat mengunci pengaturan.", 13f))

        header("Status")
        val acc = accessibilityOn()
        val adm = adminOn()
        status(acc, "Aksesibilitas Penjaga Ketat aktif")
        status(adm, "Perlindungan uninstall (Administrator perangkat) aktif")
        if (!acc) root.addView(button("Buka pengaturan Aksesibilitas") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (!adm) root.addView(button("Aktifkan perlindungan uninstall") {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Agar Penjaga Ketat tidak bisa dicopot saat Mode Ketat aktif.")
            startActivity(i)
        })

        renderStrict(strict, acc && adm)
        renderApps(strict)
        renderProtected(strict)
        renderWords(strict)
        renderWait(strict)
        renderSchedule(strict)
        renderFeeds(strict)

        scroll.post { scroll.scrollTo(0, keepY) }
    }

    private fun renderStrict(strict: Boolean, ready: Boolean) {
        header("Mode Ketat")
        if (!strict) {
            root.addView(text("Mati. Semua pengaturan bisa diubah bebas.", 13f))
            root.addView(button("Aktifkan Mode Ketat") {
                if (!ready) {
                    toast("Aktifkan Aksesibilitas dan perlindungan uninstall dulu")
                } else {
                    Store.setStrict(this, true)
                    Store.setDisableRequestedAt(this, 0L)
                    render()
                }
            })
            return
        }
        root.addView(text("AKTIF. Daftar blokir hanya bisa ditambah, tidak bisa dikurangi. Pengaturan lain terkunci. Aplikasi tidak bisa dicopot, dan halaman Pengaturan yang menyebut Penjaga Ketat diblokir.", 13f, true, Color.rgb(180, 0, 0)))
        val req = Store.disableRequestedAt(this)
        if (req == 0L) {
            root.addView(button("Minta nonaktifkan (tunggu 60 menit)") {
                Store.setDisableRequestedAt(this, System.currentTimeMillis())
                gateText = null
                render()
            })
            return
        }
        val left = req + Config.DISABLE_WAIT_MS - System.currentTimeMillis()
        if (left > 0) {
            root.addView(text("Permintaan nonaktif berjalan. Sisa ${left / 60000L + 1} menit.", 14f, true))
            root.addView(button("Batalkan permintaan") {
                Store.setDisableRequestedAt(this, 0L)
                render()
            })
            handler.postDelayed({ render() }, 30000L)
        } else {
            val code = gateText ?: Rules.randomText().also { gateText = it }
            root.addView(text("Waktu tunggu selesai. Ketik teks ini persis untuk menonaktifkan:", 13f))
            root.addView(text(code, 20f, true).apply { setTextIsSelectable(false) })
            val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
            root.addView(input)
            root.addView(button("Konfirmasi nonaktifkan") {
                if (input.text.toString() == code) {
                    Store.setStrict(this, false)
                    Store.setDisableRequestedAt(this, 0L)
                    gateText = null
                    render()
                } else {
                    toast("Teks tidak cocok")
                }
            })
            root.addView(button("Batalkan permintaan") {
                Store.setDisableRequestedAt(this, 0L)
                gateText = null
                render()
            })
        }
    }

    private fun renderApps(strict: Boolean) {
        header("Aplikasi diblokir")
        root.addView(text("Centang aplikasi yang ingin diblokir (sesuai jadwal di bawah).", 12f))
        val chosen = Store.apps(this)
        val rows = loadApps().sortedWith(compareByDescending<AppRow> { it.pkg in chosen }.thenBy { it.label.lowercase() })
        for (a in rows) {
            val cb = CheckBox(this).apply {
                text = a.label + "\n" + a.pkg
                textSize = 13f
                isChecked = a.pkg in chosen
                setOnClickListener { v ->
                    val checked = (v as CheckBox).isChecked
                    if (checked) {
                        Store.addApp(this@MainActivity, a.pkg)
                    } else if (strict) {
                        toast("Mode Ketat aktif: tidak bisa melepas blokir")
                    } else {
                        Store.removeApp(this@MainActivity, a.pkg)
                    }
                    render()
                }
            }
            root.addView(cb)
        }
    }

    private fun renderProtected(strict: Boolean) {
        header("Lindungi aplikasi lain dari uninstall")
        root.addView(text("Saat Mode Ketat aktif, halaman Pengaturan atau pemasang yang menampilkan nama aplikasi ini (copot, hentikan paksa, matikan aksesibilitas) langsung diblokir. Ini bukan penguncian sistem, tapi penghalang yang kuat.", 12f))
        val chosen = Store.protectedApps(this)
        val installed = loadApps()
        val rows = installed.sortedWith(compareByDescending<AppRow> { it.pkg in chosen }.thenBy { it.label.lowercase() })
        for (a in rows) {
            val cb = CheckBox(this).apply {
                text = a.label + "\n" + a.pkg
                textSize = 13f
                isChecked = a.pkg in chosen
                setOnClickListener { v ->
                    val checked = (v as CheckBox).isChecked
                    if (checked) {
                        Store.addProtected(this@MainActivity, a.pkg)
                    } else if (strict || a.pkg in Config.SEED_PROTECTED) {
                        toast(if (strict) "Mode Ketat aktif: tidak bisa melepas perlindungan" else "Aplikasi ini selalu dilindungi")
                    } else {
                        Store.removeProtected(this@MainActivity, a.pkg)
                    }
                    render()
                }
            }
            root.addView(cb)
        }
    }

    private fun renderWords(strict: Boolean) {
        header("Kata kunci dan situs")
        root.addView(text("Kata kunci dicek di browser dan saat mengetik. Situs dicek di kolom alamat browser.", 12f))

        val kwInput = EditText(this).apply { hint = "Tambah kata kunci"; setSingleLine() }
        root.addView(kwInput)
        root.addView(button("Tambah kata kunci") {
            val k = kwInput.text.toString().trim()
            if (k.isNotEmpty()) { Store.addKeyword(this, k); render() }
        })
        for (k in Store.keywords(this).sorted()) {
            root.addView(button("✕  $k") {
                if (strict) toast("Mode Ketat aktif: tidak bisa menghapus") else { Store.removeKeyword(this, k); render() }
            })
        }

        val dInput = EditText(this).apply { hint = "Tambah situs, mis. contoh.com"; setSingleLine() }
        root.addView(dInput)
        root.addView(button("Tambah situs") {
            val d = dInput.text.toString().trim()
            if (d.isNotEmpty()) { Store.addDomain(this, d); render() }
        })
        for (d in Store.domains(this).sorted()) {
            root.addView(button("✕  $d") {
                if (strict) toast("Mode Ketat aktif: tidak bisa menghapus") else { Store.removeDomain(this, d); render() }
            })
        }
    }

    private fun renderWait(strict: Boolean) {
        header("Timer tunggu")
        root.addView(text("Detik tunggu sebelum aplikasi terblokir bisa dibuka (0 = blokir penuh, tanpa tombol buka).", 12f))
        val wait = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Store.waitSeconds(this@MainActivity).toString())
            isEnabled = !strict
        }
        val pass = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Store.passMinutes(this@MainActivity).toString())
            isEnabled = !strict
        }
        root.addView(text("Detik tunggu", 12f))
        root.addView(wait)
        root.addView(text("Menit aplikasi terbuka setelah menunggu", 12f))
        root.addView(pass)
        root.addView(button("Simpan timer") {
            if (strict) {
                toast("Mode Ketat aktif: pengaturan terkunci")
            } else {
                val w = wait.text.toString().toIntOrNull() ?: 0
                val p = pass.text.toString().toIntOrNull() ?: 5
                Store.setWait(this, w.coerceIn(0, 600), p.coerceIn(1, 120))
                toast("Tersimpan")
            }
        })
    }

    private fun renderSchedule(strict: Boolean) {
        header("Jadwal blokir aplikasi")
        var on = Store.scheduleEnabled(this)
        var start = Store.startMin(this)
        var end = Store.endMin(this)
        var days = Store.days(this)

        val cb = CheckBox(this).apply {
            text = "Pakai jadwal (kalau mati: diblokir terus)"
            isChecked = on
            setOnClickListener {
                if (strict) { toast("Mode Ketat aktif: pengaturan terkunci"); render() }
                else { on = isChecked; Store.setSchedule(this@MainActivity, on, start, end, days) }
            }
        }
        root.addView(cb)
        root.addView(button("Mulai: ${fmt(start)}") {
            if (strict) toast("Mode Ketat aktif: pengaturan terkunci") else
                TimePickerDialog(this, { _, h, m ->
                    Store.setSchedule(this, on, h * 60 + m, end, days); render()
                }, start / 60, start % 60, true).show()
        })
        root.addView(button("Selesai: ${fmt(end)}") {
            if (strict) toast("Mode Ketat aktif: pengaturan terkunci") else
                TimePickerDialog(this, { _, h, m ->
                    Store.setSchedule(this, on, start, h * 60 + m, days); render()
                }, end / 60, end % 60, true).show()
        })
        val names = arrayOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 7) {
            val d = CheckBox(this).apply {
                text = names[i]
                textSize = 11f
                isChecked = (days and (1 shl i)) != 0
                setOnClickListener {
                    if (strict) { toast("Mode Ketat aktif: pengaturan terkunci"); render() }
                    else {
                        days = if (isChecked) days or (1 shl i) else days and (1 shl i).inv()
                        Store.setSchedule(this@MainActivity, on, start, end, days)
                    }
                }
            }
            row.addView(d)
        }
        root.addView(row)
    }

    private fun renderFeeds(strict: Boolean) {
        header("Feed video pendek")
        root.addView(text("Mencoba menutup Shorts YouTube dan Reels Instagram. Bergantung pada tampilan aplikasi, jadi bisa meleset setelah aplikasinya diperbarui.", 12f))
        val s = Store.blockShorts(this)
        val r = Store.blockReels(this)
        fun change(sv: Boolean, rv: Boolean) {
            if (strict && (!sv || !rv) && (s || r)) {
                toast("Mode Ketat aktif: pengaturan terkunci")
            } else {
                Store.setFeeds(this, sv, rv)
            }
            render()
        }
        root.addView(CheckBox(this).apply {
            text = "Blokir Shorts YouTube"
            isChecked = s
            setOnClickListener { change(isChecked, r) }
        })
        root.addView(CheckBox(this).apply {
            text = "Blokir Reels Instagram"
            isChecked = r
            setOnClickListener { change(s, isChecked) }
        })
    }
}
