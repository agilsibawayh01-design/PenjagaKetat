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

    /** Menyeragamkan kotak centang, tombol pilihan, dan saklar bawaan ke warna Lux. */
    private fun styleTree(v: View) {
        val gold = C_PRIMARY
        val grey = Color.parseColor("#8A8576")
        val checked = intArrayOf(android.R.attr.state_checked)
        when (v) {
            is android.widget.Switch -> {
                v.thumbTintList = android.content.res.ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(gold, Color.parseColor("#B5B0A2")))
                v.trackTintList = android.content.res.ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(Color.argb(120, 212, 175, 106), Color.parseColor("#2A2F3B")))
            }
            is android.widget.CompoundButton -> {
                v.buttonTintList = null
                v.setButtonDrawable(if (v is android.widget.RadioButton) R.drawable.lux_radio else R.drawable.lux_check)
                v.setTextColor(C_TEXT)
                v.textSize = 14f
                v.minHeight = dp(48)
                v.minimumHeight = dp(48)
                v.setPadding(dp(10), dp(6), dp(4), dp(6))
            }
            is android.widget.SeekBar -> {
                v.progressTintList = android.content.res.ColorStateList.valueOf(gold)
                v.thumbTintList = android.content.res.ColorStateList.valueOf(gold)
                v.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2A2F3B"))
            }
            is Spinner -> {
                v.background = bg(Color.parseColor("#10131A"), dp(12)).apply { setStroke(dp(1), Color.parseColor("#2A2F3B")) }
                v.setPadding(dp(10), dp(8), dp(10), dp(8))
            }
        }
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) styleTree(v.getChildAt(i))
    }

    private fun serifBold(): Typeface = try {
        Typeface.create(resources.getFont(R.font.cormorant), 600, false)
    } catch (t: Throwable) {
        Typeface.create("serif", Typeface.BOLD)
    }

    /** Latar lembut berwarna (seperti watermark), tetap di tempat saat halaman digulir. */
    private class AuroraView(c: android.content.Context) : View(c) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private fun blob(cv: android.graphics.Canvas, x: Float, y: Float, r: Float, argb: Int) {
            p.shader = android.graphics.RadialGradient(x, y, r, argb, argb and 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
            cv.drawCircle(x, y, r, p)
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(Color.parseColor("#0A0C10"))
            blob(canvas, w * 0.05f, h * 0.04f, w * 1.0f, Color.argb(70, 212, 175, 106))
            blob(canvas, w * 1.05f, h * 0.32f, w * 0.95f, Color.argb(60, 79, 209, 181))
            blob(canvas, w * -0.05f, h * 0.66f, w * 1.0f, Color.argb(58, 140, 112, 240))
            blob(canvas, w * 1.0f, h * 0.98f, w * 0.9f, Color.argb(52, 246, 135, 179))
        }
    }

    // Tema Lux: selalu gelap, aksen emas.
    private val night: Boolean get() = true
    private val C_BG: Int get() = Color.parseColor("#0A0C10")
    private val C_CARD: Int get() = Color.parseColor("#C9121722")
    private val C_CHIP: Int get() = Color.parseColor("#232834")
    private val C_FIELD: Int get() = Color.parseColor("#A6161B26")
    private val C_PRIMARY: Int get() = Color.parseColor("#D4AF6A")
    private val C_TEXT: Int get() = Color.parseColor("#EDE8DD")
    private val C_SUB: Int get() = Color.parseColor("#A9A396")
    private val C_OK: Int get() = Color.parseColor("#5FC9A0")
    private val C_BAD: Int get() = Color.parseColor("#E5736B")
    private val C_ON_GOLD: Int get() = Color.parseColor("#1A1405")

    /** Latar emas bergradien untuk tombol dan pilihan aktif. */
    private fun goldBg(radius: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#E6C987"), Color.parseColor("#C99A45"))).apply {
            cornerRadius = radius.toFloat()
        }

    private class AppRow(val pkg: String, val label: String)

    private var appRows: List<AppRow>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(24))
        }
        scroll.setBackgroundColor(Color.TRANSPARENT)
        scroll.addView(root)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        navBar = buildNav()
        outer.addView(navBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val frame = android.widget.FrameLayout(this)
        frame.addView(AuroraView(this), android.widget.FrameLayout.LayoutParams(-1, -1))
        frame.addView(outer, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        applySystemBars()
        Thread { try { loadApps() } catch (t: Throwable) { } }.start()
        if (intent?.getBooleanExtra("urge", false) == true) {
            screen = "urge"
            urgeEnd = 0L
            rtStep = 0
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
        if (!Store.consent(this)) showConsent()
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
            if (bold && size >= 24f) typeface = serifBold()
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
        c.elevation = 0f
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
        titleRow.addView(text(s.replace(Regex("^[^\\p{L}\\p{N}]+"), ""), 17f, true, C_TEXT).apply {
            typeface = serifBold()
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (s in COLLAPSIBLE || s.startsWith("Dilindungi (")) {
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
    private class LuxDlg(val dlg: android.app.AlertDialog, val pos: TextView, val neg: TextView?)

    /** Dialog bergaya Lux: kartu berbingkai emas, judul serif, tombol utama emas. */
    private fun luxDialog(eyebrowText: String, title: String, body: String, content: View?,
                          posLabel: String, negLabel: String?, onNeg: () -> Unit): LuxDlg {
        val outer = LinearLayout(this)
        outer.setPadding(dp(1), dp(1), dp(1), dp(1))
        outer.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(190, 212, 175, 106), Color.argb(25, 212, 175, 106), Color.argb(100, 212, 175, 106))
        ).apply { cornerRadius = dp(26).toFloat() }
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.gravity = Gravity.CENTER_HORIZONTAL
        inner.setPadding(dp(20), dp(24), dp(20), dp(20))
        inner.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#141821"), Color.parseColor("#0E1117"))
        ).apply { cornerRadius = dp(25).toFloat() }
        inner.addView(text(eyebrowText, 11f, true, C_PRIMARY).apply { letterSpacing = 0.22f; gravity = Gravity.CENTER })
        inner.addView(text(title, 28f, true, C_TEXT).apply {
            typeface = serifBold(); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        })
        if (body.isNotEmpty()) inner.addView(text(body, 13f, false, C_SUB).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(10))
        })
        if (content != null) inner.addView(content)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(14), 0, 0)
        var negTv: TextView? = null
        if (negLabel != null) {
            negTv = TextView(this).apply {
                text = negLabel
                textSize = 14f
                setTextColor(C_TEXT)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), dp(14))
                background = bg(Color.parseColor("#10131A"), dp(16)).apply { setStroke(dp(1), Color.parseColor("#2A2F3B")) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) }
            }
            row.addView(negTv)
        }
        val posTv = TextView(this).apply {
            text = posLabel
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(C_ON_GOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
            background = goldBg(dp(16))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply { leftMargin = if (negLabel != null) dp(5) else 0 }
        }
        row.addView(posTv)
        inner.addView(row)
        outer.addView(inner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val sv = ScrollView(this)
        sv.addView(outer)
        val dlg = android.app.AlertDialog.Builder(this).setView(sv).create()
        dlg.setOnCancelListener { onNeg() }
        negTv?.setOnClickListener { dlg.dismiss(); onNeg() }
        dlg.setOnShowListener {
            try {
                dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            } catch (t: Throwable) {
                // tampilan bawaan
            }
        }
        return LuxDlg(dlg, posTv, negTv)
    }

    /** Pilihan dari daftar: kartu emas dengan baris berpisah garis tipis. */
    private fun luxPicker(eyebrowText: String, title: String, labels: List<String>, current: Int = -1, onPick: (Int) -> Unit) {
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        lateinit var ld: LuxDlg
        for ((i, l) in labels.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(6), dp(14), dp(6), dp(14))
            row.addView(text(l, 14.5f, i == current, if (i == current) C_PRIMARY else C_TEXT),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (i == current) row.addView(text("✓", 15f, true, C_PRIMARY))
            row.setOnClickListener { ld.dlg.dismiss(); onPick(i) }
            list.addView(row)
            list.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#1B1F28"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
        ld = luxDialog(eyebrowText, title, "", list, "Batal", null) { }
        ld.pos.setOnClickListener { ld.dlg.dismiss() }
        ld.dlg.show()
    }

    /** Cincin hitung mundur: lingkar abu-abu, busur emas sebesar sisa waktu, angka di tengah. */
    private inner class RingView(ctx: Context) : View(ctx) {
        var frac = 1f
        var big = ""
        var small = ""
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(4).toFloat()
            color = Color.parseColor("#232834")
        }
        private val arc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(4).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = Color.parseColor("#D4AF6A")
        }
        private val tBig = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EDE8DD")
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = dp(32).toFloat()
            typeface = serifBold()
        }
        private val tSmall = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8A8576")
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = dp(11).toFloat()
        }

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(dp(112), dp(112))
        }

        override fun onDraw(c: android.graphics.Canvas) {
            val m = dp(6).toFloat()
            val r = android.graphics.RectF(m, m, width - m, height - m)
            c.drawArc(r, 0f, 360f, false, track)
            c.drawArc(r, -90f, 360f * frac.coerceIn(0f, 1f), false, arc)
            c.drawText(big, width / 2f, height / 2f + dp(6), tBig)
            c.drawText(small, width / 2f, height / 2f + dp(22), tSmall)
        }
    }

    private fun clockText(ms: Long): String {
        val sec = (ms.coerceAtLeast(0L) + 999L) / 1000L
        return String.format(java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)
    }

    private fun waitBox(big: String, small: String, frac: Float = 1f): View {
        val r = RingView(this)
        r.big = big
        r.small = small
        r.frac = frac
        val b = LinearLayout(this)
        b.gravity = Gravity.CENTER_HORIZONTAL
        b.addView(r)
        b.setPadding(0, dp(6), 0, dp(4))
        b.tag = r
        return b
    }

    private fun textGate(what: String, onOk: () -> Unit, onCancel: () -> Unit) {
        val waitMin = Night.scaleWait(this, if (Store.exitWaitOn(this)) Store.exitDelayMin(this) else 0)
        val textLen = Zone.requiredTextLen(this, Store.exitTextLen(this))
        val friend = FriendApproval.isSet(this)

        if (waitMin > 0) {
            if (!SafeTimer.isRunning(this, "feat")) {
                val ld = luxDialog("WAKTU TUNGGU", "Butuh waktu tunggu",
                    "Untuk $what, kamu harus menunggu " + fmtDur(waitMin) + " (sama seperti keluar Mode Ketat). Setelah waktunya habis, ulangi aksi ini.",
                    waitBox(clockText(waitMin * 60000L), "waktu tunggu"), "Mulai", "Batal") { onCancel() }
                ld.pos.setOnClickListener {
                    SafeTimer.start(this, "feat")
                    Store.log(this, "Permintaan: $what")
                    ld.dlg.dismiss()
                    onCancel()
                }
                ld.dlg.show()
                return
            }
            val left = waitMin * 60000L - SafeTimer.elapsed(this, "feat")
            if (left > 0L) {
                val box = waitBox(clockText(left), "tersisa", left.toFloat() / (waitMin * 60000f))
                val ring = box.tag as RingView
                val ld = luxDialog("MASIH MENUNGGU", "Tunggu sebentar lagi",
                    "Waktu dihitung dari jam internal, jadi mengubah jam HP tidak mempercepatnya. Setelah habis, ulangi aksi ini.",
                    box, "Mengerti", "Batalkan permintaan") { onCancel() }
                ld.neg?.setOnClickListener { SafeTimer.cancel(this, "feat"); ld.dlg.dismiss(); onCancel() }
                ld.pos.setOnClickListener { ld.dlg.dismiss(); onCancel() }
                val tick = object : Runnable {
                    override fun run() {
                        if (!ld.dlg.isShowing) return
                        val rest = waitMin * 60000L - SafeTimer.elapsed(this@MainActivity, "feat")
                        ring.big = clockText(rest)
                        ring.frac = rest.toFloat() / (waitMin * 60000f)
                        ring.invalidate()
                        if (rest > 0L) handler.postDelayed(this, 1000L)
                    }
                }
                ld.dlg.show()
                handler.postDelayed(tick, 1000L)
                return
            }
        }

        val code = if (textLen > 0) Rules.randomText(textLen) else ""
        var typed = ""
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(4), 0, dp(4))
        col.addView(letterCard())
        var et: EditText? = null
        if (textLen > 0) {
            col.addView(text("Ketik teks ini persis ($textLen huruf, huruf besar dan kecil dibedakan). Hanya 5 huruf berikutnya yang ditampilkan, ketik tanpa spasi.", 12f, false, C_SUB))
            val shown = TextView(this)
            shown.textSize = 30f
            shown.typeface = Typeface.MONOSPACE
            shown.gravity = Gravity.CENTER
            shown.letterSpacing = 0.18f
            shown.setTextColor(Color.parseColor("#E6D6B0"))
            shown.setPadding(dp(12), dp(12), dp(12), dp(12))
            shown.background = bg(Color.parseColor("#0A0C10"), dp(14)).apply { setStroke(dp(1), C_CHIP) }
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
                val end = Math.min(code.length, cp + 5)
                shown.text = code.substring(cp, end)
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
        val ld = luxDialog("GERBANG KEAMANAN", "Konfirmasi",
            "Untuk $what, selesaikan verifikasi yang sama dengan keluar Mode Ketat.", col, "Lanjut", "Batal") { onCancel() }
        val dlg = ld.dlg
        try {
            dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } catch (t: Throwable) {
            // abaikan
        }
        dlg.show()
        ld.pos.setOnClickListener {
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
        "Teks motivasi", "Pengecualian (ALLOW)", "Per hari",
        "Uninstall Penjaga Ketat & aplikasi pelindung", "Kunci aplikasi", "Surat untuk diri sendiri",
        "Uji perlindungan", "Persetujuan teman", "Lindungi aplikasi lain dari uninstall",
        "Backup dan restore", "Blokir bagian aplikasi sosmed", "Channel Telegram", "🕘 Riwayat Situs",
        "Tambah aplikasi", "Jam rawan", "Celah pemasangan", "Perlindungan diri",
        "30 hari terakhir", "Teratas 7 hari", "Riwayat per aplikasi atau situs", "Kategori hari ini"
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
        "urge" -> "Napas, gerak, pindah tempat sampai dorongan turun"
        "focus" -> "Sesi fokus untuk menjaga konsentrasi"
        "words" -> "Browser, kategori, kata kunci, dan fitur sosmed"
        "w_tg" -> "Daftar boleh grup, channel, dan bot"
        "s_method" -> "Cara keluar dari Mode Ketat"
        "s_guard" -> "Pemeriksaan dan pemulihan otomatis"
        "s_ai" -> "Pemindaian gambar di layar"
        "s_night" -> "Gerbang lebih berat di jam rawan"
        "w_browser" -> "Browser yang dipantau"
        "w_cat" -> "Blokir satu kelompok topik sekaligus"
        "w_kw" -> "Situs, kata kunci, dan channel Telegram"
        "w_feed" -> "Shorts, Reels, Cerita, dan Pencarian"
        "sched", "profiles" -> "Jadwal otomatis dan profil aturan"
        "strict" -> "Kunci pengaturan agar sulit dimatikan"
        "net" -> "Filter DNS dan kontrol notifikasi"
        "vpn" -> "Filter situs lewat DNS dan SafeSearch Google"
        "notif" -> "Pengingat waktu dan kontrol notifikasi"
        "set_lock", "set_friend", "set_zone", "set_sec" -> "Mengunci dan melindungi pengaturan"
        "set_backup", "set_test", "set_misc" -> "Cadangan, uji, dan catatan"
        "set_help" -> "Langkah jika terkunci atau terjebak"
        "set_about" -> "Data, izin, dan batas perlindungan"
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
        "Langkah awal" to "Beberapa izin masih kurang. Selesaikan satu per satu agar Penjaga Ketat bekerja.",
        "🛡️ Keyword Bawaan PenjagaKetat" to "Daftar kata bawaan; bisa dimatikan satu per satu.",
        "🕘 Riwayat Situs" to "Situs yang baru dibuka di browser, bisa langsung diblokir.",
        "🔗 Keyword URL" to "Kata yang diperiksa di alamat situs.",
        "📱 Keyword Layar" to "Kata yang diperiksa di teks layar aplikasi."
    )

    private fun status(ok: Boolean, label: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(5), 0, dp(5))
        val col = if (ok) C_OK else C_BAD
        row.addView(TextView(this).apply {
            text = if (ok) "✓" else "!"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(col)
            gravity = Gravity.CENTER
            background = bg(Color.argb(38, Color.red(col), Color.green(col), Color.blue(col)), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) }
        })
        row.addView(text(label, 13.5f, false, if (ok) C_TEXT else Color.parseColor("#F0B9B3")).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        put(row)
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(C_ON_GOLD)
            setTypeface(null, Typeface.BOLD)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(70, 255, 255, 255)),
                goldBg(dp(16)), null
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
            background = bg(Color.parseColor("#10131A"), dp(14)).apply { setStroke(dp(1), Color.argb(130, 212, 175, 106)) }
            setTypeface(null, Typeface.BOLD)
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
        sv.background = bg(Color.parseColor("#0A0C10"), dp(14)).apply { setStroke(dp(1), C_CHIP) }
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
                row.addView(text("✕", 13f, true, C_BAD).apply {
                    gravity = Gravity.CENTER
                    background = bg(Color.argb(30, 229, 115, 107), dp(14))
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(8) }
                    setOnClickListener {
                        if (Store.strictOn(this@MainActivity)) {
                            toast("Mode Ketat aktif: tidak bisa mengubah keyword bawaan")
                        } else {
                            gateOff(true, "mematikan keyword bawaan") { Store.disableBuiltinKeyword(this@MainActivity, item) }
                        }
                    }
                })
            }
            row.setPadding(0, dp(6), 0, dp(6))
            col.addView(row)
            col.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#1B1F28"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
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
            row.addView(text("✕", 13f, true, C_BAD).apply {
                gravity = Gravity.CENTER
                background = bg(Color.argb(30, 229, 115, 107), dp(14))
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(8) }
                setOnClickListener { onRemove(item) }
            })
            row.setPadding(0, dp(6), 0, dp(6))
            col.addView(row)
            col.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#1B1F28"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
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
        Triple(R.drawable.ic_nav_home, "Beranda", "home"),
        Triple(R.drawable.ic_nav_shield, "Proteksi", "proteksi"),
        Triple(R.drawable.ic_nav_globe, "Situs", "words"),
        Triple(R.drawable.ic_nav_settings, "Pengaturan", "more")
    )

    private fun tabOf(s: String): Int = when (s) {
        "home", "urge" -> 0
        "proteksi", "strict", "s_method", "s_guard", "s_ai", "s_night", "apps", "profiles", "sched", "net", "focus" -> 1
        "words", "w_tg", "w_browser", "w_cat", "w_kw", "w_feed" -> 2
        "stats" -> 0
        else -> 3
    }

    private val MORE_SUB = setOf("set_lock", "set_friend", "set_zone", "set_sec", "vpn", "notif", "set_backup", "set_test", "set_misc", "set_help", "set_about")

    private fun isTabRoot(s: String): Boolean = s == "home" || s == "proteksi" || s == "words" || s == "more"

    private val WORDS_SUB = setOf("w_tg", "w_browser", "w_cat", "w_kw", "w_feed")
    private val STRICT_SUB = setOf("s_method", "s_guard", "s_ai", "s_night")

    private fun parentOf(s: String): String = when {
        s == "urge" -> "home"
        s in WORDS_SUB -> "words"
        s in STRICT_SUB -> "strict"
        s in MORE_SUB -> "more"
        isTabRoot(s) -> "home"
        tabOf(s) == 1 -> "proteksi"
        else -> "home"
    }

    private fun buildNav(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(6), dp(6), dp(6), dp(6))
        bar.background = bg(Color.parseColor("#E610131A"), 0).apply { setStroke(dp(1), C_CHIP) }
        bar.elevation = 0f
        for ((icon, label, target) in TABS) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.tag = target
            item.setPadding(0, dp(4), 0, dp(4))
            item.addView(ImageView(this).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            })
            item.addView(TextView(this).apply { text = label; textSize = 11f; gravity = Gravity.CENTER; tag = "lbl" })
            item.setOnClickListener { goScreen(target) }
            bar.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    private fun updateNav() {
        val sel = tabOf(screen)
        for (i in 0 until navBar.childCount) {
            val item = navBar.getChildAt(i) as LinearLayout
            val on = i == sel
            val lbl = item.getChildAt(1) as TextView
            lbl.setTextColor(if (on) C_PRIMARY else C_SUB)
            lbl.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            (item.getChildAt(0) as ImageView).setColorFilter(if (on) C_PRIMARY else C_SUB)
            item.background = if (on) bg(Color.argb(30, 212, 175, 106), dp(14)) else null
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
                    renderStrictHub(strict)
                }
                "s_method" -> renderMethods(strict)
                "s_guard" -> {
                    renderProtection(strict)
                    renderBypassGuard(strict)
                }
                "s_ai" -> renderNsfwAi(strict)
                "s_night" -> renderNight(strict)
                "apps" -> {
                    renderApps(strict)
                    renderPauseApps(strict)
                }
                "urge" -> renderUrge()
                "words" -> renderWordsHub(strict)
                "w_tg" -> renderTgWork(strict)
                "w_browser" -> renderBrowsers(strict)
                "w_cat" -> renderCategories(strict)
                "w_kw" -> renderWords(strict)
                "w_feed" -> renderFeeds(strict)
                "profiles" -> renderProfiles(strict)
                "net" -> {
                    renderVpn(strict)
                    renderNotif()
                }
                "proteksi" -> renderProteksi()
                "stats" -> renderDashboard(strict)
                "focus" -> renderFocus(strict)
                "sched" -> renderSchedule(strict)
                "set_lock" -> {
                    renderAppLock(strict)
                    renderUninstallGate(strict)
                }
                "set_friend" -> {
                    renderFriend()
                    renderLetter(strict)
                }
                "set_zone" -> renderZoneSection(strict)
                "set_sec" -> {
                    renderProtected(strict)
                    renderNewApps(strict)
                }
                "vpn" -> renderVpn(strict)
                "notif" -> renderNotif()
                "set_backup" -> renderBackup(strict)
                "set_test" -> renderSelfTest()
                "set_misc" -> {
                    renderMotivation(strict)
                    renderLog()
                }
                "set_help" -> renderHelp()
                "set_about" -> renderAbout()
                else -> renderMoreHub(strict)
            }
        }
        collapseAll()
        styleTree(root)
        val same = lastScreen == screen
        lastScreen = screen
        scroll.post { scroll.scrollTo(0, if (same) keepY else 0) }
        if (!same) {
            root.animate().cancel()
            root.alpha = 0f
            root.translationY = dp(14).toFloat()
            root.animate().alpha(1f).translationY(0f).setDuration(200L).start()
        } else {
            root.alpha = 1f
            root.translationY = 0f
        }
        // Layar teks acak: blokir screenshot dan rekam layar (juga bantuan OCR/AI berbasis tangkapan layar).
        try {
            if (gateShown) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } catch (t: Throwable) {
            // abaikan
        }
    }

    private class NavItem(val icon: Int, val title: String, val sub: String, val status: String, val color: Int, val target: String)

    private fun navGroup(label: String, items: List<NavItem>) {
        eyebrow(label)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(2), dp(16), dp(2))
        card.background = bg(C_CARD, dp(20)).apply { setStroke(dp(1), C_CHIP) }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        for ((i, it) in items.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(12), 0, dp(12))
            row.addView(ImageView(this).apply {
                setImageResource(it.icon)
                setColorFilter(C_PRIMARY)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = bg(Color.TRANSPARENT, dp(11)).apply { setStroke(dp(1), Color.parseColor("#2A2F3B")) }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            })
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(14), 0, dp(8), 0)
            col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            col.addView(text(it.title, 14.5f, false, C_TEXT))
            col.addView(text(it.sub, 12f, false, C_SUB))
            row.addView(col)
            if (it.status.isNotEmpty()) row.addView(text(it.status, 11f, true, it.color))
            else row.addView(text("›", 20f, true, C_SUB))
            val target = it.target
            row.setOnClickListener { goScreen(target) }
            card.addView(row)
            if (i < items.size - 1) card.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#1B1F28"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
        root.addView(card)
    }

    private fun renderWordsHub(strict: Boolean) {
        val tgOn = Store.tgWorkOn(this)
        val kwCount = Store.keywords(this).size + Store.domains(this).size
        val cats = Categories.enabled(this).size
        val feeds = Features.ALL.count { f -> Features.enabled(this, f) }
        navGroup("TELEGRAM", listOf(
            NavItem(R.drawable.ic_ly_send, "Telegram Kerja", "Hanya grup, channel, dan bot yang diizinkan",
                if (tgOn) "Aktif" else "Mati", if (tgOn) C_OK else C_SUB, "w_tg")
        ))
        navGroup("SITUS DAN KATA", listOf(
            NavItem(R.drawable.ic_ly_globe, "Situs dan kata kunci", "Domain, kata di alamat dan layar", "$kwCount aturan", C_PRIMARY, "w_kw"),
            NavItem(R.drawable.ic_nav_globe, "Kategori situs", "Dewasa, judi, dan topik lain", "$cats aktif", C_PRIMARY, "w_cat"),
            NavItem(R.drawable.ic_ly_app, "Browser", "Browser yang dipantau untuk blokir situs", "", C_PRIMARY, "w_browser")
        ))
        navGroup("SOSMED", listOf(
            NavItem(R.drawable.ic_ly_phone, "Bagian aplikasi sosmed", "Tutup Shorts, Reels, Cerita, Pencarian", "$feeds aktif", C_PRIMARY, "w_feed")
        ))
    }

    private fun renderMoreHub(strict: Boolean) {
        val strictLabel = if (strict) "Aktif" else "Mati"
        val vpnLabel = if (VpnStatus.running) "Berjalan" else "Mati"
        val friendLabel = if (FriendApproval.isSet(this)) "Aktif" else "Belum diatur"
        navGroup("RINGKASAN", listOf(
            NavItem(R.drawable.ic_nav_stats, "Statistik", "Pemakaian, blokir, dan pola waktu", "", C_PRIMARY, "stats")
        ))
        navGroup("KEAMANAN", listOf(
            NavItem(R.drawable.ic_nav_shield, "Mode Ketat", "Metode keluar dan kedaluwarsa", strictLabel, if (strict) C_OK else C_SUB, "strict"),
            NavItem(R.drawable.ic_lock, "Kunci aplikasi", "Sidik jari atau PIN saat dibuka", "", C_PRIMARY, "set_lock"),
            NavItem(R.drawable.ic_ly_send, "Persetujuan teman", "Kode dari teman untuk keluar", friendLabel, if (FriendApproval.isSet(this)) C_OK else C_SUB, "set_friend"),
            NavItem(R.drawable.ic_ly_target, "Zona Ketat", "Aturan berdasarkan lokasi", "", C_PRIMARY, "set_zone"),
            NavItem(R.drawable.ic_ly_app, "Lindungi aplikasi lain", "Cegah uninstall dan pindai aplikasi baru", "", C_PRIMARY, "set_sec")
        ))
        navGroup("JARINGAN", listOf(
            NavItem(R.drawable.ic_nav_globe, "VPN DNS", "Filter situs, SafeSearch Google", vpnLabel, if (VpnStatus.running) C_OK else C_SUB, "vpn"),
            NavItem(R.drawable.ic_ly_phone, "Notifikasi", "Pengingat waktu dan kontrol notifikasi", "", C_PRIMARY, "notif")
        ))
        navGroup("DATA DAN UJI", listOf(
            NavItem(R.drawable.ic_ly_plug, "Cadangan dan pulihkan", "Simpan atau kembalikan pengaturan", "", C_PRIMARY, "set_backup"),
            NavItem(R.drawable.ic_ly_eye, "Uji perlindungan", "Cek halaman Setelan HP terdeteksi", "", C_PRIMARY, "set_test"),
            NavItem(R.drawable.ic_ly_calendar, "Catatan dan motivasi", "Riwayat dan teks penyemangat", "", C_PRIMARY, "set_misc")
        ))
        navGroup("BANTUAN DAN INFO", listOf(
            NavItem(R.drawable.ic_lock, "Bantuan darurat", "Jika terkunci di Mode Ketat atau butuh keluar", "", C_PRIMARY, "set_help"),
            NavItem(R.drawable.ic_nav_shield, "Privasi dan batasan", "Data yang dipakai dan yang tidak bisa dicegah", "", C_PRIMARY, "set_about")
        ))
    }

    private fun renderHelp() {
        header("Terkunci di Mode Ketat?")
        put(infoNote("Mode Ketat memang dibuat sulit dimatikan, tetapi selalu ada jalan keluar yang sah. Tarik napas dulu, lalu coba urutan ini."))
        put(text("1. Jalur resmi", 14f, true, C_TEXT).apply { setPadding(0, dp(8), 0, 0) })
        put(infoNote("Buka Mode Ketat, tekan Minta nonaktifkan, tunggu waktu jeda sampai habis, lalu ketik teks acak (dan kode teman bila diatur). Ini jalur yang selalu tersedia tanpa komputer."))
        put(text("2. Jika Mode Ketat punya waktu kedaluwarsa", 14f, true, C_TEXT).apply { setPadding(0, dp(8), 0, 0) })
        put(infoNote("Mode Ketat mati sendiri saat waktunya habis. Cek sisa waktunya di Mode Ketat, Metode keluar."))
        put(text("3. Jika aplikasi tidak bisa dibuka sama sekali", 14f, true, C_TEXT).apply { setPadding(0, dp(8), 0, 0) })
        put(infoNote("Nyalakan Safe Mode HP (umumnya: tahan tombol daya, tahan Matikan, pilih Safe Mode). Di Safe Mode aplikasi pihak ketiga tidak berjalan, sehingga kamu bisa menonaktifkan Administrator perangkat dan mencopot Penjaga Ketat lewat Pengaturan. Caranya sedikit berbeda tiap merek HP."))
        header("Sedang berat?")
        put(infoNote("Dorongan itu naik dan turun. Ruang Tenang di Beranda punya langkah singkat: napas, gerak, pindah tempat. Kamu tidak sendirian menghadapinya, dan memulai lagi kapan pun itu boleh."))
        put(button("Buka Ruang Tenang") {
            urgeEnd = 0L
            rtStep = 0
            goScreen("urge")
        })
    }

    private fun renderAbout() {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (t: Throwable) { "" }
        header("Data kamu")
        put(infoNote("Semua pemrosesan terjadi di HP ini. Penjaga Ketat tidak mengirim data apa pun ke server, tidak memakai akun, dan tidak memasang pelacak. Pengaturan, log, dan statistik hanya tersimpan di perangkat. Cadangan hanya berupa file yang kamu simpan sendiri."))
        header("Izin dan gunanya")
        put(infoNote("Aksesibilitas: membaca nama aplikasi, alamat browser, dan teks layar untuk memutuskan apa yang diblokir. Teks tidak disimpan atau dikirim."))
        put(infoNote("Tangkapan layar (AI): layar dianalisis di HP oleh model AI untuk mendeteksi gambar terlarang. Gambar tidak disimpan."))
        put(infoNote("VPN DNS: memeriksa nama situs yang dibuka di HP ini untuk memblokir situs terlarang dan menyalakan SafeSearch. Lalu lintas tidak dikirim ke pihak lain."))
        put(infoNote("Administrator perangkat: mencegah Penjaga Ketat dicopot saat Mode Ketat aktif. Lokasi: hanya untuk Zona Ketat, bila kamu menyalakannya."))
        header("Yang tidak bisa dicegah")
        put(infoNote("Penjaga Ketat tidak bisa mencegah: Force Stop lewat ADB, pencabutan izin lewat ADB, Safe Mode, profil kerja, atau factory reset. Ia hanya bisa mendeteksi dan memberi tahu. Salah blokir juga bisa terjadi."))
        put(infoNote("Penjaga Ketat adalah alat bantu kebiasaan, bukan layanan medis atau terapi."))
        if (ver.isNotEmpty()) put(text("Versi $ver", 12f, false, Color.parseColor("#7C7768")).apply { setPadding(0, dp(8), 0, 0) })
    }

    private var consentShown = false

    /** Persetujuan awal: dijelaskan sebelum izin-izin sensitif diminta. */
    private fun showConsent() {
        if (consentShown) return
        consentShown = true
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(6), dp(22), dp(16))
        box.addView(text("Penjaga Ketat membantumu menjaga diri dari konten dan kebiasaan yang ingin kamu jauhi. Sebelum mulai, ini yang perlu kamu tahu:", 13f, false, C_SUB))
        fun item(t: String, d: String) {
            box.addView(text(t, 14.5f, true, C_TEXT).apply { setPadding(0, dp(14), 0, 0) })
            box.addView(text(d, 12.5f, false, Color.parseColor("#A9A396")))
        }
        item("Semua diproses di HP", "Tidak ada data yang dikirim ke server, tidak ada akun, dan tidak ada pelacak.")
        item("Akses yang akan diminta", "Aksesibilitas (membaca nama aplikasi, alamat browser, dan teks layar), tangkapan layar untuk deteksi AI, VPN DNS, dan Administrator perangkat. Semuanya hanya untuk memblokir sesuai aturanmu dan bisa kamu cabut lewat Pengaturan HP.")
        item("Mode Ketat sengaja sulit dimatikan", "Setelah aktif, kamu harus memenuhi metode keluar yang kamu pilih. Pilih dengan sadar. Panduan keluar darurat ada di Pengaturan, Bantuan darurat.")
        item("Ada batasnya", "Penjaga Ketat tidak bisa mencegah Safe Mode, ADB, atau factory reset, dan kadang salah blokir. Ini alat bantu kebiasaan, bukan layanan medis atau terapi.")
        luxScreenDialog("", "SEBELUM MULAI", "Persetujuan", box, "Saya mengerti dan setuju", emptyList(), false, {
            Store.setConsent(this, true)
            true
        }, { finish() }, "Keluar", R.drawable.ic_nav_shield)
    }

    private fun screenTitle(s: String): String = when (s) {
        "strict" -> "Mode Ketat dan status"
        "apps" -> "Aplikasi diblokir"
        "words" -> "Situs dan kata kunci"
        "w_tg" -> "Telegram Kerja"
        "s_method" -> "Metode keluar"
        "s_guard" -> "Perlindungan diri"
        "s_ai" -> "Deteksi gambar AI"
        "s_night" -> "Jam rawan"
        "w_browser" -> "Browser"
        "w_cat" -> "Kategori situs"
        "w_kw" -> "Situs dan kata kunci"
        "w_feed" -> "Bagian aplikasi sosmed"
        "profiles" -> "Profil"
        "sched" -> "Jadwal & profil"
        "net" -> "VPN DNS dan notifikasi"
        "proteksi" -> "Proteksi"
        "stats" -> "Statistik"
        "focus" -> "Mode Fokus"
        "urge" -> "Ruang Tenang"
        "set_lock" -> "Kunci aplikasi"
        "set_friend" -> "Persetujuan teman"
        "set_zone" -> "Zona Ketat"
        "set_sec" -> "Lindungi aplikasi lain"
        "vpn" -> "VPN DNS"
        "notif" -> "Notifikasi"
        "set_backup" -> "Cadangan dan pulihkan"
        "set_test" -> "Uji perlindungan"
        "set_misc" -> "Catatan dan motivasi"
        "set_help" -> "Bantuan darurat"
        "set_about" -> "Privasi dan batasan"
        else -> "Setelan"
    }

    private fun goScreen(s: String) {
        screen = s
        render()
    }

    private fun eyebrowFor(s: String): String = when (s) {
        "proteksi" -> "PROTEKSI"
        "words", "w_tg", "w_browser", "w_cat", "w_kw", "w_feed" -> "SITUS & CHAT"
        "apps" -> "ATURAN BLOKIR"
        "sched", "profiles" -> "ATURAN OTOMATIS"
        "focus" -> "MODE"
        "strict", "s_method", "s_guard", "s_ai", "s_night" -> "MODE KETAT"
        "net", "vpn", "notif" -> "JARINGAN"
        "stats" -> "STATISTIK"
        "urge" -> "RUANG TENANG"
        else -> "PENGATURAN"
    }

    private fun topBar(title: String) {
        root.addView(text(eyebrowFor(screen), 11f, true, C_PRIMARY).apply {
            letterSpacing = 0.22f
            setPadding(dp(4), dp(6), 0, 0)
        })
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
        row.addView(text(title, 26f, true).apply {
            typeface = serifBold()
            setPadding(if (isTabRoot(screen)) dp(4) else dp(14), 0, 0, 0)
        })
        row.setPadding(0, dp(2), 0, dp(6))
        root.addView(row)
        root.addView(text(screenSummary(screen), 13f, false, C_SUB).apply { setPadding(dp(4), 0, 0, dp(10)) })
        if (Store.strictOn(this)) {
            root.addView(text("🔒  Mode Ketat aktif · pengaturan penting terkunci", 12f, true, C_PRIMARY).apply {
                background = bg(Color.parseColor("#1F1A0E"), dp(12)).apply { setStroke(dp(1), Color.argb(90, 212, 175, 106)) }
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
        root.addView(text(if (strict) "MODE KETAT AKTIF" else modeLabel().uppercase(), 11f, true, C_PRIMARY).apply {
            letterSpacing = 0.22f
            setPadding(dp(4), dp(10), 0, 0)
        })
        root.addView(text("Penjaga Ketat", 38f, true, C_TEXT).apply {
            typeface = serifBold()
            setPadding(dp(4), 0, 0, dp(14))
        })
        val protectionOk = acc && adm && Health.criticalOk(this)
        val outer = LinearLayout(this)
        outer.setPadding(dp(1), dp(1), dp(1), dp(1))
        outer.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(190, 212, 175, 106), Color.argb(25, 212, 175, 106), Color.argb(100, 212, 175, 106))
        ).apply { cornerRadius = dp(24).toFloat() }
        outer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        val banner = LinearLayout(this)
        banner.orientation = LinearLayout.HORIZONTAL
        banner.gravity = Gravity.CENTER_VERTICAL
        banner.setPadding(dp(20), dp(20), dp(20), dp(20))
        banner.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#161B25"), Color.parseColor("#0E1117"))
        ).apply { cornerRadius = dp(23).toFloat() }
        banner.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val ring = android.widget.FrameLayout(this)
        ring.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(30, 212, 175, 106))
            setStroke(dp(2), Color.argb(150, 212, 175, 106))
        }
        ring.layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
        ring.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_shield)
            setColorFilter(C_PRIMARY)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
        })
        banner.addView(ring)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), 0, 0, 0)
        col.addView(text(if (protectionOk) "Terlindungi" else "Perlu tindakan", 28f, true, C_TEXT).apply {
            typeface = serifBold()
        })
        col.addView(text(
            if (protectionOk) "Semua lapisan aktif.\n" + (if (Store.nsfwAi(this)) "Deteksi AI memantau layar." else "Deteksi AI sedang mati.")
            else "Aktifkan izin yang kurang\ndi bawah ini.",
            13f, false, C_SUB
        ))
        banner.addView(col)
        outer.addView(banner)
        root.addView(outer)
        renderStatTiles()
        renderModeSwitch()
        renderLayers(strict)

        val problems = Health.problems(this)
        val stepIds = setOf("acc", "admin", "usage", "battery", "notif")
        if (!protectionOk) {
            val steps = Health.items(this).filter { it.id in stepIds }
            val done = steps.count { it.ok }
            val first = steps.firstOrNull { !it.ok }
            cur = null
            root.addView(text("LANGKAH AWAL · ${minOf(done + 1, steps.size)} DARI ${steps.size}", 11f, true, C_PRIMARY).apply {
                letterSpacing = 0.22f
                setPadding(dp(4), dp(14), 0, dp(4))
            })
            root.addView(text("Aktifkan perlindungan", 26f, true, C_TEXT).apply { setPadding(dp(4), 0, 0, dp(2)) })
            root.addView(text("Penjaga Ketat butuh beberapa izin supaya bisa berjaga. Semuanya diproses di HP ini saja.", 13f, false, C_SUB).apply {
                setPadding(dp(4), 0, dp(4), dp(10))
            })
            val bar = LinearLayout(this)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.background = bg(C_CHIP, dp(3))
            bar.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)).apply { bottomMargin = dp(14) }
            bar.addView(View(this).apply {
                background = goldBg(dp(3))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, done.toFloat().coerceAtLeast(0.01f))
            })
            bar.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (steps.size - done).toFloat().coerceAtLeast(0.01f))
            })
            root.addView(bar)
            for ((idx, st) in steps.withIndex()) {
                val card = LinearLayout(this)
                card.orientation = LinearLayout.VERTICAL
                card.setPadding(dp(14), dp(13), dp(14), dp(13))
                val isCur = st === first
                card.background = (if (isCur) GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#161B25"), Color.parseColor("#0E1117"))
                ) else bg(Color.parseColor("#10131A"), 0)).apply {
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), if (isCur) Color.argb(170, 212, 175, 106) else C_CHIP)
                }
                card.alpha = if (!st.ok && !isCur) 0.7f else 1f
                card.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                val top = LinearLayout(this)
                top.orientation = LinearLayout.HORIZONTAL
                top.gravity = Gravity.CENTER_VERTICAL
                val badge = TextView(this)
                badge.gravity = Gravity.CENTER
                badge.textSize = 12f
                badge.setTypeface(null, Typeface.BOLD)
                if (st.ok) {
                    badge.text = "✓"
                    badge.setTextColor(C_OK)
                    badge.background = bg(Color.argb(36, 95, 201, 160), dp(14))
                } else {
                    badge.text = (idx + 1).toString()
                    badge.setTextColor(if (isCur) C_PRIMARY else C_SUB)
                    badge.background = bg(Color.TRANSPARENT, dp(14)).apply {
                        setStroke(dp(2), if (isCur) C_PRIMARY else Color.parseColor("#4A4F5B"))
                    }
                }
                badge.layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                top.addView(badge)
                val tc = LinearLayout(this)
                tc.orientation = LinearLayout.VERTICAL
                tc.setPadding(dp(14), 0, 0, 0)
                tc.addView(text(st.title, if (isCur) 15f else 14.5f, isCur, C_TEXT))
                tc.addView(text(st.help, 12f, false, Color.parseColor("#8A8576")))
                top.addView(tc, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(top)
                val fixText = st.fixLabel
                if (isCur && fixText != null) {
                    card.addView(text("Di layar berikutnya, nyalakan izin untuk Penjaga Ketat, lalu kembali ke aplikasi ini.", 12.5f, false, C_SUB).apply {
                        setPadding(0, dp(10), 0, dp(8))
                    })
                    card.addView(button(fixText) { healthFix(st.id) })
                }
                root.addView(card)
            }
            root.addView(text("Kamu bisa melanjutkan nanti. Status tampil di Beranda.", 12f, false, Color.parseColor("#7C7768")).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(10))
            })
        }
        val extras = if (protectionOk) problems else problems.filter { it.id !in stepIds }
        if (extras.isNotEmpty()) {
            header("Saran perbaikan")
            for (pr in extras) {
                status(false, pr.label)
                val fl = pr.fixLabel
                if (fl != null) put(button(fl) { healthFix(pr.id) })
            }
        }

        renderFocusLine()

        root.addView(button("🌿  Butuh jeda? Masuk Ruang Tenang") {
            urgeEnd = 0L
            rtStep = 0
            goScreen("urge")
        }.apply {
            setTextColor(C_BAD)
            background = bg(C_CHIP, dp(14))
        })

    }

    private fun healthFix(id: String) {
        when (id) {
            "acc" -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "admin" -> requestAdmin()
            "usage" -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            "battery" -> try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (t: Throwable) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            "pdns" -> try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } catch (t: Throwable) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            "notif" -> if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            "listener" -> {
                if (!SelfProtector.enableNotifListener(this)) startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            "browsers" -> Store.setBlockOtherBrowsers(this, true)
        }
        handler.postDelayed({ render() }, 800L)
    }

    private fun renderProteksi() {
        val strict = Store.strictOn(this)
        val profiles = ProfileStore.load(this)
        val activeP = profiles.count { ProfileEngine.isActive(this, it) }
        val wordCount = Store.keywords(this).size + Store.domains(this).size
        val cats = Categories.enabled(this).size
        eyebrow("SAKLAR FITUR")
        val card = newCard()
        root.addView(card)
        val feats = listOf(
            Triple("apps", "Aplikasi", "Blokir, batas waktu, jadwal"),
            Triple("keywords", "Kata kunci", "Teks di layar dan kolom pencarian"),
            Triple("sites", "Situs", "Alamat di Chrome dan DNS"),
            Triple("social", "Bagian sosmed", "Shorts, Reels, Cerita, Pencarian")
        )
        for ((i, f) in feats.withIndex()) {
            card.addView(luxRow(0, f.second, f.third, Store.featureOn(this, f.first), strict, i == feats.size - 1) {
                toggleFeature(f.first, f.second, strict)
            })
        }
        val ai = newCard()
        ai.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#161B25"), Color.parseColor("#0E1117"))
        ).apply { cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.argb(150, 212, 175, 106)) }
        root.addView(ai)
        ai.addView(luxRow(0, "Deteksi AI gambar", "Model ONNX · pemindaian adaptif", Store.nsfwAi(this), strict, true) { toggleNsfwAi(strict) })
        ai.addView(text(NsfwAi.status(this), 11f, false, C_SUB))
        val lockCard = newCard()
        root.addView(lockCard)
        lockCard.addView(text("Kunci layar bertahap", 15f, true))
        lockCard.addView(text("5 layar blokir dalam 10 menit → terkunci 5 menit", 12f, false, C_SUB))
        eyebrow("ATURAN & MODE")
        homeGroup("ATURAN BLOKIR")
        homeRow(R.drawable.ic_ly_phone, "Aplikasi", "Blokir, batasi waktu, atau beri timer tunggu.", "${Store.apps(this).size} diblokir", Color.parseColor("#C99A45"), "apps")
        homeRow(R.drawable.ic_nav_globe, "Situs & kata", "Situs, kata kunci, kategori, dan bagian aplikasi sosmed.", "$wordCount aturan • $cats kategori", Color.parseColor("#A9A396"), "words")
        homeRow(R.drawable.ic_ly_calendar, "Jadwal & profil", "Aturan otomatis per hari dan jam (Kerja, Tidur, dll.).", scheduleTileLabel() + " • $activeP profil aktif", Color.parseColor("#5FC9A0"), "sched")
        homeGroup("MODE")
        homeRow(R.drawable.ic_ly_target, "Mode Fokus", "Sesi waktu yang menutup aplikasi dan situs pemicu.", focusTileLabel(), Color.parseColor("#F687B3"), "focus")
        homeRow(R.drawable.ic_nav_shield, "Mode Ketat & status", "Kunci pengaturan dan cek perlindungan diri.", modeLabel(), C_PRIMARY, "strict")
        homeRow(R.drawable.ic_ly_plug, "VPN & notifikasi", "Filter situs lewat DNS dan kontrol notifikasi.", if (VpnStatus.running) "VPN DNS aktif" else if (Store.vpnOn(this)) "VPN DNS: " + VpnStatus.state.label else "VPN DNS mati", Color.parseColor("#68D391"), "net")

    }

    /** Warna aksen; di mode gelap dibuat lebih terang supaya terbaca. */
    private fun accent(color: Int): Int = color

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
        t.addView(text(value, 22f, true, accent(color)).apply { gravity = Gravity.CENTER; typeface = serifBold() })
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
        val locks = try { Strikes.count(this).toString() } catch (t: Throwable) { "0" }
        row.addView(statTile(blocks, "Diblokir hari ini", Color.parseColor("#E6C987"), true, false, "stats"))
        row.addView(statTile(cleanStreak().toString(), "Hari bersih", Color.parseColor("#68D391"), false, false, "stats"))
        row.addView(statTile(locks, "Kunci layar", Color.parseColor("#A9A396"), false, true, "stats"))
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

    private fun homeRow(icon: Int, title: String, help: String, status: String, color: Int, target: String) {
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
        row.elevation = 0f
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(color)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = bg(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)), dp(14)).apply { setStroke(dp(1), Color.argb(120, Color.red(color), Color.green(color), Color.blue(color))) }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        })
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(text(title, 15f, true))
        col.addView(text(help, 12f, false, C_SUB))
        col.addView(text(status, 12f, true, color))
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
        if (acc && adm) return
        header("Perlu tindakan")
        status(acc, "Aksesibilitas Penjaga Ketat aktif")
        status(adm, "Perlindungan uninstall (Administrator perangkat) aktif")
        if (!acc) put(button("Buka pengaturan Aksesibilitas") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (!adm) put(button("Aktifkan perlindungan uninstall") { requestAdmin() })
    }

    /** Catatan penjelasan: teks panjang yang redup, dengan garis emas di kiri. */
    private fun infoNote(s: String): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(6), 0, dp(6))
        row.addView(View(this).apply {
            background = bg(Color.argb(120, 212, 175, 106), dp(1))
            layoutParams = LinearLayout.LayoutParams(dp(2), LinearLayout.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(10) }
        })
        row.addView(text(s, 12f, false, Color.parseColor("#8A8576")).apply {
            setPadding(0, 0, 0, 0)
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    /** Baris saklar tanpa ikon: judul, keterangan singkat, saklar emas. */
    private fun switchLine(title: String, sub: String, on: Boolean, locked: Boolean = false, onToggle: (Boolean) -> Unit) {
        put(luxRow(0, title, sub, on, locked, false) { onToggle(!on) })
    }

    /** Dua ubin waktu berdampingan, mis. Mulai dan Selesai. */
    private fun timeRow(l1: String, v1: String, c1: () -> Unit, l2: String, v2: String, c2: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(6), 0, dp(6))
        fun tile(l: String, v: String, click: () -> Unit, first: Boolean): View {
            val t = LinearLayout(this)
            t.orientation = LinearLayout.VERTICAL
            t.setPadding(dp(14), dp(10), dp(14), dp(10))
            t.background = bg(Color.parseColor("#10131A"), dp(16)).apply { setStroke(dp(1), C_CHIP) }
            t.addView(text(l, 11f, false, Color.parseColor("#8A8576")))
            val vt = text(v, 28f, true, C_PRIMARY)
            t.addView(vt)
            t.tag = vt
            t.setOnClickListener { click() }
            t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (first) rightMargin = dp(5) else leftMargin = dp(5)
            }
            return t
        }
        val t1 = tile(l1, v1, c1, true)
        val t2 = tile(l2, v2, c2, false)
        row.addView(t1)
        row.addView(t2)
        row.tag = listOf(t1.tag, t2.tag)
        return row
    }

    private fun timeTv(holder: View, idx: Int): Any? = (holder.tag as? List<*>)?.getOrNull(idx)

    /** Pilihan segmen (mis. Selalu | Interval waktu). */
    private fun segmented(labels: List<String>, selected: Int, onSel: (Int) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.background = bg(Color.parseColor("#0A0C10"), dp(18)).apply { setStroke(dp(1), C_CHIP) }
        row.setPadding(dp(3), dp(3), dp(3), dp(3))
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(6) }
        fun paint(sel: Int) {
            row.removeAllViews()
            for ((i, l) in labels.withIndex()) {
                row.addView(segPill(l, i == sel, C_PRIMARY) {
                    paint(i)
                    onSel(i)
                }.apply { textSize = 13f })
            }
        }
        paint(selected)
        return row
    }

    /** Deretan chip pilihan angka (mis. 5 / 10 / 15 / 30 menit). */
    private fun chipChoices(options: List<Int>, selected: Int, suffix: String, onSel: (Int) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(6) }
        fun paint(sel: Int) {
            row.removeAllViews()
            for ((i, o) in options.withIndex()) {
                val on = i == sel
                row.addView(TextView(this).apply {
                    text = "$o $suffix"
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (on) C_ON_GOLD else C_SUB)
                    setPadding(0, dp(11), 0, dp(11))
                    background = if (on) goldBg(dp(12)) else bg(Color.parseColor("#10131A"), dp(12)).apply { setStroke(dp(1), C_CHIP) }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = if (i == 0) 0 else dp(3); rightMargin = if (i == options.size - 1) 0 else dp(3)
                    }
                    setOnClickListener { paint(i); onSel(i) }
                })
            }
        }
        paint(selected)
        return row
    }

    /** Saklar yang menyimpan statusnya sendiri; mengembalikan tampilan dan pembaca status. */
    private fun toggleRow(title: String, sub: String, initial: Boolean, card: Boolean = true): Pair<View, () -> Boolean> {
        var state = initial
        val holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        if (card) {
            holder.setPadding(dp(14), 0, dp(14), 0)
            holder.background = bg(Color.parseColor("#10131A"), dp(16)).apply { setStroke(dp(1), C_CHIP) }
            holder.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        fun build() {
            holder.removeAllViews()
            holder.addView(luxRow(0, title, sub, state, false, true) {
                state = !state
                build()
            })
        }
        build()
        return Pair(holder, { state })
    }

    private fun ghostButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(C_TEXT)
            background = bg(Color.parseColor("#10131A"), dp(16)).apply { setStroke(dp(1), Color.parseColor("#2A2F3B")) }
            stateListAnimator = null
            elevation = 0f
            minHeight = dp(48)
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun methodSummary(): String {
        val parts = ArrayList<String>()
        val tl = Store.exitTextLen(this)
        if (tl > 0) parts.add("Teks acak $tl")
        if (Store.exitWaitOn(this)) parts.add("Jeda " + fmtDur(Store.exitDelayMin(this)))
        if (FriendApproval.isSet(this)) parts.add("Teman")
        if (Store.expireMin(this) > 0) parts.add("Kedaluwarsa")
        return if (parts.isEmpty()) "Belum diatur" else parts.joinToString(" · ")
    }

    private fun renderStrictHub(strict: Boolean) {
        val bad = AntiBypass.checks(this).count { !it.first }
        navGroup("ATURAN KELUAR", listOf(
            NavItem(R.drawable.ic_lock, "Metode keluar", methodSummary(), "", C_PRIMARY, "s_method"),
            NavItem(R.drawable.ic_ly_target, "Zona Ketat", "Perketat otomatis di lokasi tertentu", "", C_PRIMARY, "set_zone")
        ))
        navGroup("PERLINDUNGAN", listOf(
            NavItem(R.drawable.ic_nav_shield, "Perlindungan diri", "Pemeriksaan, pemulih, dan celah pemasangan",
                if (bad == 0) "Aman" else "$bad perlu dicek", if (bad == 0) C_OK else C_BAD, "s_guard"),
            NavItem(R.drawable.ic_ly_eye, "Deteksi gambar AI", "Tutup layar saat ada gambar terlarang",
                if (Store.nsfwAi(this)) "Aktif" else "Mati", if (Store.nsfwAi(this)) C_OK else C_SUB, "s_ai"),
            NavItem(R.drawable.ic_ly_calendar, "Jam rawan", "Gerbang dua kali lebih berat di jam tertentu",
                if (Night.enabled(this)) "Aktif" else "Mati", if (Night.enabled(this)) C_OK else C_SUB, "s_night")
        ))
    }

    private fun renderMethods(strict: Boolean) {
        val raw = Store.strictRaw(this)
        put(infoNote(
            if (raw) "Mode Ketat aktif, jadi metode di bawah terkunci. Metode ini berlaku untuk keluar dari Mode Ketat."
            else "Pilih cara keluar dari Mode Ketat. Semua metode yang dipilih harus dipenuhi."
        ))
        header("Metode keluar")
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
            if (FriendApproval.isSet(this)) "Kode teman sudah diatur" else "Belum diatur (ketuk untuk mengatur)",
            true
        ) { friendDialog() }
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
        val goldSel = selected && color == C_PRIMARY
        t.setTextColor(if (goldSel) C_ON_GOLD else if (selected) Color.WHITE else C_SUB)
        t.background = if (goldSel) goldBg(dp(if (seg) 14 else 12)) else bg(if (selected) color else if (seg) Color.TRANSPARENT else C_FIELD, dp(if (seg) 14 else 12))
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
        row.background = bg(C_FIELD, dp(18)).apply { setStroke(dp(1), C_CHIP) }
        row.setPadding(dp(3), dp(3), dp(3), dp(3))
        row.addView(segPill("Off", paused, Color.parseColor("#4A5160")) {
            if (raw) toast("Mode Ketat aktif. Keluar dulu lewat metode penonaktifan.") else pauseDialog()
        })
        row.addView(segPill("Normal", !paused && !raw, Color.parseColor("#3F8F78")) {
            if (raw) toast("Mode Ketat aktif. Keluar dulu lewat metode penonaktifan.") else {
                Store.pauseStop(this)
                render()
            }
        })
        row.addView(segPill("Ketat", raw, C_PRIMARY) {
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
        luxPicker("MODE OFF", "Jeda berapa lama?", labels.toList()) { i ->
            Store.pauseStart(this, mins[i])
            render()
        }
    }

    private fun methodRow(title: String, summary: String, enabled: Boolean, onClick: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        row.background = bg(C_FIELD, dp(14)).apply { setStroke(dp(1), C_CHIP) }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(text(title, 14f, true, if (enabled) C_TEXT else C_SUB))
        col.addView(text(summary, 12f, false, C_SUB))
        row.addView(col)
        row.addView(text("›", 22f, true, if (enabled) C_PRIMARY else C_SUB))
        if (enabled) row.setOnClickListener { onClick() }
        put(row)
    }

    private var exitTick: Runnable? = null

    private fun confirmStrict() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(text("Yang akan terkunci", 13f, true, C_PRIMARY))
        box.addView(infoNote("Daftar blokir hanya bisa ditambah, pengaturan lain terkunci, dan Penjaga Ketat tidak bisa dicopot."))
        box.addView(text("Cara keluar yang kamu pilih", 13f, true, C_PRIMARY).apply { setPadding(0, dp(8), 0, 0) })
        box.addView(infoNote(methodSummary() + ". Panduan keluar darurat ada di Pengaturan, Bantuan darurat."))
        val noExpiry = Store.expireMin(this) <= 0
        var wantExpiry: () -> Boolean = { false }
        if (noExpiry) {
            val (view, get) = toggleRow("Batas 7 hari", "Mode Ketat mati sendiri setelah 7 hari. Disarankan untuk pertama kali.", true)
            box.addView(view)
            wantExpiry = get
        }
        val ld = luxDialog("MODE KETAT", "Aktifkan sekarang?", "", box, "Aktifkan", "Batal") { }
        ld.pos.setOnClickListener {
            if (noExpiry && wantExpiry()) Store.setExpireMin(this, 10080)
            Store.setStrict(this, true)
            SafeTimer.cancel(this, "exit")
            Store.log(this, "Mode Ketat diaktifkan")
            WatchdogService.start(this)
            ld.dlg.dismiss()
            render()
        }
        ld.dlg.show()
    }

    private fun renderStrict(strict: Boolean, ready: Boolean) {
        val raw = Store.strictRaw(this)
        renderModeSwitch()
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
                    confirmStrict()
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
            val total = (waitMin * 60000L).coerceAtLeast(1L)
            put(text("MASIH MENUNGGU", 11f, true, C_PRIMARY).apply { letterSpacing = 0.22f; gravity = Gravity.CENTER })
            put(text("Tunggu sebentar lagi", 26f, true, C_TEXT).apply { gravity = Gravity.CENTER })
            val wb = waitBox(clockText(left), "tersisa", left / total.toFloat())
            put(wb)
            put(text("Waktu dihitung dari jam internal HP, jadi mengubah jam tidak mempercepatnya. Setelah habis, kamu bisa melanjutkan.", 12.5f, false, C_SUB).apply { gravity = Gravity.CENTER })
            put(ghostButton("Batalkan permintaan") {
                SafeTimer.cancel(this, "exit")
                render()
            })
            val ring = wb.tag as RingView
            exitTick?.let { handler.removeCallbacks(it) }
            val tick = object : Runnable {
                override fun run() {
                    if (!ring.isAttachedToWindow) return
                    val l = waitMin * 60000L - SafeTimer.elapsed(this@MainActivity, "exit")
                    if (l <= 0L) {
                        render()
                        return
                    }
                    ring.big = clockText(l)
                    ring.frac = l / total.toFloat()
                    ring.invalidate()
                    handler.postDelayed(this, 1000L)
                }
            }
            exitTick = tick
            handler.postDelayed(tick, 1000L)
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
            put(text("GERBANG KEAMANAN", 11f, true, C_PRIMARY).apply { letterSpacing = 0.22f; gravity = Gravity.CENTER })
            put(text("Ketik teks ini", 28f, true, C_TEXT).apply { gravity = Gravity.CENTER })
            put(text("Ketik teks acak $textLen huruf persis seperti yang tertulis. Hanya 5 huruf berikutnya yang ditampilkan. Layar ini tidak bisa di-screenshot.", 13f, false, C_SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(10)) })
            val shown = TextView(this)
            shown.textSize = 30f
            shown.typeface = Typeface.MONOSPACE
            shown.gravity = Gravity.CENTER
            shown.letterSpacing = 0.18f
            shown.setTextColor(Color.parseColor("#E6D6B0"))
            shown.setPadding(dp(12), dp(12), dp(12), dp(12))
            shown.background = bg(Color.parseColor("#0A0C10"), dp(14)).apply { setStroke(dp(1), C_CHIP) }
            shown.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            put(shown)
            val counter = text("0 / $textLen", 12f, true, C_PRIMARY).apply { gravity = Gravity.END }
            put(text("Huruf besar dan kecil dibedakan. Ketik tanpa spasi.", 12f, false, Color.parseColor("#8A8576")))
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
                val end = Math.min(code.length, cp + 5)
                shown.text = code.substring(cp, end)
                counter.text = "${gateTyped.length} / $textLen"
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
        put(ghostButton("Batalkan permintaan") {
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
        box.setPadding(dp(22), dp(6), dp(22), dp(16))
        val zdlg = luxScreenDialog("", "ZONA KETAT", "Tambah zona", box, "Simpan zona", emptyList(), false, save@{
            val lat = latF.text.toString().trim().toDoubleOrNull()
            val lon = lonF.text.toString().trim().toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                toast("Lokasi tidak valid: isi lintang dan bujur, atau pakai lokasi saya sekarang.")
                return@save false
            }
            if (!Zone.add(this, lat, lon, radii[seek.progress])) {
                toast("Jumlah zona sudah penuh")
                return@save false
            }
            if (!LocationTracker.hasPermission(this) || !LocationTracker.hasBackground(this)) askLocationPermission()
            Zone.update(this)
            render()
            true
        }, { render() }, "Zona Ketat", R.drawable.ic_ly_target)
        zdlg.setOnDismissListener { zoneMap?.destroy() }
    }

    private fun dialogExpire() {
        val labels = arrayOf("Tidak ada (manual)", "1 jam", "3 jam", "6 jam", "12 jam", "1 hari", "3 hari", "7 hari", "14 hari", "30 hari")
        val mins = intArrayOf(0, 60, 180, 360, 720, 1440, 4320, 10080, 20160, 43200)
        luxPicker("METODE KELUAR", "Waktu kedaluwarsa", labels.toList(), mins.indexOf(Store.expireMin(this))) { i ->
            Store.setExpireMin(this, mins[i])
            render()
        }
    }

    private fun luxField(hint: String, password: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        setSingleLine()
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setTextColor(C_TEXT)
        setHintTextColor(Color.parseColor("#7C7768"))
        textSize = 14.5f
        background = bg(Color.parseColor("#10131A"), dp(14)).apply { setStroke(dp(1), Color.parseColor("#2A2F3B")) }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    /** Konfirmasi bergaya Lux. */
    private fun luxConfirm(eyebrowText: String, title: String, body: String, posLabel: String, onPos: () -> Unit) {
        val ld = luxDialog(eyebrowText, title, body, null, posLabel, "Batal") { }
        ld.pos.setOnClickListener { ld.dlg.dismiss(); onPos() }
        ld.dlg.show()
    }

    /** Tujuh chip hari; mengembalikan tampilan dan pembaca mask hari. */
    private fun dayChips(initial: Int): Pair<View, () -> Int> {
        var mask = initial
        val names = arrayOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6); bottomMargin = dp(6) }
        fun paint() {
            row.removeAllViews()
            for (i in 0 until 7) {
                val on = (mask and (1 shl i)) != 0
                row.addView(TextView(this).apply {
                    text = names[i]
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (on) C_ON_GOLD else C_SUB)
                    setPadding(0, dp(11), 0, dp(11))
                    background = if (on) goldBg(dp(12)) else bg(Color.parseColor("#10131A"), dp(12)).apply { setStroke(dp(1), C_CHIP) }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = if (i == 0) 0 else dp(3); rightMargin = if (i == 6) 0 else dp(3)
                    }
                    setOnClickListener { mask = mask xor (1 shl i); paint() }
                })
            }
        }
        paint()
        return Pair(row, { if (mask == 0) 127 else mask })
    }

    private fun rowButton(title: String, sub: String, onClick: () -> Unit): View =
        listRow(title, sub, C_TEXT, onClick, null)

    private fun dialogStrictSchedule() {
        val sch = Store.strictSchedules(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        if (sch.isEmpty()) {
            box.addView(text("Belum ada jadwal: Mode Ketat aktif terus.", 13f, false, C_SUB))
        } else for (it in sch) {
            box.addView(text("${fmt(it.start)} - ${fmt(it.end)}   ${dayNames(it.days)}", 14f, true, C_TEXT).apply {
                background = bg(Color.parseColor("#10131A"), dp(14)).apply { setStroke(dp(1), C_CHIP) }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            })
        }
        box.addView(infoNote("Di luar jadwal, Mode Ketat berperilaku seperti Normal."))
        lateinit var ld: LuxDlg
        if (sch.isNotEmpty()) box.addView(ghostButton("Hapus semua jadwal") {
            Store.setStrictSchedules(this, emptyList())
            ld.dlg.dismiss()
            render()
        })
        ld = luxDialog("METODE KELUAR", "Jadwal Mode Ketat", "", box, "Tambah jadwal", "Tutup") { }
        ld.pos.setOnClickListener {
            ld.dlg.dismiss()
            editSchedule { s ->
                Store.setStrictSchedules(this, Store.strictSchedules(this) + s)
                render()
            }
        }
        ld.dlg.show()
    }

    private fun dialogTextLen() {
        val lens = intArrayOf(0, 10, 50, 100, 200, 500, 600, 2000)
        val labels = lens.map { if (it == 0) "Mati (tanpa teks acak)" else "$it huruf" }.toTypedArray()
        luxPicker("METODE KELUAR", "Panjang teks acak", labels.toList(), lens.indexOf(Store.exitTextLen(this))) { i ->
            Store.setExitTextLen(this, lens[i])
            gateText = null
            gateTyped = ""
            render()
        }
    }

    private fun dialogWait() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val (swView, swState) = toggleRow("Pakai waktu tunggu", "Mode Ketat baru bisa dimatikan setelah menunggu", Store.exitWaitOn(this), false)
        box.addView(swView)
        val (tile, f) = bigNumber("Menit tunggu", "menit", Store.exitDelayMin(this),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        box.addView(tile)
        box.addView(text("Antara 10 dan 1440 menit.", 12f, false, C_SUB).apply { setPadding(0, dp(6), 0, 0) })
        val ld = luxDialog("METODE KELUAR", "Jeda", "", box, "Simpan", "Batal") { }
        ld.pos.setOnClickListener {
            Store.setExitWaitOn(this, swState())
            Store.setExitDelayMin(this, (f.text.toString().toIntOrNull() ?: 60).coerceIn(10, 1440))
            ld.dlg.dismiss()
            render()
        }
        ld.dlg.show()
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
                    val et = luxField("PIN saat ini").apply {
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    }
                    val ld = luxDialog("KUNCI APLIKASI", "Matikan kunci aplikasi", "Masukkan PIN saat ini.", et, "Matikan", "Batal") { }
                    ld.pos.setOnClickListener {
                        if (AppLock.verifyPin(this, et.text.toString())) {
                            AppLock.turnOff(this)
                            ld.dlg.dismiss()
                        } else {
                            toast("PIN salah atau terkunci")
                        }
                        render()
                    }
                    ld.dlg.show()
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
        val pw = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val old = luxField("PIN lama").apply { inputType = pw }
        if (AppLock.hasPin(this)) box.addView(old)
        val n1 = luxField("PIN baru (4 sampai 8 angka)").apply { inputType = pw }
        box.addView(n1)
        val n2 = luxField("Ulangi PIN baru").apply { inputType = pw }
        box.addView(n2)
        val ld = luxDialog("KUNCI APLIKASI", "PIN aplikasi", "", box, "Simpan", "Batal") { }
        ld.pos.setOnClickListener {
            if (AppLock.hasPin(this) && !AppLock.verifyPin(this, old.text.toString())) {
                toast("PIN lama salah atau terkunci")
            } else if (n1.text.toString() != n2.text.toString()) {
                toast("PIN tidak sama")
            } else {
                val err = AppLock.setPin(this, n1.text.toString())
                if (err == null) AppLock.markUnlocked()
                toast(err ?: "PIN tersimpan")
                if (err == null) ld.dlg.dismiss()
            }
            render()
        }
        ld.dlg.show()
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
        put(infoNote("Batas yang tidak bisa ditutup tanpa root/device-owner: Force Stop lewat ADB, pencabutan izin lewat ADB, Safe Mode, profil kerja, dan factory reset. Penjaga Ketat hanya bisa mendeteksi dan memberi tahu, bukan mencegahnya."))
        header("Perlindungan diri")
        val secure = SelfProtector.canWriteSecure(this)
        val usage = Foreground.hasUsageAccess(this)
        status(secure, "Pemulih otomatis aksesibilitas (izin WRITE_SECURE_SETTINGS)")
        status(usage, "Akses Penggunaan (mengenali aplikasi bank terbuka)")
        status(WatchdogService.running, "Pengawas berjalan (hanya saat Mode Ketat aktif)")
        if (!secure) {
            put(infoNote("Agar aksesibilitas yang dimatikan otomatis menyala lagi, jalankan SEKALI dari PC (USB debugging aktif). Tanpa izin ini, pengawas hanya memberi peringatan lewat notifikasi."))
            put(text("adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS", 12f).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                background = bg(Color.parseColor("#0A0C10"), dp(12)).apply { setStroke(dp(1), C_CHIP) }
                setPadding(dp(12), dp(10), dp(12), dp(10))
            })
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
        put(infoNote("Jam HP diubah saat Mode Ketat aktif: jadwal diabaikan dan blokir berlaku penuh 30 menit."))
        put(infoNote("Pemulihan darurat: keluar Mode Ketat lewat tombol di layar Mode Ketat (tunggu lalu ketik teks acak). Itu jalur pemulihan yang selalu tersedia, tanpa PC."))
    }

    // ---------- Deteksi gambar AI ----------
    private fun renderNsfwAi(strict: Boolean) {
        header("Deteksi gambar AI (NSFW)")
        val on = Store.nsfwAi(this)
        switchLine("Aktifkan deteksi", "Layar dianalisis di HP, tanpa internet", on, strict && on) { v ->
            if (!v && strict) {
                toast("Mode Ketat aktif: tidak bisa dimatikan")
                render()
            } else {
                gateOff(!v, "mematikan deteksi gambar AI") { Store.setNsfwAi(this, v) }
            }
        }
        put(text(NsfwAi.status(this), 12f, false, C_SUB))
        if (android.os.Build.VERSION.SDK_INT < 30) put(text("HP ini di bawah Android 11: tangkapan layar tidak tersedia.", 12f, false, C_BAD))
        put(infoNote("Layar difoto tiap sekitar 1 detik saat HP menyala, lalu dianalisis di HP oleh model AI. Tidak ada gambar yang disimpan atau dikirim. Bila ada gambar telanjang, layar langsung ditutup dan kamu dikembalikan ke beranda."))
        put(infoNote("Hanya aplikasi yang bisa memuat media (browser, sosmed, galeri, pemutar video) dan aplikasi yang baru dipasang (14 hari) yang dipotret, supaya hemat baterai. Butuh Android 11 ke atas. Salah blokir bisa terjadi."))
        put(infoNote("Bila status tidak menunjukkan pemindaian setelah beberapa menit, matikan lalu hidupkan lagi layanan Aksesibilitas Penjaga Ketat."))
    }

    // ---------- Celah pemasangan (saklar terpisah) ----------
    private fun renderBypassGuard(strict: Boolean) {
        header("Celah pemasangan")
        val on = Store.blockUnknownInstall(this)
        switchLine("Blokir pemasangan APK luar", "Saat Mode Ketat, kecuali aplikasi buatanmu", on, strict && on) { v ->
            if (!v && strict) {
                toast("Mode Ketat aktif: tidak bisa dimatikan")
                render()
            } else {
                gateOff(!v, "mematikan blokir pemasangan APK") { Store.setBlockUnknownInstall(this, v) }
            }
        }
        put(infoNote("Saat Mode Ketat aktif, halaman Pengaturan untuk menambah pengguna/tamu dan Aplikasi admin perangkat selalu ditutup."))
        put(infoNote("Layar pemasang APK ditutup bila yang dipasang bukan aplikasi buatanmu, dan halaman izin Instal aplikasi tidak dikenal ikut ditutup. Update Penjaga Ketat dan Channel Blocker lewat Chrome atau Pengelola file tetap bisa. Instal dari Play Store tidak terpengaruh."))
    }

    // ---------- Jam rawan ----------
    private fun renderNight(strict: Boolean) {
        header("Jam rawan")
        if (Night.active(this)) put(text("Sekarang sedang jam rawan (gerbang x2).", 13f, true, C_BAD))
        val on = Night.enabled(this)
        switchLine("Aktifkan jam rawan", "Gerbang keluar dua kali lebih berat", on, strict && on) { v ->
            if (!v && strict) {
                toast("Mode Ketat aktif: tidak bisa dimatikan")
                render()
            } else {
                gateOff(!v, "mematikan jam rawan") { Night.setEnabled(this, v) }
            }
        }
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
        put(timeRow("Mulai", Night.fmt(Night.start(this)), { pick(true) }, "Selesai", Night.fmt(Night.end(this)), { pick(false) }))
        put(infoNote("Di jam rawan, teks acak dua kali lebih panjang dan waktu tunggu dua kali lebih lama, baik untuk keluar Mode Ketat maupun mematikan fitur."))
        header("Kunci total di jam rawan")
        val lp = Night.lockPlay(this)
        switchLine("Tutup Play Store", "Tidak bisa memasang aplikasi", lp, strict && lp) { v ->
            if (!v && strict) {
                toast("Mode Ketat aktif: tidak bisa dimatikan")
                render()
            } else gateOff(!v, "mematikan kunci Play Store") { Night.setLockPlay(this, v) }
        }
        val ls = Night.lockSettings(this)
        switchLine("Tutup aplikasi Pengaturan", "Aksesibilitas tidak bisa dinyalakan ulang sampai selesai", ls, strict && ls) { v ->
            if (!v && strict) {
                toast("Mode Ketat aktif: tidak bisa dimatikan")
                render()
            } else gateOff(!v, "mematikan kunci Pengaturan") { Night.setLockSettings(this, v) }
        }
        header("Profil Malam")
        put(infoNote("Profil Malam menutup browser dan aplikasi sosmed yang terpasang selama jam rawan, setiap hari. Saat Mode Ketat aktif, tombol ini hanya menambah aplikasi, tidak mengubah jadwal."))
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
        val rt = Store.ruangLog(this).filter { (it.getOrNull(0)?.toLongOrNull() ?: 0L) >= weekAgo }
        put(text("Ruang Tenang 7 hari terakhir: $urges kali", 12f, false, C_TEXT))
        val drops = rt.mapNotNull { (it.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null) - (it.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null) }
        if (drops.isNotEmpty()) put(text("Rata-rata dorongan turun ${String.format("%.1f", drops.average())} poin setelah Ruang Tenang", 12f, false, C_SUB))
        val trig = rt.mapNotNull { it.getOrNull(3)?.takeIf { t -> t.isNotBlank() } }.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (trig != null) put(text("Pemicu paling sering: ${trig.key} (${trig.value} kali)", 12f, false, C_SUB))
    }

    // ---------- Ruang Tenang ----------
    private var rtStep = 0          // 0 nilai awal, 1 pemicu, 2..5 langkah, 6 nilai akhir, 7 ringkasan
    private var rtBefore = 5
    private var rtAfter = 5
    private var rtTrigger = ""
    private var urgeEnd = 0L
    private var urgeTv: TextView? = null
    private var urgeHint: TextView? = null
    private var urgeBtn: Button? = null

    private class RtStep(val title: String, val body: String, val sec: Int, val breath: Boolean)
    private val rtSteps = listOf(
        RtStep("Napas pelan", "Duduk atau berdiri tegak. Ikuti irama di bawah: tarik, tahan, hembuskan.", 60, true),
        RtStep("Gerakkan badan", "Berdiri sekarang. Lompat-lompat di tempat atau jalan cepat. Biarkan jantung berdetak lebih cepat.", 45, false),
        RtStep("Pindah tempat", "Tinggalkan ruangan ini. Keluar kamar, ke ruang keluarga atau teras. Taruh HP agak jauh dari badan.", 60, false),
        RtStep("Wudhu dan dzikir", "Ambil wudhu, lalu istighfar atau dzikir pelan. Air dan gerakan membantu menenangkan.", 60, false)
    )

    private val urgeTick = object : Runnable {
        override fun run() {
            if (screen != "urge" || urgeEnd == 0L || rtStep !in 2..5) return
            val st = rtSteps[rtStep - 2]
            val left = urgeEnd - System.currentTimeMillis()
            val tv = urgeTv ?: return
            if (left <= 0L) {
                tv.text = "Selesai"
                urgeHint?.text = "Bagus. Lanjut ke langkah berikutnya."
                urgeBtn?.text = "Lanjut"
                return
            }
            val sec = (left + 999L) / 1000L
            tv.text = String.format(java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)
            if (st.breath) {
                val elapsed = (st.sec.toLong() - sec).coerceAtLeast(0L)
                val ph = elapsed % 14L
                urgeHint?.text = when {
                    ph < 4L -> "Tarik napas pelan..."
                    ph < 8L -> "Tahan sebentar..."
                    else -> "Hembuskan perlahan..."
                }
            }
            handler.postDelayed(this, 500L)
        }
    }

    private fun rtScale(selected: Int, onPick: (Int) -> Unit) {
        for (rowStart in intArrayOf(0, 5)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (v in rowStart..rowStart + 5 - 1 + (if (rowStart == 5) 1 else 0)) {
                row.addView(segPill(v.toString(), v == selected, C_PRIMARY) { onPick(v) })
            }
            put(row)
        }
    }

    private fun rtFinishAndExit() {
        urgeEnd = 0L
        rtStep = 0
        rtTrigger = ""
        goScreen("home")
    }

    private fun renderUrge() {
        handler.removeCallbacks(urgeTick)
        header("Ruang Tenang")
        when (rtStep) {
            0 -> {
                put(text("Tidak apa-apa. Dorongan itu naik lalu turun, seperti ombak. Kamu tidak sendirian dan tidak perlu menyalahkan diri. Kita turunkan pelan-pelan.", 13f))
                put(text("Seberapa kuat dorongannya sekarang? (0 = tidak ada, 10 = sangat kuat)", 13f, true))
                rtScale(rtBefore) { rtBefore = it; render() }
                put(button("Mulai") {
                    Store.addUrge(this)
                    rtStep = 1
                    render()
                })
            }
            1 -> {
                put(text("Biasanya apa yang sedang terjadi? (boleh dilewati)", 13f, true))
                val opts = listOf("Bosan", "Lelah", "Sendirian", "Stres", "Malam hari", "Lainnya")
                for (chunk in opts.chunked(3)) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    for (o in chunk) row.addView(segPill(o, o == rtTrigger, C_PRIMARY) { rtTrigger = o; render() })
                    put(row)
                }
                put(button("Lanjut ke langkah pertama") {
                    rtStep = 2
                    urgeEnd = System.currentTimeMillis() + rtSteps[0].sec * 1000L
                    render()
                })
            }
            in 2..5 -> {
                val st = rtSteps[rtStep - 2]
                put(text("Langkah ${rtStep - 1} dari ${rtSteps.size}: ${st.title}", 15f, true, C_PRIMARY))
                put(text(st.body, 13f))
                val tv = text("", 44f, true, C_PRIMARY).apply { gravity = Gravity.CENTER }
                val hint = text(if (st.breath) "" else "Lakukan sampai waktunya habis.", 16f, false, C_TEXT).apply { gravity = Gravity.CENTER }
                urgeTv = tv
                urgeHint = hint
                put(tv)
                put(hint)
                put(text(Store.motivation(this), 12f, false, C_SUB).apply { gravity = Gravity.CENTER })
                val b = button(if (System.currentTimeMillis() >= urgeEnd) "Lanjut" else "Lewati langkah ini") {
                    if (rtStep >= 5) {
                        rtStep = 6
                        urgeEnd = 0L
                    } else {
                        rtStep += 1
                        urgeEnd = System.currentTimeMillis() + rtSteps[rtStep - 2].sec * 1000L
                    }
                    render()
                }
                urgeBtn = b
                put(b)
                put(smallButton("Sudah tenang, selesai") { rtStep = 6; urgeEnd = 0L; render() })
                urgeTick.run()
            }
            6 -> {
                put(text("Sekarang seberapa kuat dorongannya? (0 = tidak ada, 10 = sangat kuat)", 13f, true))
                rtAfter = rtAfter.coerceAtMost(10)
                rtScale(rtAfter) { rtAfter = it; render() }
                put(button("Lihat hasil") {
                    Store.addRuang(this, rtBefore, rtAfter, rtTrigger)
                    rtStep = 7
                    render()
                })
            }
            else -> {
                val drop = rtBefore - rtAfter
                if (drop > 0) {
                    put(text("Dorongan turun dari $rtBefore ke $rtAfter. Kamu berhasil melewatinya.", 16f, true, C_OK))
                    put(text("Ingat caranya: napas, gerak, pindah tempat. Itu yang berhasil hari ini.", 13f, false, C_SUB))
                } else {
                    put(text("Belum turun, dan itu wajar. Dorongan datang dalam gelombang.", 16f, true, C_PRIMARY))
                    put(text("Ulangi satu putaran lagi, dan pastikan benar-benar pindah tempat serta menjauhkan HP.", 13f, false, C_SUB))
                    put(button("Ulangi langkah") {
                        rtBefore = rtAfter
                        rtStep = 2
                        urgeEnd = System.currentTimeMillis() + rtSteps[0].sec * 1000L
                        render()
                    })
                }
                put(button("Selesai") { rtFinishAndExit() })
            }
        }
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
        roundClip(icon, 11)
        top.addView(icon)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(text(a.label, 14f, true))
        col.addView(text(a.pkg, 10f, false, C_SUB))
        top.addView(col)
        top.addView(luxSwitch(on, false) {
            if (!on) {
                Store.addPauseApp(this@MainActivity, a.pkg)
            } else if (strict) {
                toast("Mode Ketat aktif: tidak bisa melepas jeda")
            } else {
                Store.removePauseApp(this@MainActivity, a.pkg)
            }
            render()
        })
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

    private var appsExpanded = false

    private fun shortSummary(c: AppCfg): String {
        val parts = ArrayList<String>()
        if (c.mode == 0) parts.add(if (c.schOn) "Jadwal ${fmt(c.start)} sampai ${fmt(c.end)}" else "Blokir penuh")
        if (c.limitMin > 0) parts.add("Batas harian ${c.limitMin} menit")
        if (c.twUse > 0 && c.twWait > 0) parts.add("Timer tunggu, pakai ${c.twUse} mnt, tunggu ${c.twWait} mnt")
        if (c.launchDay > 0) parts.add("${c.launchDay} buka per hari")
        if (c.blockUntil > System.currentTimeMillis()) parts.add("Blok cepat aktif")
        return if (parts.isEmpty()) "Belum ada kondisi" else parts.take(2).joinToString(" · ")
    }

    private fun renderApps(strict: Boolean) {
        val chosen = Store.apps(this)
        val all = loadApps()
        val chosenRows = all.filter { it.pkg in chosen }

        run {
            val lockedN = if (strict) chosenRows.count { Store.isLocked(this, it.pkg) } else 0
            val timerN = chosenRows.count { val c = Store.appCfg(this, it.pkg); c.wait > 0 || (c.twUse > 0 && c.twWait > 0) }
            cur = null
            val tiles = LinearLayout(this)
            tiles.orientation = LinearLayout.HORIZONTAL
            tiles.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            tiles.addView(statTile(chosenRows.size.toString(), "Dijaga", C_TEXT, true, false, "apps"))
            tiles.addView(statTile(timerN.toString(), "Timer tunggu", C_PRIMARY, false, false, "apps"))
            tiles.addView(statTile(lockedN.toString(), "Terkunci", C_TEXT, false, true, "apps"))
            root.addView(tiles)
        }

        cur = null
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.BOTTOM
        head.setPadding(dp(4), dp(10), dp(4), dp(2))
        head.addView(text("DIBLOKIR", 11f, true, Color.parseColor("#7C7768")).apply { letterSpacing = 0.18f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (strict) head.addView(text("Mode Ketat mengunci aturan", 12f, false, Color.parseColor("#8A8576")))
        root.addView(head)

        val listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        listBox.setPadding(dp(4), 0, dp(4), 0)
        if (chosenRows.isEmpty()) {
            listBox.addView(text("Belum ada aplikasi diblokir. Cari aplikasi di bawah, lalu ketuk untuk memilih metode blokirnya.", 13f, false, C_SUB).apply {
                setPadding(0, dp(10), 0, dp(10))
            })
        }
        val limit = if (appsExpanded) chosenRows.size else 5
        for (a in chosenRows.take(limit)) listBox.addView(appRow(a, true, strict))
        root.addView(listBox)
        if (chosenRows.size > 5) {
            root.addView(text(if (appsExpanded) "Tampilkan lebih sedikit" else "Lihat semua ${chosenRows.size} aplikasi", 12.5f, true, C_PRIMARY).apply {
                setPadding(dp(4), dp(10), dp(4), dp(6))
                setOnClickListener { appsExpanded = !appsExpanded; render() }
            })
        }

        root.addView(text("TAMBAH APLIKASI", 11f, true, Color.parseColor("#7C7768")).apply {
            letterSpacing = 0.18f
            setPadding(dp(4), dp(18), dp(4), dp(8))
        })
        val searchBox = LinearLayout(this)
        searchBox.orientation = LinearLayout.HORIZONTAL
        searchBox.gravity = Gravity.CENTER_VERTICAL
        searchBox.setPadding(dp(14), 0, dp(14), 0)
        searchBox.background = bg(Color.parseColor("#10131A"), dp(14)).apply { setStroke(dp(1), C_CHIP) }
        searchBox.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { bottomMargin = dp(6) }
        searchBox.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(Color.parseColor("#7C7768"))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        val q = EditText(this)
        q.hint = "Cari aplikasi untuk ditambahkan"
        q.setHintTextColor(Color.parseColor("#7C7768"))
        q.setTextColor(C_TEXT)
        q.textSize = 14f
        q.setSingleLine()
        q.background = null
        q.setPadding(dp(10), 0, 0, 0)
        q.setText(appQuery)
        searchBox.addView(q, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(searchBox)

        val results = LinearLayout(this)
        results.orientation = LinearLayout.VERTICAL
        results.setPadding(dp(4), 0, dp(4), 0)
        root.addView(results)
        val others = all.filter { it.pkg !in chosen }
        fun fill() {
            results.removeAllViews()
            val shown = if (appQuery.isEmpty()) others
            else others.filter { it.label.lowercase().contains(appQuery) || it.pkg.contains(appQuery) }
            if (shown.isEmpty()) {
                results.addView(text("Tidak ada aplikasi yang cocok.", 12.5f, false, C_SUB).apply { setPadding(0, dp(8), 0, dp(8)) })
            } else {
                for (a in shown.take(15)) results.addView(appRow(a, false, strict))
                if (shown.size > 15) results.addView(text("Menampilkan 15 dari ${shown.size}. Ketik nama aplikasi untuk menemukan yang lain.", 11.5f, false, C_SUB).apply {
                    setPadding(0, dp(8), 0, dp(8))
                })
            }
        }
        fill()
        q.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = (s?.toString() ?: "").trim().lowercase()
                if (v != appQuery) {
                    appQuery = v
                    fill()
                }
            }
        })
    }

    private fun appRow(a: AppRow, on: Boolean, strict: Boolean): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(11), 0, dp(11))
        try {
            val tv = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) row.foreground = getDrawable(tv.resourceId)
        } catch (t: Throwable) {
            // tanpa efek sentuh
        }
        val icon = ImageView(this)
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(a.pkg))
        } catch (e: Exception) {
            // tanpa ikon
        }
        icon.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        roundClip(icon, 12)
        row.addView(icon)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(text(a.label, 15f, false, C_TEXT))
        if (on) {
            val cfg = Store.appCfg(this, a.pkg)
            col.addView(text(shortSummary(cfg), 12f, false, Color.parseColor("#8A8576")))
            col.addView(text(usageLine(a.pkg, cfg), 11f, false, C_SUB))
        } else {
            col.addView(text("Ketuk untuk memilih metode blokir", 12f, false, Color.parseColor("#8A8576")))
        }
        row.addView(col)
        if (on) {
            if (strict && Store.isLocked(this, a.pkg)) {
                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_lock)
                    setColorFilter(Color.parseColor("#7C7768"))
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                })
            } else {
                row.addView(text("›", 20f, true, C_SUB))
            }
        } else {
            row.addView(text("Tambah", 12.5f, true, C_PRIMARY))
        }
        row.setOnClickListener { editCfg(a.pkg, a.label, strict, !on) }
        wrap.addView(row)
        wrap.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#1B1F28"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })
        return wrap
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
        e.setTextColor(C_TEXT)
        e.setSelectAllOnFocus(true)
        e.background = bg(Color.parseColor("#10131A"), dp(12)).apply { setStroke(dp(1), C_CHIP) }
        e.setPadding(dp(14), dp(10), dp(14), dp(10))
        e.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(6) }
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

    private fun conditionSection(box: LinearLayout, title: String, on: Boolean, body: LinearLayout, sub: String = ""): CheckBox {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        // CheckBox tersembunyi tetap menyimpan status; tampilannya berupa lingkaran emas.
        val cb = CheckBox(this)
        cb.isChecked = on
        val dot = View(this)
        val dotWrap = android.widget.FrameLayout(this)
        dotWrap.layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        dotWrap.addView(dot, android.widget.FrameLayout.LayoutParams(dp(22), dp(22)))
        fun paint(checked: Boolean) {
            card.background = (if (checked) GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#161B25"), Color.parseColor("#0E1117"))
            ) else bg(Color.parseColor("#10131A"), 0)).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), if (checked) Color.argb(170, 212, 175, 106) else Color.parseColor("#232834"))
            }
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (checked) {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), C_PRIMARY)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), Color.parseColor("#4A4F5B"))
                }
            }
            dotWrap.removeAllViews()
            dotWrap.addView(dot, android.widget.FrameLayout.LayoutParams(dp(22), dp(22)))
            if (checked) dotWrap.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_PRIMARY) }
            }, android.widget.FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
        }
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(dotWrap)
        val tcol = LinearLayout(this)
        tcol.orientation = LinearLayout.VERTICAL
        tcol.setPadding(dp(12), 0, 0, 0)
        tcol.addView(text(title, 14.5f, true, C_TEXT).apply { typeface = Typeface.DEFAULT_BOLD })
        if (sub.isNotEmpty()) tcol.addView(text(sub, 12f, false, C_SUB))
        head.addView(tcol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.visibility = if (on) View.VISIBLE else View.GONE
        paint(on)
        cb.setOnCheckedChangeListener { _, checked ->
            body.visibility = if (checked) View.VISIBLE else View.GONE
            paint(checked)
        }
        head.setOnClickListener { cb.isChecked = !cb.isChecked }
        card.addView(head)
        body.setPadding(0, dp(10), 0, 0)
        card.addView(body)
        box.addView(card)
        return cb
    }

    /** Kotak angka besar bergaya serif untuk timer (mis. 10 menit). */
    private fun bigNumber(label: String, unit: String, value: Int, lp: LinearLayout.LayoutParams): Pair<View, EditText> {
        val tile = LinearLayout(this)
        tile.orientation = LinearLayout.VERTICAL
        tile.setPadding(dp(12), dp(10), dp(12), dp(10))
        tile.background = bg(Color.parseColor("#10131A"), dp(14)).apply { setStroke(dp(1), C_CHIP) }
        tile.layoutParams = lp
        tile.addView(text(label, 11f, false, Color.parseColor("#8A8576")))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.BOTTOM
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(value.toString())
        e.setSelectAllOnFocus(true)
        e.textSize = 28f
        e.typeface = serifBold()
        e.setTextColor(C_PRIMARY)
        e.background = null
        e.setPadding(0, 0, dp(6), 0)
        e.minWidth = dp(44)
        row.addView(e, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(text(unit, 12f, false, C_SUB).apply { setPadding(0, 0, 0, dp(8)) })
        tile.addView(row)
        return Pair(tile, e)
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
        luxPicker("BLOK CEPAT", label, opts.map { "$it menit berikutnya" }) { i ->
            Store.extendBlockUntil(this, pkg, opts[i])
            Store.log(this, "Blok cepat ${opts[i]} menit: $label")
            render()
        }
    }

    private fun editCfg(pkg: String, label: String, strict: Boolean, isNew: Boolean = false) {
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
        box.setPadding(dp(22), dp(6), dp(22), dp(16))
        box.addView(text("KONDISI BLOKIR", 11f, true, Color.parseColor("#7C7768")).apply { letterSpacing = 0.18f; setPadding(0, dp(4), 0, dp(2)) })
        box.addView(text(
            if (isNew) "Pilih minimal satu kondisi. Aplikasi baru diblokir setelah kamu menekan Simpan; kalau dibatalkan, aplikasi tidak diblokir."
            else "Beberapa kondisi boleh dipakai bersamaan.", 12.5f, false, C_SUB))

        // 1. Blokir penuh (selalu atau interval waktu)
        val fullBody = vbox()
        var interval = cfg.schOn
        val timesRow = timeRow("Mulai", fmt(start), {
            TimePickerDialog(this, { _, h, mi ->
                start = h * 60 + mi
                ((timeTv(fullBody, 0)) as TextView).text = fmt(start)
            }, start / 60, start % 60, true).show()
        }, "Selesai", fmt(end), {
            TimePickerDialog(this, { _, h, mi ->
                end = h * 60 + mi
                ((timeTv(fullBody, 1)) as TextView).text = fmt(end)
            }, end / 60, end % 60, true).show()
        })
        fullBody.tag = timesRow.tag
        timesRow.visibility = if (interval) View.VISIBLE else View.GONE
        fullBody.addView(segmented(listOf("Selalu", "Interval waktu"), if (interval) 1 else 0) { i ->
            interval = i == 1
            timesRow.visibility = if (interval) View.VISIBLE else View.GONE
        })
        fullBody.addView(timesRow)
        val fullCb = conditionSection(box, "Blokir penuh", cfg.mode == 0 && !isNew, fullBody, "Selalu atau pada interval waktu tertentu")

        // 2. Batas penggunaan
        val useBody = vbox()
        val useRow = LinearLayout(this)
        useRow.orientation = LinearLayout.HORIZONTAL
        val (tileDay, limit) = bigNumber("Per hari", "menit", cfg.limitMin,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
        val (tileHour, hourLim) = bigNumber("Per jam", "menit", cfg.hourLimit,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        useRow.addView(tileDay)
        useRow.addView(tileHour)
        useBody.addView(useRow)
        useBody.addView(infoNote("Per jam dihitung dari 60 menit terakhir. Isi 0 untuk mematikan salah satunya."))
        val useCb = conditionSection(box, "Batas penggunaan", cfg.limitMin > 0 || cfg.hourLimit > 0, useBody, "Maksimal menit per hari atau per jam")

        // 3. Jumlah peluncuran
        val launchBody = vbox()
        val launchRow = LinearLayout(this)
        launchRow.orientation = LinearLayout.HORIZONTAL
        val (tileLD, launchD) = bigNumber("Per hari", "kali", cfg.launchDay,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
        val (tileLH, launchH) = bigNumber("Per jam", "kali", cfg.launchHour,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        launchRow.addView(tileLD)
        launchRow.addView(tileLH)
        launchBody.addView(launchRow)
        val launchCb = conditionSection(box, "Jumlah peluncuran", cfg.launchDay > 0 || cfg.launchHour > 0, launchBody, "Batas berapa kali aplikasi dibuka")

        // 4. Timer tunggu
        val twBody = vbox()
        val twRow = LinearLayout(this)
        twRow.orientation = LinearLayout.HORIZONTAL
        val (tileUse, twUse) = bigNumber("Boleh dipakai", "menit", cfg.twUse,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
        val (tileWait, twWait) = bigNumber("Lalu tunggu", "menit", cfg.twWait,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        twRow.addView(tileUse)
        twRow.addView(tileWait)
        twBody.addView(twRow)
        val (twAdView, twAdState) = toggleRow("Timer adaptif", "Makin sering dicoba, makin lama menunggu; tidak mencoba separuh masa tunggu membuat tunggu selesai lebih cepat", cfg.twAdaptive, false)
        twBody.addView(twAdView)
        val twCb = conditionSection(box, "Timer tunggu", cfg.twUse > 0 && cfg.twWait > 0, twBody, "Pakai sebentar, lalu tunggu")

        // 5. Notifikasi
        val (notifView, notifState) = toggleRow("Sembunyikan notifikasinya", "Notifikasi aplikasi ini dihapus saat diblokir (butuh Akses Notifikasi)", cfg.notif)
        box.addView(notifView)
        val locUi = locationSection(box, "app_$pkg", false)

        // Penghalang
        box.addView(text("PENGHALANG SAAT TERBLOKIR", 11f, true, Color.parseColor("#7C7768")).apply { letterSpacing = 0.18f; setPadding(0, dp(18), 0, dp(2)) })
        box.addView(text("Berlaku untuk blokir penuh dan batas harian.", 12.5f, false, C_SUB))
        val barRow = LinearLayout(this)
        barRow.orientation = LinearLayout.HORIZONTAL
        barRow.setPadding(0, dp(8), 0, 0)
        val (tileW, wait) = bigNumber("Tunggu sebelum bisa buka", "detik", cfg.wait,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
        val (tileC, cool) = bigNumber("Cooldown setelah pass", "menit", cfg.cooldown,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        barRow.addView(tileW)
        barRow.addView(tileC)
        box.addView(barRow)
        box.addView(infoNote("Isi 0 detik agar aplikasi tidak bisa dibuka sama sekali. Isi 0 menit cooldown untuk tanpa jeda setelah pass habis."))
        box.addView(text("Lama aplikasi terbuka setelah menunggu (pass)", 12f, false, C_SUB).apply { setPadding(0, dp(6), 0, 0) })
        var passSel = passOptions.indexOf(cfg.pass).let { if (it < 0) 0 else it }
        box.addView(chipChoices(passOptions, passSel, "mnt") { i -> passSel = i })

        box.addView(text("BERLAKU PADA HARI", 11f, true, Color.parseColor("#7C7768")).apply { letterSpacing = 0.18f; setPadding(0, dp(18), 0, dp(2)) })
        val (daysView, daysMask) = dayChips(cfg.days)
        box.addView(daysView)

        val doSave: () -> Boolean = save@{
                val mask = daysMask()
                fun num(e: EditText, max: Int): Int = (e.text.toString().toIntOrNull() ?: 0).coerceIn(0, max)
                val full = fullCb.isChecked
                val newCfg = cfg.copy(
                    wait = num(wait, 600),
                    pass = passOptions[passSel],
                    cooldown = num(cool, 1440),
                    mode = if (full) 0 else 1,
                    schOn = full && interval,
                    start = start,
                    end = end,
                    days = mask,
                    limitMin = if (useCb.isChecked) num(limit, 1440) else 0,
                    hourLimit = if (useCb.isChecked) num(hourLim, 60) else 0,
                    launchDay = if (launchCb.isChecked) num(launchD, 1000) else 0,
                    launchHour = if (launchCb.isChecked) num(launchH, 100) else 0,
                    twUse = if (twCb.isChecked) num(twUse, 1440) else 0,
                    twWait = if (twCb.isChecked) num(twWait, 1440) else 0,
                    twAdaptive = twAdState(),
                    notif = notifState()
                )
                val locRule = if (locUi.cb.isChecked) locUi.read() else null
                if (locUi.cb.isChecked && locRule == null) {
                    toast("Lokasi tidak valid: isi lintang dan bujur dengan benar (atau pakai lokasi saya sekarang).")
                    return@save false
                }
                if (isNew && !full && !useCb.isChecked && !launchCb.isChecked && !twCb.isChecked && locRule == null) {
                    toast("Pilih minimal satu kondisi dulu, atau tekan Batal.")
                    return@save false
                }
                if (isNew) Store.addApp(this, pkg)
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
                true
            }
        val extras = ArrayList<Pair<String, (android.app.Dialog) -> Unit>>()
        if (!isNew) {
            extras.add("Blok cepat" to { d: android.app.Dialog -> d.dismiss(); quickBlock(pkg, label) })
            extras.add("Lepas blokir" to { d: android.app.Dialog ->
                if (strict && Store.isLocked(this, pkg)) {
                    toast("Mode Ketat aktif: aplikasi ini sudah terkunci, tidak bisa melepas blokir")
                } else {
                    d.dismiss()
                    gateOff(true, "melepas blokir aplikasi $label") { Store.removeApp(this, pkg) }
                }
            })
        }
        luxScreenDialog(pkg, "ATURAN APLIKASI", label, box, if (isNew) "Blokir aplikasi ini" else "Simpan aturan", extras, strict, doSave, { render() })
    }

    /** Layar penuh bergaya Lux untuk mengatur satu aplikasi: header, isi yang bisa digulir, tombol emas di bawah. */
    private fun luxScreenDialog(pkg: String, eyebrowText: String, title: String, content: View, saveLabel: String,
                                extras: List<Pair<String, (android.app.Dialog) -> Unit>>, strictOn: Boolean,
                                onSave: () -> Boolean, onCancel: () -> Unit,
                                backLabel: String = "Aplikasi", iconRes: Int = 0): android.app.Dialog {
        val dlg = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val rootV = LinearLayout(this)
        rootV.orientation = LinearLayout.VERTICAL
        rootV.setBackgroundColor(C_BG)
        rootV.setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }
        val back = LinearLayout(rootV.context)
        back.orientation = LinearLayout.HORIZONTAL
        back.gravity = Gravity.CENTER_VERTICAL
        back.setPadding(dp(18), dp(18), dp(22), dp(4))
        back.addView(text("‹  $backLabel", 14f, false, C_SUB))
        back.setOnClickListener { dlg.dismiss(); onCancel() }
        rootV.addView(back)
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(22), dp(8), dp(22), dp(8))
        val icon = ImageView(this)
        if (iconRes != 0) {
            icon.setImageResource(iconRes)
            icon.setColorFilter(C_PRIMARY)
            icon.setPadding(dp(13), dp(13), dp(13), dp(13))
            icon.background = bg(Color.parseColor("#141821"), dp(15)).apply { setStroke(dp(1), Color.argb(100, 212, 175, 106)) }
        } else {
            try { icon.setImageDrawable(packageManager.getApplicationIcon(pkg)) } catch (e: Exception) { }
            roundClip(icon, 15)
        }
        icon.layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        head.addView(icon)
        val hc = LinearLayout(this)
        hc.orientation = LinearLayout.VERTICAL
        hc.setPadding(dp(14), 0, 0, 0)
        hc.addView(text(eyebrowText, 11f, true, C_PRIMARY).apply { letterSpacing = 0.22f })
        hc.addView(text(title, 30f, true, C_TEXT).apply { typeface = serifBold(); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        head.addView(hc, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rootV.addView(head)
        if (strictOn) {
            rootV.addView(text("Mode Ketat aktif. Aturan hanya boleh diperketat, tidak dilonggarkan.", 12.5f, false, Color.parseColor("#E6D6B0")).apply {
                background = bg(Color.argb(20, 212, 175, 106), dp(16)).apply { setStroke(dp(1), Color.argb(90, 212, 175, 106)) }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(22); rightMargin = dp(22); topMargin = dp(10) }
            })
        }
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(content)
        rootV.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setPadding(dp(22), dp(8), dp(22), dp(20))
        val save = TextView(this)
        save.text = saveLabel
        save.textSize = 15f
        save.setTypeface(null, Typeface.BOLD)
        save.setTextColor(C_ON_GOLD)
        save.gravity = Gravity.CENTER
        save.setPadding(dp(8), dp(16), dp(8), dp(16))
        save.background = goldBg(dp(18))
        save.setOnClickListener { if (onSave()) dlg.dismiss() }
        bottom.addView(save)
        if (extras.isNotEmpty()) {
            val er = LinearLayout(this)
            er.orientation = LinearLayout.HORIZONTAL
            for ((i, ex) in extras.withIndex()) {
                val t = TextView(this)
                t.text = ex.first
                t.textSize = 13f
                t.setTextColor(C_TEXT)
                t.gravity = Gravity.CENTER
                t.setPadding(dp(8), dp(12), dp(8), dp(12))
                t.background = bg(Color.parseColor("#10131A"), dp(16)).apply { setStroke(dp(1), Color.parseColor("#2A2F3B")) }
                t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    topMargin = dp(10); leftMargin = if (i == 0) 0 else dp(5); rightMargin = if (i == extras.size - 1) 0 else dp(5)
                }
                t.setOnClickListener { ex.second(dlg) }
                er.addView(t)
            }
            bottom.addView(er)
        }
        rootV.addView(bottom)
        dlg.setContentView(rootV)
        dlg.setOnCancelListener { onCancel() }
        dlg.setOnShowListener {
            try {
                dlg.window?.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
                dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C_BG))
                dlg.window?.statusBarColor = C_BG
                dlg.window?.navigationBarColor = C_BG
                dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            } catch (t: Throwable) {
                // tampilan bawaan
            }
        }
        dlg.show()
        return dlg
    }

    private fun editSchedule(onDone: (Sched) -> Unit) {
        var start = 21 * 60
        var end = 5 * 60
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val timesRow = timeRow("Mulai", fmt(start), {
            TimePickerDialog(this, { _, h, mi -> start = h * 60 + mi; (timeTv(box, 0) as TextView).text = fmt(start) }, start / 60, start % 60, true).show()
        }, "Selesai", fmt(end), {
            TimePickerDialog(this, { _, h, mi -> end = h * 60 + mi; (timeTv(box, 1) as TextView).text = fmt(end) }, end / 60, end % 60, true).show()
        })
        box.tag = timesRow.tag
        box.addView(timesRow)
        box.addView(text("BERLAKU PADA HARI", 11f, true, Color.parseColor("#7C7768")).apply { letterSpacing = 0.18f; setPadding(0, dp(10), 0, 0) })
        val (daysView, daysMask) = dayChips(127)
        box.addView(daysView)
        box.addView(infoNote("Jika jam selesai lebih awal dari jam mulai, jadwal berlanjut sampai esok hari."))
        val ld = luxDialog("JADWAL", "Tambah jadwal", "", box, "Tambah", "Batal") { }
        ld.pos.setOnClickListener {
            ld.dlg.dismiss()
            onDone(Sched(start, end, daysMask()))
        }
        ld.dlg.show()
    }

    private fun pickApps(chosen: MutableSet<String>, onDone: () -> Unit) {
        val rows = loadApps()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(6), dp(22), dp(16))
        val counter = text("", 12.5f, true, C_PRIMARY)
        box.addView(counter)
        val search = luxField("Cari aplikasi")
        box.addView(search)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        box.addView(list)
        fun paint() {
            counter.text = "${chosen.size} aplikasi dipilih"
            list.removeAllViews()
            val q = search.text.toString().trim().lowercase()
            for (r in rows) {
                if (q.isNotEmpty() && !r.label.lowercase().contains(q)) continue
                val on = r.pkg in chosen
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(0, dp(10), 0, dp(10))
                val ic = ImageView(this)
                try { ic.setImageDrawable(packageManager.getApplicationIcon(r.pkg)) } catch (e: Exception) { }
                ic.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                roundClip(ic, 11)
                row.addView(ic)
                row.addView(text(r.label, 14.5f, on, if (on) C_TEXT else Color.parseColor("#CFC9BC")).apply {
                    setPadding(dp(14), 0, dp(8), 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(ImageView(this).apply {
                    setImageResource(if (on) R.drawable.lux_check_on else R.drawable.lux_check_off)
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                })
                row.setOnClickListener {
                    if (on) chosen.remove(r.pkg) else chosen.add(r.pkg)
                    paint()
                }
                list.addView(row)
                list.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#1B1F28"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                })
            }
            if (list.childCount == 0) list.addView(text("Tidak ada aplikasi yang cocok.", 13f, false, C_SUB).apply { setPadding(0, dp(16), 0, 0) })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { paint() }
        })
        paint()
        luxScreenDialog("", "PILIH", "Aplikasi", box, "Selesai", emptyList(), false, { onDone(); true }, { onDone() }, "Kembali", R.drawable.ic_ly_app)
    }

    private fun scheduleTileLabel(): String {
        val n = ScheduleNotifier.activeNow(this).size
        return if (n > 0) "$n jadwal aktif" else "Atur per hari & jam"
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val et = luxField("Nama")
        et.setText(initial)
        val ld = luxDialog("NAMA", title, "", et, "Simpan", "Batal") { }
        ld.pos.setOnClickListener {
            val v = et.text.toString().trim()
            if (v.isNotEmpty()) {
                ld.dlg.dismiss()
                onOk(v)
            }
        }
        ld.dlg.show()
    }

    private fun renderSchedule(strict: Boolean) {
        val profiles = ProfileStore.load(this)
        header("Sekarang")
        val act = ScheduleNotifier.activeNow(this)
        put(text(if (act.isEmpty()) "○  Tidak ada profil berjadwal yang berjalan" else "●  Sedang berjalan", 13f, true, if (act.isEmpty()) C_SUB else C_OK))
        for (p in act) put(text(p.name, 22f, true, C_TEXT).apply { typeface = serifBold() })
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
        val names = arrayOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
        val todayIdx = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        for (d in 0 until 7) {
            val rows = ArrayList<Pair<Int, String>>()
            for (p in profiles) {
                if (!p.enabled) continue
                for (sc in p.schedules) {
                    if ((sc.days and (1 shl d)) == 0) continue
                    val over = sc.start > sc.end
                    rows.add(Pair(sc.start, fmt(sc.start) + "–" + fmt(sc.end) + (if (over) " (s/d esok)" else "") + " · " + p.name))
                }
            }
            rows.sortBy { it.first }
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.setPadding(0, dp(6), 0, dp(6))
            line.addView(text(names[d], 13f, true, if (d == todayIdx) C_PRIMARY else C_SUB).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val right = LinearLayout(this)
            right.orientation = LinearLayout.VERTICAL
            right.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (rows.isEmpty()) right.addView(text("—", 12f, false, C_SUB))
            for (r in rows) right.addView(chip(r.second, Color.parseColor("#5FC9A0")).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            })
            line.addView(right)
            put(line)
        }

        header("Profil")
        for (p in profiles) {
            val sch = if (p.schedules.isEmpty()) "tanpa jadwal (aktif terus bila dinyalakan)" else
                p.schedules.joinToString("; ") { "${fmt(it.start)}-${fmt(it.end)} ${dayNames(it.days)}" }
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(14), dp(12), dp(14), dp(12))
            card.background = bg(C_FIELD, dp(16)).apply { setStroke(dp(1), C_CHIP) }
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            card.addView(text(p.name + (if (p.enabled) "" else " (mati)"), 15f, true))
            card.addView(text(sch, 12f, false, C_SUB))
            val acts = LinearLayout(this)
            acts.orientation = LinearLayout.HORIZONTAL
            acts.setPadding(0, dp(8), 0, 0)
            fun actBtn(label: String, onClick: () -> Unit) = smallButton(label, onClick).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
            }
            acts.addView(actBtn("Atur") { editProfile(p, strict) })
            acts.addView(actBtn("Nama") {
                if (strict) toast("Mode Ketat aktif: tidak bisa mengubah profil") else
                    askName("Nama profil", p.name) { ProfileStore.rename(this, p.id, it); render() }
            })
            acts.addView(actBtn("Hapus") {
                if (strict) toast("Mode Ketat aktif: profil tidak bisa dihapus")
                else luxConfirm("PROFIL", "Hapus " + p.name + "?", "Profil dan jadwalnya dihapus.", "Hapus") {
                    if (!ProfileStore.remove(this, p.id)) toast("Minimal harus ada satu profil")
                    render()
                }
            })
            card.addView(acts)
            put(card)
        }
        put(button("Tambah profil baru") {
            askName("Nama profil baru", "") {
                val np = ProfileStore.add(this, it)
                render()
                editProfile(np, strict)
            }
        })

        header("Pemberitahuan")
        put(luxRow(0, "Beri tahu saat profil mulai dan selesai", "Hanya pemberitahuan", ScheduleNotifier.enabled(this), false, true) {
            val v = !ScheduleNotifier.enabled(this)
            if (v && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1004)
            ScheduleNotifier.setEnabled(this, v)
            render()
        })
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
        box.setPadding(dp(22), dp(6), dp(22), dp(16))
        if (strict) box.addView(infoNote("Mode Ketat aktif: perubahan hanya menambah, tidak mengurangi."))

        box.addView(text("BATAS HARIAN", 11f, true, Color.parseColor("#7C7768")).apply { letterSpacing = 0.18f; setPadding(0, dp(8), 0, dp(6)) })
        val (limTile, limit) = bigNumber("Per aplikasi", "menit", p.limitMin,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(limTile)
        box.addView(infoNote("Isi 0 untuk blokir penuh semua aplikasi di profil ini."))

        box.addView(text("ISI PROFIL", 11f, true, Color.parseColor("#7C7768")).apply { letterSpacing = 0.18f; setPadding(0, dp(14), 0, 0) })
        val appsHolder = LinearLayout(this)
        appsHolder.orientation = LinearLayout.VERTICAL
        fun paintApps() {
            appsHolder.removeAllViews()
            appsHolder.addView(rowButton("Aplikasi", "${apps.size} dipilih") {
                pickApps(apps) { paintApps() }
            })
        }
        paintApps()
        box.addView(appsHolder)
        box.addView(text("Kata kunci (pisahkan dengan koma)", 12f, false, C_SUB).apply { setPadding(0, dp(8), 0, 0) })
        val kw = luxField("mis. judi, slot")
        kw.setText(p.keywords.sorted().joinToString(", "))
        box.addView(kw)
        box.addView(text("Situs (pisahkan dengan koma)", 12f, false, C_SUB).apply { setPadding(0, dp(10), 0, 0) })
        val dm = luxField("mis. contoh.com, situs.net")
        dm.setText(p.domains.sorted().joinToString(", "))
        box.addView(dm)

        box.addView(text("JADWAL", 11f, true, Color.parseColor("#7C7768")).apply { letterSpacing = 0.18f; setPadding(0, dp(16), 0, dp(2)) })
        box.addView(text("Boleh lebih dari satu.", 12.5f, false, C_SUB))
        val schedBox = LinearLayout(this)
        schedBox.orientation = LinearLayout.VERTICAL
        box.addView(schedBox)
        fun refreshScheds() {
            schedBox.removeAllViews()
            if (scheds.isEmpty()) schedBox.addView(text("Tanpa jadwal: aktif terus bila profil dinyalakan.", 12.5f, false, C_SUB).apply { setPadding(0, dp(6), 0, dp(6)) })
            for (i in scheds.indices) {
                val sc = scheds[i]
                schedBox.addView(listRow("${fmt(sc.start)} - ${fmt(sc.end)}", dayNames(sc.days), C_TEXT, null) {
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
        box.addView(ghostButton("Tambah jadwal") {
            editSchedule { sc ->
                scheds.add(sc)
                refreshScheds()
            }
        })

        val doSave: () -> Boolean = save@{
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
            if (!np.enabled && (np.apps.isNotEmpty() || np.keywords.isNotEmpty() || np.domains.isNotEmpty())) {
                np = np.copy(enabled = true)
                toast("Profil ${np.name} diaktifkan")
            }
            ProfileStore.replace(this, np)
            render()
            true
        }
        luxScreenDialog("", "PROFIL", p.name, box, "Simpan profil", emptyList(), false, doSave, { render() }, "Jadwal", R.drawable.ic_ly_calendar)
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
            for (a in shown.take(15)) col.addView(protectedRow(a, false, strict))
            put(scrollBox(360, col))
            if (shown.size > 15) put(text("Menampilkan 15 dari ${shown.size}. Ketik nama aplikasi lalu tekan Cari untuk menemukan yang lain.", 11f, false, C_SUB))
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
        roundClip(icon, 11)
        top.addView(icon)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(text(a.label, 14f, true))
        col.addView(text(a.pkg, 10f, false, C_SUB))
        top.addView(col)
        top.addView(luxSwitch(on, false) {
            val checked = !on
            if (checked) {
                Store.addProtected(this@MainActivity, a.pkg)
            } else if (strict || a.pkg in Config.SEED_PROTECTED) {
                toast(if (strict) "Mode Ketat aktif: tidak bisa melepas perlindungan" else "Aplikasi ini selalu dilindungi")
            } else {
                Store.removeProtected(this@MainActivity, a.pkg)
            }
            render()
        })
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
        val et = luxField(if (domain) "satu situs per baris, mis. contoh.com" else "satu per baris").apply {
            setSingleLine(false)
            setText(current.sorted().joinToString("\n"))
            minLines = 5
            gravity = Gravity.TOP
        }
        val ld = luxDialog("DAFTAR", title, "Satu item per baris. Koma juga boleh.", et, "Simpan", "Batal") { }
        ld.pos.setOnClickListener {
            val out = HashSet<String>()
            for (line in et.text.toString().split('\n', ',', ';')) {
                val v = if (domain) Rules.normalizeDomain(line) else line.trim()
                if (v.isNotEmpty()) out.add(v)
            }
            ld.dlg.dismiss()
            onSave(out)
        }
        ld.dlg.show()
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
                    luxConfirm("MODE FOKUS", "Hentikan Mode Fokus?", "Aturan permanen tetap berlaku.", "Hentikan") {
                        Focus.stop(this)
                        render()
                    }
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
                val (tile, et) = bigNumber("Durasi", "menit", 45,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                val ld = luxDialog("MODE FOKUS", "Durasi Fokus", "Isi antara 1 dan 1440 menit.", tile, "Mulai", "Batal") { }
                ld.pos.setOnClickListener {
                    val m = (et.text.toString().toIntOrNull() ?: 0).coerceIn(0, 1440)
                    if (m > 0) {
                        ld.dlg.dismiss()
                        startFor(m)
                    } else toast("Isi durasi dalam menit")
                }
                ld.dlg.show()
            })
            put(row)
        }

        val x = Focus.cfg(this)
        header("Diblokir saat Fokus")
        if (locked) put(text("Mode Ketat aktif dan Fokus berjalan: daftar ini terkunci.", 11f, false, C_SUB))
        put(rowButton("Aplikasi", "${x.blockApps.size} dipilih") {
            if (locked) toast("Terkunci") else {
                val chosen = x.blockApps.toMutableSet()
                pickApps(chosen) {
                    Focus.setCfg(this, Focus.Cfg(chosen, x.blockSites, x.blockCats, x.allowApps, x.allowSites))
                    render()
                }
            }
        })
        put(rowButton("Situs", "${x.blockSites.size} dipilih") {
            if (locked) toast("Terkunci") else editListDialog("Situs diblokir saat Fokus", x.blockSites, true) { s ->
                Focus.setCfg(this, Focus.Cfg(x.blockApps, s, x.blockCats, x.allowApps, x.allowSites))
                render()
            }
        })
        put(rowButton("Kategori", "${x.blockCats.size} dipilih") {
            if (locked) toast("Terkunci") else {
                val checked = BooleanArray(Focus.CATS.size) { Focus.CATS[it].key in x.blockCats }
                val list = LinearLayout(this)
                list.orientation = LinearLayout.VERTICAL
                fun paintCats() {
                    list.removeAllViews()
                    for (i in Focus.CATS.indices) {
                        val row = LinearLayout(this)
                        row.orientation = LinearLayout.HORIZONTAL
                        row.gravity = Gravity.CENTER_VERTICAL
                        row.setPadding(dp(6), dp(13), dp(6), dp(13))
                        row.addView(text(Focus.CATS[i].name, 14.5f, checked[i], C_TEXT), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        row.addView(ImageView(this).apply {
                            setImageResource(if (checked[i]) R.drawable.lux_check_on else R.drawable.lux_check_off)
                            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                        })
                        row.setOnClickListener { checked[i] = !checked[i]; paintCats() }
                        list.addView(row)
                        list.addView(View(this).apply {
                            setBackgroundColor(Color.parseColor("#1B1F28"))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        })
                    }
                }
                paintCats()
                val ld = luxDialog("MODE FOKUS", "Kategori diblokir", "", list, "Simpan", "Batal") { }
                ld.pos.setOnClickListener {
                    val cats = HashSet<String>()
                    for (i in Focus.CATS.indices) if (checked[i]) cats.add(Focus.CATS[i].key)
                    Focus.setCfg(this, Focus.Cfg(x.blockApps, x.blockSites, cats, x.allowApps, x.allowSites))
                    ld.dlg.dismiss()
                    render()
                }
                ld.dlg.show()
            }
        })
        put(text("Kategori aplikasi memakai kategori bawaan Android, jadi tidak semua aplikasi terdeteksi. Aplikasi bank, telepon, Pengaturan, dan Penjaga Ketat tidak pernah ikut diblokir Fokus.", 11f, false, C_SUB))

        header("Pengecualian (ALLOW)")
        put(text("Pengecualian hanya membebaskan dari aturan Fokus. Aturan permanen (blokir penuh, timer, jadwal, profil) tetap berlaku.", 12f, false, C_SUB))
        put(rowButton("Aplikasi diizinkan", "${x.allowApps.size} dipilih") {
            if (locked) toast("Terkunci") else {
                val chosen = x.allowApps.toMutableSet()
                pickApps(chosen) {
                    Focus.setCfg(this, Focus.Cfg(x.blockApps, x.blockSites, x.blockCats, chosen, x.allowSites))
                    render()
                }
            }
        })
        put(rowButton("Situs diizinkan", "${x.allowSites.size} dipilih") {
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
        val maxMs = Math.max(1L, rows.maxOfOrNull { it.ms } ?: 1L)
        val sb = StringBuilder()
        for (r in rows) sb.append(barLine(r.label, r.ms, maxMs, if (r.blocks > 0) " • ${r.blocks}x blokir" else "")).append('\n')
        val body = text(sb.toString().trim(), 12f, false, C_TEXT).apply {
            typeface = Typeface.MONOSPACE
            background = bg(Color.parseColor("#0A0C10"), dp(14)).apply { setStroke(dp(1), C_CHIP) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val ld = luxDialog("RIWAYAT PEMAKAIAN", title, "", body, "Tutup", null) { }
        ld.pos.setOnClickListener { ld.dlg.dismiss() }
        ld.dlg.show()
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
        put(chipRow(
            chip("${s.blocksToday}x diblokir", Color.parseColor("#E6C987")),
            chip("${s.attemptsToday}x percobaan", Color.parseColor("#A9A396"))
        ))
        if (s.reasonsToday.isNotEmpty()) {
            put(text(s.reasonsToday.joinToString(", ") { reasonName(it.first) + " " + it.second }, 11f, false, C_SUB))
        }

        header("Penggunaan hari ini")
        if (s.topAppsToday.isEmpty()) put(text("Belum ada data.", 12f, false, C_SUB))
        val maxA = Math.max(1L, s.topAppsToday.maxOfOrNull { it.ms } ?: 1L)
        for (it in s.topAppsToday) put(barRow(it.label, it.ms, maxA, Color.parseColor("#D4AF6A")))
        if (s.topSitesToday.isNotEmpty()) {
            put(text("Situs (yang punya aturan; sudah termasuk dalam waktu Chrome)", 12f, true))
            val maxS = Math.max(1L, s.topSitesToday.maxOf { it.ms })
            for (it in s.topSitesToday) put(barRow(it.label, it.ms, maxS, Color.parseColor("#A9A396")))
        }

        if (s.appCategories.isNotEmpty() || s.siteCategories.isNotEmpty()) {
            header("Kategori hari ini")
            val catMax = Math.max(1L, s.appCategories.take(6).maxOf { it.second })
            for ((n, ms) in s.appCategories.take(6)) put(barRow(n, ms, catMax, Color.parseColor("#5FC9A0")))
            if (s.siteCategories.isNotEmpty()) {
                put(text("Situs:", 12f, true))
                for ((n, ms) in s.siteCategories) put(text("$n — ${Dashboard.dur(ms)}", 12f))
            }
        }

        header("7 hari terakhir")
        put(text("Pemakaian per hari (menit)", 11f, false, C_SUB))
        put(BarChartView(this, s.days.map { it.ms / 60000L }, s.days.map { it.label.substringBefore(' ') }, Color.parseColor("#D4AF6A"), C_TEXT, C_SUB) { "${it}m" }
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

        header("Teratas 7 hari")
        if (s.topAppsWeek.isEmpty()) put(text("Belum ada data.", 12f, false, C_SUB))
        val maxW = Math.max(1L, s.topAppsWeek.maxOfOrNull { it.ms } ?: 1L)
        for (it in s.topAppsWeek) put(barRow(it.label, it.ms, maxW, Color.parseColor("#C99A45")))
        if (s.topSitesWeek.isNotEmpty()) {
            put(text("Situs:", 12f, true))
            val maxSW = Math.max(1L, s.topSitesWeek.maxOf { it.ms })
            for (it in s.topSitesWeek) put(barRow(it.label, it.ms, maxSW, Color.parseColor("#A9A396")))
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
                luxPicker("STATISTIK", "Pilih aplikasi atau situs", entries.map { (if (it.third) "🌐 " else "") + it.second }) { i ->
                    showHistory(entries[i].first, entries[i].second, entries[i].third)
                }
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
        put(CheckBox(this).apply {
            text = "Paksa SafeSearch Google lewat DNS"
            isChecked = Store.safeSearch(this@MainActivity)
            setOnClickListener {
                if (!isChecked && strict) {
                    toast("Mode Ketat aktif: tidak bisa dimatikan")
                    render()
                } else {
                    Store.setSafeSearch(this@MainActivity, isChecked)
                    SafeSearch.on = isChecked
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
            luxConfirm("BACKUP", "Backup tidak valid", parsed.error + "\nKonfigurasi kamu tidak diubah.", "Mengerti") { }
            return
        }
        val strict = Store.strictRaw(this)
        luxConfirm("BACKUP", title, parsed.summary + "\n\n" +
            (if (strict) "Mode Ketat aktif: hanya aturan baru yang ditambahkan." else "Konfigurasi sekarang diganti isi backup. Salinan konfigurasi sekarang disimpan dulu."), "Restore") {
            val msg = try { Backup.apply(this, parsed.root!!) } catch (t: Throwable) { "Restore gagal. Konfigurasi lama tetap ada." }
            toast(msg)
            render()
        }
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
        val old = luxField("Kode lama", true)
        if (FriendApproval.isSet(this)) box.addView(old)
        val n1 = luxField("Kode baru (min 6 karakter)", true)
        box.addView(n1)
        val n2 = luxField("Ulangi kode baru", true)
        box.addView(n2)
        val ld = luxDialog("PERSETUJUAN TEMAN", "Kode teman", "Serahkan HP ke temanmu. Dia yang mengetik kodenya.", box, "Simpan", "Batal") { }
        ld.pos.setOnClickListener {
            if (n1.text.toString() != n2.text.toString()) {
                toast("Kode tidak sama")
            } else {
                val err = FriendApproval.set(this, old.text.toString(), n1.text.toString())
                toast(err ?: "Kode teman tersimpan")
                if (err == null) ld.dlg.dismiss()
            }
            render()
        }
        ld.dlg.show()
    }

    private fun friendClearDialog() {
        val et = luxField("Kode teman", true)
        val ld = luxDialog("PERSETUJUAN TEMAN", "Hapus kode teman", "Temanmu perlu mengetik kodenya.", et, "Hapus", "Batal") { }
        ld.pos.setOnClickListener {
            val err = FriendApproval.clear(this, et.text.toString())
            toast(err ?: "Kode teman dihapus")
            if (err == null) ld.dlg.dismiss()
            render()
        }
        ld.dlg.show()
    }

    private fun renderDetection() {
        header("Deteksi gambar (NSFW)")
        val e = Detection.engine
        status(e.ready, "Mesin deteksi: " + e.name)
        if (!e.ready) {
            put(text("PLACEHOLDER. Antarmuka sudah ada, tetapi belum ada model dan belum ada pengambilan layar yang terhubung, jadi gambar belum diperiksa. Pemblokiran saat ini mengandalkan kata kunci, situs, kategori, dan VPN DNS.", 12f))
        }
    }

    // ---------- Komponen Lux ----------
    private fun chip(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(color)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = bg(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)), dp(10))
            .apply { setStroke(dp(1), Color.argb(90, Color.red(color), Color.green(color), Color.blue(color))) }
    }

    private fun chipRow(vararg chips: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        for (c in chips) addView(c, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) })
    }

    /** Baris dengan batang gradien: nama di kiri, durasi di kanan. */
    private fun barRow(label: String, ms: Long, max: Long, color: Int): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(6), 0, dp(6))
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.addView(text(label, 13f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        top.addView(text(Dashboard.dur(ms), 12f, true, color))
        col.addView(top)
        val track = android.widget.FrameLayout(this)
        track.background = bg(Color.parseColor("#1B1F28"), dp(4))
        track.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(4) }
        val fill = View(this)
        fill.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(120, Color.red(color), Color.green(color), Color.blue(color)), color)
        ).apply { cornerRadius = dp(4).toFloat() }
        fill.layoutParams = android.widget.FrameLayout.LayoutParams(0, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        track.addView(fill)
        track.post {
            val w = (track.width * (ms.toFloat() / max.toFloat())).toInt().coerceAtLeast(dp(4))
            fill.layoutParams = android.widget.FrameLayout.LayoutParams(w, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        }
        col.addView(track)
        return col
    }

    private fun roundClip(v: View, radiusDp: Int) {
        val r = dp(radiusDp).toFloat()
        v.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        v.clipToOutline = true
    }

    private fun luxSwitch(on: Boolean, locked: Boolean, onClick: () -> Unit): View {
        val h = dp(27)
        val lit = on && !locked
        val f = android.widget.FrameLayout(this)
        f.background = if (lit) goldBg(h / 2) else bg(Color.parseColor("#2A2F3B"), h / 2)
        val knob = View(this)
        knob.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (lit) C_ON_GOLD else Color.parseColor("#8A8576"))
        }
        knob.layoutParams = android.widget.FrameLayout.LayoutParams(dp(21), dp(21)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setMargins(dp(3), 0, dp(3), 0)
        }
        val travel = dp(19).toFloat()
        knob.translationX = if (on) travel else 0f
        f.addView(knob)
        f.layoutParams = LinearLayout.LayoutParams(dp(46), h)
        f.setOnClickListener {
            if (locked) {
                onClick()
            } else {
                knob.animate().translationX(if (on) 0f else travel).setDuration(170L)
                    .withEndAction { onClick() }.start()
            }
        }
        return f
    }

    /** Satu baris saklar: ikon garis emas, judul, keterangan, saklar. */
    private fun luxRow(icon: Int, title: String, sub: String, on: Boolean, locked: Boolean, last: Boolean, tint: Int = Color.parseColor("#D4AF6A"), onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(12), 0, dp(12))
        if (icon != 0) {
            val box = android.widget.FrameLayout(this)
            box.background = bg(Color.argb(38, Color.red(tint), Color.green(tint), Color.blue(tint)), dp(12)).apply { setStroke(dp(1), Color.argb(110, Color.red(tint), Color.green(tint), Color.blue(tint))) }
            box.layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(14) }
            box.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(tint)
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            })
            row.addView(box)
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(text(title, 15f, true).apply { setPadding(0, 0, 0, 0) })
        col.addView(text(sub, 12f, false, C_SUB).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(col)
        row.addView(luxSwitch(on, locked, onClick))
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(row)
        if (!last) wrap.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#1B1F28"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })
        return wrap
    }

    private fun eyebrow(s: String) {
        root.addView(text(s, 11f, true, C_SUB).apply {
            letterSpacing = 0.18f
            setPadding(dp(4), dp(14), 0, dp(6))
        })
    }

    private fun toggleFeature(key: String, label: String, strict: Boolean) {
        if (!Store.featureOn(this, key)) {
            Store.setFeatureOn(this, key, true)
            render()
        } else if (strict) {
            toast("Mode Ketat aktif: tidak bisa dimatikan")
        } else {
            textGate("mematikan fitur $label", { Store.setFeatureOn(this, key, false); render() }, { render() })
        }
    }

    private fun toggleNsfwAi(strict: Boolean) {
        val on = Store.nsfwAi(this)
        if (!on) {
            Store.setNsfwAi(this, true)
        } else if (strict) {
            toast("Mode Ketat aktif: tidak bisa dimatikan")
        } else {
            gateOff(true, "mematikan deteksi gambar AI") { Store.setNsfwAi(this, false) }
        }
        render()
    }

    private fun toggleTgWork(strict: Boolean) {
        if (!Store.tgWorkOn(this)) {
            Store.setTgWorkOn(this, true)
            render()
        } else if (strict) {
            toast("Mode Ketat aktif: tidak bisa dimatikan")
        } else {
            textGate("mematikan Mode Telegram Kerja", { Store.setTgWorkOn(this, false); render() }, { render() })
        }
    }

    /** Hari bersih beruntun (tanpa satu pun blokir), sama dengan kartu Hari bersih. */
    private fun cleanStreak(): Int {
        return try {
            val now = System.currentTimeMillis()
            val per = StatsDb.blocksPerDay(this, 35)
            val first = packageManager.getPackageInfo(packageName, 0).firstInstallTime
            val firstKey = StatsDb.dayKey(first)
            var n = 0
            for (i in 0 until 35) {
                val k = StatsDb.dayKey(now - i.toLong() * 86400000L)
                if (k < firstKey) break
                if ((per[k] ?: 0) == 0) n++ else break
            }
            n
        } catch (t: Throwable) {
            0
        }
    }

    private fun renderLayers(strict: Boolean) {
        eyebrow("LAPISAN PELINDUNG")
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(4), 0, dp(4), 0)
        box.addView(luxRow(R.drawable.ic_ly_eye, "Deteksi AI gambar", "Memindai layar secara adaptif",
            Store.nsfwAi(this), strict, false) { toggleNsfwAi(strict) })
        box.addView(luxRow(R.drawable.ic_ly_send, "Telegram Kerja",
            "${Store.tgAllow(this).size} chat boleh · pribadi selalu boleh",
            Store.tgWorkOn(this), strict && Store.tgWorkOn(this), false, Color.parseColor("#C99A45")) { toggleTgWork(strict) })
        box.addView(luxRow(R.drawable.ic_ly_globe, "Situs & kata kunci", "Hanya Chrome · DNS aman",
            Store.featureOn(this, "sites"), strict, false, Color.parseColor("#A9A396")) { toggleFeature("sites", "Situs", strict) })
        box.addView(luxRow(R.drawable.ic_ly_app, "Aplikasi baru", "Diblokir otomatis saat baru dipasang",
            Store.autoBlockNew(this), strict, true, Color.parseColor("#5FC9A0")) {
            if (Store.autoBlockNew(this) && strict) toast("Mode Ketat aktif: tidak bisa dimatikan")
            else { Store.setAutoBlockNew(this, !Store.autoBlockNew(this)); render() }
        })
        root.addView(box)
    }

    private var tgShowAll = false

    private fun renderTgWork(strict: Boolean) {
        val on = Store.tgWorkOn(this)
        val list = Store.tgAllow(this).sorted()
        header("Telegram Kerja")
        put(text("Hanya grup, channel, dan bot di daftar yang boleh dibuka. Chat pribadi selalu boleh. Cocok jika nama chat mengandung kata yang kamu daftarkan.", 12f, false, C_SUB))
        put(luxRow(0, "Mode Telegram Kerja", "Chat di luar daftar langsung ditutup", on, strict && on, true) { toggleTgWork(strict) })

        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.addView(button("Pilih dari Telegram") {
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
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(8); rightMargin = dp(6) } })
        btns.addView(smallButton("Ketik nama") {
            val add = { askName("Nama chat (boleh sebagian nama)", "") { Store.addTgAllow(this, it); render() } }
            if (!on) add()
            else if (strict) toast("Mode Ketat aktif: tidak bisa menambah daftar boleh")
            else textGate("menambah chat yang boleh dibuka", { add() }, { render() })
        }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { topMargin = dp(8); leftMargin = dp(6); rightMargin = 0 } })
        put(btns)

        put(text("DAFTAR BOLEH  ·  ${list.size} chat", 11f, true, C_SUB).apply { letterSpacing = 0.12f; setPadding(0, dp(14), 0, dp(4)) })
        if (list.isEmpty()) put(text("Daftar kosong: bila mode aktif, semua grup, channel, dan bot yang terbaca akan ditutup.", 12f, false, C_SUB))
        for (nm in (if (tgShowAll) list else list.take(6))) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))
            row.addView(TextView(this).apply {
                text = nm.take(1).uppercase()
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(C_PRIMARY)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#1B1F28"))
                    setStroke(dp(1), Color.argb(110, 212, 175, 106))
                }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) }
            })
            row.addView(text(nm, 14.5f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(text("✕", 16f, true, C_SUB).apply {
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener {
                    // Mengurangi daftar boleh = lebih ketat, jadi langsung.
                    Store.removeTgAllow(this@MainActivity, nm)
                    render()
                }
            })
            put(row)
        }
        if (list.size > 6) put(text(if (tgShowAll) "Ringkas daftar ▴" else "Tampilkan semua (${list.size}) ▾", 13f, true, C_PRIMARY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { tgShowAll = !tgShowAll; render() }
        })
        put(text("Menambah daftar saat mode aktif wajib lewat teks acak dan tidak bisa saat Mode Ketat. Menghapus entri langsung. Selama mode belum dicentang, daftar bebas diisi.", 12f, false, C_SUB).apply { setPadding(0, dp(10), 0, 0) })
    }

    private var kwTab = 0

    /** Kolom isian dengan tombol tambah emas di sebelah kanan. */
    private fun addField(hint: String, onAdd: (String) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6), 0, dp(6))
        val et = EditText(this).apply {
            this.hint = hint
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val btn = TextView(this).apply {
            text = "Tambah"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(C_ON_GOLD)
            gravity = Gravity.CENTER
            background = goldBg(dp(14))
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply { leftMargin = dp(10) }
            setOnClickListener {
                val v = et.text.toString().trim()
                if (v.isNotEmpty()) onAdd(v)
            }
        }
        row.addView(btn)
        return row
    }

    /** Baris daftar: judul, keterangan, panah, dan tombol hapus bulat. */
    private fun listRow(title: String, sub: String, titleColor: Int, onClick: (() -> Unit)?, onRemove: (() -> Unit)?): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(10), 0, dp(10))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(text(title, 14f, true, titleColor).apply { typeface = Typeface.DEFAULT_BOLD })
        if (sub.isNotEmpty()) col.addView(text(sub, 12f, false, Color.parseColor("#8A8576")))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (onClick != null) {
            row.addView(text("›", 22f, true, C_PRIMARY))
            row.setOnClickListener { onClick() }
        }
        if (onRemove != null) row.addView(text("✕", 13f, true, C_BAD).apply {
            gravity = Gravity.CENTER
            background = bg(Color.argb(30, 229, 115, 107), dp(14))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(12) }
            setOnClickListener { onRemove() }
        })
        wrap.addView(row)
        wrap.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#1B1F28"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })
        return wrap
    }

    private fun renderWords(strict: Boolean) {
        val domains = Store.domains(this).sorted()
        val urlKw = Store.urlKeywords(this).sorted()
        val scrKw = Store.screenKeywords(this).sorted()
        val history = SiteHistory.list(this, 30)
        put(segmented(listOf("Situs ${domains.size}", "Kata kunci ${urlKw.size + scrKw.size}", "Riwayat"), kwTab) { i ->
            kwTab = i
            render()
        })
        when (kwTab) {
            0 -> {
                header("Tambah situs")
                put(addField("mis. contoh.com") { raw ->
                    val d = Rules.normalizeDomain(raw)
                    if (d.isEmpty()) toast("Masukkan domain situs terlebih dahulu")
                    else if (Store.domains(this).contains(d)) editSiteDialog(d, strict)
                    else editSiteDialog(d, false, true)
                })
                put(infoNote("Dicek di kolom alamat browser dan lewat VPN DNS. Subdomain ikut terblokir. Setelah dimasukkan, pilih cara memblokirnya."))
                header("Daftar situs")
                if (domains.isEmpty()) put(text("Belum ada situs yang diblokir.", 13f, false, C_SUB))
                for (d in domains) {
                    val cfg = Store.siteCfgOrNull(this, d)
                    val sub = when {
                        cfg == null || (cfg.mode == 0 && !cfg.schOn) -> "Blokir penuh"
                        cfg.twUse > 0 && cfg.twWait > 0 -> "Timer tunggu ${cfg.twUse}/${cfg.twWait} menit"
                        cfg.limitMin > 0 || cfg.hourLimit > 0 -> "Batas penggunaan"
                        cfg.schOn -> "Jadwal ${fmt(cfg.start)}-${fmt(cfg.end)}"
                        else -> "Aturan khusus"
                    }
                    put(listRow(d, sub, C_TEXT, { editSiteDialog(d, strict) }) {
                        if (strict) toast("Mode Ketat aktif: tidak bisa menghapus")
                        else gateOff(true, "menghapus situs $d") { Store.removeDomain(this@MainActivity, d) }
                    })
                }
                header("Channel Telegram")
                put(infoNote("Channel diblokir otomatis bila 5 kali terdeteksi gambar tidak pantas dalam 10 menit. Aplikasi Telegram lain tetap bisa dipakai."))
                val chs = Store.channels(this).sorted()
                if (chs.isEmpty()) put(text("Belum ada channel yang diblokir.", 13f, false, C_SUB))
                for (ch in chs) put(listRow(ch, "", C_TEXT, null) {
                    if (strict) toast("Mode Ketat aktif: tidak bisa menghapus")
                    else gateOff(true, "menghapus channel $ch") { Store.removeChannel(this@MainActivity, ch) }
                })
            }
            1 -> {
                header("Keyword URL")
                put(infoNote("Diperiksa hanya pada kolom alamat semua browser yang dipantau. Kata yang hanya muncul di teks halaman tidak ikut diblokir."))
                put(addField("Tambah keyword URL") { k ->
                    Store.addUrlKeyword(this, k)
                    render()
                })
                if (urlKw.isNotEmpty()) put(chipList(urlKw) { k ->
                    if (strict) toast("Mode Ketat aktif: tidak bisa menghapus")
                    else gateOff(true, "menghapus keyword URL") { Store.removeUrlKeyword(this, k) }
                })
                header("Keyword layar")
                put(infoNote("Diperiksa pada teks dan keterangan yang terbaca Aksesibilitas di seluruh layar dan aplikasi, bukan hanya URL browser."))
                put(addField("Tambah keyword layar") { k ->
                    Store.addScreenKeyword(this, k)
                    render()
                })
                if (scrKw.isNotEmpty()) put(chipList(scrKw) { k ->
                    if (strict) toast("Mode Ketat aktif: tidak bisa menghapus")
                    else gateOff(true, "menghapus keyword layar") { Store.removeScreenKeyword(this, k) }
                })
                header("Keyword bawaan")
                put(infoNote("Daftar bawaan yang bisa diubah. Jika terlalu luas, tekan ✕ untuk mematikannya, lalu Aktifkan untuk menyalakan kembali."))
                put(builtinKeywordList())
            }
            else -> {
                header("Riwayat situs")
                put(infoNote("Domain utama yang baru terdeteksi dari browser. Disimpan lokal di perangkat dan terus diperbarui saat situs dibuka."))
                if (history.isEmpty()) put(text("Belum ada situs yang terdeteksi.", 13f, false, C_SUB))
                for (entry in history) {
                    val blocked = Store.domains(this).contains(entry.domain)
                    put(listRow(
                        entry.domain + if (blocked) "  · diblokir" else "",
                        "Terakhir ${SiteHistory.formatTime(entry.lastOpened)} · ${entry.visits}x terdeteksi",
                        if (blocked) C_BAD else C_TEXT,
                        { editSiteDialog(entry.domain, strict, !Store.domains(this).contains(entry.domain)) }, null
                    ))
                }
            }
        }
    }

    private fun editSiteDialog(domain: String, strict: Boolean, isNew: Boolean = false) {
        val existing = Store.siteCfgOrNull(this, domain)
        val cfg = existing ?: SiteCfg()
        var start = cfg.start
        var end = cfg.end
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
        var selected = initialMode

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(6), dp(22), dp(16))
        box.addView(text("KONDISI BLOKIR", 11f, true, Color.parseColor("#7C7768")).apply { letterSpacing = 0.18f; setPadding(0, dp(4), 0, dp(2)) })
        box.addView(text(
            if (isNew) "Pilih satu cara memblokir $domain. Situs baru diblokir setelah kamu menekan Simpan."
            else "Pilih satu cara memblokir $domain.", 12.5f, false, C_SUB))
        if (locked) box.addView(infoNote("Mode Ketat aktif: aturan situs ini terkunci. Yang tampil adalah aturan yang tersimpan."))

        // Pilihan tunggal: kartu radio, isi muncul di bawah pilihan yang aktif.
        val cards = ArrayList<Pair<LinearLayout, () -> Unit>>()
        val bodies = ArrayList<LinearLayout>()
        fun repaintAll() { for (c in cards) c.second() }
        fun choice(idx: Int, title: String, sub: String, body: LinearLayout?) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(14), dp(12), dp(14), dp(12))
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            val dot = android.widget.FrameLayout(this)
            dot.layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.addView(dot)
            val tc = LinearLayout(this)
            tc.orientation = LinearLayout.VERTICAL
            tc.setPadding(dp(12), 0, 0, 0)
            tc.addView(text(title, 14.5f, true, C_TEXT).apply { typeface = Typeface.DEFAULT_BOLD })
            tc.addView(text(sub, 12f, false, C_SUB))
            head.addView(tc, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(head)
            if (body != null) {
                body.setPadding(0, dp(10), 0, 0)
                card.addView(body)
            }
            val paint = {
                val on = selected == idx
                card.background = (if (on) GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#161B25"), Color.parseColor("#0E1117"))
                ) else bg(Color.parseColor("#10131A"), 0)).apply {
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), if (on) Color.argb(170, 212, 175, 106) else Color.parseColor("#232834"))
                }
                dot.removeAllViews()
                dot.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(2), if (on) C_PRIMARY else Color.parseColor("#4A4F5B"))
                    }
                }, android.widget.FrameLayout.LayoutParams(dp(22), dp(22)))
                if (on) dot.addView(View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_PRIMARY) }
                }, android.widget.FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
                body?.visibility = if (on) View.VISIBLE else View.GONE
            }
            cards.add(Pair(card, paint))
            if (!locked) head.setOnClickListener { selected = idx; repaintAll() }
            box.addView(card)
        }

        // 0 penuh
        choice(0, "Blokir penuh", "Selalu ditutup saat dibuka", null)
        // 1 jadwal
        val scheduleBody = vbox()
        val timesRow = timeRow("Mulai", fmt(start), {
            if (!locked) TimePickerDialog(this, { _, h, m -> start = h * 60 + m; (timeTv(scheduleBody, 0) as TextView).text = fmt(start) }, start / 60, start % 60, true).show()
        }, "Selesai", fmt(end), {
            if (!locked) TimePickerDialog(this, { _, h, m -> end = h * 60 + m; (timeTv(scheduleBody, 1) as TextView).text = fmt(end) }, end / 60, end % 60, true).show()
        })
        scheduleBody.tag = timesRow.tag
        scheduleBody.addView(timesRow)
        choice(1, "Jadwal", "Ditutup hanya pada jam ini", scheduleBody)
        // 2 batas penggunaan
        val limitBody = vbox()
        val limRow = LinearLayout(this)
        limRow.orientation = LinearLayout.HORIZONTAL
        val (tD, limit) = bigNumber("Per hari", "menit", cfg.limitMin,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
        val (tH, hour) = bigNumber("Per jam", "menit", cfg.hourLimit,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        limRow.addView(tD); limRow.addView(tH)
        limitBody.addView(limRow)
        limitBody.addView(infoNote("Per jam dihitung dari 60 menit terakhir. Isi 0 untuk mematikan salah satunya."))
        choice(2, "Batas penggunaan", "Maksimal menit per hari atau per jam", limitBody)
        // 3 timer tunggu
        val twBody = vbox()
        val twRow = LinearLayout(this)
        twRow.orientation = LinearLayout.HORIZONTAL
        val (tU, twUse) = bigNumber("Boleh dipakai", "menit", if (cfg.twUse > 0) cfg.twUse else 5,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
        val (tW, twWait) = bigNumber("Lalu tunggu", "menit", if (cfg.twWait > 0) cfg.twWait else 30,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        twRow.addView(tU); twRow.addView(tW)
        twBody.addView(twRow)
        val (adView, adState) = toggleRow("Timer adaptif", "Makin sering dicoba, makin lama menunggu", cfg.twAdaptive, false)
        twBody.addView(adView)
        choice(3, "Timer tunggu", "Pakai sebentar, lalu tunggu", twBody)
        // 4 lokasi
        choice(4, "Hanya berdasarkan lokasi", "Ditutup saat di tempat tertentu", null)
        repaintAll()

        val locUi = locationSection(box, "site_" + Rules.normalizeDomain(domain), locked)
        box.addView(infoNote("Hitungan peluncuran tidak dipakai untuk situs, karena situs tidak punya konsep peluncuran aplikasi."))

        val doSave: () -> Boolean = save@{
            if (locked) return@save true
            fun num(e: EditText, max: Int) = (e.text.toString().toIntOrNull() ?: 0).coerceIn(0, max)
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
                twAdaptive = adState()
            )
            if (selected == 2 && newCfg.limitMin == 0 && newCfg.hourLimit == 0) {
                toast("Isi batas penggunaan terlebih dahulu")
                return@save false
            }
            if (selected == 3 && (newCfg.twUse <= 0 || newCfg.twWait <= 0)) {
                toast("Isi timer penggunaan dan timer tunggu terlebih dahulu")
                return@save false
            }
            val locRule = if (locUi.cb.isChecked) locUi.read() else null
            if (locUi.cb.isChecked && locRule == null) {
                toast("Lokasi tidak valid: isi lintang dan bujur dengan benar (atau pakai lokasi saya sekarang).")
                return@save false
            }
            if (selected == 4 && locRule == null) {
                toast("Mode lokasi: aktifkan bagian lokasi dan isi titiknya.")
                return@save false
            }
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
            true
        }
        luxScreenDialog("", "ATURAN SITUS", domain, box, if (locked) "Tutup" else if (isNew) "Blokir situs ini" else "Simpan aturan",
            emptyList(), strict, doSave, { render() }, "Situs", R.drawable.ic_nav_globe)
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
