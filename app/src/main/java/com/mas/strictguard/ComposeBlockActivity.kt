package com.mas.strictguard

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mas.strictguard.ui.screens.BlockScreen
import com.mas.strictguard.ui.theme.PenjagaKetatTheme

/**
 * Versi Compose dari BlockActivity.
 * Logic timer / emergency disederhanakan; sesuaikan dengan implementasi lama jika perlu.
 *
 * Cara pakai: di AndroidManifest ganti .BlockActivity menjadi .ComposeBlockActivity
 * atau biarkan BlockActivity lama memanggil setContent ini.
 */
class ComposeBlockActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var pkg = ""
    private var siteDomain = ""
    private var modeName = ""

    private var infoText by mutableStateOf("Konten ini diblokir oleh Penjaga Ketat.")
    private var reasonText by mutableStateOf("")
    private var motivation by mutableStateOf("")
    private var showOpen by mutableStateOf(false)
    private var openEnabled by mutableStateOf(false)
    private var showEmergency by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modeName = intent.getStringExtra("mode") ?: "content"
        pkg = intent.getStringExtra("pkg") ?: ""
        siteDomain = intent.getStringExtra("domain") ?: ""

        motivation = try {
            Store.motivation(this)
        } catch (_: Throwable) {
            ""
        }

        reasonText = buildReason(modeName)
        infoText = buildInfo(modeName)

        // Emergency button (sederhana)
        if (pkg.isNotEmpty()) {
            try {
                if (Emergency.canUse(this)) {
                    showEmergency = true
                }
            } catch (_: Throwable) {
            }
        }

        setContent {
            PenjagaKetatTheme {
                BlockScreen(
                    title = "Diblokir",
                    reason = reasonText,
                    info = infoText,
                    motivation = motivation,
                    showOpenButton = showOpen,
                    openEnabled = openEnabled,
                    showEmergency = showEmergency,
                    onOpen = { openTarget() },
                    onEmergency = { /* TODO: dialog emergency seperti BlockActivity lama */ },
                    onClose = { closeScreen() }
                )
            }
        }
    }

    private fun buildReason(mode: String): String {
        val target = when {
            siteDomain.isNotEmpty() -> siteDomain
            pkg.isNotEmpty() -> try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Throwable) {
                pkg
            }
            else -> ""
        }
        val cause = when (mode) {
            "until" -> "Blokir penuh sementara"
            "app" -> "Aturan blokir aplikasi"
            "limit" -> "Batas harian habis"
            "wait" -> "Timer Tunggu"
            "focus" -> "Mode Fokus"
            "profile" -> "Profil aktif"
            "site" -> "Aturan situs"
            "content" -> "Kata kunci / kategori konten"
            "protect" -> "Perlindungan Mode Ketat"
            else -> "Aturan Penjaga Ketat"
        }
        return (if (target.isNotEmpty()) "$target\n" else "") + "Diblokir oleh: $cause"
    }

    private fun buildInfo(mode: String): String = when (mode) {
        "protect" -> "Mode Ketat aktif.\nHalaman ini tidak bisa dibuka."
        "content" -> "Konten ini diblokir oleh Penjaga Ketat."
        "focus" -> "Mode Fokus aktif.\nAplikasi ini diblokir selama Fokus."
        "site" -> if (siteDomain.isNotEmpty()) "Situs ini sedang diblokir.\n$siteDomain" else "Situs ini sedang diblokir."
        "location" -> "Aplikasi ini diblokir berdasarkan lokasi kamu saat ini."
        else -> "Konten ini diblokir oleh Penjaga Ketat."
    }

    private fun openTarget() {
        try {
            val until = System.currentTimeMillis() + 5 * 60000L
            Store.setPass(this, pkg, until)
            Store.setLastPassEnd(this, pkg, until)
            Store.addPassHistory(this, pkg, 5)
        } catch (_: Throwable) {
        }
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i)
        finish()
    }

    private fun closeScreen() {
        if (modeName == "content" || modeName == "protect") {
            finish()
        } else {
            val i = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
