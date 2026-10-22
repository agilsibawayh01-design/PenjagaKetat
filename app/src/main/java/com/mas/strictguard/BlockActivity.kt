package com.mas.strictguard

import android.app.Activity
import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "content"
        pkg = intent.getStringExtra("pkg") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Diblokir"
            textSize = 28f
            setTextColor(Color.rgb(180, 0, 0))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        info = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        motiv = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(70, 70, 70))
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
        val closeBtn = Button(this).apply {
            text = "Tutup"
            setOnClickListener { goHome() }
        }
        root.addView(title)
        root.addView(info)
        root.addView(motiv)
        root.addView(openBtn)
        root.addView(closeBtn)
        setContentView(root)

        when (mode) {
            "protect" -> info.text = "Mode Ketat aktif.\nHalaman ini tidak bisa dibuka."
            "content" -> info.text = "Konten ini diblokir oleh Penjaga Ketat."
            "profile" -> info.text = "Aplikasi ini diblokir oleh profil yang sedang aktif."
            "until" -> {
                val until = Store.appCfg(this, pkg).blockUntil
                val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                info.text = "Aplikasi ini diblokir penuh sampai " + f.format(java.util.Date(until)) + "."
            }
            "wait" -> {
                val g = Store.appCfg(this, pkg)
                WaitTimer.noteAttempt(this, pkg)
                val left = WaitTimer.remaining(this, pkg, g, System.currentTimeMillis())
                info.text = "Timer tunggu aktif.\nAplikasi bisa dibuka lagi sekitar " + (left / 60000L + 1) + " menit lagi."
            }
            "hour" -> info.text = "Batas pemakaian per jam tercapai.\nCoba lagi nanti."
            "launch" -> info.text = "Batas jumlah peluncuran aplikasi ini tercapai."
            else -> startTimerFlow(mode)
        }
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goHome()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
