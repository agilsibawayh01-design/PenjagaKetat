package com.mas.strictguard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class BlockActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var remaining = 0
    private lateinit var info: TextView
    private lateinit var openBtn: Button
    private var pkg = ""

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
        openBtn = Button(this).apply {
            text = "Buka aplikasi"
            visibility = android.view.View.GONE
            setOnClickListener { openTarget() }
        }
        val closeBtn = Button(this).apply {
            text = "Tutup"
            setOnClickListener { goHome() }
        }
        root.addView(title)
        root.addView(info)
        root.addView(openBtn)
        root.addView(closeBtn)
        setContentView(root)

        when (mode) {
            "protect" -> info.text = "Mode Ketat aktif.\nHalaman pengaturan ini tidak bisa dibuka."
            "content" -> info.text = "Konten ini diblokir oleh Penjaga Ketat."
            else -> {
                val wait = Store.waitSeconds(this)
                if (wait <= 0) {
                    info.text = "Aplikasi ini sedang diblokir."
                } else {
                    remaining = wait
                    openBtn.visibility = android.view.View.VISIBLE
                    openBtn.isEnabled = false
                    tick()
                }
            }
        }
    }

    private fun tick() {
        if (remaining > 0) {
            info.text = "Tunggu $remaining detik.\nApakah kamu benar-benar perlu membuka aplikasi ini?"
            remaining--
            handler.postDelayed({ tick() }, 1000L)
        } else {
            info.text = "Waktu tunggu selesai.\nAplikasi terbuka ${Store.passMinutes(this)} menit."
            openBtn.isEnabled = true
        }
    }

    private fun openTarget() {
        Store.setPass(this, pkg, System.currentTimeMillis() + Store.passMinutes(this) * 60000L)
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
