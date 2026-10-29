package com.mas.strictguard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mas.strictguard.ui.theme.DangerLight
import com.mas.strictguard.ui.theme.SuccessLight
import com.mas.strictguard.ui.theme.Warning

enum class StatusKind { Ok, Warn, Bad, Neutral }

@Composable
fun StatusPill(
    text: String,
    kind: StatusKind,
    modifier: Modifier = Modifier
) {
    val (bg, fg, dot) = when (kind) {
        StatusKind.Ok -> Triple(
            SuccessLight.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.tertiary,
            SuccessLight
        )
        StatusKind.Warn -> Triple(
            Warning.copy(alpha = 0.18f),
            Warning,
            Warning
        )
        StatusKind.Bad -> Triple(
            DangerLight.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.error,
            DangerLight
        )
        StatusKind.Neutral -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = fg
        )
    }
}
