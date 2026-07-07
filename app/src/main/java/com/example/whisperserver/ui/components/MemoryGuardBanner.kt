package com.example.whisperserver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.example.whisperserver.data.MemoryGuardResult
import com.example.whisperserver.data.MemoryVerdict
import com.example.whisperserver.data.StorageVerdict
import com.example.whisperserver.ui.theme.GuardColors

/**
 * Colored banner summarizing the memory/storage guard verdict for a model:
 * green (OK), yellow (soft warning), red (hard block / storage block).
 */
@Composable
fun MemoryGuardBanner(
    guard: MemoryGuardResult,
    modifier: Modifier = Modifier,
) {
    val (color, icon) = bannerStyle(guard)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
        )
        Text(
            text = guard.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private fun bannerStyle(guard: MemoryGuardResult): Pair<Color, ImageVector> {
    if (guard.storage == StorageVerdict.BLOCK) {
        return GuardColors.Block to Icons.Filled.Error
    }
    return when (guard.memory) {
        MemoryVerdict.OK -> GuardColors.Ok to Icons.Filled.CheckCircle
        MemoryVerdict.SOFT_WARNING -> GuardColors.Warn to Icons.Filled.Warning
        MemoryVerdict.HARD_BLOCK -> GuardColors.Block to Icons.Filled.Error
    }
}
