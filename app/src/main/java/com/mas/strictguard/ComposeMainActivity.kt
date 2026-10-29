package com.mas.strictguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mas.strictguard.ui.screens.HomeScreen
import com.mas.strictguard.ui.screens.PlaceholderScreen
import com.mas.strictguard.ui.theme.PenjagaKetatTheme

/**
 * Entry point UI modern (Compose + Material 3).
 *
 * Cara pakai sementara:
 * 1. Ganti android:name di AndroidManifest dari .MainActivity menjadi .ComposeMainActivity
 *    ATAU ubah MainActivity agar extend ComponentActivity dan panggil setContent seperti di bawah.
 * 2. Data status (mode, accessibility, admin, stats) masih perlu diisi dari Store / logic lama.
 * 3. Layar Apps / Sites / Focus / Settings masih placeholder — port bertahap.
 */
class ComposeMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seed & safety checks (sama seperti MainActivity lama)
        try {
            Store.seed(this)
            AntiBypass.checkWiped(this)
            AntiBypass.syncMarker(this)
            if (Store.strictRaw(this) && !WatchdogService.running) {
                WatchdogService.start(this)
            }
            VpnManager.ensure(this)
        } catch (_: Throwable) {
            // ignore jika class belum siap
        }

        setContent {
            PenjagaKetatTheme {
                var selected by remember { mutableIntStateOf(0) }
                val tabs = listOf("Beranda", "Aplikasi", "Situs", "Fokus", "Lainnya")

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            tabs.forEachIndexed { index, label ->
                                NavigationBarItem(
                                    selected = selected == index,
                                    onClick = { selected = index },
                                    icon = {
                                        Icon(
                                            imageVector = when (index) {
                                                0 -> Icons.Filled.Home
                                                1 -> Icons.Filled.Apps
                                                2 -> Icons.Filled.Language
                                                3 -> Icons.Filled.Timer
                                                else -> Icons.Filled.Settings
                                            },
                                            contentDescription = label
                                        )
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                ) { inner ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(inner)) {
                        when (selected) {
                            0 -> {
                                // TODO: ganti nilai statis dengan data real dari Store / Dashboard
                                val mode = try {
                                    when {
                                        Store.strictOn(this@ComposeMainActivity) -> "Mode Ketat"
                                        else -> "Normal"
                                    }
                                } catch (_: Throwable) {
                                    "Normal"
                                }
                                val acc = try {
                                    // sesuaikan dengan helper yang kamu punya
                                    true
                                } catch (_: Throwable) {
                                    false
                                }
                                val adm = try {
                                    true
                                } catch (_: Throwable) {
                                    false
                                }
                                HomeScreen(
                                    modeLabel = mode,
                                    accessibilityOk = acc,
                                    adminOk = adm,
                                    todayUsageText = "—",
                                    blocksToday = 0,
                                    onRequestAdmin = {
                                        // panggil requestAdmin() dari logic lama jika ada
                                    }
                                )
                            }
                            1 -> PlaceholderScreen("Aplikasi")
                            2 -> PlaceholderScreen("Situs")
                            3 -> PlaceholderScreen("Mode Fokus")
                            else -> PlaceholderScreen("Pengaturan & lainnya")
                        }
                    }
                }
            }
        }
    }
}
