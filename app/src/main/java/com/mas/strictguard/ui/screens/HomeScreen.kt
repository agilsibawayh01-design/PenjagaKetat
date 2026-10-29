package com.mas.strictguard.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mas.strictguard.ui.components.PkCard
import com.mas.strictguard.ui.components.StatusKind
import com.mas.strictguard.ui.components.StatusPill
import com.mas.strictguard.ui.theme.Primary
import com.mas.strictguard.ui.theme.PrimaryLight

/**
 * Home screen modern.
 * Data status (strict, accessibility, admin) diisi dari luar
 * supaya tetap kompatibel dengan logic Store / AntiBypass yang sudah ada.
 */
@Composable
fun HomeScreen(
    modeLabel: String = "Normal",
    accessibilityOk: Boolean = false,
    adminOk: Boolean = false,
    todayUsageText: String = "—",
    blocksToday: Int = 0,
    onOpenAccessibility: () -> Unit = {},
    onRequestAdmin: () -> Unit = {},
    onOpenMode: () -> Unit = {},
    onOpenApps: () -> Unit = {},
    onOpenSites: () -> Unit = {},
    onOpenFocus: () -> Unit = {}
) {
    val context = LocalContext.current
    val fullProtection = accessibilityOk && adminOk

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Primary, PrimaryLight)
                    )
                )
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(48.dp)
                )
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(
                        text = "Penjaga Ketat",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = modeLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusPill(
                        text = if (fullProtection) "Perlindungan lengkap" else "Perlu tindakan",
                        kind = if (fullProtection) StatusKind.Ok else StatusKind.Warn
                    )
                }
            }
        }

        // Action needed
        if (!fullProtection) {
            PkCard(
                title = "Perlu tindakan",
                subtitle = "Selesaikan agar perlindungan aktif penuh."
            ) {
                if (!accessibilityOk) {
                    Text(
                        text = "Aksesibilitas belum aktif",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onOpenAccessibility()
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Buka pengaturan Aksesibilitas")
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (!adminOk) {
                    Text(
                        text = "Perlindungan uninstall belum aktif",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onRequestAdmin,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Aktifkan perlindungan uninstall")
                    }
                }
            }
        }

        // Today summary
        PkCard(title = "Hari ini") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Pemakaian",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = todayUsageText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Diblokir",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$blocksToday",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Quick actions
        PkCard(title = "Aksi cepat") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickChip("Mode", onOpenMode, Modifier.weight(1f))
                QuickChip("Aplikasi", onOpenApps, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickChip("Situs", onOpenSites, Modifier.weight(1f))
                QuickChip("Fokus", onOpenFocus, Modifier.weight(1f))
            }
        }

        // Tip
        PkCard {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Mode Ketat mengunci pengaturan agar sulit dimatikan saat godaan datang. Aktifkan setelah semua izin siap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QuickChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(
            imageVector = Icons.Outlined.Shield,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "  $label",
            style = MaterialTheme.typography.labelLarge
        )
    }
}
