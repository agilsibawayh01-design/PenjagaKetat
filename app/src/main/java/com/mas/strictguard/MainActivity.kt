package com.mas.strictguard

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.PowerManager
import android.text.Editable
import android.text.TextWatcher
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout
    private lateinit var navBar: LinearLayout
    private var statsLoading = false
    private var statsFailed = false
    private val handler = Handler(Looper.getMainLooper())
    private var gateText: String? = null
    private var cur: LinearLayout? = null
    private var screen = "home"
    private var lastScreen = ""
    private var appQuery = ""
    private var gateTyped = ""
    private var gateShown = false
    private var credAsked = false
    private var inCred = false

    private val night: Boolean
        get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val C_BG: Int get() = Color.parseColor(if (night) "#12161C" else "#F2F4F8")
    private val C_CARD: Int get() = Color.parseColor(if (night) "#1E242C" else "#FFFFFF")
    private val C_CHIP: Int get() = Color.parseColor(if (night) "#2A3340" else "#E6ECF5")
    private val C_FIELD: Int get() = Color.parseColor(if (night) "#262D37" else "#F6F7FA")
    private val C_PRIMARY: Int get() = Color.parseColor(if (night) "#5B8DEF" else "#1E3A5F")
    private val C_TEXT: Int get() = Color.parseColor(if (night) "#E8EAED" else "#1B1F27")
    private val C_SUB: Int get() = Color.parseColor(if (night) "#A0A9B5" else "#5F6B7A")
    private val C_OK: Int get() = Color.parseColor(if (night) "#4CC38A" else "#1B7F4B")
    private val C_BAD: Int get() = Color.parseColor(if (night) "#FF6B6B" else "#C62828")

    private class AppRow(val pkg: String, val label: String)

    private var appRows: List<AppRow>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(24))
        }
        scroll.setBackgroundColor(C_BG)
        scroll.addView(root)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        navBar = buildNav()
        outer.addView(navBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(outer)
        applySystemBars()
        if (intent?.getBooleanExtra("urge", false) == true) {
            screen = "urge"
            urgeEnd = 0L
        }
    }

    /** Status bar dan navigation bar mengikuti warna latar aplikasi (terang/gelap). */
    private fun applySystemBars() {
        try {
            window.statusBarColor = C_BG
            window.navigationBarColor = C_BG
            if (Build.VERSION.SDK_INT >= 30) {
                val m = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(if (night) 0 else m, m)
            } else {
                val m = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                val f = window.decorView.systemUiVisibility
                window.decorView.systemUiVisibility = if (night) f and m.inv() else f or m
            }
        } catch (t: Throwable) {
            // tampilan saja, abaikan
        }
    }

    override fun onResume() {
        super.onResume()
        statsFailed = false
        Store.seed(this)
        AntiBypass.checkWiped(this)
        AntiBypass.syncMarker(this)
        if (Store.strictRaw(this) && !WatchdogService.running) WatchdogService.start(this)
        VpnManager.ensure(this)
        if (AppLock.needsAuth(this)) showLockScreen() else render()
    }

    override fun onPause() {
        if (!inCred) credAsked = false
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 14f, bold: Boolean = false, color: Int = C_TEXT): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }

    private fun bg(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun newCard(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(14), dp(18), dp(16))
        c.background = bg(C_CARD, dp(20)).apply { setStroke(dp(1), C_CHIP) }
        c.elevation = if (night) 0f else dp(1).toFloat()
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(12)
        c.layoutParams = lp
        return c
    }

    /** Menambah tampilan ke kartu yang sedang aktif (atau langsung ke halaman). */
    private fun put(v: View) {
        (cur ?: root).addView(v)
    }

    /** Memulai kartu baru dengan judul. */
    private fun header(s: String) {
        val card = newCard()
        root.addView(card)
        cur = card
        val help = HELP[s]
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, if (help != null) dp(1) else dp(6))
        }
        titleRow.addView(View(this).apply {
            background = bg(C_PRIMARY, dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(18)).apply { rightMargin = dp(10) }
        })
        titleRow.addView(text(s, 16f, true, C_TEXT).apply {
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (s in COLLAPSIBLE) {
            card.tag = s
            titleRow.minimumHeight = dp(40)
            titleRow.addView(text("▾", 16f, true, C_SUB))
            card.layoutTransition = android.animation.LayoutTransition().apply { setDuration(180L) }
            titleRow.setOnClickListener {
                if (!openCards.add(s)) openCards.remove(s)
                applyCollapse(card)
            }
        }
        card.addView(titleRow)
        if (help != null) card.addView(text(help, 12f, false, C_SUB).apply { setPadding(0, 0, 0, dp(8)) })
    }

    private val openCards = mutableSetOf<String>()

    private val noActions = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?) = false
        override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
    }

    /** Mematikan fitur wajib mengetik teks acak. Menyalakan/menambah langsung. */
    private fun gateOff(isOff: Boolean, what: String, apply: () -> Unit) {
        if (!isOff) {
            apply()
            render()
        } else {
            textGate(what, { apply(); render() }, { render() })
        }
    }

    /**
     * Gerbang yang SAMA dengan keluar dari Mode Ketat: waktu tunggu, teks acak (panjang sama),
     * dan kode teman, sesuai metode yang kamu pilih.
     */
    private fun textGate(what: String, onOk: () -> Unit, onCancel: () -> Unit) {
        val waitMin = Night.scaleWait(this, if (Store.exitWaitOn(this)) Store.exitDelayMin(this) else 0)
        val textLen = Zone.requiredTextLen(this, Store.exitTextLen(this))
        val friend = FriendApproval.isSet(this)

        if (waitMin > 0) {
            if (!SafeTimer.isRunning(this, "feat")) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Butuh waktu tunggu")
                    .setMessage("Untuk $what, kamu harus menunggu " + fmtDur(waitMin) + " (sama seperti keluar Mode Ketat). Mulai permintaan sekarang? Setelah waktunya habis, ulangi aksi ini.")
                    .setPositiveButton("Mulai") { _, _ ->
                        SafeTimer.start(this, "feat")
                        Store.log(this, "Permintaan: $what")
                        onCancel()
                    }
                    .setNegativeButton("Batal") { _, _ -> onCancel() }
                    .setOnCancelListener { onCancel() }
                    .show()
                return
            }
            val left = waitMin * 60000L - SafeTimer.elapsed(this, "feat")
            if (left > 0L) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Masih menunggu")
                    .setMessage("Sisa ${left / 60000L + 1} menit. Waktu dihitung dari jam internal, jadi mengubah jam HP tidak mempercepatnya.")
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Batalkan permintaan") { _, _ -> SafeTimer.cancel(this, "feat") }
                    .setOnDismissListener { onCancel() }
                    .show()
                return
            }
        }

        val code = if (textLen > 0) Rules.randomText(textLen) else ""
        var typed = ""
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(8), dp(20), dp(8))
        col.addView(letterCard())
        col.addView(text("Untuk $what, selesaikan verifikasi yang sama dengan keluar Mode Ketat.", 13f))
        var et: EditText? = null
        if (textLen > 0) {
            col.addView(text("Ketik teks ini persis ($textLen huruf, huruf besar dan kecil dibedakan). Hanya bagian berikutnya yang ditampilkan, ketik tanpa spasi.", 12f, false, C_SUB))
            val shown = TextView(this)
            shown.textSize = 20f
            shown.typeface = Typeface.MONOSPACE
            shown.setTextColor(C_TEXT)
            shown.setPadding(dp(12), dp(12), dp(12), dp(12))
            shown.background = bg(C_FIELD, dp(10))
            shown.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            col.addView(shown)
            val counter = text("0/$textLen", 12f, true, C_SUB)
            col.addView(counter)
            val warn = text("", 12f, true, C_BAD)
            col.addView(warn)
            val box = EditText(this)
            box.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            box.minLines = 3
            box.isLongClickable = false
            box.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            box.customSelectionActionModeCallback = noActions
            if (Build.VERSION.SDK_INT >= 23) box.customInsertionActionModeCallback = noActions
            fun refresh() {
                var cp = 0
                while (cp < typed.length && cp < code.length && typed[cp] == code[cp]) cp++
                val end = Math.min(code.length, cp + 24)
                shown.text = code.substring(cp, end).chunked(4).joinToString(" ")
                counter.text = "${typed.length}/$textLen"
                warn.text = if (typed.length > cp) "Huruf ke-${cp + 1} salah. Hapus sampai huruf ke-$cp lalu ketik ulang." else ""
            }
            refresh()
            var prev = ""
            var guard = false
            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (guard) return
                    val now = s?.toString() ?: ""
                    if (now.length - prev.length > 3) {
                        guard = true
                        box.setText(prev)
                        box.setSelection(prev.length)
                        guard = false
                        toast("Ketik huruf satu per satu")
                        return
                    }
                    prev = now
                    typed = now
                    refresh()
                }
            })
            col.addView(box)
            et = box
        }
        val friendInput = EditText(this)
        if (friend) {
            col.addView(text("Masukkan juga kode teman (teman harus ada di dekatmu):", 13f))
            friendInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            col.addView(friendInput)
        }
        val sv = ScrollView(this)
        sv.addView(col)
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("Konfirmasi")
            .setView(sv)
            .setPositiveButton("Lanjut", null)
            .setNegativeButton("Batal") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()
        try {
            dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } catch (t: Throwable) {
            // abaikan
        }
        dlg.show()
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (textLen > 0 && (et?.text?.toString() ?: "") != code) {
                toast("Teks tidak cocok")
            } else if (friend && !FriendApproval.verify(this, friendInput.text.toString())) {
                toast("Kode teman salah atau terkunci")
            } else {
                SafeTimer.cancel(this, "feat")
                dlg.dismiss()
                onOk()
            }
        }
    }

    /** Kartu yang jarang diubah: tertutup sampai judulnya disentuh. */
    private val COLLAPSIBLE: Set<String> = setOf(
        "Metode penonaktifan", "Zona Ketat (lokasi)", "Aplikasi baru", "Riwayat pass",
        "Catatan perlindungan", "Browser", "Kategori situs", "🛡️ Keyword Bawaan PenjagaKetat",
        "🔗 Keyword URL", "📱 Keyword Layar", "Rekam layar (untuk memperbaiki deteksi)",
        "Cadangan otomatis", "Pengingat waktu hampir habis", "Kontrol notifikasi",
        "Teks motivasi", "Pengecualian (ALLOW)", "Per hari"
    )

    private fun applyCollapse(card: LinearLayout) {
        val key = card.tag as? String ?: return
        val open = key in openCards
        for (i in 1 until card.childCount) card.getChildAt(i).visibility = if (open) View.VISIBLE else View.GONE
        val row = card.getChildAt(0) as? LinearLayout ?: return
        (row.getChildAt(row.childCount - 1) as? TextView)?.text = if (open) "▾" else "▸"
    }

    private fun collapseAll() {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i) as? LinearLayout ?: continue
            if (c.tag is String) applyCollapse(c)
        }
    }

    private fun sectionLabel(label: String) {
        cur = null
        root.addView(text(label, 12f, true, C_SUB).apply {
            letterSpacing = 0.08f
            setPadding(dp(6), dp(10), 0, dp(6))
        })
    }

    private fun screenSummary(s: String): String = when (s) {
        "apps" -> "${Store.apps(this).size} aplikasi diblokir"
        "proteksi" -> "Aturan blokir, mode, jadwal, dan jaringan"
        "stats" -> "Pemakaian, blokir, dan hari bersih"
        "urge" -> "Dorongan itu naik lalu turun sendiri. Tunggu saja"
        "focus" -> "Sesi fokus untuk menjaga konsentrasi"
        "words" -> "Browser, kategori, kata kunci, dan fitur sosmed"
        "sched", "profiles" -> "Jadwal otomatis dan profil aturan"
        "strict" -> "Kunci pengaturan agar sulit dimatikan"
        "net" -> "Filter DNS dan kontrol notifikasi"
        else -> "Keamanan, cadangan, dan pengaturan lain"
    }

    private val HELP: Map<String, String> = mapOf(
        "Mode" to "Pilih Off (jeda), Normal, atau Ketat (terkunci). Saat Off, blokir bagian sosmed (Reels dll.) tetap aktif.",
        "Mode Ketat" to "Mengunci pengaturan agar sulit dimatikan saat godaan datang.",
        "Metode penonaktifan" to "Cara keluar dari Mode Ketat: menunggu, mengetik teks, atau kode teman.",
        "Status perlindungan" to "Cek apakah semua perlindungan diri masih berfungsi.",
        "Perlindungan diri" to "Pemulih otomatis aksesibilitas dan izin yang dibutuhkan.",
        "Lindungi aplikasi lain dari uninstall" to "Aplikasi lain yang ikut dijaga agar tidak dicopot.",
        "Kunci aplikasi" to "PIN atau kunci layar HP untuk membuka Penjaga Ketat.",
        "Persetujuan teman" to "Kode teman sebagai syarat tambahan keluar dari Mode Ketat.",
        "Teks motivasi" to "Kalimat yang muncul di layar blokir.",
        "Aplikasi baru" to "Blokir otomatis aplikasi yang baru dipasang.",
        "Browser" to "Pilih browser yang dipantau untuk blokir situs.",
        "Kategori situs" to "Blokir satu kelompok topik, mis. dewasa dan judi.",
        "Kata kunci" to "Blokir halaman yang memuat kata tertentu.",
        "VPN DNS (filter situs untuk semua aplikasi)" to "Menyaring situs di semua aplikasi lewat DNS.",
        "Kontrol notifikasi" to "Menghapus notifikasi aplikasi yang sedang diblokir.",
        "Pengingat waktu hampir habis" to "Peringatan 1 menit sebelum waktu penggunaan habis.",
        "Backup dan restore" to "Simpan atau pulihkan semua aturan, bisa ke Google Drive.",
        "Blokir bagian aplikasi sosmed" to "Tutup Shorts, Reels, Cerita, dan Pencarian tanpa memblokir aplikasinya.",
        "Rekam layar (untuk memperbaiki deteksi)" to "Membantu memperbaiki deteksi bagian aplikasi.",
        "Sekarang" to "Profil berjadwal yang sedang berjalan dan jadwal berikutnya.",
        "Per hari" to "Ringkasan jadwal Senin sampai Minggu.",
        "Profil" to "Kelompok aturan yang bisa dijadwalkan (Kerja, Tidur, dll.).",
        "Pemberitahuan" to "Notifikasi saat jadwal profil mulai dan selesai.",
        "Hari ini" to "Ringkasan pemakaian dan blokir hari ini.",
        "Mode Fokus" to "Sesi waktu tertentu yang menutup aplikasi dan situs pemicu.",
        "Pengecualian (ALLOW)" to "Aplikasi atau situs yang tidak terkena aturan Fokus.",
        "Diblokir saat Fokus" to "Daftar yang ditutup selama sesi Fokus.",
        "Akses Penggunaan" to "Izin untuk mengenali aplikasi yang sedang dibuka.",
        "Catatan perlindungan" to "Riwayat kejadian penting dan percobaan pembongkaran.",
        "Riwayat pass" to "Pemakaian pass sementara belakangan ini.",
        "Perlu tindakan" to "Perlindungan belum lengkap. Selesaikan agar Penjaga Ketat bekerja.",
        "🛡️ Keyword Bawaan PenjagaKetat" to "Daftar kata bawaan; bisa dimatikan satu per satu.",
        "🕘 Riwayat Situs" to "Situs yang baru dibuka di browser, bisa langsung diblokir.",
        "🔗 Keyword URL" to "Kata yang diperiksa di alamat situs.",
        "📱 Keyword Layar" to "Kata yang diperiksa di teks layar aplikasi."
    )

    private fun status(ok: Boolean, label: String) {
        put(text((if (ok) "✔  " else "✘  ") + label, 13f, false, if (ok) C_OK else C_BAD))
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(70, 255, 255, 255)),
                bg(C_PRIMARY, dp(14)), null
            )
            stateListAnimator = null
            elevation = 0f
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun smallButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(C_PRIMARY)
            background = bg(C_CHIP, dp(10))
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = dp(4)
                rightMargin = dp(6)
            }
            setOnClickListener { onClick() }
        }

    /** Kotak gulir dengan tinggi tetap, supaya daftar panjang tidak memanjangkan halaman. */
    private fun scrollBox(heightDp: Int, content: View): ScrollView {
        val sv = ScrollView(this)
        sv.addView(content)
        sv.background = bg(C_FIELD, dp(10))
        sv.isVerticalScrollBarEnabled = true
        sv.isScrollbarFadingEnabled = false
        sv.isNestedScrollingEnabled = true
        sv.overScrollMode = View.OVER_SCROLL_ALWAYS
        sv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)).apply {
            topMargin = dp(6)
        }
        // Selama jari menyentuh kotak, gulir halaman induk ditahan supaya
        // kotak ini yang bergulir; dilepas lagi begitu jari diangkat.
        sv.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        return sv
    }

    private fun builtinKeywordList(): View {
        val all = Store.builtinKeywords(this).sorted()
        if (all.isEmpty()) return text("Tidak ada keyword bawaan.", 12f, false, C_SUB)
        val disabled = Store.disabledBuiltinKeywords(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        for (item in all) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = text(item, 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            if (item in disabled) {
                row.addView(text("Mati", 12f, false, C_SUB).apply {
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                })
                row.addView(button("Aktifkan") {
                    Store.enableBuiltinKeyword(this, item)
                    Store.addUrlKeyword(this, item)
                    Store.addScreenKeyword(this, item)
                    render()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
            } else {
                row.addView(text("Aktif", 12f, true, C_OK).apply {
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                })
                row.addView(text("✕", 15f, true, C_BAD).apply {
                    setPadding(dp(14), dp(8), dp(6), dp(8))
                    setOnClickListener {
                        if (Store.strictOn(this@MainActivity)) {
                            toast("Mode Ketat aktif: tidak bisa mengubah keyword bawaan")
                        } else {
                            gateOff(true, "mematikan keyword bawaan") { Store.disableBuiltinKeyword(this@MainActivity, item) }
                        }
                    }
                })
            }
            col.addView(row)
        }
        return scrollBox(240, col)
    }

    private fun chipList(items: List<String>, onRemove: (String) -> Unit): View {
        if (items.isEmpty()) return text("Belum ada.", 12f, false, C_SUB)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(10), dp(4), dp(10), dp(4))
        for (item in items) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(text(item, 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(text("✕", 15f, true, C_BAD).apply {
                setPadding(dp(14), dp(8), dp(6), dp(8))
                setOnClickListener { onRemove(item) }
            })
            col.addView(row)
        }
        return scrollBox(180, col)
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

    // ---------- Navigasi 4 tab ----------
    private val TABS = listOf(
        Triple("🏠", "Beranda", "home"),
        Triple("🛡️", "Proteksi", "proteksi"),
        Triple("📊", "Statistik", "stats"),
        Triple("⚙️", "Setelan", "more")
    )

    private fun tabOf(s: String): Int = when (s) {
        "home", "urge" -> 0
        "proteksi", "strict", "apps", "words", "profiles", "sched", "net", "focus" -> 1
        "stats" -> 2
        else -> 3
    }

    private fun isTabRoot(s: String): Boolean = s == "home" || s == "proteksi" || s == "stats" || s == "more"

    private fun parentOf(s: String): String = when {
        s == "urge" -> "home"
        isTabRoot(s) -> "home"
        tabOf(s) == 1 -> "proteksi"
        else -> "home"
    }

    private fun buildNav(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(6), dp(6), dp(6), dp(6))
        bar.setBackgroundColor(C_CARD)
        bar.elevation = dp(8).toFloat()
        for ((icon, label, target) in TABS) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.tag = target
            item.setPadding(0, dp(4), 0, dp(4))
            item.addView(TextView(this).apply { text = icon; textSize = 20f; gravity = Gravity.CENTER })
            item.addView(TextView(this).apply { text = label; textSize = 11f; gravity = Gravity.CENTER; tag = "lbl" })
            item.setOnClickListener { goScreen(target) }
            bar.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    private fun updateNav() {
        navBar.setBackgroundColor(C_CARD)
        val sel = tabOf(screen)
        for (i in 0 until navBar.childCount) {
            val item = navBar.getChildAt(i) as LinearLayout
            val on = i == sel
            val lbl = item.getChildAt(1) as TextView
            lbl.setTextColor(if (on) C_PRIMARY else C_SUB)
            lbl.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            item.getChildAt(0).alpha = if (on) 1f else 0.55f
            item.background = if (on) bg(C_CHIP, dp(14)) else null
        }
    }

    /** Statistik dihitung di thread latar agar layar tidak macet; tampilan dirender ulang setelah selesai. */
    private fun loadStatsAsync() {
        if (statsLoading) return
        statsLoading = true
        val app = applicationContext
        Thread {
            var ok = true
            try {
                Dashboard.get(app, true)
            } catch (t: Throwable) {
                ok = false
            }
            runOnUiThread {
                statsLoading = false
                statsFailed = !ok
                if (!isFinishing && !isDestroyed && (screen == "stats" || screen == "home")) render()
            }
        }.start()
    }

    private fun render() {
        if (AppLock.needsAuth(this)) {
            showLockScreen()
            return
        }
        navBar.visibility = View.VISIBLE
        updateNav()
        val keepY = scroll.scrollY
        root.removeAllViews()
        cur = null
        gateShown = false
        val strict = Store.strictOn(this)
        val acc = accessibilityOn()
        val adm = adminOn()
        if (screen == "home") {
            renderHome(strict, acc, adm)
        } else {
            topBar(screenTitle(screen))
            when (screen) {
                "strict" -> {
                    renderStrictStatus(acc, adm)
                    renderStrict(strict, acc && adm)
                    renderProtection(strict)
                    renderBypassGuard(strict)
                    renderNsfwAi(strict)
                    renderNight(strict)
                }
                "apps" -> {
                    renderApps(strict)
                    renderPauseApps(strict)
                }
                "urge" -> renderUrge()
                "words" -> {
                    renderBrowsers(strict)
                    renderCategories(strict)
                    renderWords(strict)
                    renderFeeds(strict)
                }
                "profiles" -> renderProfiles(strict)
                "net" -> {
                    renderVpn(strict)
                    renderNotif()
                }
                "proteksi" -> renderProteksi()
                "stats" -> renderDashboard(strict)
                "focus" -> renderFocus(strict)
                "sched" -> renderSchedule(strict)
                else -> {
                    sectionLabel("KEAMANAN")
                    renderAppLock(strict)
                    renderUninstallGate(strict)
                    renderFriend()
                    renderLetter(strict)
                    renderProtected(strict)
                    renderNewApps(strict)
                    sectionLabel("CADANGAN")
                    renderBackup(strict)
                    sectionLabel("DETEKSI & TAMPILAN")
                    renderSelfTest()
                    renderMotivation(strict)
                    sectionLabel("RIWAYAT")
                    renderLog()
                }
            }
        }
        collapseAll()
        val same = lastScreen == screen
        lastScreen = screen
        scroll.post { scroll.scrollTo(0, if (same) keepY else 0) }
        // Layar teks acak: blokir screenshot dan rekam layar (juga bantuan OCR/AI berbasis tangkapan layar).
        try {
            if (gateShown) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } catch (t: Throwable) {
            // abaikan
        }
    }

    private fun screenTitle(s: String): String = when (s) {
        "strict" -> "Mode Ketat dan status"
        "apps" -> "Aplikasi diblokir"
        "words" -> "Situs dan kata kunci"
        "profiles" -> "Profil"
        "sched" -> "Jadwal & profil"
        "net" -> "VPN DNS dan notifikasi"
        "proteksi" -> "Proteksi"
        "stats" -> "Statistik"
        "focus" -> "Mode Fokus"
        "urge" -> "Dorongan"
        else -> "Setelan"
    }

    private fun goScreen(s: String) {
        screen = s
        render()
    }

    private fun topBar(title: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, 0, 0, dp(10))
        if (!isTabRoot(screen)) {
            val back = TextView(this)
            back.text = "←"
            back.textSize = 22f
            back.setTextColor(C_PRIMARY)
            back.gravity = Gravity.CENTER
            back.background = bg(C_CHIP, dp(22))
            back.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            back.setOnClickListener { goScreen(parentOf(screen)) }
            row.addView(back)
        }
        row.addView(text(title, 22f, true).apply {
            setPadding(if (isTabRoot(screen)) dp(4) else dp(14), 0, 0, 0)
        })
        row.setPadding(0, dp(2), 0, dp(6))
        root.addView(row)
        root.addView(text(screenSummary(screen), 13f, false, C_SUB).apply { setPadding(dp(4), 0, 0, dp(10)) })
        if (Store.strictOn(this)) {
            root.addView(text("🔒  Mode Ketat aktif · pengaturan penting terkunci", 12f, true, C_BAD).apply {
                background = bg(if (night) Color.parseColor("#3A1F22") else Color.parseColor("#FDECEC"), dp(12))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            })
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen != "home") goScreen(parentOf(screen)) else super.onBackPressed()
    }

    private fun tile(icon: String, title: String, sub: String, color: Int, target: String): View {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.setPadding(dp(14), dp(14), dp(14), dp(14))
        t.background = bg(color, dp(18))
        t.elevation = dp(2).toFloat()
        t.addView(TextView(this).apply {
            text = icon
            textSize = 26f
        })
        t.addView(text(title, 15f, true, Color.WHITE))
        t.addView(text(sub, 11f, false, Color.argb(225, 255, 255, 255)))
        t.setOnClickListener { goScreen(target) }
        t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }
        return t
    }

    private fun renderHome(strict: Boolean, acc: Boolean, adm: Boolean) {
        val banner = LinearLayout(this)
        banner.orientation = LinearLayout.HORIZONTAL
        banner.gravity = Gravity.CENTER_VERTICAL
        banner.setPadding(dp(18), dp(18), dp(18), dp(18))
        banner.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(if (night) "#274C80" else "#1E3A5F"), Color.parseColor(if (night) "#3B76C9" else "#2F6DB5"))
        ).apply { cornerRadius = dp(24).toFloat() }
        banner.elevation = if (night) 0f else dp(2).toFloat()
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(12)
        banner.layoutParams = lp
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_logo)
        logo.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        banner.addView(logo)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, 0, 0)
        col.addView(text("Penjaga Ketat", 22f, true, Color.WHITE))
        col.addView(text(modeLabel(), 13f, false, Color.argb(235, 255, 255, 255)))
        val protectionOk = acc && adm
        col.addView(TextView(this).apply {
            text = if (protectionOk) "●  Perlindungan lengkap" else "●  Perlu tindakan"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (protectionOk) Color.parseColor("#9BE7B8") else Color.parseColor("#FFD27A"))
            background = bg(Color.argb(46, 255, 255, 255), dp(20))
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })
        banner.addView(col)
        root.addView(banner)
        renderStatTiles()
        renderModeSwitch()

        if (!acc || !adm) {
            header("Perlu tindakan")
            if (!acc) {
                status(false, "Aksesibilitas Penjaga Ketat belum aktif")
                put(button("Buka pengaturan Aksesibilitas") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                })
            }
            if (!adm) {
                status(false, "Perlindungan uninstall belum aktif")
                put(button("Aktifkan perlindungan uninstall") { requestAdmin() })
            }
        }

        renderFocusLine()

        root.addView(button("🆘  Lagi tergoda? Tekan di sini") {
            urgeEnd = 0L
            goScreen("urge")
        }.apply {
            setTextColor(C_BAD)
            background = bg(C_CHIP, dp(14))
        })

    }

    private fun renderProteksi() {
        val profiles = ProfileStore.load(this)
        val activeP = profiles.count { ProfileEngine.isActive(this, it) }
        val wordCount = Store.keywords(this).size + Store.domains(this).size
        val cats = Categories.enabled(this).size
        homeGroup("ATURAN BLOKIR")
        homeRow("📱", "Aplikasi", "Blokir, batasi waktu, atau beri timer tunggu.", "${Store.apps(this).size} diblokir", Color.parseColor("#1565C0"), "apps")
        homeRow("🌐", "Situs & kata", "Situs, kata kunci, kategori, dan bagian aplikasi sosmed.", "$wordCount aturan • $cats kategori", Color.parseColor("#6A1B9A"), "words")
        homeRow("🗓️", "Jadwal & profil", "Aturan otomatis per hari dan jam (Kerja, Tidur, dll.).", scheduleTileLabel() + " • $activeP profil aktif", Color.parseColor("#00796B"), "sched")
        homeGroup("MODE")
        homeRow("🎯", "Mode Fokus", "Sesi waktu yang menutup aplikasi dan situs pemicu.", focusTileLabel(), Color.parseColor("#AD1457"), "focus")
        homeRow("🛡️", "Mode Ketat & status", "Kunci pengaturan dan cek perlindungan diri.", modeLabel(), Color.parseColor("#C62828"), "strict")
        homeRow("🔌", "VPN & notifikasi", "Filter situs lewat DNS dan kontrol notifikasi.", if (VpnStatus.running) "VPN DNS aktif" else if (Store.vpnOn(this)) "VPN DNS: " + VpnStatus.state.label else "VPN DNS mati", Color.parseColor("#2E7D32"), "net")

    }

    /** Warna aksen; di mode gelap dibuat lebih terang supaya terbaca. */
    private fun accent(color: Int): Int =
        if (night) Color.argb(255, Math.min(255, Color.red(color) + 70), Math.min(255, Color.green(color) + 70), Math.min(255, Color.blue(color) + 70))
        else color

    private fun statTile(value: String, label: String, color: Int, first: Boolean, last: Boolean, target: String): View {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.setPadding(dp(8), dp(14), dp(8), dp(12))
        t.background = bg(C_CARD, dp(18)).apply { setStroke(dp(1), C_CHIP) }
        t.elevation = if (night) 0f else dp(1).toFloat()
        t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(if (first) 0 else dp(4), 0, if (last) 0 else dp(4), 0)
        }
        t.addView(text(value, 20f, true, accent(color)).apply { gravity = Gravity.CENTER })
        t.addView(text(label, 11f, false, C_SUB).apply { gravity = Gravity.CENTER })
        t.setOnClickListener { goScreen(target) }
        return t
    }

    /** Tiga angka ringkas di bawah banner: pemakaian, blokir hari ini, jumlah aplikasi diblokir. */
    private fun renderStatTiles() {
        var usage = "—"
        var blocks = "0"
        try {
            val s = Dashboard.cached()
            if (s != null) {
                usage = if (s.usageAccess) Dashboard.dur(s.todayMs) else "Izin?"
                blocks = s.blocksToday.toString()
            }
            if (!Dashboard.isFresh() && !statsFailed) loadStatsAsync()
        } catch (t: Throwable) {
            // tampilkan nilai bawaan
        }
        val apps = try { Store.apps(this).size.toString() } catch (t: Throwable) { "0" }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        row.addView(statTile(usage, "Pemakaian hari ini", Color.parseColor("#1565C0"), true, false, "stats"))
        row.addView(statTile(blocks, "Diblokir hari ini", Color.parseColor("#EF6C00"), false, false, "stats"))
        row.addView(statTile(apps, "Aplikasi diblokir", Color.parseColor("#6A1B9A"), false, true, "apps"))
        root.addView(row)
    }

    /** Kartu kecil hanya saat Mode Fokus berjalan. */
    private fun renderFocusLine() {
        if (!Focus.running(this)) return
        val left = Focus.leftMs(this) / 60000L + 1L
        val card = newCard()
        card.addView(text(
            "🎯  Mode Fokus " + (if (Store.pausedNow(this)) "dijeda" else "aktif") + " • sisa sekitar $left menit",
            13f, true, C_PRIMARY
        ))
        root.addView(card)
    }

    private fun homeGroup(label: String) {
        root.addView(text(label, 11f, true, C_SUB).apply {
            letterSpacing = 0.08f
            setPadding(dp(6), dp(10), 0, dp(4))
        })
    }

    private fun homeRow(icon: String, title: String, help: String, status: String, color: Int, target: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(10), dp(14), dp(10))
        row.background = bg(C_CARD, dp(18)).apply { setStroke(dp(1), C_CHIP) }
        try {
            val tv = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                row.foreground = getDrawable(tv.resourceId)
                row.clipToOutline = true
            }
        } catch (t: Throwable) {
            // tanpa efek sentuh
        }
        row.elevation = if (night) 0f else dp(1).toFloat()
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) }
        row.addView(TextView(this).apply {
            text = icon
            textSize = 20f
            gravity = Gravity.CENTER
            background = bg(Color.argb(if (night) 70 else 38, Color.red(color), Color.green(color), Color.blue(color)), dp(14))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        })
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(text(title, 15f, true))
        col.addView(text(help, 12f, false, C_SUB))
        col.addView(text(status, 12f, true, if (night) Color.argb(255, Math.min(255, Color.red(color) + 70), Math.min(255, Color.green(color) + 70), Math.min(255, Color.blue(color) + 70)) else color))
        row.addView(col)
        row.addView(text("›", 22f, true, C_SUB))
        row.setOnClickListener { goScreen(target) }
        root.addView(row)
    }

    private fun requestAdmin() {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Agar Penjaga Ketat tidak bisa dicopot saat Mode Ketat aktif.")
        startActivity(i)
    }

    /** Saklar per fitur blokir. Mati = fitur itu tidak memblokir. Tidak bisa dimatikan saat Mode Ketat aktif; setelah Mode Ketat mati, wajib lewat gerbang yang sama dengan keluar Mode Ketat. */
    private fun renderFeatureSwitches(strict: Boolean) {
        header("Saklar per fitur")
        put(text("Matikan satu fitur saja tanpa mematikan Mode Ketat. Menyalakan langsung. Saat Mode Ketat aktif, saklar tidak bisa dimatikan: nonaktifkan Mode Ketat dulu, lalu matikan saklar lewat gerbang (waktu tunggu, teks acak, atau kode teman) yang sama.", 12f, false, C_SUB))
        val items = listOf(
            Triple("apps", "Blokir aplikasi", "Daftar aplikasi diblokir (termasuk notifikasinya)"),
            Triple("keywords", "Kata kunci", "Kata kunci layar dan URL"),
            Triple("sites", "Situs", "Daftar situs dan VPN DNS untuk situs itu"),
            Triple("social", "Bagian sosmed", "Reels, Shorts, Cerita, dan sejenisnya")
        )
        for ((key, label, hint) in items) {
            val cb = CheckBox(this)
            cb.text = label + " — " + hint
            cb.isChecked = Store.featureOn(this, key)
            cb.setOnClickListener {
                val v = cb.isChecked
                if (v) {
                    Store.setFeatureOn(this, key, true)
                    render()
                } else if (strict) {
                    cb.isChecked = true
                    toast("Mode Ketat aktif: tidak bisa dimatikan")
                } else {
                    cb.isChecked = true
                    textGate("mematikan fitur $label", { Store.setFeatureOn(this, key, false); render() }, { render() })
                }
            }
            put(cb)
        }
    }

    private fun renderStrictStatus(acc: Boolean, adm: Boolean) {
        header("Status")
        status(acc, "Aksesibilitas Penjaga Ketat aktif")
        status(adm, "Perlindungan uninstall (Administrator perangkat) aktif")
        if (!acc) put(button("Buka pengaturan Aksesibilitas") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (!adm) put(button("Aktifkan perlindungan uninstall") { requestAdmin() })
    }

    private fun modeLabel(): String {
        val raw = Store.strictRaw(this)
        return when {
            Store.pausedNow(this) -> "Mode OFF (jeda)"
            raw && Zone.active(this) -> "Mode KETAT (Zona Ketat)"
            raw && Store.strictOn(this) -> "Mode KETAT aktif"
            raw -> "Mode Ketat terjadwal"
            else -> "Mode Normal"
        }
    }

    private fun fmtDur(min: Int): String = when {
        min <= 0 -> "-"
        min < 60 -> "$min menit"
        min < 1440 -> (min / 60).toString() + " jam" + (if (min % 60 != 0) " " + (min % 60) + " menit" else "")
        else -> (min / 1440).toString() + " hari"
    }

    private fun pill(label: String, selected: Boolean, color: Int, onClick: () -> Unit): TextView =
        pillImpl(label, selected, color, false, onClick)

    private fun segPill(label: String, selected: Boolean, color: Int, onClick: () -> Unit): TextView =
        pillImpl(label, selected, color, true, onClick)

    private fun pillImpl(label: String, selected: Boolean, color: Int, seg: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 14f
        t.setTypeface(null, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        t.setTextColor(if (selected) Color.WHITE else C_SUB)
        t.background = bg(if (selected) color else if (seg) Color.TRANSPARENT else C_CHIP, dp(if (seg) 14 else 12))
        if (seg && selected) t.elevation = dp(2).toFloat()
        t.setPadding(dp(8), dp(12), dp(8), dp(12))
        t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
        t.setOnClickListener { onClick() }
        return t
    }

    private fun renderModeSwitch() {
        val raw = Store.strictRaw(this)
        val paused = Store.pausedNow(this)
        header("Mode")
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.background = bg(C_CHIP, dp(18))
        row.setPadding(dp(3), dp(3), dp(3), dp(3))
        row.addView(segPill("Off", paused, Color.parseColor("#78909C")) {
            if (raw) toast("Mode Ketat aktif. Keluar dulu lewat metode penonaktifan.") else pauseDialog()
        })
        row.addView(segPill("Normal", !paused && !raw, C_PRIMARY) {
            if (raw) toast("Mode Ketat aktif. Keluar dulu lewat metode penonaktifan.") else {
                Store.pauseStop(this)
                render()
            }
        })
        row.addView(segPill("Ketat", raw, C_BAD) {
            if (screen != "strict") goScreen("strict") else toast("Atur metode penonaktifan di bawah, lalu tekan Aktifkan Mode Ketat.")
        })
        put(row)
        val desc = when {
            paused -> {
                val left = Store.pauseLeftMin(this)
                "OFF: kunci Mode Ketat mati, tetapi blokir aplikasi, kata kunci, dan situs TETAP aktif. Mematikannya satu per satu wajib teks acak. " + (if (left >= 0L) "Berakhir sekitar $left menit lagi." else "Berlaku sampai kamu memilih Normal.")
            }
            raw && Zone.active(this) -> "KETAT (Zona Ketat): kamu di dalam zona. Mematikannya wajib mengetik teks acak ${Zone.MIN_TEXT} huruf."
            raw && Store.strictOn(this) -> "KETAT aktif: pengaturan terkunci dan sulit dimatikan."
            raw -> "KETAT terjadwal: sedang di luar jadwal, berperilaku seperti Normal."
            else -> "NORMAL: blokir bekerja, pengaturan masih bebas diubah."
        }
        put(text(desc, 12f, false, C_SUB))
        if (paused && Store.pauseLeftMin(this) >= 0L) handler.postDelayed({ render() }, 30000L)
    }

    private fun pauseDialog() {
        val labels = arrayOf("15 menit", "30 menit", "1 jam", "2 jam", "4 jam", "Sampai saya nyalakan lagi")
        val mins = intArrayOf(15, 30, 60, 120, 240, 0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Mode Off: kunci Ketat mati, blokir tetap aktif")
            .setItems(labels) { _, i ->
                Store.pauseStart(this, mins[i])
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun methodRow(title: String, summary: String, enabled: Boolean, onClick: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(text(title, 14f, true, if (enabled) C_TEXT else C_SUB))
        col.addView(text(summary, 12f, false, C_SUB))
        row.addView(col)
        row.addView(text("›", 22f, true, C_SUB))
        if (enabled) row.setOnClickListener { onClick() }
        put(row)
        put(View(this).apply {
            setBackgroundColor(C_CHIP)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })
    }

    private fun renderStrict(strict: Boolean, ready: Boolean) {
        val raw = Store.strictRaw(this)
        renderModeSwitch()
        renderFeatureSwitches(strict)
        header("Metode penonaktifan")
        put(text(
            if (raw) "Metode di bawah berlaku untuk keluar dari Mode Ketat dan terkunci selama Mode Ketat aktif."
            else "Pilih bagaimana Mode Ketat dapat dinonaktifkan. Semua metode yang dipilih harus dipenuhi.",
            12f, false, C_SUB
        ))
        val editable = !raw
        val exp = Store.expireMin(this)
        methodRow("Waktu kedaluwarsa", if (exp > 0) "Nonaktif otomatis setelah " + fmtDur(exp) else "Tidak ada", editable) { dialogExpire() }
        methodRow("Kode QR", "Belum tersedia", false) {}
        val sch = Store.strictSchedules(this)
        methodRow(
            "Dijadwalkan",
            if (sch.isEmpty()) "Tidak ada (aktif terus)" else "Aktif hanya: " + sch.joinToString("; ") { "${fmt(it.start)}-${fmt(it.end)} ${dayNames(it.days)}" },
            editable
        ) { dialogStrictSchedule() }
        val tl = Store.exitTextLen(this)
        methodRow("Teks acak", if (tl > 0) "$tl huruf" else "Mati", editable) { dialogTextLen() }
        methodRow(
            "Jeda",
            if (Store.exitWaitOn(this)) "Tunggu " + fmtDur(Store.exitDelayMin(this)) else "Mati",
            editable
        ) { dialogWait() }
        methodRow(
            "Persetujuan teman",
            if (FriendApproval.isSet(this)) "Kode teman sudah diatur" else "Belum diatur (atur di Lainnya atau ketuk di sini)",
            true
        ) { friendDialog() }

        renderZoneSection(strict)
        header("Mode Ketat")
        if (Zone.active(this)) {
            put(text("Kamu berada di Zona Ketat: untuk keluar dari Mode Ketat wajib mengetik teks acak ${Zone.MIN_TEXT} huruf (selain metode lain yang sudah diatur). Keluar dari radius zona mengembalikan Mode Ketat ke pengaturan semula.", 13f, true, C_BAD))
        }
        if (!raw) {
            put(text("Mati. Semua pengaturan bisa diubah bebas.", 13f))
            put(button("Aktifkan Mode Ketat") {
                if (!ready) {
                    toast("Aktifkan Aksesibilitas dan perlindungan uninstall dulu")
                } else if (!Store.exitWaitOn(this) && Store.exitTextLen(this) == 0 && !FriendApproval.isSet(this)) {
                    toast("Pilih minimal satu metode keluar: Jeda, Teks acak, atau Persetujuan teman")
                } else {
                    Store.setStrict(this, true)
                    SafeTimer.cancel(this, "exit")
                    Store.log(this, "Mode Ketat diaktifkan")
                    WatchdogService.start(this)
                    render()
                }
            })
            return
        }
        put(text(
            if (strict) "AKTIF. Daftar blokir hanya bisa ditambah, tidak bisa dikurangi. Pengaturan lain terkunci. Aplikasi tidak bisa dicopot, dan halaman Pengaturan yang menyebut Penjaga Ketat diblokir."
            else "Terjadwal: sedang di luar jadwal, jadi pengaturan bebas diubah sementara.",
            13f, true, C_BAD
        ))
        put(letterCard())
        val waitMin = Night.scaleWait(this, if (Store.exitWaitOn(this)) Store.exitDelayMin(this) else 0)
        val textLen = Zone.requiredTextLen(this, Store.exitTextLen(this))
        val friend = FriendApproval.isSet(this)
        if (!SafeTimer.isRunning(this, "exit")) {
            put(button("Minta nonaktifkan" + (if (waitMin > 0) " (tunggu " + fmtDur(waitMin) + ")" else "")) {
                SafeTimer.start(this, "exit")
                Store.log(this, "Permintaan keluar Mode Ketat dimulai")
                gateText = null
                gateTyped = ""
                render()
            })
            return
        }
        val left = waitMin * 60000L - SafeTimer.elapsed(this, "exit")
        if (left > 0) {
            put(text("Permintaan nonaktif berjalan. Sisa ${left / 60000L + 1} menit. Waktu dihitung dari jam internal, jadi mengubah jam HP tidak mempercepatnya.", 14f, true))
            put(button("Batalkan permintaan") {
                SafeTimer.cancel(this, "exit")
                render()
            })
            handler.postDelayed({ render() }, 30000L)
            return
        }
        var code = ""
        var input: EditText? = null
        if (textLen > 0) {
            if (gateText == null || gateText!!.length != textLen) {
                gateText = Rules.randomText(textLen)
                gateTyped = ""
            }
            code = gateText!!
            gateShown = true
            put(text("Ketik teks ini persis ($textLen huruf, huruf besar dan kecil dibedakan). Hanya bagian yang perlu diketik berikutnya yang ditampilkan, ketik tanpa spasi. Layar ini tidak bisa di-screenshot.", 13f))
            val shown = TextView(this)
            shown.textSize = 20f
            shown.typeface = Typeface.MONOSPACE
            shown.setTextColor(C_TEXT)
            shown.setPadding(dp(12), dp(12), dp(12), dp(12))
            shown.background = bg(C_FIELD, dp(10))
            shown.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            put(shown)
            val counter = text("0/$textLen", 12f, true, C_SUB)
            put(counter)
            val warn = text("", 12f, true, C_BAD)
            put(warn)
            val et = EditText(this)
            et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            et.minLines = 3
            et.isLongClickable = false
            et.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val noMenu = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
                override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
                override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?) = false
                override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
            }
            et.customSelectionActionModeCallback = noMenu
            et.customInsertionActionModeCallback = noMenu
            fun refresh() {
                var cp = 0
                while (cp < gateTyped.length && cp < code.length && gateTyped[cp] == code[cp]) cp++
                val end = Math.min(code.length, cp + 24)
                shown.text = code.substring(cp, end).chunked(4).joinToString(" ")
                counter.text = "${gateTyped.length}/$textLen"
                warn.text = if (gateTyped.length > cp) "Huruf ke-${cp + 1} salah. Hapus sampai huruf ke-$cp lalu ketik ulang." else ""
            }
            et.setText(gateTyped)
            refresh()
            var prev = gateTyped
            var guard = false
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (guard) return
                    val now = s?.toString() ?: ""
                    // Tolak tempel/isi otomatis: lebih dari 3 huruf sekaligus.
                    if (now.length - prev.length > 3) {
                        guard = true
                        et.setText(prev)
                        et.setSelection(prev.length)
                        guard = false
                        toast("Ketik huruf satu per satu")
                        return
                    }
                    prev = now
                    gateTyped = now
                    refresh()
                }
            })
            put(et)
            input = et
        }
        val friendInput = EditText(this)
        if (friend) {
            put(text("Masukkan juga kode teman (teman harus ada di dekatmu):", 13f))
            friendInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            put(friendInput)
            val lk = FriendApproval.lockedMinutes(this)
            if (lk > 0L) put(text("Terkunci karena salah 3 kali. Coba lagi $lk menit lagi.", 12f, true, C_BAD))
        }
        if (textLen == 0 && !friend) put(text("Waktu tunggu selesai.", 13f))
        put(button("Konfirmasi nonaktifkan") {
            if (textLen > 0 && (input?.text?.toString() ?: "") != code) {
                toast("Teks tidak cocok")
                return@button
            }
            if (friend && !FriendApproval.verify(this, friendInput.text.toString())) {
                toast("Kode teman salah atau terkunci")
                return@button
            }
            Store.setStrict(this, false)
            Zone.onUserExit(this)
            SafeTimer.cancel(this, "exit")
            Store.log(this, "Mode Ketat dinonaktifkan")
            gateText = null
            gateTyped = ""
            render()
        })
        put(button("Batalkan permintaan") {
            SafeTimer.cancel(this, "exit")
            gateText = null
            gateTyped = ""
            render()
        })
    }


    private fun renderZoneSection(strict: Boolean) {
        Zone.update(this)
        header("Zona Ketat (lokasi)")
        put(text(
            "Di dalam zona, Mode Ketat menyala otomatis, dan bila sudah ketat menjadi lebih ketat: hanya bisa dimatikan dengan teks acak ${Zone.MIN_TEXT} huruf. Keluar dari zona mengembalikan Mode Ketat seperti semula. Perlu izin lokasi \"Izinkan sepanjang waktu\" dan GPS menyala.",
            12f, false, C_SUB
        ))
        val zs = Zone.zones(this)
        val canEdit = !Store.strictOn(this)
        if (zs.isEmpty()) put(text("Belum ada zona.", 13f))
        for ((i, r) in zs) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(text(
                "Zona $i: ${String.format(java.util.Locale.US, "%.5f, %.5f", r.lat, r.lon)} (radius ${r.radius} m)",
                13f
            ).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            if (canEdit) row.addView(text("Hapus", 13f, true, C_BAD).apply {
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener {
                    Zone.remove(this@MainActivity, i)
                    render()
                }
            })
            put(row)
        }
        if (zs.isNotEmpty()) {
            val d = Zone.distanceToEdge(this)
            put(text(
                when {
                    Zone.active(this) -> "Status: di DALAM zona, Zona Ketat berlaku."
                    Zone.inside(this) && Zone.overridden(this) -> "Status: di dalam zona, tetapi dilewati sampai kamu keluar radius."
                    d == null -> "Status: posisi belum diketahui (cek izin lokasi dan GPS)."
                    else -> "Status: di luar zona, sekitar ${Math.max(0, d)} m dari tepi zona terdekat."
                },
                12f, true, C_SUB
            ))
            if (!canEdit) put(text("Zona tidak bisa dihapus saat Mode Ketat berlaku. Menambah zona tetap boleh.", 11f, false, C_SUB))
        }
        if (zs.size < Zone.MAX_ZONES) put(button("Tambah zona") { dialogZone() })
    }

    private fun dialogZone() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))
        val inType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        val latF = EditText(this).apply { hint = "Lintang, mis. -7.290500"; inputType = inType }
        val lonF = EditText(this).apply { hint = "Bujur, mis. 112.734000"; inputType = inType }
        val status = text(
            if (LocationTracker.hasPermission(this)) "" else "Izin lokasi belum diberikan. Ketuk tombol di bawah untuk memberi izin.",
            11f, false, C_SUB
        )
        var zoneMap: MapPicker.Handle? = null
        val useBtn = Button(this).apply { text = "Pakai lokasi saya sekarang" }
        useBtn.setOnClickListener {
            if (!LocationTracker.hasPermission(this@MainActivity)) {
                askLocationPermission()
            } else {
                status.text = "Mencari lokasi..."
                LocationTracker.fetchNow(this@MainActivity) { l ->
                    if (l == null) {
                        status.text = "Lokasi belum didapat. Nyalakan GPS lalu coba lagi."
                    } else {
                        latF.setText(String.format(java.util.Locale.US, "%.6f", l.latitude))
                        lonF.setText(String.format(java.util.Locale.US, "%.6f", l.longitude))
                        zoneMap?.center(l.latitude, l.longitude)
                        status.text = "Lokasi didapat (akurasi sekitar ${l.accuracy.toInt()} m)."
                    }
                }
            }
        }
        box.addView(useBtn)
        box.addView(text("Ketuk peta untuk menaruh titik zona (peta butuh internet).", 11f, false, C_SUB))
        zoneMap = MapPicker.attach(this, box, latF, lonF, 200, dp(280))
        box.addView(latF)
        box.addView(lonF)
        box.addView(status)
        val radii = intArrayOf(100, 150, 200, 300, 500, 1000)
        val radiusLabel = text("", 12f)
        val seek = android.widget.SeekBar(this).apply { max = radii.size - 1; progress = 2 }
        radiusLabel.text = "Radius: ${radii[seek.progress]} m"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                radiusLabel.text = "Radius: ${radii[p]} m"
                zoneMap?.setRadius(radii[p])
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        box.addView(radiusLabel)
        box.addView(seek)
        android.app.AlertDialog.Builder(this)
            .setTitle("Tambah Zona Ketat")
            .setView(ScrollView(this).apply { addView(box) })
            .setOnDismissListener { zoneMap?.destroy() }
            .setPositiveButton("Simpan") { _, _ ->
                val lat = latF.text.toString().trim().toDoubleOrNull()
                val lon = lonF.text.toString().trim().toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    toast("Lokasi tidak valid: isi lintang dan bujur, atau pakai lokasi saya sekarang.")
                } else if (!Zone.add(this, lat, lon, radii[seek.progress])) {
                    toast("Jumlah zona sudah penuh")
                } else {
                    if (!LocationTracker.hasPermission(this) || !LocationTracker.hasBackground(this)) askLocationPermission()
                    Zone.update(this)
                    render()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun dialogExpire() {
        val labels = arrayOf("Tidak ada (manual)", "1 jam", "3 jam", "6 jam", "12 jam", "1 hari", "3 hari", "7 hari", "14 hari", "30 hari")
        val mins = intArrayOf(0, 60, 180, 360, 720, 1440, 4320, 10080, 20160, 43200)
        android.app.AlertDialog.Builder(this)
            .setTitle("Waktu kedaluwarsa")
            .setItems(labels) { _, i ->
                Store.setExpireMin(this, mins[i])
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun dialogStrictSchedule() {
        val sch = Store.strictSchedules(this)
        val msg = if (sch.isEmpty()) "Belum ada jadwal: Mode Ketat aktif terus."
        else sch.joinToString("\n") { "${fmt(it.start)}-${fmt(it.end)} ${dayNames(it.days)}" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Jadwal Mode Ketat")
            .setMessage(msg + "\n\nDi luar jadwal, Mode Ketat berperilaku seperti Normal.")
            .setPositiveButton("Tambah jadwal") { _, _ ->
                editSchedule { s ->
                    Store.setStrictSchedules(this, Store.strictSchedules(this) + s)
                    render()
                }
            }
            .setNeutralButton("Hapus semua") { _, _ ->
                Store.setStrictSchedules(this, emptyList())
                render()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun dialogTextLen() {
        val lens = intArrayOf(0, 10, 50, 100, 200, 500, 600, 2000)
        val labels = lens.map { if (it == 0) "Mati (tanpa teks acak)" else "$it huruf" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Panjang teks acak")
            .setItems(labels) { _, i ->
                Store.setExitTextLen(this, lens[i])
                gateText = null
                gateTyped = ""
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun dialogWait() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))
        val cb = CheckBox(this)
        cb.text = "Pakai waktu tunggu"
        cb.isChecked = Store.exitWaitOn(this)
        box.addView(cb)
        box.addView(text("Menit tunggu (10 sampai 1440)", 12f))
        val f = numberField(Store.exitDelayMin(this))
        box.addView(f)
        android.app.AlertDialog.Builder(this)
            .setTitle("Jeda")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                Store.setExitWaitOn(this, cb.isChecked)
                Store.setExitDelayMin(this, (f.text.toString().toIntOrNull() ?: 60).coerceIn(10, 1440))
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---- Kunci aplikasi ----
    private fun showLockScreen() {
        navBar.visibility = View.GONE
        root.removeAllViews()
        cur = null
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_logo)
        logo.layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(60)
        }
        root.addView(logo)
        root.addView(text("Penjaga Ketat terkunci", 20f, true).apply { gravity = Gravity.CENTER })
        if (AppLock.mode(this) == 2) {
            root.addView(text("Gunakan kunci layar HP untuk membuka.", 13f, false, C_SUB).apply { gravity = Gravity.CENTER })
            root.addView(button("Buka dengan kunci layar") { askCredential() })
            if (!credAsked) {
                credAsked = true
                askCredential()
            }
        } else {
            root.addView(text("Masukkan PIN", 13f, false, C_SUB).apply { gravity = Gravity.CENTER })
            val pin = EditText(this)
            pin.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            pin.gravity = Gravity.CENTER
            pin.textSize = 22f
            root.addView(pin)
            val lk = AppLock.lockedMinutes(this)
            if (lk > 0L) root.addView(text("Terkunci karena salah 5 kali. Coba lagi $lk menit lagi.", 12f, true, C_BAD).apply { gravity = Gravity.CENTER })
            root.addView(button("Buka") {
                if (AppLock.verifyPin(this, pin.text.toString())) {
                    render()
                } else {
                    toast(if (AppLock.lockedMinutes(this) > 0L) "Terkunci sementara" else "PIN salah")
                    showLockScreen()
                }
            })
        }
    }

    private fun askCredential() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val i = km.createConfirmDeviceCredentialIntent("Penjaga Ketat", "Masukkan kunci layar untuk membuka")
        if (i == null) {
            AppLock.setMode(this, 0)
            toast("Kunci layar HP belum diatur, kunci aplikasi dimatikan")
            render()
        } else {
            inCred = true
            startActivityForResult(i, 88)
        }
    }

    private fun renderUninstallGate(strict: Boolean) {
        header("Uninstall Penjaga Ketat & aplikasi pelindung")
        put(text("Di luar Mode Ketat, halaman uninstall dan penonaktifan Penjaga Ketat (termasuk Aksesibilitas dan Administrator perangkat) serta aplikasi pelindung lain (BlockP, Stay Focused, Channel Blocker, dan daftar Lindungi) tetap ditutup. Untuk membukanya, lewati gerbang yang sama dengan keluar dari Mode Ketat.", 12f, false, C_SUB))
        if (Store.uninstallWindowOpen(this)) {
            put(text("Terbuka: sisa sekitar ${Store.uninstallLeftMin(this)} menit.", 13f, true, C_BAD))
            put(button("Tutup sekarang") { Store.closeUninstallWindow(this); render() })
        } else if (strict) {
            put(text("Mode Ketat aktif: halaman itu terkunci penuh.", 12f, true, C_SUB))
        } else {
            put(button("Izinkan uninstall (10 menit)") {
                textGate("mengizinkan uninstall atau menonaktifkan Penjaga Ketat dan aplikasi pelindung", { Store.openUninstallWindow(this); render() }, { render() })
            })
        }
    }

    private fun renderAppLock(strict: Boolean) {
        header("Kunci aplikasi")
        put(text("Mencegah orang lain membuka Penjaga Ketat. Pilih PIN sendiri atau kunci layar HP (PIN, pola, sidik jari). Setelah dibuka, aplikasi tidak meminta lagi selama 1 menit.", 12f, false, C_SUB))
        val mode = AppLock.mode(this)
        status(mode != 0, when (mode) {
            1 -> "Kunci aktif: PIN"
            2 -> "Kunci aktif: kunci layar HP"
            else -> "Kunci aplikasi mati"
        })
        put(button(if (AppLock.hasPin(this)) "Ganti PIN" else "Pakai PIN") { pinDialog() })
        put(button("Pakai kunci layar HP") {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isDeviceSecure) {
                toast("HP belum punya kunci layar. Atur dulu di Pengaturan.")
            } else {
                AppLock.setMode(this, 2)
                AppLock.markUnlocked()
                render()
            }
        })
        if (mode != 0) {
            put(button("Matikan kunci aplikasi") {
                if (strict) {
                    toast("Mode Ketat aktif: kunci aplikasi tidak bisa dimatikan")
                } else if (mode == 1) {
                    val et = EditText(this)
                    et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    et.hint = "PIN saat ini"
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Matikan kunci aplikasi")
                        .setView(et)
                        .setPositiveButton("Matikan") { _, _ ->
                            if (AppLock.verifyPin(this, et.text.toString())) {
                                AppLock.turnOff(this)
                            } else {
                                toast("PIN salah atau terkunci")
                            }
                            render()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                } else {
                    AppLock.turnOff(this)
                    render()
                }
            })
        }
    }

    private fun pinDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))
        val pw = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val old = EditText(this)
        old.inputType = pw
        old.hint = "PIN lama"
        if (AppLock.hasPin(this)) box.addView(old)
        val n1 = EditText(this)
        n1.inputType = pw
        n1.hint = "PIN baru (4 sampai 8 angka)"
        box.addView(n1)
        val n2 = EditText(this)
        n2.inputType = pw
        n2.hint = "Ulangi PIN baru"
        box.addView(n2)
        android.app.AlertDialog.Builder(this)
            .setTitle("PIN aplikasi")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                if (AppLock.hasPin(this) && !AppLock.verifyPin(this, old.text.toString())) {
                    toast("PIN lama salah atau terkunci")
                } else if (n1.text.toString() != n2.text.toString()) {
                    toast("PIN tidak sama")
                } else {
                    val err = AppLock.setPin(this, n1.text.toString())
                    if (err == null) AppLock.markUnlocked()
                    toast(err ?: "PIN tersimpan")
                }
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun renderBrowsers(strict: Boolean) {
        header("Browser")
        put(text("Hanya Chrome yang didukung. Browser lain, termasuk yang baru dipasang, otomatis diblokir.", 12f, false, C_SUB))
        val cb = CheckBox(this)
        cb.text = "Blokir browser selain Chrome"
        cb.isChecked = Store.blockOtherBrowsers(this)
        cb.setOnClickListener {
            if (!cb.isChecked && strict) {
                toast("Mode Ketat aktif: tidak bisa dimatikan")
            } else {
                val v = cb.isChecked
                gateOff(!v, "mematikan blokir browser lain") { Store.setBlockOtherBrowsers(this, v) }
            }
            render()
        }
        put(cb)
        val ib = CheckBox(this)
        ib.text = "Blokir situs berupa alamat IP (mis. 178.62.96.212)"
        ib.isChecked = Store.blockIpUrls(this)
        ib.setOnClickListener {
            if (!ib.isChecked && strict) {
                toast("Mode Ketat aktif: tidak bisa dimatikan")
            } else {
                val v = ib.isChecked
                gateOff(!v, "mematikan blokir situs IP") { Store.setBlockIpUrls(this, v) }
            }
            render()
        }
        put(ib)
        put(text("Situs yang dibuka lewat angka IP melewati filter nama situs. IP jaringan rumah (192.168.x.x, 10.x) tetap boleh.", 11f, false, C_SUB))
        put(text("Diagnosa Play Store (buka Play Store lalu kembali ke sini dan tekan Segarkan):", 11f, true, C_SUB))
        put(text("Event: " + BrowserStoreGuard.seenInfo + " | " + BrowserStoreGuard.gateInfo, 11f, false, C_SUB).apply { typeface = Typeface.MONOSPACE })
        put(text("Toko: " + BrowserStoreGuard.diag, 11f, false, C_SUB).apply { typeface = Typeface.MONOSPACE })
        put(text("Layar: " + BrowserStoreGuard.screenDiag, 11f, false, C_SUB).apply { typeface = Typeface.MONOSPACE })
        put(button("Segarkan diagnosa") { render() })
        val sb = CheckBox(this)
        sb.text = "Cegah memasang browser lain dari Play Store"
        sb.isChecked = BrowserStoreGuard.enabled(this)
        sb.setOnClickListener {
            if (!sb.isChecked && strict) {
                toast("Mode Ketat aktif: tidak bisa dimatikan")
            } else {
                val v = sb.isChecked
                gateOff(!v, "mematikan pencegah browser di Play Store") { BrowserStoreGuard.setEnabled(this, v) }
            }
            render()
        }
        put(sb)
        put(text("Halaman detail aplikasi browser di Play Store dan pencarian nama browser otomatis ditutup. Tidak mencegah APK langsung atau toko lain. Tambahkan nama browser tambahan di bawah bila ada yang lolos.", 11f, false, C_SUB))
        val nameBox = EditText(this)
        nameBox.hint = "Nama browser tambahan (mis. Opera GX)"
        nameBox.setSingleLine(true)
        put(nameBox)
        put(button("Tambah nama") {
            val v = nameBox.text.toString().trim()
            if (v.length < 3) toast("Minimal 3 huruf") else {
                BrowserStoreGuard.addName(this, v)
                render()
            }
        })
        for (n in BrowserStoreGuard.customNames(this).sorted()) {
            put(text("• $n   ✕", 12f, false, C_SUB).apply {
                setOnClickListener {
                    if (strict) toast("Mode Ketat aktif: tidak bisa dihapus") else {
                        gateOff(true, "menghapus nama browser") { BrowserStoreGuard.removeName(this@MainActivity, n) }
                    }
                }
            })
        }
        val list = Store.unsupportedBrowsers(this)
        if (list.isEmpty()) {
            put(text("Tidak ada browser lain terdeteksi.", 12f, false, C_SUB))
        } else {
            for (p in list.sorted()) put(text("• " + appLabel(p), 12f, false, C_SUB))
        }
    }

    private fun renderProtection(strict: Boolean) {
        header("Status perlindungan")
        for (ch in AntiBypass.checks(this)) status(ch.first, ch.second)
        val df = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale("id", "ID"))
        if (AntiBypass.killCount(this) > 0) put(text("Pengawas pernah mati tidak wajar ${AntiBypass.killCount(this)} kali, terakhir " +
            df.format(java.util.Date(AntiBypass.killAt(this))) + " (Force Stop, crash, atau update).", 12f, false, C_BAD))
        if (AntiBypass.wipeAt(this) > 0L) put(text("Data aplikasi tampak terhapus/dipasang ulang saat Mode Ketat aktif (" +
            df.format(java.util.Date(AntiBypass.wipeAt(this))) + "). Konfigurasi perlu dipulihkan.", 12f, true, C_BAD))
        if (!AntiBypass.batteryExempt(this)) put(button("Kecualikan dari penghemat baterai") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(android.net.Uri.parse("package:$packageName")))
            } catch (t: Throwable) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        put(text("Batas yang tidak bisa ditutup tanpa root/device-owner: Force Stop lewat ADB, pencabutan izin lewat ADB, Safe Mode, profil kerja, dan factory reset. Penjaga Ketat hanya bisa mendeteksi dan memberi tahu, bukan mencegahnya.", 11f, false, C_SUB))
        header("Perlindungan diri")
        val secure = SelfProtector.canWriteSecure(this)
        val usage = Foreground.hasUsageAccess(this)
        status(secure, "Pemulih otomatis aksesibilitas (izin WRITE_SECURE_SETTINGS)")
        status(usage, "Akses Penggunaan (mengenali aplikasi bank terbuka)")
        status(WatchdogService.running, "Pengawas berjalan (hanya saat Mode Ketat aktif)")
        if (!secure) {
            put(text("Agar aksesibilitas yang dimatikan otomatis menyala lagi, jalankan SEKALI dari PC (USB debugging aktif):", 12f))
            put(text("adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS", 12f).apply {
                setTextIsSelectable(true)
                setBackgroundColor(C_CHIP)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            put(text("Tanpa izin ini, pengawas hanya memberi peringatan lewat notifikasi, tidak bisa menyalakan sendiri.", 12f))
        }
        if (!usage) put(button("Buka pengaturan Akses Penggunaan") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            put(button("Izinkan notifikasi peringatan") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }
        put(text("Jam HP diubah saat Mode Ketat aktif: jadwal diabaikan dan blokir berlaku penuh 30 menit.", 12f))
        put(text("Pemulihan darurat: keluar Mode Ketat lewat tombol di atas (tunggu lalu ketik teks acak). Itu jalur pemulihan yang selalu tersedia, tanpa PC.", 12f))
    }

    // ---------- Deteksi gambar AI ----------
    private fun renderNsfwAi(strict: Boolean) {
        header("Deteksi gambar AI (NSFW)")
        put(text("Layar difoto tiap sekitar 1 detik saat HP menyala, lalu dianalisis di HP oleh model AI (tanpa internet, tidak ada gambar yang disimpan atau dikirim). Bila ada gambar telanjang, layar langsung ditutup dan kamu dikembalikan ke beranda. Hanya aplikasi yang bisa memuat media (browser, sosmed, galeri, pemutar video) dan aplikasi yang baru dipasang (14 hari) yang dipotret; aplikasi lain dilewati agar hemat baterai. Butuh Android 11 ke atas. Salah blokir bisa terjadi.", 12f))
        val cb = CheckBox(this)
        cb.text = "Aktifkan deteksi gambar AI"
        cb.isChecked = Store.nsfwAi(this)
        cb.setOnClickListener {
            val v = cb.isChecked
            if (!v && strict) {
                toast("Mode Ketat aktif: tidak bisa dimatikan")
            } else {
                gateOff(!v, "mematikan deteksi gambar AI") { Store.setNsfwAi(this, v) }
            }
            render()
        }
        put(cb)
        put(text(NsfwAi.status(this), 11f))
        if (android.os.Build.VERSION.SDK_INT < 30) put(text("HP ini di bawah Android 11: tangkapan layar tidak tersedia.", 11f))
        put(text("Bila status tidak menunjukkan pemindaian setelah beberapa menit, matikan lalu hidupkan lagi layanan Aksesibilitas Penjaga Ketat (izin tangkapan layar baru dibaca saat layanan dimulai ulang).", 11f))
    }

    // ---------- Celah pemasangan (saklar terpisah) ----------
    private fun renderBypassGuard(strict: Boolean) {
        header("Celah pemasangan")
        put(text("Saat Mode Ketat aktif, halaman Pengaturan untuk menambah pengguna/tamu dan Aplikasi admin perangkat selalu ditutup.", 12f))
        val cb = CheckBox(this)
        cb.text = "Saat Mode Ketat: blokir pemasangan APK dari luar Play Store (kecuali Penjaga Ketat & Channel Blocker) dan tutup halaman izin Instal aplikasi tidak dikenal"
        cb.isChecked = Store.blockUnknownInstall(this)
        cb.setOnClickListener {
            val v = cb.isChecked
            if (!v && strict) {
                toast("Mode Ketat aktif: tidak bisa dimatikan")
            } else {
                gateOff(!v, "mematikan blokir pemasangan APK") { Store.setBlockUnknownInstall(this, v) }
            }
            render()
        }
        put(cb)
        put(text("Layar pemasang APK ditutup bila yang dipasang bukan aplikasi buatanmu. Update Penjaga Ketat dan Channel Blocker lewat Chrome atau Pengelola file tetap bisa. Instal dari Play Store tidak terpengaruh.", 11f, false, C_SUB))
    }

    // ---------- Jam rawan ----------
    private fun renderNight(strict: Boolean) {
        header("Jam rawan")
        put(text("Di jam rawan, gerbang keluar Mode Ketat dan gerbang mematikan fitur dua kali lebih berat: teks acak dua kali lebih panjang dan waktu tunggu dua kali lebih lama.", 12f))
        if (Night.active(this)) put(text("Sekarang sedang jam rawan (gerbang x2).", 13f, true, C_BAD))
        val cb = CheckBox(this)
        cb.text = "Aktifkan jam rawan"
        cb.isChecked = Night.enabled(this)
        cb.setOnClickListener {
            val v = cb.isChecked
            if (!v && strict) {
                toast("Mode Ketat aktif: tidak bisa dimatikan")
            } else {
                gateOff(!v, "mematikan jam rawan") { Night.setEnabled(this, v) }
            }
            render()
        }
        put(cb)
        fun pick(isStart: Boolean) {
            if (strict) {
                toast("Mode Ketat aktif: jam rawan tidak bisa diubah")
                return
            }
            val cur = if (isStart) Night.start(this) else Night.end(this)
            android.app.TimePickerDialog(this, { _, h, m ->
                val v = h * 60 + m
                textGate("mengubah jam rawan", {
                    if (isStart) Night.setWindow(this, v, Night.end(this)) else Night.setWindow(this, Night.start(this), v)
                    render()
                }, { render() })
            }, cur / 60, cur % 60, true).show()
        }
        put(button("Mulai: " + Night.fmt(Night.start(this))) { pick(true) })
        put(button("Selesai: " + Night.fmt(Night.end(this))) { pick(false) })
        put(text("Kunci total di jam rawan (pilihan):", 12f, true))
        val cp = CheckBox(this)
        cp.text = "Tutup Play Store"
        cp.isChecked = Night.lockPlay(this)
        cp.setOnClickListener {
            val v = cp.isChecked
            if (!v && strict) toast("Mode Ketat aktif: tidak bisa dimatikan")
            else gateOff(!v, "mematikan kunci Play Store") { Night.setLockPlay(this, v) }
            render()
        }
        put(cp)
        val cs = CheckBox(this)
        cs.text = "Tutup aplikasi Pengaturan (aksesibilitas tidak bisa dinyalakan ulang sampai jam rawan selesai)"
        cs.isChecked = Night.lockSettings(this)
        cs.setOnClickListener {
            val v = cs.isChecked
            if (!v && strict) toast("Mode Ketat aktif: tidak bisa dimatikan")
            else gateOff(!v, "mematikan kunci Pengaturan") { Night.setLockSettings(this, v) }
            render()
        }
        put(cs)
        put(text("Profil Malam menutup browser dan aplikasi sosmed yang terpasang selama jam rawan, setiap hari. Saat Mode Ketat aktif, tombol ini hanya menambah aplikasi, tidak mengubah jadwal.", 12f, false, C_SUB))
        put(button("Buat / perbarui profil Malam") { createNightProfile(strict) })
    }

    private fun letterCard(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(10), dp(14), dp(10))
        box.background = bg(C_CHIP, dp(14))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        box.layoutParams = lp
        box.addView(text("Surat untuk dirimu", 11f, true, C_SUB))
        box.addView(text(Store.letter(this), 14f, false, C_TEXT).apply { setTypeface(null, Typeface.ITALIC) })
        return box
    }

    private fun renderLetter(strict: Boolean) {
        header("Surat untuk diri sendiri")
        put(text("Tulis sekali, di saat tenang. Tulisan ini muncul di dialog gerbang saat kamu mencoba keluar dari Mode Ketat atau mematikan fitur.", 12f))
        val et = EditText(this).apply {
            setText(Store.letter(this@MainActivity))
            textSize = 14f
            minLines = 3
            isEnabled = !strict
        }
        put(et)
        put(button("Simpan surat") {
            val v = et.text.toString().trim()
            if (strict) toast("Mode Ketat aktif: surat tidak bisa diubah")
            else if (v.length < 5) toast("Isi surat terlalu pendek")
            else {
                Store.setLetter(this, v)
                toast("Tersimpan")
                render()
            }
        })
    }

    private fun createNightProfile(strict: Boolean) {
        val social = listOf(
            "com.instagram.android", "com.facebook.katana", "com.facebook.lite", "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill", "com.twitter.android", "com.reddit.frontpage", "com.google.android.youtube",
            "com.snapchat.android", "com.pinterest"
        )
        val candidates = (Config.BROWSERS - "com.google.android.googlequicksearchbox") + social
        val installed = candidates.filter { packageManager.getLaunchIntentForPackage(it) != null }.toSet()
        val list = ProfileStore.load(this).toMutableList()
        val idx = list.indexOfFirst { it.id == "malam" }
        val sched = listOf(Sched(Night.start(this), Night.end(this), 127))
        if (idx < 0) {
            list.add(Profile("malam", "Malam", true, installed, emptySet(), emptySet(), sched, 0))
            Store.log(this, "Profil Malam dibuat (${installed.size} aplikasi)")
        } else {
            val old = list[idx]
            list[idx] = if (strict) old.copy(apps = old.apps + installed, enabled = true)
            else old.copy(apps = old.apps + installed, enabled = true, schedules = sched)
            Store.log(this, "Profil Malam diperbarui (${list[idx].apps.size} aplikasi)")
        }
        ProfileStore.save(this, list)
        toast("Profil Malam aktif: ${installed.size} aplikasi ditutup di jam rawan")
        render()
    }

    private fun renderHourMap() {
        header("Jam paling rawan")
        val h = try { StatsDb.blocksByHour(this, 35) } catch (t: Throwable) { IntArray(24) }
        val total = h.sum()
        if (total == 0) {
            put(text("Belum ada blokir tercatat.", 12f, false, C_SUB))
            return
        }
        val max = Math.max(1, h.max())
        val bars = LinearLayout(this)
        bars.orientation = LinearLayout.HORIZONTAL
        bars.gravity = Gravity.BOTTOM
        bars.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70))
        for (i in 0 until 24) {
            val inNight = run {
                val s = Night.start(this) / 60
                val e = Night.end(this) / 60
                if (s < e) i in s until e else (i >= s || i < e)
            }
            val barH = if (h[i] == 0) dp(3) else Math.max(dp(5), (dp(64) * h[i] / max))
            bars.addView(View(this).apply {
                background = bg(if (h[i] == 0) C_CHIP else if (inNight) C_BAD else C_PRIMARY, dp(3))
                layoutParams = LinearLayout.LayoutParams(0, barH, 1f).apply { setMargins(dp(1), 0, dp(1), 0) }
            })
        }
        put(bars)
        put(text("00        06        12        18        23", 10f, false, C_SUB).apply { typeface = Typeface.MONOSPACE })
        var top = 0
        for (i in 1 until 24) if (h[i] > h[top]) top = i
        put(text("Paling banyak: jam ${String.format(java.util.Locale.US, "%02d", top)}.00 (${h[top]} dari $total blokir, 35 hari). Merah = di dalam jam rawan, biru = di luar.", 12f))
        val outside = (0 until 24).filter { i ->
            val s = Night.start(this) / 60
            val e = Night.end(this) / 60
            !(if (s < e) i in s until e else (i >= s || i < e))
        }.sumOf { h[it] }
        if (Night.enabled(this) && outside * 100 / total >= 50) {
            put(text("Lebih dari separuh blokir terjadi di luar jam rawan. Pertimbangkan memperluas jamnya di Mode Ketat dan status.", 12f, true, C_BAD))
        }
    }

    // ---------- Uji perlindungan ----------
    private fun renderSelfTest() {
        header("Uji perlindungan")
        put(text("Membuka halaman Pengaturan satu per satu dan memeriksa apakah penandanya terdeteksi di HP ini (Opsi pengembang, VPN, izin instal, tambah pengguna, admin perangkat). Layar akan berpindah sendiri selama sekitar 20 detik.", 12f))
        put(button("Mulai uji") {
            val svc = GuardService.instance
            if (svc == null) toast("Aksesibilitas belum aktif") else {
                toast("Uji berjalan, jangan sentuh layar")
                svc.startSelfTest()
            }
        })
        put(button("Pindai layar yang kubuka (10 detik)") {
            val svc = GuardService.instance
            if (svc == null) toast("Aksesibilitas belum aktif") else {
                toast("Buka halaman yang mau diuji (mis. DNS Pribadi) sekarang")
                svc.startManualScan(10)
                moveTaskToBack(true)
            }
        })
        if (SelfTest.results.isEmpty()) {
            put(text("Belum ada hasil.", 12f, false, C_SUB))
        } else {
            for (r in SelfTest.results) {
                put(text((if (r.ok) "✔  " else "✘  ") + r.name, 13f, true, if (r.ok) C_OK else C_BAD))
                put(text(r.detail, 11f, false, C_SUB))
            }
            put(text("Baris merah berarti halaman itu belum tertutup di HP ini. Kirim screenshot hasilnya supaya penandanya bisa disesuaikan.", 11f, false, C_SUB))
        }
    }

    // ---------- Hari bersih (streak) ----------
    private fun renderStreak() {
        header("Hari bersih")
        val now = System.currentTimeMillis()
        val per = try { StatsDb.blocksPerDay(this, 35) } catch (t: Throwable) { emptyMap() }
        val first = try { packageManager.getPackageInfo(packageName, 0).firstInstallTime } catch (t: Throwable) { now }
        val firstKey = StatsDb.dayKey(first)
        val days = ArrayList<Pair<String, Int>>() // lama -> baru; -1 = sebelum terpasang
        for (i in 34 downTo 0) {
            val k = StatsDb.dayKey(now - i.toLong() * 86400000L)
            days.add(Pair(k, if (k < firstKey) -1 else (per[k] ?: 0)))
        }
        var streak = 0
        for (i in days.indices.reversed()) {
            if (days[i].second == 0) streak++ else break
        }
        var best = 0
        var run = 0
        for (d in days) {
            if (d.second == 0) { run++; if (run > best) best = run } else run = 0
        }
        put(text("$streak hari", 30f, true, C_OK))
        put(text("beruntun tanpa satu pun blokir • rekor $best hari (35 hari terakhir)", 12f, false, C_SUB))
        val last28 = days.takeLast(28)
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(0, dp(8), 0, dp(4))
        for (r in 0 until 4) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until 7) {
                val d = last28[r * 7 + c]
                val color = when {
                    d.second < 0 -> C_CHIP
                    d.second == 0 -> C_OK
                    else -> C_BAD
                }
                row.addView(View(this).apply {
                    background = bg(color, dp(6))
                    layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
                })
            }
            grid.addView(row)
        }
        put(grid)
        put(text("Hijau = bersih, merah = ada blokir, abu = sebelum aplikasi terpasang. Riwayat disimpan 35 hari.", 11f, false, C_SUB))
        val weekAgo = now - 7L * 86400000L
        val urges = Store.urgeTimes(this).count { it >= weekAgo }
        put(text("Dorongan dilawan 7 hari terakhir: $urges kali", 12f, false, C_TEXT))
    }

    // ---------- Dorongan ----------
    private var urgeEnd = 0L
    private var urgeTv: TextView? = null
    private var urgeHint: TextView? = null
    private var urgeBtn: Button? = null
    private val urgeTick = object : Runnable {
        override fun run() {
            if (screen != "urge" || urgeEnd == 0L) return
            val left = urgeEnd - System.currentTimeMillis()
            val tv = urgeTv ?: return
            if (left <= 0L) {
                tv.text = "Selesai"
                urgeHint?.text = "Kamu berhasil melewatinya. Lanjutkan harimu."
                urgeBtn?.text = "Selesai, ke layar utama"
                return
            }
            val sec = (left + 999L) / 1000L
            tv.text = String.format(java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)
            val elapsed = (90L - sec).coerceAtLeast(0L)
            val ph = (elapsed % 14L)
            urgeHint?.text = when {
                ph < 4L -> "Tarik napas pelan..."
                ph < 8L -> "Tahan sebentar..."
                else -> "Hembuskan perlahan..."
            }
            handler.postDelayed(this, 500L)
        }
    }

    private fun renderUrge() {
        handler.removeCallbacks(urgeTick)
        header("Dorongan")
        if (urgeEnd == 0L) {
            put(text("Dorongan itu naik lalu turun sendiri, biasanya dalam beberapa menit. Kamu tidak perlu melawannya, cukup menunggu 90 detik sambil mengatur napas.", 13f))
            put(text(Store.motivation(this), 13f, false, C_SUB))
            put(button("Mulai 90 detik") {
                urgeEnd = System.currentTimeMillis() + 90000L
                Store.addUrge(this)
                render()
            })
            return
        }
        val tv = text("", 44f, true, C_PRIMARY).apply { gravity = Gravity.CENTER }
        val hint = text("", 16f, false, C_TEXT).apply { gravity = Gravity.CENTER }
        urgeTv = tv
        urgeHint = hint
        put(tv)
        put(hint)
        put(text(Store.motivation(this), 12f, false, C_SUB).apply { gravity = Gravity.CENTER })
        val b = button(if (System.currentTimeMillis() >= urgeEnd) "Selesai, ke layar utama" else "Batal") {
            val done = System.currentTimeMillis() >= urgeEnd
            urgeEnd = 0L
            if (done) {
                try {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (t: Throwable) {
                    // abaikan
                }
            }
            goScreen("home")
        }
        urgeBtn = b
        put(b)
        urgeTick.run()
    }

    // ---------- Jeda sebelum buka aplikasi ----------
    private var pauseQuery = ""

    private fun renderPauseApps(strict: Boolean) {
        header("Jeda sebelum buka")
        put(text("Aplikasi yang dipilih meminta jeda napas 10 detik dan satu kalimat tujuan sebelum dibuka (berlaku lagi setelah 5 menit). Ini bukan blokir, hanya gesekan. Saat Mode Ketat aktif, daftar ini hanya bisa ditambah.", 12f))
        val chosen = Store.pauseApps(this)
        val all = loadApps()
        val byPkg = all.associateBy { it.pkg }
        val chosenRows = chosen.map { byPkg[it] ?: AppRow(it, it) }.sortedBy { it.label.lowercase() }
        if (chosenRows.isEmpty()) put(text("Belum ada aplikasi.", 12f, false, C_SUB))
        for (a in chosenRows) put(pauseRow(a, true, strict))
        val q = EditText(this)
        q.hint = "Cari aplikasi untuk ditambah"
        q.setSingleLine()
        q.setText(pauseQuery)
        put(q)
        put(button("Cari") {
            pauseQuery = q.text.toString().trim().lowercase()
            render()
        })
        val others = all.filter { it.pkg !in chosen }
        val shown = if (pauseQuery.isEmpty()) others
        else others.filter { it.label.lowercase().contains(pauseQuery) || it.pkg.contains(pauseQuery) }
        if (shown.isEmpty()) {
            put(text("Tidak ada aplikasi yang cocok.", 12f, false, C_SUB))
        } else {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(10), dp(4), dp(10), dp(4))
            for (a in shown) col.addView(pauseRow(a, false, strict))
            put(scrollBox(300, col))
        }
    }

    private fun pauseRow(a: AppRow, on: Boolean, strict: Boolean): View {
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(0, dp(6), 0, dp(6))
        val icon = ImageView(this)
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(a.pkg))
        } catch (e: Exception) {
            // tanpa ikon
        }
        icon.layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        top.addView(icon)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(text(a.label, 14f, true))
        col.addView(text(a.pkg, 10f, false, C_SUB))
        top.addView(col)
        val sw = Switch(this)
        sw.isChecked = on
        sw.setOnClickListener {
            if (sw.isChecked) {
                Store.addPauseApp(this@MainActivity, a.pkg)
            } else if (strict) {
                toast("Mode Ketat aktif: tidak bisa melepas jeda")
            } else {
                Store.removePauseApp(this@MainActivity, a.pkg)
            }
            render()
        }
        top.addView(sw)
        return top
    }

    private fun renderNewApps(strict: Boolean) {
        header("Aplikasi baru")
        val cb = CheckBox(this)
        cb.text = "Blokir otomatis aplikasi yang baru dipasang"
        cb.isChecked = Store.autoBlockNew(this)
        cb.setOnClickListener {
            val v = cb.isChecked
            if (!v && strict) {
                toast("Mode Ketat aktif: tidak bisa dimatikan")
            } else {
                Store.setAutoBlockNew(this, v)
            }
            render()
        }
        put(cb)
        val list = Store.newApps(this)
        if (list.isEmpty()) {
            put(text("Belum ada aplikasi baru yang terdeteksi.", 12f))
        }
        val fmtDate = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
        for ((p, t) in list) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
            } catch (e: Exception) {
                p + " (sudah dicopot)"
            }
            val blocked = if (p in Store.apps(this)) " - diblokir" else ""
            put(text(fmtDate.format(java.util.Date(t)) + "  " + label + blocked, 12f))
        }
    }

    private fun renderLog() {
        header("Riwayat pass")
        val ph = Store.passHistory(this)
        if (ph.isEmpty()) {
            put(text("Belum ada pass yang dipakai.", 12f))
        } else {
            for (line in ph.takeLast(10).reversed()) {
                val parts = line.split('|')
                if (parts.size < 3) continue
                val label = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(parts[1], 0)).toString()
                } catch (e: Exception) {
                    parts[1]
                }
                put(text(parts[0] + "  " + label + "  " + parts[2] + " mnt", 11f))
            }
        }
        header("Catatan perlindungan")
        val lines = Store.logs(this)
        if (lines.isEmpty()) {
            put(text("Belum ada catatan.", 12f))
        } else {
            for (l in lines.takeLast(15).reversed()) put(text(l, 11f))
        }
    }

    private fun renderApps(strict: Boolean) {
        val chosen = Store.apps(this)
        val all = loadApps()
        val chosenRows = all.filter { it.pkg in chosen }

        header("Diblokir (${chosenRows.size})")
        put(text("Ketuk aplikasi untuk mengatur kondisi blokir. Saat Mode Ketat aktif, aplikasi yang sudah diatur terkunci, tetapi aplikasi baru masih bisa ditambah dan diatur.", 12f, false, C_SUB))
        if (chosenRows.isEmpty()) put(text("Belum ada aplikasi diblokir.", 13f))
        for (a in chosenRows) put(appRow(a, true, strict))

        header("Tambah aplikasi")
        val q = EditText(this)
        q.hint = "Cari nama aplikasi"
        q.setSingleLine()
        q.setText(appQuery)
        put(q)
        put(button("Cari") {
            appQuery = q.text.toString().trim().lowercase()
            render()
        })
        val others = all.filter { it.pkg !in chosen }
        val shown = if (appQuery.isEmpty()) others
        else others.filter { it.label.lowercase().contains(appQuery) || it.pkg.contains(appQuery) }
        if (shown.isEmpty()) {
            put(text("Tidak ada aplikasi yang cocok.", 12f, false, C_SUB))
        } else {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(10), dp(4), dp(10), dp(4))
            for (a in shown) col.addView(appRow(a, false, strict))
            put(scrollBox(360, col))
        }
    }

    private fun appRow(a: AppRow, on: Boolean, strict: Boolean): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(6), 0, dp(6))
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val icon = ImageView(this)
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(a.pkg))
        } catch (e: Exception) {
            // tanpa ikon
        }
        icon.layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        top.addView(icon)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(text(a.label, 14f, true))
        if (on) {
            val cfg = Store.appCfg(this, a.pkg)
            col.addView(text(summary(cfg), 11f, false, C_SUB))
            col.addView(text(usageLine(a.pkg, cfg), 11f, false, C_SUB))
            if (strict && Store.isLocked(this, a.pkg)) col.addView(text("🔒 terkunci", 11f, true, C_BAD))
        } else {
            col.addView(text(a.pkg, 10f, false, C_SUB))
        }
        top.addView(col)
        val sw = Switch(this)
        sw.isChecked = on
        sw.setOnClickListener {
            val checked = sw.isChecked
            if (checked) {
                Store.addApp(this, a.pkg)
                render()
                editCfg(a.pkg, a.label, strict)
            } else if (strict && Store.isLocked(this, a.pkg)) {
                toast("Mode Ketat aktif: aplikasi ini sudah terkunci, tidak bisa melepas blokir")
                render()
            } else {
                gateOff(true, "melepas blokir aplikasi ${a.label}") { Store.removeApp(this, a.pkg) }
            }
        }
        top.addView(sw)
        if (on) top.setOnClickListener { editCfg(a.pkg, a.label, strict) }
        row.addView(top)
        if (on) {
            val actions = LinearLayout(this)
            actions.orientation = LinearLayout.HORIZONTAL
            actions.setPadding(dp(54), 0, 0, 0)
            actions.addView(smallButton("Atur aturan") { editCfg(a.pkg, a.label, strict) })
            actions.addView(smallButton("Blok cepat") { quickBlock(a.pkg, a.label) })
            row.addView(actions)
        }
        return row
    }

    private fun summary(c: AppCfg): String {
        val mode = if (c.mode == 1) "Hanya kondisi batas"
        else if (c.wait <= 0) "Blokir penuh"
        else "Timer ${c.wait} dtk, terbuka ${c.pass} mnt"
        val sch = if (c.schOn) "${fmt(c.start)}-${fmt(c.end)}" else "selalu"
        var s = "$mode • $sch"
        if (c.limitMin > 0) s += " • batas ${c.limitMin} mnt/hari"
        if (c.cooldown > 0) s += " • cooldown ${c.cooldown} mnt"
        if (c.notif) s += " • notifikasi ikut diblokir"
        if (c.hourLimit > 0) s += " • ${c.hourLimit} mnt/jam"
        if (c.launchDay > 0) s += " • ${c.launchDay} buka/hari"
        if (c.launchHour > 0) s += " • ${c.launchHour} buka/jam"
        if (c.twUse > 0 && c.twWait > 0) s += " • timer: pakai ${c.twUse} mnt → tunggu ${c.twWait} mnt"
        s += " • " + dayNames(c.days)
        if (c.blockUntil > System.currentTimeMillis()) {
            val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            s += " • blokir penuh sampai " + f.format(java.util.Date(c.blockUntil))
        }
        return s
    }

    private fun usageLine(pkg: String, c: AppCfg): String {
        if (!Foreground.hasUsageAccess(this)) return "Pemakaian: aktifkan Akses Penggunaan"
        val u = UsageTracker.get(this)
        val usedMin = (u.ms[pkg] ?: 0L) / 60000L
        var s = "Hari ini: $usedMin mnt, ${u.launches[pkg] ?: 0}x buka"
        if (c.limitMin > 0) s += " • sisa " + Math.max(0L, c.limitMin - usedMin) + " mnt"
        val last = u.last[pkg] ?: 0L
        if (last > 0L) {
            val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            s += " • terakhir " + f.format(java.util.Date(last))
        }
        return s
    }

    private fun spinnerOf(items: List<String>, selected: Int): Spinner {
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(if (selected in items.indices) selected else 0)
        return sp
    }

    private fun numberField(value: Int): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(value.toString())
        return e
    }

    private fun dayNames(mask: Int): String {
        val names = arrayOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
        val out = ArrayList<String>()
        for (i in 0 until 7) if ((mask and (1 shl i)) != 0) out.add(names[i])
        return if (out.size == 7) "tiap hari" else out.joinToString(",")
    }

    private fun vbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(16), 0, 0, dp(8))
        return l
    }

    private class LocUi(val cb: CheckBox, val read: () -> LocRule?)

    /** Bagian "Lokasi" untuk aturan aplikasi ([key] = app_paket) atau situs ([key] = site_domain). */
    private fun locationSection(box: LinearLayout, key: String, locked: Boolean): LocUi {
        val old = LocationRules.get(this, key)
        val body = vbox()
        body.addView(text("Blokir hanya berlaku di sekitar titik yang kamu tetapkan. Tanpa peta: pakai lokasi saat ini atau isi koordinat (salin dari Google Maps).", 11f, false, C_SUB))
        val inType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        val latF = EditText(this).apply { hint = "Lintang, mis. -7.290500"; inputType = inType; setText(old?.lat?.toString() ?: "") }
        val lonF = EditText(this).apply { hint = "Bujur, mis. 112.734000"; inputType = inType; setText(old?.lon?.toString() ?: "") }
        val status = text(
            if (LocationTracker.hasPermission(this)) "" else "Izin lokasi belum diberikan. Ketuk tombol di bawah untuk memberi izin.",
            11f, false, C_SUB
        )
        var ruleMap: MapPicker.Handle? = null
        val useBtn = Button(this).apply { text = "Pakai lokasi saya sekarang" }
        useBtn.setOnClickListener {
            if (!LocationTracker.hasPermission(this@MainActivity)) {
                askLocationPermission()
            } else {
                status.text = "Mencari lokasi..."
                LocationTracker.fetchNow(this@MainActivity) { l ->
                    if (l == null) {
                        status.text = "Lokasi belum didapat. Nyalakan GPS lalu coba lagi."
                    } else {
                        latF.setText(String.format(java.util.Locale.US, "%.6f", l.latitude))
                        lonF.setText(String.format(java.util.Locale.US, "%.6f", l.longitude))
                        ruleMap?.center(l.latitude, l.longitude)
                        status.text = "Lokasi didapat (akurasi sekitar ${l.accuracy.toInt()} m)."
                    }
                }
            }
        }
        body.addView(useBtn)
        if (!locked) {
            body.addView(text("Ketuk peta untuk menaruh titik (peta butuh internet).", 11f, false, C_SUB))
            ruleMap = MapPicker.attach(this, body, latF, lonF, old?.radius ?: 150, dp(260))
        }
        body.addView(latF)
        body.addView(lonF)
        body.addView(status)
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbHere = RadioButton(this).apply { text = "Blokir saat saya di sini"; id = View.generateViewId() }
        val rbAway = RadioButton(this).apply { text = "Blokir saat saya jauh"; id = View.generateViewId() }
        rg.addView(rbHere)
        rg.addView(rbAway)
        if (old?.away == true) rbAway.isChecked = true else rbHere.isChecked = true
        body.addView(rg)
        val radiusLabel = text("", 12f)
        val seek = android.widget.SeekBar(this).apply { max = 39 }
        fun radiusOf(p: Int) = 50 + p * 50
        seek.progress = ((old?.radius ?: 150) - 50).coerceIn(0, 1950) / 50
        radiusLabel.text = "Radius: ${radiusOf(seek.progress)} m"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                radiusLabel.text = "Radius: ${radiusOf(p)} m"
                ruleMap?.setRadius(radiusOf(p))
            }

            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        body.addView(radiusLabel)
        body.addView(seek)
        val unavail = CheckBox(this).apply {
            text = "Blokir jika lokasi tidak tersedia (izin lokasi atau GPS dimatikan)"
            isChecked = old?.blockUnavailable ?: true
        }
        body.addView(unavail)
        val cb = conditionSection(box, "Berdasarkan lokasi", old != null, body)
        if (locked) {
            cb.isEnabled = false
            for (v in listOf<View>(useBtn, latF, lonF, rbHere, rbAway, seek, unavail)) v.isEnabled = false
        }
        val read: () -> LocRule? = {
            val lat = latF.text.toString().trim().toDoubleOrNull()
            val lon = lonF.text.toString().trim().toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) null
            else LocRule(lat, lon, radiusOf(seek.progress), rbAway.isChecked, unavail.isChecked)
        }
        return LocUi(cb, read)
    }

    private fun askLocationPermission() {
        if (!LocationTracker.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1010)
        } else if (!LocationTracker.hasBackground(this)) {
            toast("Pilih Izin lokasi > Izinkan sepanjang waktu, agar blokir lokasi tetap jalan saat aplikasi ditutup.")
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (t: Throwable) {
                if (Build.VERSION.SDK_INT >= 29) requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 1011)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1010) {
            LocationTracker.ensure(this)
            if (LocationTracker.hasPermission(this)) askLocationPermission()
        }
    }

    private fun conditionSection(box: LinearLayout, title: String, on: Boolean, body: LinearLayout): CheckBox {
        val cb = CheckBox(this)
        cb.text = title
        cb.textSize = 14f
        cb.setTypeface(null, Typeface.BOLD)
        cb.isChecked = on
        body.visibility = if (on) View.VISIBLE else View.GONE
        cb.setOnCheckedChangeListener { _, checked ->
            body.visibility = if (checked) View.VISIBLE else View.GONE
        }
        box.addView(cb)
        box.addView(body)
        return cb
    }

    private fun onPick(sp: Spinner, f: (Int) -> Unit) {
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                f(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun quickBlock(pkg: String, label: String) {
        val opts = listOf(10, 30, 60, 120, 240)
        android.app.AlertDialog.Builder(this)
            .setTitle("Blok cepat: $label")
            .setItems(opts.map { "$it menit berikutnya" }.toTypedArray()) { _, i ->
                Store.extendBlockUntil(this, pkg, opts[i])
                Store.log(this, "Blok cepat ${opts[i]} menit: $label")
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun editCfg(pkg: String, label: String, strict: Boolean) {
        if (strict && Store.isLocked(this, pkg)) {
            toast("Mode Ketat aktif: aturan aplikasi ini terkunci karena sudah diatur sebelum Mode Ketat. Aplikasi baru masih bisa diatur.")
            return
        }
        val cfg = Store.appCfg(this, pkg)
        var start = cfg.start
        var end = cfg.end
        val passOptions = listOf(5, 10, 15, 30)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))
        box.addView(text("Pilih kondisi pemblokiran. Beberapa kondisi boleh dipakai bersamaan.", 12f))

        // 1. Blokir penuh (selalu atau interval waktu)
        val fullBody = vbox()
        fullBody.addView(text("Kapan diblokir", 12f))
        val whenSp = spinnerOf(listOf("Selalu", "Hanya pada interval waktu"), if (cfg.schOn) 1 else 0)
        fullBody.addView(whenSp)
        val startBtn = Button(this)
        startBtn.text = "Mulai: ${fmt(start)}"
        startBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, mi ->
                start = h * 60 + mi
                startBtn.text = "Mulai: ${fmt(start)}"
            }, start / 60, start % 60, true).show()
        }
        val endBtn = Button(this)
        endBtn.text = "Selesai: ${fmt(end)}"
        endBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, mi ->
                end = h * 60 + mi
                endBtn.text = "Selesai: ${fmt(end)}"
            }, end / 60, end % 60, true).show()
        }
        fullBody.addView(startBtn)
        fullBody.addView(endBtn)
        onPick(whenSp) { pos ->
            val v = if (pos == 1) View.VISIBLE else View.GONE
            startBtn.visibility = v
            endBtn.visibility = v
        }
        val showTimes = if (cfg.schOn) View.VISIBLE else View.GONE
        startBtn.visibility = showTimes
        endBtn.visibility = showTimes
        val fullCb = conditionSection(box, "Blokir penuh", cfg.mode == 0, fullBody)

        // 2. Batas penggunaan
        val useBody = vbox()
        useBody.addView(text("Batas harian (menit)", 12f))
        val limit = numberField(cfg.limitMin)
        useBody.addView(limit)
        useBody.addView(text("Batas per jam (menit dalam 60 menit terakhir)", 12f))
        val hourLim = numberField(cfg.hourLimit)
        useBody.addView(hourLim)
        val useCb = conditionSection(box, "Batas penggunaan", cfg.limitMin > 0 || cfg.hourLimit > 0, useBody)

        // 3. Jumlah peluncuran
        val launchBody = vbox()
        launchBody.addView(text("Peluncuran per hari", 12f))
        val launchD = numberField(cfg.launchDay)
        launchBody.addView(launchD)
        launchBody.addView(text("Peluncuran per jam", 12f))
        val launchH = numberField(cfg.launchHour)
        launchBody.addView(launchH)
        val launchCb = conditionSection(box, "Jumlah peluncuran", cfg.launchDay > 0 || cfg.launchHour > 0, launchBody)

        // 4. Timer tunggu
        val twBody = vbox()
        twBody.addView(text("Setelah dipakai ... menit", 12f))
        val twUse = numberField(cfg.twUse)
        twBody.addView(twUse)
        twBody.addView(text("... aplikasi diblokir selama ... menit", 12f))
        val twWait = numberField(cfg.twWait)
        twBody.addView(twWait)
        val twAd = CheckBox(this)
        twAd.text = "Timer adaptif: bila tidak mencoba membuka aplikasi selama separuh masa tunggu, tunggu selesai lebih cepat"
        twAd.isChecked = cfg.twAdaptive
        twBody.addView(twAd)
        val twCb = conditionSection(box, "Timer tunggu", cfg.twUse > 0 && cfg.twWait > 0, twBody)

        // 5. Notifikasi
        val notifCb = CheckBox(this)
        notifCb.text = "Blokir juga notifikasi aplikasi ini saat sedang diblokir (butuh Akses Notifikasi)"
        notifCb.isChecked = cfg.notif
        box.addView(notifCb)

        box.addView(text("Blok cepat: pakai tombol Blok cepat di bawah aplikasinya.", 12f))
        val locUi = locationSection(box, "app_$pkg", false)
        box.addView(text("Berdasarkan tujuan: belum tersedia.", 12f))

        // Penghalang
        box.addView(text("Penghalang saat terblokir (blokir penuh dan batas harian)", 13f, true))
        box.addView(text("Detik tunggu sebelum bisa membuka (0 = tidak bisa dibuka sama sekali)", 12f))
        val wait = numberField(cfg.wait)
        box.addView(wait)
        box.addView(text("Lama aplikasi terbuka setelah menunggu (pass)", 12f))
        val passSp = spinnerOf(passOptions.map { "$it menit" }, passOptions.indexOf(cfg.pass))
        box.addView(passSp)
        box.addView(text("Cooldown setelah pass habis (menit, 0 = tanpa cooldown)", 12f))
        val cool = numberField(cfg.cooldown)
        box.addView(cool)

        box.addView(text("Berlaku pada hari", 13f, true))
        val names = arrayOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
        val dayBoxes = ArrayList<CheckBox>()
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (i in 0 until 7) {
            val d = CheckBox(this)
            d.text = names[i]
            d.textSize = 10f
            d.isChecked = (cfg.days and (1 shl i)) != 0
            dayBoxes.add(d)
            row.addView(d)
        }
        box.addView(row)

        val scrollBox = ScrollView(this)
        scrollBox.addView(box)
        android.app.AlertDialog.Builder(this)
            .setTitle("Kondisi blokir: $label")
            .setView(scrollBox)
            .setPositiveButton("Simpan") { _, _ ->
                var mask = 0
                for (i in 0 until 7) if (dayBoxes[i].isChecked) mask = mask or (1 shl i)
                if (mask == 0) mask = 127
                fun num(e: EditText, max: Int): Int = (e.text.toString().toIntOrNull() ?: 0).coerceIn(0, max)
                val full = fullCb.isChecked
                val newCfg = cfg.copy(
                    wait = num(wait, 600),
                    pass = passOptions[passSp.selectedItemPosition],
                    cooldown = num(cool, 1440),
                    mode = if (full) 0 else 1,
                    schOn = full && whenSp.selectedItemPosition == 1,
                    start = start,
                    end = end,
                    days = mask,
                    limitMin = if (useCb.isChecked) num(limit, 1440) else 0,
                    hourLimit = if (useCb.isChecked) num(hourLim, 60) else 0,
                    launchDay = if (launchCb.isChecked) num(launchD, 1000) else 0,
                    launchHour = if (launchCb.isChecked) num(launchH, 100) else 0,
                    twUse = if (twCb.isChecked) num(twUse, 1440) else 0,
                    twWait = if (twCb.isChecked) num(twWait, 1440) else 0,
                    twAdaptive = twAd.isChecked,
                    notif = notifCb.isChecked
                )
                val locRule = if (locUi.cb.isChecked) locUi.read() else null
                if (locUi.cb.isChecked && locRule == null) {
                    toast("Lokasi tidak valid: isi lintang dan bujur dengan benar (atau pakai lokasi saya sekarang).")
                    return@setPositiveButton
                }
                Store.setAppCfg(this, pkg, newCfg)
                LocationRules.set(this, "app_$pkg", locRule)
                if (locRule != null && (!LocationTracker.hasPermission(this) || !LocationTracker.hasBackground(this))) askLocationPermission()
                WaitTimer.onConfigSaved(this, pkg, cfg, newCfg)
                if (newCfg.twUse > 0 && newCfg.twWait > 0 && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
                }
                if (strict) Store.lockApp(this, pkg)
                if (newCfg.notif && !SelfProtector.notifListenerOn(this)) {
                    if (!SelfProtector.enableNotifListener(this)) {
                        toast("Nyalakan Akses Notifikasi untuk Penjaga Ketat di bagian Notifikasi di bawah.")
                    }
                }
                if (!full && !useCb.isChecked && !launchCb.isChecked && !twCb.isChecked && locRule == null) {
                    toast("Belum ada kondisi yang dipilih: aplikasi ini tidak akan terblokir.")
                }
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun editSchedule(onDone: (Sched) -> Unit) {
        var start = 21 * 60
        var end = 5 * 60
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))
        val startBtn = Button(this)
        startBtn.text = "Mulai: ${fmt(start)}"
        startBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, mi ->
                start = h * 60 + mi
                startBtn.text = "Mulai: ${fmt(start)}"
            }, start / 60, start % 60, true).show()
        }
        box.addView(startBtn)
        val endBtn = Button(this)
        endBtn.text = "Selesai: ${fmt(end)}"
        endBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, mi ->
                end = h * 60 + mi
                endBtn.text = "Selesai: ${fmt(end)}"
            }, end / 60, end % 60, true).show()
        }
        box.addView(endBtn)
        val names = arrayOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
        val dayBoxes = ArrayList<CheckBox>()
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (i in 0 until 7) {
            val d = CheckBox(this)
            d.text = names[i]
            d.textSize = 10f
            d.isChecked = true
            dayBoxes.add(d)
            row.addView(d)
        }
        box.addView(row)
        android.app.AlertDialog.Builder(this)
            .setTitle("Tambah jadwal")
            .setView(box)
            .setPositiveButton("Tambah") { _, _ ->
                var mask = 0
                for (i in 0 until 7) if (dayBoxes[i].isChecked) mask = mask or (1 shl i)
                if (mask == 0) mask = 127
                onDone(Sched(start, end, mask))
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pickApps(chosen: MutableSet<String>, onDone: () -> Unit) {
        val rows = loadApps()
        val labels = Array(rows.size) { rows[it].label }
        val checked = BooleanArray(rows.size) { rows[it].pkg in chosen }
        android.app.AlertDialog.Builder(this)
            .setTitle("Pilih aplikasi")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) chosen.add(rows[which].pkg) else chosen.remove(rows[which].pkg)
            }
            .setPositiveButton("OK") { _, _ -> onDone() }
            .show()
    }

    private fun scheduleTileLabel(): String {
        val n = ScheduleNotifier.activeNow(this).size
        return if (n > 0) "$n jadwal aktif" else "Atur per hari & jam"
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this)
        et.setText(initial)
        et.setSingleLine(true)
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("Simpan") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isNotEmpty()) onOk(v)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun renderSchedule(strict: Boolean) {
        val profiles = ProfileStore.load(this)
        header("Sekarang")
        val act = ScheduleNotifier.activeNow(this)
        put(text(if (act.isEmpty()) "Tidak ada profil berjadwal yang sedang berjalan." else
            act.joinToString("\n") { "• " + it.name }, 13f, act.isNotEmpty()))
        val nb = ScheduleNotifier.nextBoundary(this, System.currentTimeMillis())
        if (nb != null) {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = nb.first
            val today = java.util.Calendar.getInstance()
            val same = cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) &&
                cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
            val dn = arrayOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")[(cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7]
            put(text("Berikutnya: " + nb.second + " • " + (if (same) "hari ini" else dn) + " " +
                fmt(cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)), 12f, false, C_SUB))
        }
        put(text("Jadwal berjalan otomatis tanpa membuka aplikasi dan tetap berlaku setelah restart. Profil mati tidak berjalan. Jadwal yang saling tumpang tindih digabung: diblokir bila salah satu aktif.", 12f))

        header("Per hari")
        val names = arrayOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")
        for (d in 0 until 7) {
            val rows = ArrayList<Pair<Int, String>>()
            for (p in profiles) {
                if (!p.enabled) continue
                for (sc in p.schedules) {
                    if ((sc.days and (1 shl d)) == 0) continue
                    val over = sc.start > sc.end
                    rows.add(Pair(sc.start, fmt(sc.start) + "-" + fmt(sc.end) + (if (over) " (s/d esok)" else "") + "  " + p.name))
                }
            }
            rows.sortBy { it.first }
            put(text(names[d] + (if (rows.isEmpty()) ": tidak ada" else ""), 13f, true))
            for (r in rows) put(text("   " + r.second, 12f, false, C_SUB))
        }

        header("Profil")
        for (p in profiles) {
            val sch = if (p.schedules.isEmpty()) "tanpa jadwal (aktif terus bila dinyalakan)" else
                p.schedules.joinToString("; ") { "${fmt(it.start)}-${fmt(it.end)} ${dayNames(it.days)}" }
            put(text(p.name + (if (p.enabled) "" else " (mati)"), 13f, true))
            put(text(sch, 12f, false, C_SUB))
            put(button("Atur jadwal & isi: " + p.name) { editProfile(p, strict) })
            put(button("Ganti nama") {
                if (strict) toast("Mode Ketat aktif: tidak bisa mengubah profil") else
                    askName("Nama profil", p.name) { ProfileStore.rename(this, p.id, it); render() }
            })
            put(button("Hapus profil") {
                if (strict) toast("Mode Ketat aktif: profil tidak bisa dihapus")
                else android.app.AlertDialog.Builder(this)
                    .setTitle("Hapus " + p.name + "?")
                    .setPositiveButton("Hapus") { _, _ ->
                        if (!ProfileStore.remove(this, p.id)) toast("Minimal harus ada satu profil")
                        render()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            })
        }
        put(button("Tambah profil baru") {
            askName("Nama profil baru", "") {
                val np = ProfileStore.add(this, it)
                render()
                editProfile(np, strict)
            }
        })

        header("Pemberitahuan")
        val cb = CheckBox(this)
        cb.text = "Beri tahu saat profil mulai dan selesai"
        cb.isChecked = ScheduleNotifier.enabled(this)
        cb.setOnClickListener {
            val v = cb.isChecked
            if (v && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1004)
            ScheduleNotifier.setEnabled(this, v)
            render()
        }
        put(cb)
        put(text("Hanya pemberitahuan. Pemblokiran tidak bergantung padanya. Bila mati, tidak ada alarm sama sekali (hemat baterai). Waktu pemberitahuan bisa meleset beberapa menit karena mode hemat daya Android.", 12f, false, C_SUB))
    }

    private fun renderProfiles(strict: Boolean) {
        header("Profil")
        put(text("Profil (Kerja, Belajar, Tidur, Mode Ketat, Custom) memblokir aplikasinya selama jadwalnya berjalan, dan menambah kata kunci dan situs selama aktif. Profil tanpa jadwal aktif terus selama dinyalakan. Batas harian lebih dari 0: aplikasi boleh dipakai sampai batas itu, setelah itu diblokir. Saat Mode Ketat aktif, profil hanya bisa diperketat.", 12f))
        for (p in ProfileStore.load(this)) {
            val live = ProfileEngine.isActive(this, p)
            val state = if (!p.enabled) "mati" else if (live) "AKTIF sekarang" else "menunggu jadwal"
            header(p.name + " • " + state)
            val sch = if (p.schedules.isEmpty()) "tanpa jadwal" else
                p.schedules.joinToString("; ") { "${fmt(it.start)}-${fmt(it.end)} ${dayNames(it.days)}" }
            put(text("${p.apps.size} aplikasi • ${p.keywords.size} kata kunci • ${p.domains.size} situs • $sch" +
                (if (p.limitMin > 0) " • batas ${p.limitMin} mnt" else ""), 12f, false, C_SUB))
            val cb = CheckBox(this)
            cb.text = "Aktifkan profil " + p.name
            cb.isChecked = p.enabled
            cb.setOnClickListener {
                val v = cb.isChecked
                if (!v && strict) {
                    toast("Mode Ketat aktif: profil tidak bisa dimatikan")
                } else {
                    ProfileStore.replace(this, p.copy(enabled = v))
                }
                render()
            }
            put(cb)
            put(button("Atur profil: " + p.name) { editProfile(p, strict) })
        }
    }

    private fun editProfile(p: Profile, strict: Boolean) {
        val apps = HashSet(p.apps)
        val scheds = ArrayList(p.schedules)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))
        if (strict) box.addView(text("Mode Ketat aktif: perubahan hanya menambah, tidak mengurangi.", 12f, true, C_BAD))

        box.addView(text("Batas harian per aplikasi (menit, 0 = blokir penuh)", 12f))
        val limit = numberField(p.limitMin)
        box.addView(limit)

        val appsBtn = Button(this)
        appsBtn.text = "Pilih aplikasi (${apps.size})"
        appsBtn.setOnClickListener {
            pickApps(apps) { appsBtn.text = "Pilih aplikasi (${apps.size})" }
        }
        box.addView(appsBtn)

        box.addView(text("Kata kunci (pisahkan dengan koma)", 12f))
        val kw = EditText(this)
        kw.setText(p.keywords.sorted().joinToString(", "))
        box.addView(kw)

        box.addView(text("Situs (pisahkan dengan koma)", 12f))
        val dm = EditText(this)
        dm.setText(p.domains.sorted().joinToString(", "))
        box.addView(dm)

        box.addView(text("Jadwal (boleh lebih dari satu)", 12f, true))
        val schedBox = LinearLayout(this)
        schedBox.orientation = LinearLayout.VERTICAL
        box.addView(schedBox)

        fun refreshScheds() {
            schedBox.removeAllViews()
            for (i in scheds.indices) {
                val s = scheds[i]
                schedBox.addView(button("Hapus: ${fmt(s.start)}-${fmt(s.end)} ${dayNames(s.days)}") {
                    if (strict) {
                        toast("Mode Ketat aktif: jadwal tidak bisa dihapus")
                    } else {
                        scheds.removeAt(i)
                        refreshScheds()
                    }
                })
            }
        }
        refreshScheds()
        box.addView(button("Tambah jadwal") {
            editSchedule { s ->
                scheds.add(s)
                refreshScheds()
            }
        })

        val scrollBox = ScrollView(this)
        scrollBox.addView(box)
        android.app.AlertDialog.Builder(this)
            .setTitle("Profil: " + p.name)
            .setView(scrollBox)
            .setPositiveButton("Simpan") { _, _ ->
                val newKw = kw.text.toString().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                val newDm = dm.text.toString().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                var np = p.copy(
                    apps = apps,
                    keywords = newKw,
                    domains = newDm,
                    schedules = scheds,
                    limitMin = (limit.text.toString().toIntOrNull() ?: 0).coerceIn(0, 1440)
                )
                if (strict) np = ProfileStore.mergeStrict(p, np)
                // Menyimpan profil yang berisi aturan otomatis mengaktifkannya (lebih ketat, tidak pernah mematikan).
                if (!np.enabled && (np.apps.isNotEmpty() || np.keywords.isNotEmpty() || np.domains.isNotEmpty())) {
                    np = np.copy(enabled = true)
                    toast("Profil ${np.name} diaktifkan")
                }
                ProfileStore.replace(this, np)
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private var protQuery = ""

    private fun renderProtected(strict: Boolean) {
        header("Lindungi aplikasi lain dari uninstall")
        put(text("Saat Mode Ketat aktif, halaman Pengaturan atau pemasang yang menampilkan nama aplikasi ini (copot, hentikan paksa, matikan aksesibilitas) langsung diblokir. Ini bukan penguncian sistem, tapi penghalang yang kuat.", 12f))
        val chosen = Store.protectedApps(this)
        val all = loadApps()
        val byPkg = all.associateBy { it.pkg }

        // Yang sudah dilindungi (termasuk yang tidak punya ikon peluncur).
        val chosenRows = chosen.map { byPkg[it] ?: AppRow(it, it) }.sortedBy { it.label.lowercase() }
        header("Dilindungi (${chosenRows.size})")
        for (a in chosenRows) put(protectedRow(a, true, strict))

        header("Tambah aplikasi")
        val q = EditText(this)
        q.hint = "Cari nama aplikasi"
        q.setSingleLine()
        q.setText(protQuery)
        put(q)
        put(button("Cari") {
            protQuery = q.text.toString().trim().lowercase()
            render()
        })
        val others = all.filter { it.pkg !in chosen }
        val shown = if (protQuery.isEmpty()) others
        else others.filter { it.label.lowercase().contains(protQuery) || it.pkg.contains(protQuery) }
        if (shown.isEmpty()) {
            put(text("Tidak ada aplikasi yang cocok.", 12f, false, C_SUB))
        } else {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(10), dp(4), dp(10), dp(4))
            for (a in shown) col.addView(protectedRow(a, false, strict))
            put(scrollBox(360, col))
        }
    }

    private fun protectedRow(a: AppRow, on: Boolean, strict: Boolean): View {
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(0, dp(6), 0, dp(6))
        val icon = ImageView(this)
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(a.pkg))
        } catch (e: Exception) {
            // tanpa ikon
        }
        icon.layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        top.addView(icon)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(text(a.label, 14f, true))
        col.addView(text(a.pkg, 10f, false, C_SUB))
        top.addView(col)
        val sw = Switch(this)
        sw.isChecked = on
        sw.setOnClickListener {
            val checked = sw.isChecked
            if (checked) {
                Store.addProtected(this@MainActivity, a.pkg)
            } else if (strict || a.pkg in Config.SEED_PROTECTED) {
                toast(if (strict) "Mode Ketat aktif: tidak bisa melepas perlindungan" else "Aplikasi ini selalu dilindungi")
            } else {
                Store.removeProtected(this@MainActivity, a.pkg)
            }
            render()
        }
        top.addView(sw)
        return top
    }

    private fun renderCategories(strict: Boolean) {
        header("Kategori situs")
        put(text("Nyalakan kategori untuk memblokir situs dan kata kunci sekaligus. Subdomain ikut terblokir (mis. m.situs.com). Saat Mode Ketat aktif, kategori hanya bisa dinyalakan, tidak bisa dimatikan.", 12f))
        for (cat in Categories.ALL) {
            val on = Store.categoryOn(this, cat.id)
            put(CheckBox(this).apply {
                text = cat.name + "\n" + cat.desc
                textSize = 13f
                isChecked = on
                setOnClickListener { v ->
                    val now = (v as CheckBox).isChecked
                    if (!now && strict) {
                        toast("Mode Ketat aktif: kategori tidak bisa dimatikan")
                    } else {
                        gateOff(!now, "mematikan kategori ${cat.name}") {
                            Store.setCategory(this@MainActivity, cat.id, now)
                            Store.log(this@MainActivity, "Kategori ${cat.name}: " + (if (now) "ON" else "OFF"))
                        }
                    }
                    render()
                }
            })
        }
    }

    private fun renderMotivation(strict: Boolean) {
        header("Teks motivasi")
        put(text("Ditampilkan di layar blokir.", 12f))
        val et = EditText(this).apply {
            setText(Store.motivation(this@MainActivity))
            textSize = 13f
        }
        put(et)
        put(button("Simpan teks motivasi") {
            Store.setMotivation(this, et.text.toString())
            toast("Tersimpan")
        })
    }

    private fun reasonName(r: String): String = when (r) {
        "app" -> "Aplikasi diblokir"
        "limit" -> "Batas harian"
        "hour" -> "Batas per jam"
        "launch" -> "Batas peluncuran"
        "wait" -> "Timer tunggu"
        "until" -> "Blokir penuh sementara"
        "profile" -> "Profil"
        "content" -> "Konten/situs/kata kunci"
        "dns" -> "Situs diblokir (DNS)"
        "protect" -> "Perlindungan pengaturan"
        "focus" -> "Mode Fokus"
        else -> r
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** Kartu ringkas "Hari ini" di beranda. */
    private fun renderTodayCard(protectedOk: Boolean) {
        header("Hari ini")
        try {
            val s = Dashboard.get(this)
            if (!s.usageAccess) {
                put(text("Aktifkan Akses Penggunaan agar pemakaian tercatat.", 12f, false, C_SUB))
            } else {
                put(text(Dashboard.dur(s.todayMs), 28f, true, C_PRIMARY))
                put(text("penggunaan • ${s.blocksToday}x diblokir", 12f, false, C_SUB))
                for (it in s.topAppsToday.take(3)) put(text("${it.label} — ${Dashboard.dur(it.ms)}", 13f))
            }
        } catch (t: Throwable) {
            put(text("Ringkasan belum tersedia.", 12f, false, C_SUB))
        }
        if (Focus.running(this)) {
            val left = Focus.leftMs(this) / 60000L + 1L
            put(text("🎯 Mode Fokus " + (if (Store.pausedNow(this)) "dijeda" else "aktif") + ": sisa sekitar $left menit", 13f, true, C_PRIMARY))
        }
        status(protectedOk, if (protectedOk) "Perlindungan aktif" else "Perlindungan belum lengkap")
        put(button("Lihat dashboard") { goScreen("stats") })
    }

    private fun focusTileLabel(): String {
        if (!Focus.running(this)) return "Belum aktif"
        val left = Focus.leftMs(this) / 60000L + 1L
        return (if (Store.pausedNow(this)) "dijeda • " else "") + "sisa $left menit"
    }

    /** Dialog daftar teks (satu item per baris). [normalize] menormalkan domain. */
    private fun editListDialog(title: String, current: Set<String>, domain: Boolean, onSave: (Set<String>) -> Unit) {
        val et = EditText(this).apply {
            setText(current.sorted().joinToString("\n"))
            hint = if (domain) "satu situs per baris, mis. contoh.com" else ""
            minLines = 4
            gravity = Gravity.TOP
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("Simpan") { _, _ ->
                val out = HashSet<String>()
                for (line in et.text.toString().split('\n', ',', ';')) {
                    val v = if (domain) Rules.normalizeDomain(line) else line.trim()
                    if (v.isNotEmpty()) out.add(v)
                }
                onSave(out)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun renderFocus(strict: Boolean) {
        val running = Focus.running(this)
        val locked = running && Store.strictOn(this)
        header("Mode Fokus")
        put(text("Fokus memblokir pilihanmu untuk sementara, di atas aturan yang sudah ada. Aturan permanen tidak diubah dan kembali normal saat Fokus selesai.", 12f, false, C_SUB))
        if (running) {
            val paused = Store.pausedNow(this)
            val left = Focus.leftMs(this) / 60000L + 1L
            put(text("Sisa sekitar $left menit", 24f, true, C_PRIMARY))
            put(text(
                if (paused) "Dijeda oleh Mode Off. Dilanjutkan otomatis dengan sisa waktu ini."
                else "Fokus sedang berjalan.",
                12f, false, C_SUB
            ))
            put(button("Tambah 15 menit") {
                Focus.extend(this, 15)
                render()
            })
            put(button("Hentikan Fokus") {
                if (Store.strictOn(this)) {
                    toast("Mode Ketat aktif: Fokus tidak bisa dihentikan sebelum waktunya")
                } else {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Hentikan Mode Fokus?")
                        .setPositiveButton("Hentikan") { _, _ ->
                            Focus.stop(this)
                            render()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            })
            handler.postDelayed({ if (screen == "focus") render() }, 30000L)
        } else {
            put(text("Mulai Mode Fokus", 14f, true))
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            fun startFor(min: Int) {
                if (Focus.cfg(this).isEmpty) {
                    toast("Pilih dulu aplikasi, situs, atau kategori yang diblokir di bawah.")
                } else {
                    Focus.start(this, min)
                    render()
                }
            }
            row.addView(pill("30 mnt", false, C_PRIMARY) { startFor(30) })
            row.addView(pill("1 jam", false, C_PRIMARY) { startFor(60) })
            row.addView(pill("2 jam", false, C_PRIMARY) { startFor(120) })
            row.addView(pill("Custom", false, C_PRIMARY) {
                val et = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    hint = "menit (1-1440)"
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("Durasi Fokus")
                    .setView(et)
                    .setPositiveButton("Mulai") { _, _ ->
                        val m = (et.text.toString().toIntOrNull() ?: 0).coerceIn(0, 1440)
                        if (m > 0) startFor(m) else toast("Isi durasi dalam menit")
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            })
            put(row)
        }

        val x = Focus.cfg(this)
        header("Diblokir saat Fokus")
        if (locked) put(text("Mode Ketat aktif dan Fokus berjalan: daftar ini terkunci.", 11f, false, C_SUB))
        put(button("Aplikasi (${x.blockApps.size})") {
            if (locked) toast("Terkunci") else {
                val chosen = x.blockApps.toMutableSet()
                pickApps(chosen) {
                    Focus.setCfg(this, Focus.Cfg(chosen, x.blockSites, x.blockCats, x.allowApps, x.allowSites))
                    render()
                }
            }
        })
        put(button("Situs (${x.blockSites.size})") {
            if (locked) toast("Terkunci") else editListDialog("Situs diblokir saat Fokus", x.blockSites, true) { s ->
                Focus.setCfg(this, Focus.Cfg(x.blockApps, s, x.blockCats, x.allowApps, x.allowSites))
                render()
            }
        })
        put(button("Kategori (${x.blockCats.size})") {
            if (locked) toast("Terkunci") else {
                val labels = Focus.CATS.map { it.name }.toTypedArray()
                val checked = BooleanArray(Focus.CATS.size) { Focus.CATS[it].key in x.blockCats }
                android.app.AlertDialog.Builder(this)
                    .setTitle("Kategori yang diblokir")
                    .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
                    .setPositiveButton("Simpan") { _, _ ->
                        val cats = HashSet<String>()
                        for (i in Focus.CATS.indices) if (checked[i]) cats.add(Focus.CATS[i].key)
                        Focus.setCfg(this, Focus.Cfg(x.blockApps, x.blockSites, cats, x.allowApps, x.allowSites))
                        render()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        })
        put(text("Kategori aplikasi memakai kategori bawaan Android, jadi tidak semua aplikasi terdeteksi. Aplikasi bank, telepon, Pengaturan, dan Penjaga Ketat tidak pernah ikut diblokir Fokus.", 11f, false, C_SUB))

        header("Pengecualian (ALLOW)")
        put(text("Pengecualian hanya membebaskan dari aturan Fokus. Aturan permanen (blokir penuh, timer, jadwal, profil) tetap berlaku.", 12f, false, C_SUB))
        put(button("Aplikasi diizinkan (${x.allowApps.size})") {
            if (locked) toast("Terkunci") else {
                val chosen = x.allowApps.toMutableSet()
                pickApps(chosen) {
                    Focus.setCfg(this, Focus.Cfg(x.blockApps, x.blockSites, x.blockCats, chosen, x.allowSites))
                    render()
                }
            }
        })
        put(button("Situs diizinkan (${x.allowSites.size})") {
            if (locked) toast("Terkunci") else editListDialog("Situs diizinkan saat Fokus", x.allowSites, true) { s ->
                Focus.setCfg(this, Focus.Cfg(x.blockApps, x.blockSites, x.blockCats, x.allowApps, s))
                render()
            }
        })
    }

    private fun barLine(label: String, ms: Long, maxMs: Long, suffix: String = ""): String {
        val bars = (ms * 14L / Math.max(1L, maxMs)).toInt()
        return label + "  " + "█".repeat(bars) + " " + Dashboard.dur(ms) + suffix
    }

    private fun showHistory(key: String, title: String, isSite: Boolean) {
        val rows = Dashboard.history(this, key, isSite)
        val maxMs = Math.max(1L, rows.maxOf { it.ms })
        val sb = StringBuilder()
        for (r in rows) sb.append(barLine(r.label, r.ms, maxMs, if (r.blocks > 0) " • ${r.blocks}x blokir" else "")).append('\n')
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(sb.toString().trim())
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun renderDashboard(strict: Boolean) {
        val s = Dashboard.cached()
        if (s == null) {
            header("Statistik")
            if (statsFailed) {
                put(text("Statistik belum tersedia.", 12f, false, C_SUB))
                put(button("Coba lagi") { statsFailed = false; loadStatsAsync(); render() })
            } else {
                put(text("Memuat statistik…", 12f, false, C_SUB))
                loadStatsAsync()
            }
            return
        }
        if (!Dashboard.isFresh() && !statsFailed) loadStatsAsync()
        if (!s.usageAccess) {
            header("Akses Penggunaan")
            put(text("Aktifkan Akses Penggunaan agar pemakaian aplikasi tercatat. Angka pemakaian di bawah kosong sampai itu aktif.", 12f))
        }

        renderStreak()
        renderHourMap()

        header("Hari ini")
        put(text(Dashboard.dur(s.todayMs), 30f, true, C_PRIMARY))
        val diff = if (s.yesterdayMs > 0L) {
            val pct = ((s.todayMs - s.yesterdayMs) * 100L / s.yesterdayMs).toInt()
            " • " + (if (pct >= 0) "naik $pct%" else "turun ${-pct}%") + " dari kemarin"
        } else ""
        put(text("penggunaan (aplikasi yang bisa dibuka, tanpa launcher dan Pengaturan)$diff", 11f, false, C_SUB))
        put(text("${s.blocksToday}x diblokir • ${s.attemptsToday}x percobaan membuka aplikasi/situs terblokir", 13f))
        if (s.reasonsToday.isNotEmpty()) {
            put(text(s.reasonsToday.joinToString(", ") { reasonName(it.first) + " " + it.second }, 11f, false, C_SUB))
        }

        header("Penggunaan hari ini")
        if (s.topAppsToday.isEmpty()) put(text("Belum ada data.", 12f, false, C_SUB))
        val maxA = Math.max(1L, s.topAppsToday.maxOfOrNull { it.ms } ?: 1L)
        for (it in s.topAppsToday) put(text(barLine(it.label, it.ms, maxA), 12f))
        if (s.topSitesToday.isNotEmpty()) {
            put(text("Situs (yang punya aturan; sudah termasuk dalam waktu Chrome)", 12f, true))
            val maxS = Math.max(1L, s.topSitesToday.maxOf { it.ms })
            for (it in s.topSitesToday) put(text(barLine(it.label, it.ms, maxS), 12f))
        }

        if (s.appCategories.isNotEmpty() || s.siteCategories.isNotEmpty()) {
            header("Kategori hari ini")
            for ((n, ms) in s.appCategories.take(6)) put(text("$n — ${Dashboard.dur(ms)}", 12f))
            if (s.siteCategories.isNotEmpty()) {
                put(text("Situs:", 12f, true))
                for ((n, ms) in s.siteCategories) put(text("$n — ${Dashboard.dur(ms)}", 12f))
            }
        }

        header("7 hari terakhir")
        put(text("Pemakaian per hari (menit)", 11f, false, C_SUB))
        put(BarChartView(this, s.days.map { it.ms / 60000L }, s.days.map { it.label.substringBefore(' ') }, C_PRIMARY, C_TEXT, C_SUB) { "${it}m" }
            .apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)) })
        put(text("Blokir per hari", 11f, false, C_SUB))
        put(BarChartView(this, s.days.map { it.blocks.toLong() }, s.days.map { it.label.substringBefore(' ') }, C_BAD, C_TEXT, C_SUB) { "${it}x" }
            .apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)) })
        put(text("Total ${Dashboard.dur(s.weekMs)} • ${s.blocksWeek}x diblokir", 13f, true))
        val wk = StatsMath.percentChange(s.weekMs, s.prevWeekMs)
        if (wk != null) {
            put(text("Minggu lalu ${Dashboard.dur(s.prevWeekMs)}: " + (if (wk >= 0) "naik $wk%" else "turun ${-wk}%"), 12f, false, C_SUB))
        } else {
            put(text("Perbandingan dengan minggu lalu muncul setelah ada data 14 hari.", 11f, false, C_SUB))
        }

        header("30 hari terakhir")
        put(text("Pemakaian per hari (menit), data yang tersimpan paling lama 35 hari", 11f, false, C_SUB))
        put(BarChartView(this, s.days30.map { it.second / 60000L }, s.days30.map { it.first.substring(8) }, C_PRIMARY, C_TEXT, C_SUB) { "${it}m" }
            .apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)) })

        header("Mode Fokus")
        put(text("Data belum tersedia: riwayat sesi Fokus belum dicatat oleh aplikasi.", 12f, false, C_SUB))

        header("Teratas 7 hari")
        if (s.topAppsWeek.isEmpty()) put(text("Belum ada data.", 12f, false, C_SUB))
        for (it in s.topAppsWeek) put(text("${it.label} — ${Dashboard.dur(it.ms)}", 12f))
        if (s.topSitesWeek.isNotEmpty()) {
            put(text("Situs:", 12f, true))
            for (it in s.topSitesWeek) put(text("${it.label} — ${Dashboard.dur(it.ms)}", 12f))
        }

        header("Riwayat per aplikasi atau situs")
        put(text("Pilih satu untuk melihat riwayat 14 hari dan jumlah blokirnya.", 12f, false, C_SUB))
        val entries = ArrayList<Triple<String, String, Boolean>>()
        for (it in s.topAppsWeek) entries.add(Triple(it.key, it.label, false))
        for (it in s.topSitesWeek) entries.add(Triple(it.key, it.label, true))
        for (d in Store.domains(this).sorted()) if (entries.none { it.first == d }) entries.add(Triple(d, d, true))
        put(button("Pilih aplikasi/situs") {
            if (entries.isEmpty()) {
                toast("Belum ada data")
            } else {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Pilih")
                    .setItems(entries.map { (if (it.third) "🌐 " else "") + it.second }.toTypedArray()) { _, i ->
                        showHistory(entries[i].first, entries[i].second, entries[i].third)
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        })
        put(text("Catatan: pemakaian situs hanya tercatat untuk situs yang punya aturan dan saat kolom alamat Chrome terlihat.", 11f, false, C_SUB))

        put(button("Hapus statistik") {
            if (strict) toast("Mode Ketat aktif: statistik terkunci") else { StatsDb.clear(this); Dashboard.invalidate(); render() }
        })
    }

    private fun renderStats(strict: Boolean) {
        header("Statistik")
        if (!Foreground.hasUsageAccess(this)) {
            put(text("Aktifkan Akses Penggunaan agar pemakaian aplikasi tercatat.", 12f))
        } else {
            UsageTracker.get(this)
        }
        try {
            val dayStart = UsageTracker.startOfDay()
            val today = StatsDb.blocksByReason(this, dayStart)
            val week = StatsDb.blocksByReason(this, dayStart - 6L * 86400000L)
            put(text("Blokir hari ini: " + (if (today.isEmpty()) "0" else today.joinToString(", ") { reasonName(it.first) + " " + it.second }), 12f))
            put(text("Blokir 7 hari: " + (if (week.isEmpty()) "0" else week.joinToString(", ") { reasonName(it.first) + " " + it.second }), 12f))

            val totals = StatsDb.dailyTotals(this, 7)
            val maxMs = Math.max(1L, totals.maxOf { it.second })
            put(text("Pemakaian 7 hari (semua aplikasi tercatat):", 12f, true))
            for ((day, ms) in totals) {
                val bars = (ms * 20L / maxMs).toInt()
                val line = day.substring(5) + "  " + "█".repeat(bars) + " " + (ms / 60000L) + " mnt"
                put(text(line, 11f))
            }
            val top = StatsDb.topApps(this, 7, 8)
            if (top.isNotEmpty()) {
                put(text("Aplikasi teratas 7 hari:", 12f, true))
                for ((pkg, ms, l) in top) put(text(appLabel(pkg) + "  " + (ms / 60000L) + " mnt, " + l + "x buka", 11f))
            }
        } catch (t: Throwable) {
            put(text("Statistik belum tersedia.", 12f))
        }
        put(button("Hapus statistik") {
            if (strict) toast("Mode Ketat aktif: statistik terkunci") else { StatsDb.clear(this); render() }
        })
    }

    private fun renderVpn(strict: Boolean) {
        header("VPN DNS (filter situs untuk semua aplikasi)")
        put(text("VPN lokal yang hanya memeriksa nama situs (DNS) dan menolak situs terblokir, termasuk dari aplikasi dan browser dalam aplikasi. Data lain tidak lewat VPN. Aturan yang dipakai sama dengan daftar situs, kata kunci, kategori dan profil aktif.", 12f))
        val st = VpnStatus.state
        val wanted = Store.vpnOn(this)
        status(st == VpnState.RUNNING, "VPN DNS: " + VpnStatus.summary())
        if (wanted) {
            put(text("Query DNS: ${VpnStatus.qTotal.get()} • diblokir ${VpnStatus.qBlocked.get()} • diteruskan ${VpnStatus.qForwarded.get()} • cache ${VpnStatus.qCached.get()} • gagal ${VpnStatus.qFailed.get()}", 11f, false, C_SUB))
            put(button("Segarkan status") { render() })
            put(button("Sambung ulang VPN") {
                VpnManager.restart(this)
                handler.postDelayed({ render() }, 1500L)
            })
        }
        if (st == VpnState.FAILSAFE) {
            put(text("VPN dijeda otomatis karena DNS tidak sehat. Internet tetap jalan lewat jaringan biasa. VPN mencoba lagi sendiri.", 12f, true, Color.rgb(180, 90, 0)))
        }
        if (!wanted || st == VpnState.NEED_PERMISSION) {
            put(button(if (wanted) "Beri izin VPN" else "Nyalakan VPN DNS") {
                val i = VpnService.prepare(this)
                if (i != null) {
                    startActivityForResult(i, 77)
                } else {
                    VpnManager.start(this)
                    handler.postDelayed({ render() }, 1000L)
                }
            })
        }
        if (wanted) {
            put(button("Matikan VPN DNS") {
                if (strict) {
                    toast("Mode Ketat aktif: VPN DNS tidak bisa dimatikan")
                } else {
                    textGate("mematikan VPN DNS", {
                        VpnManager.stop(this)
                        handler.postDelayed({ render() }, 500L)
                    }, { render() })
                }
            })
        }
        put(CheckBox(this).apply {
            text = "Kecualikan aplikasi bank dari VPN (wondr, BRImo, myBCA, Livin, Bibit, dll.)"
            isChecked = Store.vpnExcludeBanks(this@MainActivity)
            setOnClickListener {
                if (!isChecked && strict) {
                    toast("Mode Ketat aktif: tidak bisa dimatikan")
                    render()
                } else {
                    Store.setVpnExcludeBanks(this@MainActivity, isChecked)
                    if (Store.vpnOn(this@MainActivity)) VpnManager.restart(this@MainActivity)
                }
            }
        })
        val exc = Store.vpnExcluded(this)
        put(button("Aplikasi lain yang dikecualikan dari VPN (${exc.size})") {
            if (strict) {
                toast("Mode Ketat aktif: pengaturan terkunci")
            } else {
                val chosen = exc.toMutableSet()
                pickApps(chosen) {
                    Store.setVpnExcluded(this, chosen)
                    if (Store.vpnOn(this)) VpnManager.restart(this)
                    render()
                }
            }
        })
        put(text("Aplikasi yang dikecualikan tidak lewat filter VPN (situsnya tidak terblokir lewat DNS), tetapi internetnya tidak tersentuh sama sekali.", 11f, false, C_SUB))
        put(text("DNS upstream", 12f, true))
        val upSp = spinnerOf(
            listOf("Cloudflare Family 1.1.1.3 (ikut memblokir dewasa dan malware)", "Cloudflare 1.1.1.1", "Google 8.8.8.8"),
            Store.vpnUpstream(this)
        )
        put(upSp)
        onPick(upSp) { pos ->
            if (pos != Store.vpnUpstream(this)) {
                if (strict) {
                    toast("Mode Ketat aktif: pengaturan terkunci")
                    upSp.setSelection(Store.vpnUpstream(this))
                } else {
                    Store.setVpnUpstream(this, pos)
                }
            }
        }
        put(CheckBox(this).apply {
            text = "Blokir DNS terenkripsi (DoH) umum agar tidak mem-bypass filter"
            isChecked = Store.vpnDoh(this@MainActivity)
            setOnClickListener {
                if (!isChecked && strict) {
                    toast("Mode Ketat aktif: tidak bisa dimatikan")
                    render()
                } else {
                    Store.setVpnDoh(this@MainActivity, isChecked)
                }
            }
        })
        val pdns = try {
            Settings.Global.getString(contentResolver, "private_dns_mode") ?: ""
        } catch (t: Throwable) {
            ""
        }
        if (pdns == "hostname") {
            put(text("Peringatan: Private DNS di HP diatur ke nama host tertentu, sehingga melewati filter ini. Ubah ke Otomatis atau Mati di Pengaturan.", 12f, true, C_BAD))
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        status(batteryOk, "Boleh berjalan di latar belakang tanpa dibatasi baterai")
        if (!batteryOk) {
            put(button("Izinkan berjalan di latar belakang") {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (t: Throwable) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            })
        }
        put(text("Batasan: aplikasi yang memakai DNS terenkripsinya sendiri (mis. pengaturan DNS aman di Chrome ke penyedia lain) bisa lolos. Untuk menjaga VPN tidak dilepas, nyalakan juga VPN Selalu Aktif di Pengaturan > Jaringan > VPN.", 11f))
    }

    private fun renderNotif() {
        header("Pengingat waktu hampir habis")
        put(CheckBox(this).apply {
            text = "Beri tahu 1 menit sebelum waktu penggunaan habis"
            isChecked = TimerNotifier.warnEnabled(this@MainActivity)
            setOnClickListener {
                TimerNotifier.setWarnEnabled(this@MainActivity, isChecked)
                if (isChecked && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1005)
            }
        })
        header("Kontrol notifikasi")
        put(text("Notifikasi aplikasi yang sedang diblokir dihapus bila opsi notifikasi dicentang di aturan aplikasinya (atau aplikasi diblokir profil aktif).", 12f))
        val on = SelfProtector.notifListenerOn(this)
        status(on, "Akses Notifikasi Penjaga Ketat aktif")
        if (!on) {
            put(button("Nyalakan Akses Notifikasi") {
                if (!SelfProtector.enableNotifListener(this)) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                handler.postDelayed({ render() }, 800L)
            })
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 88) {
            inCred = false
            if (resultCode == RESULT_OK) {
                AppLock.markUnlocked()
                credAsked = false
            } else {
                credAsked = true
            }
            return
        }
        if (requestCode == 5101 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            val json = Backup.export(this)
            Thread {
                val ok = try {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    true
                } catch (t: Throwable) {
                    false
                }
                runOnUiThread { toast(if (ok) "Backup tersimpan" else "Gagal menyimpan backup") }
            }.start()
            return
        }
        if (requestCode == 5104 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (t: Throwable) {
                toast("Izin file tidak bisa disimpan permanen. Pilih lokasi lain (mis. Google Drive).")
                return
            }
            AutoBackup.enable(this, uri)
            AutoBackup.now(this) { ok ->
                runOnUiThread {
                    toast(if (ok) "Cadangan otomatis aktif dan sudah tersimpan" else "Cadangan otomatis aktif, tetapi penulisan pertama gagal. Akan dicoba lagi.")
                    render()
                }
            }
            return
        }
        if (requestCode == 5103 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            val txt = FeatureDump.text()
            Thread {
                val ok = try {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(txt.toByteArray(Charsets.UTF_8)) }
                    true
                } catch (t: Throwable) {
                    false
                }
                runOnUiThread { toast(if (ok) "Rekaman tersimpan" else "Gagal menyimpan rekaman") }
            }.start()
            return
        }
        if (requestCode == 5102 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            Thread {
                var text: String? = null
                try {
                    contentResolver.openInputStream(uri)!!.use { ins ->
                        val buf = java.io.ByteArrayOutputStream()
                        val b = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = ins.read(b)
                            if (n < 0) break
                            total += n
                            if (total > 4 * 1024 * 1024) throw java.io.IOException("terlalu besar")
                            buf.write(b, 0, n)
                        }
                        text = buf.toString("UTF-8")
                    }
                } catch (t: Throwable) {
                    text = null
                }
                runOnUiThread { confirmRestore(text) }
            }.start()
            return
        }
        if (requestCode == 77 && resultCode == RESULT_OK) {
            VpnManager.start(this)
            handler.postDelayed({ render() }, 1000L)
        }
    }

    private fun renderBackup(strict: Boolean) {
        header("Backup dan restore")
        put(text("Backup berisi aturan aplikasi dan website, Timer Tunggu, batas harian, jadwal, profil, kategori, Mode Fokus, lokasi, dan pengaturan metode keluar Mode Ketat. Tidak ikut: PIN dan kode teman, timer yang sedang berjalan, serta log. Saat menyimpan, pilih Google Drive di daftar lokasi agar tersimpan di akun Gmail-mu dan tetap ada walau Penjaga Ketat dihapus.", 12f))
        if (strict) put(text("Mode Ketat aktif: restore hanya menambah aturan, tidak mengurangi atau melonggarkan.", 12f, true, C_BAD))
        put(button("Ekspor backup (simpan ke file / Google Drive)") {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/json"
            i.putExtra(Intent.EXTRA_TITLE, Backup.fileName())
            try {
                startActivityForResult(i, 5101)
            } catch (t: Throwable) {
                toast("Tidak ada aplikasi penyimpan file")
            }
        })
        put(button("Impor / restore dari file") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            try {
                startActivityForResult(i, 5102)
            } catch (t: Throwable) {
                toast("Tidak ada aplikasi pemilih file")
            }
        })
        header("Cadangan otomatis")
        put(text("Pilih sebuah file di Google Drive sekali saja. Penjaga Ketat menulis ulang file itu otomatis setiap ada perubahan aturan (paling cepat tiap 5 menit) dan paling lambat sehari sekali. Setelah pasang ulang atau ganti HP, tekan \"Impor / restore dari file\" dan pilih file itu.", 12f))
        if (AutoBackup.enabled(this)) {
            val at = AutoBackup.lastAt(this)
            put(text("Aktif: " + AutoBackup.displayName(this), 13f, true))
            put(text(
                if (at > 0L) "Terakhir tersimpan: " + java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale("id")).format(java.util.Date(at))
                else "Belum pernah tersimpan.",
                12f, false, C_SUB
            ))
            val er = AutoBackup.error(this)
            if (er.isNotEmpty()) put(text("Terakhir gagal: $er (akan dicoba lagi)", 12f, true, C_BAD))
            put(button("Cadangkan sekarang") {
                AutoBackup.now(this) { ok -> runOnUiThread { toast(if (ok) "Tersimpan" else "Gagal menyimpan"); render() } }
            })
            put(button("Matikan cadangan otomatis") {
                AutoBackup.disable(this)
                render()
            })
        } else {
            put(button("Aktifkan cadangan otomatis (pilih file di Google Drive)") {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "application/json"
                i.putExtra(Intent.EXTRA_TITLE, "PenjagaKetat_Otomatis.json")
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                try {
                    startActivityForResult(i, 5104)
                } catch (t: Throwable) {
                    toast("Tidak ada aplikasi penyimpan file")
                }
            })
        }
        val last = Backup.lastSafety(this)
        if (last != null) put(button("Batalkan restore terakhir") {
            val parsed = Backup.parse(try { last.readText() } catch (t: Throwable) { "" })
            if (!parsed.ok) toast("Cadangan sebelum restore tidak bisa dibaca") else confirmRestore(last.readText(), "Kembalikan konfigurasi sebelum restore terakhir?")
        })
    }

    private fun confirmRestore(text: String?, title: String = "Restore backup") {
        if (text == null) {
            toast("File tidak bisa dibaca")
            return
        }
        val parsed = Backup.parse(text)
        if (!parsed.ok) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Backup tidak valid")
                .setMessage(parsed.error + "\nKonfigurasi kamu tidak diubah.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val strict = Store.strictRaw(this)
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(parsed.summary + "\n\n" +
                (if (strict) "Mode Ketat aktif: hanya aturan baru yang ditambahkan." else "Konfigurasi sekarang diganti isi backup. Salinan konfigurasi sekarang disimpan dulu.") )
            .setPositiveButton("Restore") { _, _ ->
                val msg = try { Backup.apply(this, parsed.root!!) } catch (t: Throwable) { "Restore gagal. Konfigurasi lama tetap ada." }
                toast(msg)
                render()
            }
            .setNegativeButton("Batalkan", null)
            .show()
    }

    private fun renderFriend() {
        header("Persetujuan teman")
        put(text("Teman mengetik sebuah kode di HP ini. Hanya hash-nya yang disimpan, dan kode itu menjadi syarat tambahan untuk keluar dari Mode Ketat. Serahkan HP ke temanmu saat mengatur kode dan jangan tanya kodenya. Persetujuan jarak jauh dari HP teman belum tersedia.", 12f))
        val isSet = FriendApproval.isSet(this)
        status(isSet, "Kode teman sudah diatur")
        put(button(if (isSet) "Ganti kode teman" else "Atur kode teman") { friendDialog() })
        if (isSet) put(button("Hapus kode teman") { friendClearDialog() })
    }

    private fun friendDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))
        val pw = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val old = EditText(this)
        old.inputType = pw
        old.hint = "Kode lama"
        if (FriendApproval.isSet(this)) box.addView(old)
        val n1 = EditText(this)
        n1.inputType = pw
        n1.hint = "Kode baru (min 6 karakter)"
        box.addView(n1)
        val n2 = EditText(this)
        n2.inputType = pw
        n2.hint = "Ulangi kode baru"
        box.addView(n2)
        android.app.AlertDialog.Builder(this)
            .setTitle("Kode teman")
            .setMessage("Serahkan HP ke temanmu. Dia yang mengetik kodenya.")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                if (n1.text.toString() != n2.text.toString()) {
                    toast("Kode tidak sama")
                } else {
                    val err = FriendApproval.set(this, old.text.toString(), n1.text.toString())
                    toast(err ?: "Kode teman tersimpan")
                }
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun friendClearDialog() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        et.hint = "Kode teman"
        android.app.AlertDialog.Builder(this)
            .setTitle("Hapus kode teman")
            .setView(et)
            .setPositiveButton("Hapus") { _, _ ->
                val err = FriendApproval.clear(this, et.text.toString())
                toast(err ?: "Kode teman dihapus")
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun renderDetection() {
        header("Deteksi gambar (NSFW)")
        val e = Detection.engine
        status(e.ready, "Mesin deteksi: " + e.name)
        if (!e.ready) {
            put(text("PLACEHOLDER. Antarmuka sudah ada, tetapi belum ada model dan belum ada pengambilan layar yang terhubung, jadi gambar belum diperiksa. Pemblokiran saat ini mengandalkan kata kunci, situs, kategori, dan VPN DNS.", 12f))
        }
    }

    private fun renderWords(strict: Boolean) {
        header("Kata kunci")
        put(text("Pisahkan antara keyword yang hanya diperiksa di URL browser dan keyword yang diperiksa pada teks seluruh layar.", 12f, false, C_SUB))

        header("🕘 Riwayat Situs")
        put(text("Domain utama yang baru terdeteksi dari browser. Riwayat disimpan lokal di perangkat dan terus diperbarui saat situs dibuka.", 12f, false, C_SUB))
        val history = SiteHistory.list(this, 30)
        if (history.isEmpty()) {
            put(text("Belum ada situs yang terdeteksi.", 12f, false, C_SUB))
        } else {
            val historyCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (entry in history) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                }
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val blocked = Store.domains(this).contains(entry.domain)
                col.addView(text(entry.domain + if (blocked) "  • diblokir" else "", 13f, true, if (blocked) C_BAD else C_TEXT))
                col.addView(text("Terakhir: ${SiteHistory.formatTime(entry.lastOpened)} • ${entry.visits}x terdeteksi", 11f, false, C_SUB))
                row.addView(col)
                row.addView(smallButton(if (blocked) "Atur" else "Atur blokir") {
                    editSiteDialog(entry.domain, strict, !Store.domains(this).contains(entry.domain))
                })
                historyCol.addView(row)
            }
            put(scrollBox(320, historyCol))
        }

        header("🛡️ Keyword Bawaan PenjagaKetat")
        put(text("Daftar keyword bawaan yang dapat kamu ubah. Jika terlalu luas, tekan ✕ untuk mematikannya. Jika ingin mengaktifkan kembali, tekan Aktifkan.", 12f, false, C_SUB))
        put(builtinKeywordList())

        header("🔗 Keyword URL")
        put(text("Diperiksa hanya pada kolom alamat/URL semua browser yang dipantau. Tidak memblokir kata yang hanya muncul sebagai teks halaman.", 12f, false, C_SUB))
        val urlInput = EditText(this).apply { hint = "Tambah keyword URL"; setSingleLine() }
        put(urlInput)
        put(button("Tambah keyword URL") {
            val k = urlInput.text.toString().trim()
            if (k.isNotEmpty()) {
                Store.addUrlKeyword(this, k)
                render()
            }
        })
        put(chipList(Store.urlKeywords(this).sorted()) { k ->
            if (strict) toast("Mode Ketat aktif: tidak bisa menghapus")
            else gateOff(true, "menghapus keyword URL") { Store.removeUrlKeyword(this, k) }
        })

        header("📱 Keyword Layar")
        put(text("Diperiksa pada teks dan contentDescription yang dapat dibaca Accessibility di seluruh layar/aplikasi, bukan hanya URL browser.", 12f, false, C_SUB))
        val screenInput = EditText(this).apply { hint = "Tambah keyword layar"; setSingleLine() }
        put(screenInput)
        put(button("Tambah keyword layar") {
            val k = screenInput.text.toString().trim()
            if (k.isNotEmpty()) {
                Store.addScreenKeyword(this, k)
                render()
            }
        })
        put(chipList(Store.screenKeywords(this).sorted()) { k ->
            if (strict) toast("Mode Ketat aktif: tidak bisa menghapus")
            else gateOff(true, "menghapus keyword layar") { Store.removeScreenKeyword(this, k) }
        })

        header("Situs")
        put(text("Dicek di kolom alamat browser dan lewat VPN DNS. Subdomain ikut terblokir.", 12f, false, C_SUB))
        val dInput = EditText(this).apply { hint = "Tambah situs, mis. contoh.com"; setSingleLine() }
        put(dInput)
        put(button("Tambah situs") {
            val d = Rules.normalizeDomain(dInput.text.toString())
            if (d.isNotEmpty()) {
                if (Store.domains(this).contains(d)) {
                    editSiteDialog(d, strict)
                } else {
                    editSiteDialog(d, false, true)
                }
            } else {
                toast("Masukkan domain situs terlebih dahulu")
            }
        })
        val siteCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (d in Store.domains(this).sorted()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text(d, 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(smallButton("Atur") { editSiteDialog(d, strict) })
            row.addView(text("✕", 15f, true, C_BAD).apply {
                setPadding(dp(10), dp(8), dp(6), dp(8))
                setOnClickListener {
                    if (strict) toast("Mode Ketat aktif: tidak bisa menghapus")
                    else gateOff(true, "menghapus situs $d") { Store.removeDomain(this@MainActivity, d) }
                }
            })
            siteCol.addView(row)
        }
        put(if (siteCol.childCount == 0) text("Belum ada.", 12f, false, C_SUB) else scrollBox(220, siteCol))

        // Channel Telegram yang diblokir (terisi otomatis oleh pendeteksi gambar AI). Melepas blokir lewat gerbang yang sama.
        header("Channel Telegram")
        put(text("Channel diblokir otomatis bila 5 kali terdeteksi gambar tidak pantas dalam 10 menit. Aplikasi Telegram lain tetap bisa dipakai.", 12f, false, C_SUB))
        val chCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (ch in Store.channels(this).sorted()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text(ch, 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(text("✕", 15f, true, C_BAD).apply {
                setPadding(dp(10), dp(8), dp(6), dp(8))
                setOnClickListener {
                    if (strict) toast("Mode Ketat aktif: tidak bisa menghapus")
                    else gateOff(true, "menghapus channel $ch") { Store.removeChannel(this@MainActivity, ch) }
                }
            })
            chCol.addView(row)
        }
        put(if (chCol.childCount == 0) text("Belum ada.", 12f, false, C_SUB) else scrollBox(160, chCol))

        // Mode Telegram Kerja: daftar boleh (allowlist).
        header("Telegram Kerja (daftar boleh)")
        put(text("Bila aktif, Telegram resmi hanya boleh membuka chat (grup, channel, bot, orang) yang namanya ada di daftar ini. Chat lain langsung ditutup, Telegram tetap bisa dipakai. Cocok jika nama chat MENGANDUNG kata yang kamu daftarkan. Selama mode belum dicentang, daftar bebas diisi. Setelah dicentang, menambah daftar dan mematikan mode wajib lewat gerbang dan tidak bisa saat Mode Ketat.", 12f, false, C_SUB))
        val twCb = CheckBox(this)
        twCb.text = "Aktifkan Mode Telegram Kerja"
        twCb.isChecked = Store.tgWorkOn(this)
        twCb.setOnClickListener {
            val v = twCb.isChecked
            if (v) {
                Store.setTgWorkOn(this, true)
                render()
            } else if (strict) {
                twCb.isChecked = true
                toast("Mode Ketat aktif: tidak bisa dimatikan")
            } else {
                twCb.isChecked = true
                textGate("mematikan Mode Telegram Kerja", { Store.setTgWorkOn(this, false); render() }, { render() })
            }
        }
        put(twCb)
        // Selama mode belum dicentang, menambah daftar tidak melonggarkan apa pun: bebas, tanpa gerbang dan tanpa batas waktu.
        put(button(if (Store.tgWorkOn(this)) "Pilih dari Telegram (10 menit)" else "Pilih dari Telegram") {
            val on = Store.tgWorkOn(this)
            val start = {
                val tg = NsfwSource.TG_PACKAGES.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
                if (tg == null) toast("Telegram tidak ditemukan")
                else {
                    Store.setTgPickUntil(this, if (on) System.currentTimeMillis() + 10 * 60_000L else Long.MAX_VALUE / 2)
                    toast("Buka chat kerja, lalu tekan tombol ＋ Boleh di bawah layar. Tekan Selesai bila sudah.")
                    startActivity(tg)
                }
            }
            if (!on) start()
            else if (strict) toast("Mode Ketat aktif: tidak bisa menambah daftar boleh")
            else textGate("menambah chat yang boleh dibuka", { start() }, { render() })
        })
        put(button("Tambah chat dengan mengetik nama") {
            val add = { askName("Nama chat (boleh sebagian nama)", "") { Store.addTgAllow(this, it); render() } }
            if (!Store.tgWorkOn(this)) add()
            else if (strict) toast("Mode Ketat aktif: tidak bisa menambah daftar boleh")
            else textGate("menambah chat yang boleh dibuka", { add() }, { render() })
        })
        val awCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (nm in Store.tgAllow(this).sorted()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text(nm, 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(text("✕", 15f, true, C_BAD).apply {
                setPadding(dp(10), dp(8), dp(6), dp(8))
                setOnClickListener {
                    // Mengurangi daftar boleh = lebih ketat, jadi langsung.
                    Store.removeTgAllow(this@MainActivity, nm)
                    render()
                }
            })
            awCol.addView(row)
        }
        put(if (awCol.childCount == 0) text("Daftar kosong: bila mode aktif, semua chat yang terbaca akan ditutup.", 12f, false, C_SUB) else scrollBox(200, awCol))
    }

    private fun editSiteDialog(domain: String, strict: Boolean, isNew: Boolean = false) {
        val existing = Store.siteCfgOrNull(this, domain)
        val cfg = existing ?: SiteCfg()
        var start = cfg.start
        var end = cfg.end

        // Mode UI: 0 = penuh, 1 = jadwal, 2 = batas penggunaan, 3 = timer tunggu.
        // Penyimpanan tetap kompatibel dengan SiteCfg lama: mode 0 = penuh, mode 1 = aturan khusus.
        // Dikunci Mode Ketat: situs yang sudah ada tidak boleh diubah. Tampilan harus mencerminkan nilai tersimpan.
        val locked = strict && !isNew
        val initialMode = when {
            cfg.twUse > 0 && cfg.twWait > 0 -> 3
            cfg.mode == 0 && !cfg.schOn -> 0
            cfg.limitMin > 0 || cfg.hourLimit > 0 -> 2
            cfg.schOn -> 1
            cfg.mode == 0 -> 0
            LocationRules.get(this, "site_" + Rules.normalizeDomain(domain)) != null -> 4
            else -> 1
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        box.addView(text(
            if (isNew) "Pilih dulu mode pemblokiran untuk $domain." else "Aturan khusus untuk $domain",
            13f, true
        ))

        val modes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbFull = RadioButton(this).apply { text = "Blokir penuh"; id = View.generateViewId() }
        val rbSchedule = RadioButton(this).apply { text = "Blokir sesuai jadwal"; id = View.generateViewId() }
        val rbLimit = RadioButton(this).apply { text = "Batas penggunaan"; id = View.generateViewId() }
        val rbTimer = RadioButton(this).apply { text = "Timer tunggu"; id = View.generateViewId() }
        val rbLoc = RadioButton(this).apply { text = "Hanya berdasarkan lokasi"; id = View.generateViewId() }
        modes.addView(rbFull); modes.addView(rbSchedule); modes.addView(rbLimit); modes.addView(rbTimer); modes.addView(rbLoc)
        when (initialMode) {
            0 -> rbFull.isChecked = true
            1 -> rbSchedule.isChecked = true
            2 -> rbLimit.isChecked = true
            4 -> rbLoc.isChecked = true
            else -> rbTimer.isChecked = true
        }
        box.addView(modes)

        // Pada situs lama saat Mode Ketat aktif, pertahankan aturan lama: hanya Blokir penuh.
        // Situs baru tidak dikunci karena pengguna belum memasukkannya ke daftar.
        if (locked) {
            rbFull.isEnabled = false
            rbSchedule.isEnabled = false
            rbLimit.isEnabled = false
            rbTimer.isEnabled = false
            rbLoc.isEnabled = false
            box.addView(text("Mode Ketat aktif: aturan situs ini terkunci dan tidak bisa diubah. Yang tampil adalah aturan yang tersimpan.", 11f, false, C_SUB))
        }

        val detailBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        // Jadwal
        val scheduleBody = vbox()
        val startBtn = Button(this).apply { text = "Mulai: ${fmt(start)}" }
        val endBtn = Button(this).apply { text = "Selesai: ${fmt(end)}" }
        startBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> start = h * 60 + m; startBtn.text = "Mulai: ${fmt(start)}" }, start / 60, start % 60, true).show()
        }
        endBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> end = h * 60 + m; endBtn.text = "Selesai: ${fmt(end)}" }, end / 60, end % 60, true).show()
        }
        scheduleBody.addView(text("Situs diblokir hanya pada jam berikut:", 12f))
        scheduleBody.addView(startBtn)
        scheduleBody.addView(endBtn)
        detailBox.addView(scheduleBody)

        // Batas penggunaan
        val limitBody = vbox()
        val limit = numberField(cfg.limitMin)
        val hour = numberField(cfg.hourLimit)
        limitBody.addView(text("Batas harian (menit, 0 = mati)", 12f))
        limitBody.addView(limit)
        limitBody.addView(text("Batas per jam (menit dalam 60 menit terakhir, 0 = mati)", 12f))
        limitBody.addView(hour)
        detailBox.addView(limitBody)

        // Timer tunggu
        val twBody = vbox()
        val twUse = numberField(if (cfg.twUse > 0) cfg.twUse else 5)
        val twWait = numberField(if (cfg.twWait > 0) cfg.twWait else 30)
        twBody.addView(text("Pakai selama (menit)", 12f))
        twBody.addView(twUse)
        twBody.addView(text("Lalu blokir selama (menit)", 12f))
        twBody.addView(twWait)
        val adaptive = CheckBox(this).apply {
            text = "Timer adaptif"
            isChecked = cfg.twAdaptive
        }
        twBody.addView(adaptive)
        detailBox.addView(twBody)
        box.addView(detailBox)
        val locUi = locationSection(box, "site_" + Rules.normalizeDomain(domain), locked)

        if (locked) {
            startBtn.isEnabled = false
            endBtn.isEnabled = false
            limit.isEnabled = false
            hour.isEnabled = false
            twUse.isEnabled = false
            twWait.isEnabled = false
            adaptive.isEnabled = false
        }

        fun updateDetails(mode: Int) {
            scheduleBody.visibility = if (mode == 1) View.VISIBLE else View.GONE
            limitBody.visibility = if (mode == 2) View.VISIBLE else View.GONE
            twBody.visibility = if (mode == 3) View.VISIBLE else View.GONE
        }
        updateDetails(if (rbFull.isChecked) 0 else if (rbSchedule.isChecked) 1 else if (rbLimit.isChecked) 2 else if (rbLoc.isChecked) 4 else 3)
        modes.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                rbSchedule.id -> 1
                rbLimit.id -> 2
                rbTimer.id -> 3
                rbLoc.id -> 4
                else -> 0
            }
            updateDetails(mode)
        }
        box.addView(text("Launch count/pass tidak diterapkan pada website karena website tidak memiliki konsep peluncuran aplikasi.", 11f, false, C_SUB))

        android.app.AlertDialog.Builder(this)
            .setTitle(if (isNew) "Atur blokir situs" else "Atur blokir situs")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(if (locked) "Tutup" else "Simpan") { _, _ ->
                if (locked) return@setPositiveButton
                fun num(e: EditText, max: Int) = (e.text.toString().toIntOrNull() ?: 0).coerceIn(0, max)
                val selected = when {
                    rbSchedule.isChecked -> 1
                    rbLimit.isChecked -> 2
                    rbTimer.isChecked -> 3
                    rbLoc.isChecked -> 4
                    else -> 0
                }
                val full = selected == 0
                val newCfg = SiteCfg(
                    mode = if (full) 0 else 1,
                    schOn = selected == 1,
                    start = start,
                    end = end,
                    days = 127,
                    limitMin = if (selected == 2) num(limit, 1440) else 0,
                    hourLimit = if (selected == 2) num(hour, 60) else 0,
                    twUse = if (selected == 3) num(twUse, 1440) else 0,
                    twWait = if (selected == 3) num(twWait, 1440) else 0,
                    twAdaptive = adaptive.isChecked
                )
                if (newCfg.mode != 0 && selected == 2 && newCfg.limitMin == 0 && newCfg.hourLimit == 0) {
                    toast("Isi batas penggunaan terlebih dahulu")
                    return@setPositiveButton
                }
                if (selected == 3 && (newCfg.twUse <= 0 || newCfg.twWait <= 0)) {
                    toast("Isi timer penggunaan dan timer tunggu terlebih dahulu")
                    return@setPositiveButton
                }
                val locRule = if (locUi.cb.isChecked) locUi.read() else null
                if (locUi.cb.isChecked && locRule == null) {
                    toast("Lokasi tidak valid: isi lintang dan bujur dengan benar (atau pakai lokasi saya sekarang).")
                    return@setPositiveButton
                }
                if (selected == 4 && locRule == null) {
                    toast("Mode lokasi: aktifkan bagian lokasi dan isi titiknya.")
                    return@setPositiveButton
                }
                // Simpan konfigurasi dulu, baru masukkan domain ke daftar (tanpa jeda blokir penuh sementara).
                LocationRules.set(this, "site_" + Rules.normalizeDomain(domain), locRule)
                if (locRule != null && (!LocationTracker.hasPermission(this) || !LocationTracker.hasBackground(this))) askLocationPermission()
                Store.setSiteCfg(this, domain, newCfg)
                Store.addDomain(this, domain)
                if (newCfg.twUse > 0 && newCfg.twWait > 0 && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1003)
                }
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun renderFeeds(strict: Boolean) {
        header("Blokir bagian aplikasi sosmed")
        put(text("Aplikasinya tetap bisa dipakai, tetapi bagian pemicu kecanduan (Shorts, Reels, Cerita, Pencarian, tab Pembaruan) ditutup otomatis. Deteksi bergantung pada tampilan tiap aplikasi, jadi bisa meleset atau salah kena setelah aplikasinya diperbarui. Bila ada yang tidak terdeteksi atau salah blokir, pakai Rekam layar di bawah. Saat Mode Ketat aktif, fitur yang sudah menyala tidak bisa dimatikan. Di luar Mode Ketat, mematikannya wajib lewat gerbang yang sama dengan keluar Mode Ketat (teks acak dan seterusnya). Saat Mode Off (jeda), bagian yang menyala tetap ditutup: aplikasinya bisa dibuka, Reels dan sejenisnya tidak.", 12f))
        var lastGroup = ""
        for (f in Features.ALL) {
            if (f.group != lastGroup) {
                lastGroup = f.group
                put(text(f.group, 13f, true))
            }
            val on = Features.enabled(this, f)
            put(CheckBox(this).apply {
                text = f.label
                isChecked = on
                setOnClickListener {
                    val v = isChecked
                    if (strict && on && !v) {
                        toast("Mode Ketat aktif: pengaturan terkunci")
                    } else {
                        gateOff(on && !v, "mematikan blokir ${f.group} ${f.label}") { Features.setEnabled(this@MainActivity, f, v) }
                    }
                    render()
                }
            })
        }
        header("Rekam layar (untuk memperbaiki deteksi)")
        put(text("Tekan Mulai, buka aplikasi sosmed, dan pindah ke bagian yang bermasalah (mis. tab Pembaruan WhatsApp, Reels, kolom pencarian). Rekaman 20 detik hanya menyimpan nama komponen dan teks pendek (maks 24 huruf), bukan isi pesan. Lalu simpan hasilnya dan kirim ke saya.", 12f))
        put(button("Mulai rekam 20 detik") {
            FeatureDump.start(20)
            toast("Merekam 20 detik. Buka aplikasi sosmed sekarang.")
        })
        put(button("Simpan hasil rekaman ke file") {
            if (FeatureDump.isEmpty()) {
                toast("Belum ada rekaman")
            } else {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "text/plain"
                i.putExtra(Intent.EXTRA_TITLE, "PenjagaKetat_Rekam_Layar.txt")
                try {
                    startActivityForResult(i, 5103)
                } catch (t: Throwable) {
                    toast("Tidak ada aplikasi penyimpan file")
                }
            }
        })
    }
}
