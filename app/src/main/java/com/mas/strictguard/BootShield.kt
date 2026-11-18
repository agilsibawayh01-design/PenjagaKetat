package com.mas.strictguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Perisai awal boot: begitu layanan Aksesibilitas aktif setelah HP dinyalakan, layar ditutup penuh selama
 * [SECONDS] detik supaya pengaturan atau aplikasi tidak bisa diotak-atik selagi perlindungan dipulihkan.
 * Memakai jendela TYPE_ACCESSIBILITY_OVERLAY (tidak butuh izin tampil di atas aplikasi lain).
 * Hanya sekali per boot, tidak pernah lebih dari SECONDS detik, dan tidak pernah melempar exception.
 */
object BootShield {
    const val SECONDS = 10
    private val ui = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var tv: TextView? = null
    private var wm: WindowManager? = null

    private fun sp(c: Context) = c.getSharedPreferences("bootshield", Context.MODE_PRIVATE)
    fun enabled(c: Context): Boolean = sp(c).getBoolean("on", true)
    fun setEnabled(c: Context, v: Boolean) { sp(c).edit().putBoolean("on", v).commit() }

    fun maybeShow(svc: AccessibilityService) {
        try {
            if (!enabled(svc) || view != null) return
            val boots = Settings.Global.getInt(svc.contentResolver, Settings.Global.BOOT_COUNT, -1)
            if (boots < 0) return
            val p = sp(svc)
            val last = p.getInt("boot_seen", -1)
            if (last == boots) return
            p.edit().putInt("boot_seen", boots).commit()
            // Pertama kali (instalasi baru) atau layanan baru menyala lama setelah boot: jangan menutup layar.
            if (last == -1 || android.os.SystemClock.elapsedRealtime() > 180_000L) return
            show(svc)
        } catch (t: Throwable) {
            // jangan pernah menggagalkan layanan
        }
    }

    private fun show(svc: AccessibilityService) {
        val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = svc.resources.displayMetrics.density
        val box = LinearLayout(svc)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setBackgroundColor(Color.parseColor("#0A0C10"))
        box.setPadding((24 * d).toInt(), 0, (24 * d).toInt(), 0)
        val title = TextView(svc)
        title.text = "Penjaga Ketat sedang memulihkan perlindungan"
        title.setTextColor(Color.parseColor("#F1ECE0"))
        title.textSize = 20f
        title.gravity = Gravity.CENTER
        val t = TextView(svc)
        t.setTextColor(Color.parseColor("#D4AF6A"))
        t.textSize = 44f
        t.gravity = Gravity.CENTER
        t.setPadding(0, (18 * d).toInt(), 0, (8 * d).toInt())
        val sub = TextView(svc)
        sub.text = "Layar terbuka lagi otomatis."
        sub.setTextColor(Color.parseColor("#A9A396"))
        sub.textSize = 13f
        sub.gravity = Gravity.CENTER
        box.addView(title)
        box.addView(t)
        box.addView(sub)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        )
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        w.addView(box, lp)
        wm = w
        view = box
        tv = t
        var left = SECONDS
        val tick = object : Runnable {
            override fun run() {
                if (left <= 0) {
                    hide()
                    return
                }
                tv?.text = left.toString()
                // Rapikan bila ada yang menarik panel notifikasi atau menekan Home selagi perisai tampil.
                try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) } catch (e: Throwable) { }
                left--
                ui.postDelayed(this, 1000L)
            }
        }
        ui.post(tick)
        Store.log(svc, "Perisai awal boot tampil $SECONDS detik")
    }

    fun hide() {
        try {
            ui.removeCallbacksAndMessages(null)
            view?.let { wm?.removeView(it) }
        } catch (t: Throwable) {
            // abaikan
        }
        view = null
        tv = null
    }
}
