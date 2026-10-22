package com.mas.strictguard

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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
        if (Store.strictOn(this) && !WatchdogService.running) WatchdogService.start(this)
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
        renderProtection(strict)
        renderApps(strict)
        renderProfiles(strict)
        renderNewApps(strict)
        renderProtected(strict)
        renderCategories(strict)
        renderWords(strict)
        renderVpn(strict)
        renderNotif()
        renderFriend()
        renderDetection()
        renderFeeds(strict)
        renderMotivation(strict)
        renderStats(strict)
        renderLog()

        scroll.post { scroll.scrollTo(0, keepY) }
    }

    private fun renderStrict(strict: Boolean, ready: Boolean) {
        header("Mode Ketat")
        if (!strict) {
            root.addView(text("Mati. Semua pengaturan bisa diubah bebas.", 13f))
            root.addView(text("Waktu tunggu untuk keluar dari Mode Ketat (menit, 10 sampai 1440)", 12f))
            val delay = EditText(this)
            delay.inputType = InputType.TYPE_CLASS_NUMBER
            delay.setText(Store.exitDelayMin(this).toString())
            root.addView(delay)
            root.addView(button("Aktifkan Mode Ketat") {
                if (!ready) {
                    toast("Aktifkan Aksesibilitas dan perlindungan uninstall dulu")
                } else {
                    val d = (delay.text.toString().toIntOrNull() ?: 60).coerceIn(10, 1440)
                    Store.setExitDelayMin(this, d)
                    Store.setStrict(this, true)
                    SafeTimer.cancel(this, "exit")
                    Store.log(this, "Mode Ketat diaktifkan (tunggu keluar $d menit)")
                    WatchdogService.start(this)
                    render()
                }
            })
            return
        }
        root.addView(text("AKTIF. Daftar blokir hanya bisa ditambah, tidak bisa dikurangi. Pengaturan lain terkunci. Aplikasi tidak bisa dicopot, dan halaman Pengaturan yang menyebut Penjaga Ketat diblokir.", 13f, true, Color.rgb(180, 0, 0)))
        if (!SafeTimer.isRunning(this, "exit")) {
            val delay = Store.exitDelayMin(this)
            root.addView(button("Minta nonaktifkan (tunggu $delay menit)") {
                SafeTimer.start(this, "exit")
                Store.log(this, "Permintaan keluar Mode Ketat dimulai")
                gateText = null
                render()
            })
            return
        }
        val totalMs = Store.exitDelayMin(this) * 60000L
        val left = totalMs - SafeTimer.elapsed(this, "exit")
        if (left > 0) {
            root.addView(text("Permintaan nonaktif berjalan. Sisa ${left / 60000L + 1} menit. Waktu dihitung dari jam internal, jadi mengubah jam HP tidak mempercepatnya.", 14f, true))
            root.addView(button("Batalkan permintaan") {
                SafeTimer.cancel(this, "exit")
                render()
            })
            handler.postDelayed({ render() }, 30000L)
        } else {
            val code = gateText ?: Rules.randomText().also { gateText = it }
            root.addView(text("Waktu tunggu selesai. Ketik teks ini persis untuk menonaktifkan:", 13f))
            root.addView(text(code, 20f, true))
            val input = EditText(this)
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            root.addView(input)
            val friendInput = EditText(this)
            if (FriendApproval.isSet(this)) {
                root.addView(text("Masukkan juga kode teman (teman harus ada di dekatmu):", 13f))
                friendInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                root.addView(friendInput)
                val lk = FriendApproval.lockedMinutes(this)
                if (lk > 0L) root.addView(text("Terkunci karena salah 3 kali. Coba lagi $lk menit lagi.", 12f, true, Color.rgb(180, 0, 0)))
            }
            root.addView(button("Konfirmasi nonaktifkan") {
                if (input.text.toString() == code) {
                    if (FriendApproval.isSet(this) && !FriendApproval.verify(this, friendInput.text.toString())) {
                        toast("Kode teman salah atau terkunci")
                        render()
                        return@button
                    }
                    Store.setStrict(this, false)
                    SafeTimer.cancel(this, "exit")
                    Store.log(this, "Mode Ketat dinonaktifkan")
                    gateText = null
                    render()
                } else {
                    toast("Teks tidak cocok")
                }
            })
            root.addView(button("Batalkan permintaan") {
                SafeTimer.cancel(this, "exit")
                gateText = null
                render()
            })
        }
    }

    private fun renderProtection(strict: Boolean) {
        header("Perlindungan diri")
        val secure = SelfProtector.canWriteSecure(this)
        val usage = Foreground.hasUsageAccess(this)
        status(secure, "Pemulih otomatis aksesibilitas (izin WRITE_SECURE_SETTINGS)")
        status(usage, "Akses Penggunaan (mengenali aplikasi bank terbuka)")
        status(WatchdogService.running, "Pengawas berjalan (hanya saat Mode Ketat aktif)")
        if (!secure) {
            root.addView(text("Agar aksesibilitas yang dimatikan otomatis menyala lagi, jalankan SEKALI dari PC (USB debugging aktif):", 12f))
            root.addView(text("adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS", 12f).apply {
                setTextIsSelectable(true)
                setBackgroundColor(Color.rgb(235, 235, 235))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            root.addView(text("Tanpa izin ini, pengawas hanya memberi peringatan lewat notifikasi, tidak bisa menyalakan sendiri.", 12f))
        }
        if (!usage) root.addView(button("Buka pengaturan Akses Penggunaan") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            root.addView(button("Izinkan notifikasi peringatan") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }
        root.addView(text("Jam HP diubah saat Mode Ketat aktif: jadwal diabaikan dan blokir berlaku penuh 30 menit.", 12f))
        root.addView(text("Pemulihan darurat: keluar Mode Ketat lewat tombol di atas (tunggu lalu ketik teks acak). Itu jalur pemulihan yang selalu tersedia, tanpa PC.", 12f))
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
        root.addView(cb)
        val list = Store.newApps(this)
        if (list.isEmpty()) {
            root.addView(text("Belum ada aplikasi baru yang terdeteksi.", 12f))
        }
        val fmtDate = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
        for ((p, t) in list) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
            } catch (e: Exception) {
                p + " (sudah dicopot)"
            }
            val blocked = if (p in Store.apps(this)) " - diblokir" else ""
            root.addView(text(fmtDate.format(java.util.Date(t)) + "  " + label + blocked, 12f))
        }
    }

    private fun renderLog() {
        header("Riwayat pass")
        val ph = Store.passHistory(this)
        if (ph.isEmpty()) {
            root.addView(text("Belum ada pass yang dipakai.", 12f))
        } else {
            for (line in ph.takeLast(10).reversed()) {
                val parts = line.split('|')
                if (parts.size < 3) continue
                val label = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(parts[1], 0)).toString()
                } catch (e: Exception) {
                    parts[1]
                }
                root.addView(text(parts[0] + "  " + label + "  " + parts[2] + " mnt", 11f))
            }
        }
        header("Catatan perlindungan")
        val lines = Store.logs(this)
        if (lines.isEmpty()) {
            root.addView(text("Belum ada catatan.", 12f))
        } else {
            for (l in lines.takeLast(15).reversed()) root.addView(text(l, 11f))
        }
    }

    private fun renderApps(strict: Boolean) {
        header("Aplikasi diblokir")
        root.addView(text("Centang aplikasi yang ingin diblokir, lalu pilih kondisi pemblokirannya (blokir penuh, batas penggunaan, jumlah peluncuran, timer tunggu, interval waktu). Tombol Atur aturan mengubahnya lagi. Saat Mode Ketat aktif, aplikasi yang sudah diatur terkunci, tetapi aplikasi baru masih bisa ditambah dan diatur.", 12f))
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
                        render()
                        editCfg(a.pkg, a.label, strict)
                        return@setOnClickListener
                    } else if (strict && Store.isLocked(this@MainActivity, a.pkg)) {
                        toast("Mode Ketat aktif: aplikasi ini sudah terkunci, tidak bisa melepas blokir")
                    } else {
                        Store.removeApp(this@MainActivity, a.pkg)
                    }
                    render()
                }
            }
            root.addView(cb)
            if (a.pkg in chosen) {
                val cfg = Store.appCfg(this, a.pkg)
                root.addView(text("     " + summary(cfg), 12f, false, Color.rgb(90, 90, 90)))
                root.addView(text("     " + usageLine(a.pkg, cfg), 12f, false, Color.rgb(90, 90, 90)))
                root.addView(button("Atur aturan: ${a.label}") { editCfg(a.pkg, a.label, strict) })
                root.addView(button("Blok cepat: ${a.label}") { quickBlock(a.pkg, a.label) })
            }
        }
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
        box.addView(text("Berdasarkan tujuan dan Lokasi: belum tersedia.", 12f))

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
                Store.setAppCfg(this, pkg, newCfg)
                if (strict) Store.lockApp(this, pkg)
                if (newCfg.notif && !SelfProtector.notifListenerOn(this)) {
                    if (!SelfProtector.enableNotifListener(this)) {
                        toast("Nyalakan Akses Notifikasi untuk Penjaga Ketat di bagian Notifikasi di bawah.")
                    }
                }
                if (!full && !useCb.isChecked && !launchCb.isChecked && !twCb.isChecked) {
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

    private fun renderProfiles(strict: Boolean) {
        header("Profil")
        root.addView(text("Profil (Kerja, Belajar, Tidur, Mode Ketat, Custom) memblokir aplikasinya selama jadwalnya berjalan, dan menambah kata kunci dan situs selama aktif. Profil tanpa jadwal aktif terus selama dinyalakan. Batas harian lebih dari 0: aplikasi boleh dipakai sampai batas itu, setelah itu diblokir. Saat Mode Ketat aktif, profil hanya bisa diperketat.", 12f))
        for (p in ProfileStore.load(this)) {
            val live = ProfileEngine.isActive(this, p)
            val state = if (!p.enabled) "mati" else if (live) "AKTIF sekarang" else "menunggu jadwal"
            root.addView(text(p.name + " - " + state, 14f, true))
            val sch = if (p.schedules.isEmpty()) "tanpa jadwal" else
                p.schedules.joinToString("; ") { "${fmt(it.start)}-${fmt(it.end)} ${dayNames(it.days)}" }
            root.addView(text("${p.apps.size} aplikasi • ${p.keywords.size} kata kunci • ${p.domains.size} situs • $sch" +
                (if (p.limitMin > 0) " • batas ${p.limitMin} mnt" else ""), 12f, false, Color.rgb(90, 90, 90)))
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
            root.addView(cb)
            root.addView(button("Atur profil: " + p.name) { editProfile(p, strict) })
        }
    }

    private fun editProfile(p: Profile, strict: Boolean) {
        val apps = HashSet(p.apps)
        val scheds = ArrayList(p.schedules)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))
        if (strict) box.addView(text("Mode Ketat aktif: perubahan hanya menambah, tidak mengurangi.", 12f, true, Color.rgb(180, 0, 0)))

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
                ProfileStore.replace(this, np)
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
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

    private fun renderCategories(strict: Boolean) {
        header("Kategori situs")
        root.addView(text("Nyalakan kategori untuk memblokir situs dan kata kunci sekaligus. Subdomain ikut terblokir (mis. m.situs.com). Saat Mode Ketat aktif, kategori hanya bisa dinyalakan, tidak bisa dimatikan.", 12f))
        for (cat in Categories.ALL) {
            val on = Store.categoryOn(this, cat.id)
            root.addView(CheckBox(this).apply {
                text = cat.name + "\n" + cat.desc
                textSize = 13f
                isChecked = on
                setOnClickListener { v ->
                    val now = (v as CheckBox).isChecked
                    if (!now && strict) {
                        toast("Mode Ketat aktif: kategori tidak bisa dimatikan")
                    } else {
                        Store.setCategory(this@MainActivity, cat.id, now)
                        Store.log(this@MainActivity, "Kategori ${cat.name}: " + (if (now) "ON" else "OFF"))
                    }
                    render()
                }
            })
        }
    }

    private fun renderMotivation(strict: Boolean) {
        header("Teks motivasi")
        root.addView(text("Ditampilkan di layar blokir.", 12f))
        val et = EditText(this).apply {
            setText(Store.motivation(this@MainActivity))
            textSize = 13f
        }
        root.addView(et)
        root.addView(button("Simpan teks motivasi") {
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
        else -> r
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun renderStats(strict: Boolean) {
        header("Statistik")
        if (!Foreground.hasUsageAccess(this)) {
            root.addView(text("Aktifkan Akses Penggunaan agar pemakaian aplikasi tercatat.", 12f))
        } else {
            UsageTracker.get(this)
        }
        try {
            val dayStart = UsageTracker.startOfDay()
            val today = StatsDb.blocksByReason(this, dayStart)
            val week = StatsDb.blocksByReason(this, dayStart - 6L * 86400000L)
            root.addView(text("Blokir hari ini: " + (if (today.isEmpty()) "0" else today.joinToString(", ") { reasonName(it.first) + " " + it.second }), 12f))
            root.addView(text("Blokir 7 hari: " + (if (week.isEmpty()) "0" else week.joinToString(", ") { reasonName(it.first) + " " + it.second }), 12f))

            val totals = StatsDb.dailyTotals(this, 7)
            val maxMs = Math.max(1L, totals.maxOf { it.second })
            root.addView(text("Pemakaian 7 hari (semua aplikasi tercatat):", 12f, true))
            for ((day, ms) in totals) {
                val bars = (ms * 20L / maxMs).toInt()
                val line = day.substring(5) + "  " + "█".repeat(bars) + " " + (ms / 60000L) + " mnt"
                root.addView(text(line, 11f))
            }
            val top = StatsDb.topApps(this, 7, 8)
            if (top.isNotEmpty()) {
                root.addView(text("Aplikasi teratas 7 hari:", 12f, true))
                for ((pkg, ms, l) in top) root.addView(text(appLabel(pkg) + "  " + (ms / 60000L) + " mnt, " + l + "x buka", 11f))
            }
        } catch (t: Throwable) {
            root.addView(text("Statistik belum tersedia.", 12f))
        }
        root.addView(button("Hapus statistik") {
            if (strict) toast("Mode Ketat aktif: statistik terkunci") else { StatsDb.clear(this); render() }
        })
    }

    private fun renderVpn(strict: Boolean) {
        header("VPN DNS (filter situs untuk semua aplikasi)")
        root.addView(text("VPN lokal yang hanya memeriksa nama situs (DNS) dan menolak situs terblokir, termasuk dari aplikasi dan browser dalam aplikasi. Data lain tidak lewat VPN. Aturan yang dipakai sama dengan daftar situs, kata kunci, kategori dan profil aktif.", 12f))
        val on = DnsVpnService.running
        status(on, "VPN DNS berjalan")
        if (!on) {
            root.addView(button("Nyalakan VPN DNS") {
                val i = VpnService.prepare(this)
                if (i != null) {
                    startActivityForResult(i, 77)
                } else {
                    Store.setVpnOn(this, true)
                    DnsVpnService.start(this)
                    handler.postDelayed({ render() }, 800L)
                }
            })
        } else {
            root.addView(button("Matikan VPN DNS") {
                if (strict) {
                    toast("Mode Ketat aktif: VPN DNS tidak bisa dimatikan")
                } else {
                    Store.setVpnOn(this, false)
                    DnsVpnService.stop(this)
                    render()
                }
            })
        }
        root.addView(text("DNS upstream", 12f, true))
        val upSp = spinnerOf(
            listOf("Cloudflare Family 1.1.1.3 (ikut memblokir dewasa dan malware)", "Cloudflare 1.1.1.1", "Google 8.8.8.8"),
            Store.vpnUpstream(this)
        )
        root.addView(upSp)
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
        root.addView(CheckBox(this).apply {
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
            root.addView(text("Peringatan: Private DNS di HP diatur ke nama host tertentu, sehingga melewati filter ini. Ubah ke Otomatis atau Mati di Pengaturan.", 12f, true, Color.rgb(180, 0, 0)))
        }
        root.addView(text("Batasan: aplikasi yang memakai DNS terenkripsinya sendiri (mis. pengaturan DNS aman di Chrome ke penyedia lain) bisa lolos. Untuk menjaga VPN tidak dilepas, nyalakan juga VPN Selalu Aktif di Pengaturan > Jaringan > VPN.", 11f))
    }

    private fun renderNotif() {
        header("Kontrol notifikasi")
        root.addView(text("Notifikasi aplikasi yang sedang diblokir dihapus bila opsi notifikasi dicentang di aturan aplikasinya (atau aplikasi diblokir profil aktif).", 12f))
        val on = SelfProtector.notifListenerOn(this)
        status(on, "Akses Notifikasi Penjaga Ketat aktif")
        if (!on) {
            root.addView(button("Nyalakan Akses Notifikasi") {
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
        if (requestCode == 77 && resultCode == RESULT_OK) {
            Store.setVpnOn(this, true)
            DnsVpnService.start(this)
            handler.postDelayed({ render() }, 800L)
        }
    }

    private fun renderFriend() {
        header("Persetujuan teman")
        root.addView(text("Teman mengetik sebuah kode di HP ini. Hanya hash-nya yang disimpan, dan kode itu menjadi syarat tambahan untuk keluar dari Mode Ketat. Serahkan HP ke temanmu saat mengatur kode dan jangan tanya kodenya. Persetujuan jarak jauh dari HP teman belum tersedia.", 12f))
        val isSet = FriendApproval.isSet(this)
        status(isSet, "Kode teman sudah diatur")
        root.addView(button(if (isSet) "Ganti kode teman" else "Atur kode teman") { friendDialog() })
        if (isSet) root.addView(button("Hapus kode teman") { friendClearDialog() })
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
            root.addView(text("PLACEHOLDER. Antarmuka sudah ada, tetapi belum ada model dan belum ada pengambilan layar yang terhubung, jadi gambar belum diperiksa. Pemblokiran saat ini mengandalkan kata kunci, situs, kategori, dan VPN DNS.", 12f))
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
