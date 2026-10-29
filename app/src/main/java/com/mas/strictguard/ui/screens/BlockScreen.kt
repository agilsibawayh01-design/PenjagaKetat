package com.mas.strictguard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mas.strictguard.ui.theme.Danger
import com.mas.strictguard.ui.theme.DangerLight

/**
 * Layar blokir modern (ganti UI BlockActivity).
 * Logic timer / emergency / open tetap di Activity; screen ini hanya tampilan.
 */
@Composable
fun BlockScreen(
    title: String = "Diblokir",
    reason: String = "",
    info: String = "Konten ini diblokir oleh Penjaga Ketat.",
    motivation: String = "",
    showOpenButton: Boolean = false,
    openEnabled: Boolean = false,
    openLabel: String = "Buka aplikasi",
    showEmergency: Boolean = false,
    emergencyLabel: String = "Darurat 5 menit",
    onOpen: () -> Unit = {},
    onEmergency: () -> Unit = {},
    onClose: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Block,
            contentDescription = null,
            tint = DangerLight,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = Danger,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        if (reason.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = info,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        if (motivation.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = motivation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Light
            )
        }
        Spacer(Modifier.height(32.dp))

        if (showOpenButton) {
            Button(
                onClick = onOpen,
                enabled = openEnabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(openLabel)
            }
            Spacer(Modifier.height(10.dp))
        }

        if (showEmergency) {
            OutlinedButton(
                onClick = onEmergency,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(emergencyLabel)
            }
            Spacer(Modifier.height(10.dp))
        }

        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Text("Tutup")
        }
    }
}
