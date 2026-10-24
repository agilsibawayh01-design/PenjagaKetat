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
    private val handler = Handler(Looper.getMainLooper())
    private var gateText: String? = null
    private var cur: LinearLayout? = null
    private var screen = "home"
    private var lastScreen = ""
    private var appQuery = ""
    private var gateTyped = ""
    private var credAsked = false
    private var inCred = false

    private val C_BG = Color.parseColor("#F2F4F8")
    private val C_PRIMARY = Color.parseColor("#1E3A5F")
    private val C_TEXT = Color.parseColor("#1B1F27")
    private val C_SUB = Color.parseColor("#5F6B7A")
    private val C_OK = Color.parseColor("#1B7F4B")
    private val C_BAD = Color.parseColor("#C62828")

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
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        Store.seed(this)
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
        c.setPadding(dp(16), dp(12), dp(16), dp(14))
        c.background = bg(Color.WHITE, dp(16))
        c.elevation = dp(2).toFloat()
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
        card.addView(text(s, 16f, true, C_PRIMARY).apply { setPadding(0, 0, 0, dp(6)) })
    }

    private fun status(ok: Boolean, label: String) {
        put(text((if (ok) "✔  " else "✘  ") + label, 13f, false, if (ok) C_OK else C_BAD))
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            background = bg(C_PRIMARY, dp(12))
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(14), dp(10), dp(14), dp(10))
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
            background = bg(Color.parseColor("#E6ECF5"), dp(10))
            minHeight = 0
            minimumHeight = 0
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
        sv.background = bg(Color.parseColor("#F6F7FA"), dp(10))
        sv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)).apply {
            topMargin = dp(6)
        }
        sv.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
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
                            Store.disableBuiltinKeyword(this@MainActivity, item)
                            render()
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

    private fun render() {
        if (AppLock.needsAuth(this)) {
            showLockScreen()
            return
        }
        val keepY = scroll.scrollY
        root.removeAllViews()
        cur = null
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
                }
                "apps" -> renderApps(strict)
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
                "stats" -> renderDashboard(strict)
                else -> {
                    renderAppLock(strict)
                    renderFriend()
                    renderDetection()
                    renderMotivation(strict)
                    renderNewApps(strict)
                    renderProtected(strict)
                    renderLog()
                }
            }
        }
        val same = lastScreen == screen
        lastScreen = screen
        scroll.post { scroll.scrollTo(0, if (same) keepY else 0) }
    }

    private fun screenTitle(s: String): String = when (s) {
        "strict" -> "Mode Ketat dan status"
        "apps" -> "Aplikasi diblokir"
        "words" -> "Situs dan kata kunci"
        "profiles" -> "Profil"
        "net" -> "VPN DNS dan notifikasi"
        "stats" -> "Dashboard"
        else -> "Lainnya"
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
        val back = TextView(this)
        back.text = "←"
        back.textSize = 22f
        back.setTextColor(Color.WHITE)
        back.gravity = Gravity.CENTER
        back.background = bg(C_PRIMARY, dp(12))
        back.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        back.setOnClickListener { goScreen("home") }
        row.addView(back)
        row.addView(text(title, 20f, true).apply {
            setPadding(dp(12), 0, 0, 0)
        })
        root.addView(row)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen != "home") goScreen("home") else super.onBackPressed()
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
        banner.setPadding(dp(16), dp(16), dp(16), dp(16))
        banner.background = bg(C_PRIMARY, dp(20))
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
        val line = modeLabel() + " • " +
            (if (acc && adm) "perlindungan lengkap" else "perlu tindakan")
        col.addView(text(line, 12f, false, Color.argb(225, 255, 255, 255)))
        banner.addView(col)
        root.addView(banner)
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

        renderTodayCard(acc && adm)

        val blocks = try {
            StatsDb.blocksByReason(this, UsageTracker.startOfDay()).sumOf { it.second }
        } catch (t: Throwable) {
            0
        }
        val profiles = ProfileStore.load(this)
        val activeP = profiles.count { ProfileEngine.isActive(this, it) }
        val wordCount = Store.keywords(this).size + Store.domains(this).size
        val cats = Categories.enabled(this).size
        val tiles = listOf(
            tile("🛡️", "Mode & Ketat", modeLabel(), Color.parseColor("#C62828"), "strict"),
            tile("📱", "Aplikasi", "${Store.apps(this).size} diblokir", Color.parseColor("#1565C0"), "apps"),
            tile("🌐", "Situs & kata", "$wordCount aturan • $cats kategori", Color.parseColor("#6A1B9A"), "words"),
            tile("🗂️", "Profil", "$activeP aktif dari ${profiles.size}", Color.parseColor("#00796B"), "profiles"),
            tile("🔌", "VPN & notifikasi", if (VpnStatus.running) "VPN DNS aktif" else if (Store.vpnOn(this)) "VPN DNS: " + VpnStatus.state.label else "VPN DNS mati", Color.parseColor("#2E7D32"), "net"),
            tile("📊", "Dashboard", "$blocks blokir hari ini", Color.parseColor("#EF6C00"), "stats"),
            tile("⚙️", "Lainnya", "Teman, log, deteksi", Color.parseColor("#455A64"), "more")
        )
        var i = 0
        while (i < tiles.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(tiles[i])
            if (i + 1 < tiles.size) {
                row.addView(tiles[i + 1])
            } else {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
                })
            }
            root.addView(row)
            i += 2
        }
    }

    private fun requestAdmin() {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Agar Penjaga Ketat tidak bisa dicopot saat Mode Ketat aktif.")
        startActivity(i)
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

    private fun pill(label: String, selected: Boolean, color: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 14f
        t.setTypeface(null, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        t.setTextColor(if (selected) Color.WHITE else C_SUB)
        t.background = bg(if (selected) color else Color.parseColor("#E6E9EF"), dp(12))
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
        row.addView(pill("Off", paused, Color.parseColor("#78909C")) {
            if (raw) toast("Mode Ketat aktif. Keluar dulu lewat metode penonaktifan.") else pauseDialog()
        })
        row.addView(pill("Normal", !paused && !raw, C_PRIMARY) {
            if (raw) toast("Mode Ketat aktif. Keluar dulu lewat metode penonaktifan.") else {
                Store.pauseStop(this)
                render()
            }
        })
        row.addView(pill("Ketat", raw, C_BAD) {
            if (screen != "strict") goScreen("strict") else toast("Atur metode penonaktifan di bawah, lalu tekan Aktifkan Mode Ketat.")
        })
        put(row)
        val desc = when {
            paused -> {
                val left = Store.pauseLeftMin(this)
                "OFF: semua blokir dijeda. " + (if (left >= 0L) "Berakhir sekitar $left menit lagi." else "Berlaku sampai kamu memilih Normal.")
            }
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
            .setTitle("Mode Off: jeda blokir")
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
            setBackgroundColor(Color.parseColor("#E6E9EF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })
    }

    private fun renderStrict(strict: Boolean, ready: Boolean) {
        val raw = Store.strictRaw(this)
        renderModeSwitch()
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

        header("Mode Ketat")
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
        val waitMin = if (Store.exitWaitOn(this)) Store.exitDelayMin(this) else 0
        val textLen = Store.exitTextLen(this)
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
            put(text("Ketik teks ini persis ($textLen huruf, huruf besar dan kecil dibedakan):", 13f))
            val shown = TextView(this)
            shown.text = code
            shown.textSize = 15f
            shown.typeface = Typeface.MONOSPACE
            shown.setTextColor(C_TEXT)
            shown.setPadding(dp(10), dp(8), dp(10), dp(8))
            put(scrollBox(if (textLen > 100) 170 else 90, shown))
            val counter = text("0/$textLen", 12f, true, C_SUB)
            put(counter)
            val et = EditText(this)
            et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            et.minLines = 3
            et.isLongClickable = false
            et.setText(gateTyped)
            counter.text = "${gateTyped.length}/$textLen"
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    gateTyped = s?.toString() ?: ""
                    counter.text = "${gateTyped.length}/$textLen"
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
                Store.setBlockOtherBrowsers(this, cb.isChecked)
            }
            render()
        }
        put(cb)
        val list = Store.unsupportedBrowsers(this)
        if (list.isEmpty()) {
            put(text("Tidak ada browser lain terdeteksi.", 12f, false, C_SUB))
        } else {
            for (p in list.sorted()) put(text("• " + appLabel(p), 12f, false, C_SUB))
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
            put(text("Agar aksesibilitas yang dimatikan otomatis menyala lagi, jalankan SEKALI dari PC (USB debugging aktif):", 12f))
            put(text("adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS", 12f).apply {
                setTextIsSelectable(true)
                setBackgroundColor(Color.rgb(235, 235, 235))
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
        val shown = if (appQuery.isEmpty()) others.take(15)
        else others.filter { it.label.lowercase().contains(appQuery) || it.pkg.contains(appQuery) }.take(40)
        if (appQuery.isEmpty() && others.size > 15) put(text("Menampilkan 15 pertama. Ketik nama untuk mencari yang lain.", 11f, false, C_SUB))
        for (a in shown) put(appRow(a, false, strict))
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
                Store.removeApp(this, a.pkg)
                render()
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
                        status.text = "Lokasi didapat (akurasi sekitar ${l.accuracy.toInt()} m)."
                    }
                }
            }
        }
        body.addView(useBtn)
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
        put(text("Saat Mode Ketat aktif, halaman Pengaturan atau pemasang yang menampilkan nama aplikasi ini (copot, hentikan paksa, matikan aksesibilitas) langsung diblokir. Ini bukan penguncian sistem, tapi penghalang yang kuat.", 12f))
        val chosen = Store.protectedApps(this)
        val installed = loadApps()
        val rows = installed.sortedWith(compareByDescending<AppRow> { it.pkg in chosen }.thenBy { it.label.lowercase() })
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
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
            col.addView(cb)
        }
        put(scrollBox(320, col))
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
        status(protectedOk, if (protectedOk) "Perlindungan aktif" else "Perlindungan belum lengkap")
        put(button("Lihat dashboard") { goScreen("stats") })
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
        val s = Dashboard.get(this, true)
        if (!s.usageAccess) {
            header("Akses Penggunaan")
            put(text("Aktifkan Akses Penggunaan agar pemakaian aplikasi tercatat. Angka pemakaian di bawah kosong sampai itu aktif.", 12f))
        }

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
        val maxD = Math.max(1L, s.days.maxOf { it.ms })
        for (d in s.days) put(text(barLine(d.label, d.ms, maxD, if (d.blocks > 0) " • ${d.blocks}x blokir" else ""), 11f))
        put(text("Total ${Dashboard.dur(s.weekMs)} • ${s.blocksWeek}x diblokir", 13f, true))
        if (s.prevWeekMs > 0L) {
            val pct = ((s.weekMs - s.prevWeekMs) * 100L / s.prevWeekMs).toInt()
            put(text("Minggu lalu ${Dashboard.dur(s.prevWeekMs)}: " + (if (pct >= 0) "naik $pct%" else "turun ${-pct}%"), 12f, false, C_SUB))
        } else {
            put(text("Perbandingan dengan minggu lalu muncul setelah ada data 14 hari.", 11f, false, C_SUB))
        }

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
            if (strict) toast("Mode Ketat aktif: statistik terkunci") else { StatsDb.clear(this); Dashboard.get(this, true); render() }
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
                    VpnManager.stop(this)
                    handler.postDelayed({ render() }, 500L)
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
            put(text("Peringatan: Private DNS di HP diatur ke nama host tertentu, sehingga melewati filter ini. Ubah ke Otomatis atau Mati di Pengaturan.", 12f, true, Color.rgb(180, 0, 0)))
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
        if (requestCode == 77 && resultCode == RESULT_OK) {
            VpnManager.start(this)
            handler.postDelayed({ render() }, 1000L)
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
            else { Store.removeUrlKeyword(this, k); render() }
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
            else { Store.removeScreenKeyword(this, k); render() }
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
                    else { Store.removeDomain(this@MainActivity, d); render() }
                }
            })
            siteCol.addView(row)
        }
        put(if (siteCol.childCount == 0) text("Belum ada.", 12f, false, C_SUB) else scrollBox(220, siteCol))
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
        header("Feed video pendek")
        put(text("Mencoba menutup Shorts YouTube dan Reels Instagram (Stories Instagram tidak ikut diblokir). Bergantung pada tampilan aplikasi, jadi bisa meleset setelah aplikasinya diperbarui.", 12f))
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
        put(CheckBox(this).apply {
            text = "Blokir Shorts YouTube"
            isChecked = s
            setOnClickListener { change(isChecked, r) }
        })
        put(CheckBox(this).apply {
            text = "Blokir Reels Instagram (bukan Stories)"
            isChecked = r
            setOnClickListener { change(s, isChecked) }
        })
    }
}
