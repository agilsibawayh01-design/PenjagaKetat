package com.mas.strictguard

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class BlockActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var remaining = 0
    private lateinit var info: TextView
    private lateinit var motiv: TextView
    private lateinit var openBtn: Button
    private var pkg = ""
    private var passMin = 5
    private var modeName = ""
    private var siteDomain = ""
    private lateinit var emgBtn: Button
    private lateinit var why: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "content"
        pkg = intent.getStringExtra("pkg") ?: ""
        modeName = mode
        siteDomain = intent.getStringExtra("domain") ?: ""

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val cBg = if (night) Color.parseColor("#12161C") else Color.WHITE
        val cFg = if (night) Color.parseColor("#E8EAED") else Color.BLACK
        val cSub = if (night) Color.parseColor("#A0A9B5") else Color.rgb(70, 70, 70)
        val cRed = if (night) Color.parseColor("#FF6B6B") else Color.rgb(180, 0, 0)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(cBg)
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Diblokir"
            textSize = 28f
            setTextColor(cRed)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        why = TextView(this).apply {
            textSize = 14f
            setTextColor(cSub)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        info = TextView(this).apply {
            textSize = 16f
            setTextColor(cFg)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        motiv = TextView(this).apply {
            textSize = 14f
            setTextColor(cSub)
            setTypeface(null, Typeface.ITALIC)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
            text = Store.motivation(this@BlockActivity)
        }
        openBtn = Button(this).apply {
            text = "Buka aplikasi"
            visibility = View.GONE
            setOnClickListener { openTarget() }
        }
        emgBtn = Button(this).apply {
            text = "Darurat ${Emergency.MINUTES} menit"
            visibility = View.GONE
            setOnClickListener { emergencyDialog() }
        }
        val closeBtn = Button(this).apply {
            text = "Tutup"
            setOnClickListener { closeScreen() }
        }
        root.addView(title)
        root.addView(why)
        root.addView(info)
        root.addView(motiv)
        root.addView(openBtn)
        root.addView(emgBtn)
        root.addView(closeBtn)
        setContentView(root)

        when (mode) {
            "protect" -> info.text = "Mode Ketat aktif.\nHalaman ini tidak bisa dibuka."
            "content" -> info.text = "Konten ini diblokir oleh Penjaga Ketat."
            "screen_keyword" -> info.text = "Konten ini diblokir karena mengandung kata kunci layar yang dibatasi."
            "profile" -> info.text = "Aplikasi ini diblokir oleh profil yang sedang aktif."
            "focus" -> {
                val left = Focus.leftMs(this) / 60000L + 1L
                info.text = "Mode Fokus aktif.\nAplikasi ini diblokir selama Fokus.\nSisa sekitar $left menit."
            }
            "until" -> {
                val until = Store.appCfg(this, pkg).blockUntil
                val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                info.text = "Aplikasi ini diblokir penuh sampai " + f.format(java.util.Date(until)) + "."
            }
            "wait" -> {
                val g = Store.appCfg(this, pkg)
                WaitTimer.noteAttempt(this, pkg)
                showWaitCountdown(g)
            }
            "site" -> info.text = if (siteDomain.isNotEmpty()) "Situs ini sedang diblokir.\n$siteDomain" else "Situs ini sedang diblokir."
            "site_wait" -> {
                if (siteDomain.isNotEmpty()) SiteTracker.noteAttempt(this, siteDomain)
                showSiteWaitCountdown()
            }
            "location" -> info.text = "Aplikasi ini diblokir berdasarkan lokasi kamu saat ini."
            "browser" -> info.text = "Browser ini tidak didukung.\nGunakan Chrome saja."
            "hour" -> info.text = "Batas pemakaian per jam tercapai.\nCoba lagi nanti."
            "launch" -> info.text = "Batas jumlah peluncuran aplikasi ini tercapai."
            else -> startTimerFlow(mode)
        }
        why.text = reasonText(mode)
        setupEmergency(mode)
    }

    private fun appLabel(): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            pkg
        }

    /** Satu baris yang menyebut apa yang diblokir dan oleh aturan apa. */
    private fun reasonText(mode: String): String {
        val target = if (siteDomain.isNotEmpty()) siteDomain else if (pkg.isNotEmpty()) appLabel() else ""
        val cause = when (mode) {
            "until" -> "Blokir penuh sementara"
            "app" -> "Aturan blokir aplikasi"
            "limit" -> "Batas harian habis"
            "wait" -> "Timer Tunggu"
            "hour" -> "Batas pemakaian per jam"
            "launch" -> "Batas jumlah buka aplikasi"
            "location" -> "Aturan lokasi"
            "focus" -> "Mode Fokus"
            "profile" -> {
                val names = try {
                    ProfileStore.load(this).filter { pkg in it.apps && ProfileEngine.isActive(this, it) }.map { it.name }
                } catch (t: Throwable) {
                    emptyList()
                }
                if (names.isEmpty()) "Profil aktif" else "Profil " + names.joinToString(", ")
            }
            "site" -> "Aturan situs"
            "site_wait" -> "Timer Tunggu situs"
            "content" -> "Kata kunci atau kategori konten"
            "screen_keyword" -> "Kata kunci layar"
            "protect", "protect_click" -> "Perlindungan Mode Ketat"
            "browser" -> "Browser tidak didukung"
            else -> "Aturan Penjaga Ketat"
        }
        return (if (target.isNotEmpty()) target + "\n" else "") + "Diblokir oleh: " + cause
    }

    private fun setupEmergency(mode: String) {
        val ok = setOf("until", "app", "limit", "wait", "hour", "launch", "profile", "focus", "location")
        if (pkg.isEmpty() || mode !in ok) return
        if (Emergency.canUse(this)) {
            emgBtn.visibility = View.VISIBLE
        } else if (Emergency.usedToday(this)) {
            emgBtn.text = "Darurat hari ini sudah dipakai"
            emgBtn.isEnabled = false
            emgBtn.visibility = View.VISIBLE
        }
    }

    private fun emergencyDialog() {
        val labels = arrayOf("Telepon atau pesan penting", "Pekerjaan mendesak", "Navigasi / transportasi", "Lainnya")
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(TextView(this).apply {
            text = "Hanya 1 kali per hari, untuk 1 aplikasi, selama ${Emergency.MINUTES} menit. Alasannya dicatat."
            textSize = 14f
        })
        val group = android.widget.RadioGroup(this)
        for ((i, l) in labels.withIndex()) {
            group.addView(android.widget.RadioButton(this).apply {
                text = l
                id = 7100 + i
            })
        }
        box.addView(group)
        val note = android.widget.EditText(this).apply {
            hint = "Catatan (boleh dikosongkan)"
            setSingleLine(true)
        }
        box.addView(note)
        android.app.AlertDialog.Builder(this)
            .setTitle("Darurat ${Emergency.MINUTES} menit")
            .setView(box)
            .setPositiveButton("Oke", null)
            .setNegativeButton("Batal", null)
            .create()
            .also { d ->
                d.setOnShowListener {
                    d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val idx = group.checkedRadioButtonId - 7100
                        if (idx < 0 || idx >= labels.size) {
                            android.widget.Toast.makeText(this, "Pilih salah satu alasan", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            val extra = note.text.toString().trim().take(60)
                            val reason = labels[idx] + (if (extra.isNotEmpty()) " - $extra" else "")
                            d.dismiss()
                            if (Emergency.grant(this, pkg, reason)) {
                                val intent = packageManager.getLaunchIntentForPackage(pkg)
                                if (intent != null) startActivity(intent)
                                finish()
                            } else {
                                emgBtn.visibility = View.GONE
                            }
                        }
                    }
                }
            }
            .show()
    }


    private fun showSiteWaitCountdown() {
        val g = Store.siteCfg(this, siteDomain)
        val left = SiteTracker.remaining(this, siteDomain, g, System.currentTimeMillis())
        if (left <= 0L) {
            info.text = "Waktu tunggu selesai.\nKamu bisa mencoba membuka situs lagi."
            openBtn.visibility = View.GONE
            TimerNotifier.cancel(this)
            return
        }
        val total = left / 1000L
        val min = total / 60L
        val sec = total % 60L
        info.text = String.format(
            java.util.Locale.getDefault(),
            "%s\nTimer tunggu aktif.\nSisa waktu: %02d:%02d\n\nSitus akan tetap diblokir sampai timer selesai.",
            siteDomain, min, sec
        )
        handler.postDelayed({ showSiteWaitCountdown() }, 1000L)
    }

    private fun showWaitCountdown(g: AppCfg) {
        val left = WaitTimer.remaining(this, pkg, g, System.currentTimeMillis())
        if (left <= 0L) {
            info.text = "Waktu tunggu selesai.\nKamu bisa mencoba membuka aplikasi lagi."
            openBtn.visibility = View.GONE
            return
        }
        val total = left / 1000L
        val min = total / 60L
        val sec = total % 60L
        info.text = String.format(
            java.util.Locale.getDefault(),
            "Timer tunggu aktif.\nSisa waktu: %02d:%02d\n\nAplikasi akan tetap diblokir sampai timer selesai.",
            min, sec
        )
        handler.postDelayed({ showWaitCountdown(g) }, 1000L)
    }

    private fun startTimerFlow(mode: String) {
        val cfg = Store.appCfg(this, pkg)
        passMin = cfg.pass
        val head = if (mode == "limit") "Batas harian habis.\n" else ""
        val cooldownLeft = Store.lastPassEnd(this, pkg) + cfg.cooldown * 60000L - System.currentTimeMillis()
        if (cfg.cooldown > 0 && cooldownLeft > 0) {
            info.text = head + "Cooldown: tunggu " + (cooldownLeft / 60000L + 1) + " menit sebelum bisa meminta pass lagi."
            return
        }
        if (cfg.wait <= 0) {
            info.text = head + "Aplikasi ini sedang diblokir."
            return
        }
        remaining = cfg.wait
        openBtn.visibility = View.VISIBLE
        openBtn.isEnabled = false
        tick(head)
    }

    private fun tick(head: String) {
        if (remaining > 0) {
            info.text = head + "Tunggu " + remaining + " detik.\nApakah kamu benar-benar perlu membuka aplikasi ini?"
            remaining--
            handler.postDelayed({ tick(head) }, 1000L)
        } else {
            info.text = head + "Waktu tunggu selesai.\nAplikasi terbuka " + passMin + " menit."
            openBtn.isEnabled = true
        }
    }

    private fun openTarget() {
        val until = System.currentTimeMillis() + passMin * 60000L
        Store.setPass(this, pkg, until)
        Store.setLastPassEnd(this, pkg, until)
        Store.addPassHistory(this, pkg, passMin)
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i)
        finish()
    }

    private fun goHome() {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        finish()
    }

    /** Situs, kata kunci, dan perlindungan: cukup tutup layar blokir (halaman sebelumnya sudah dikembalikan). Lainnya: ke layar utama. */
    private fun closeScreen() {
        if (modeName == "content" || modeName == "protect") finish() else goHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        closeScreen()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
