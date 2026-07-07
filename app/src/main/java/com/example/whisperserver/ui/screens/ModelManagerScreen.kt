package com.example.whisperserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.whisperserver.data.MemoryGuard
import com.example.whisperserver.data.MemoryVerdict
import com.example.whisperserver.data.StorageVerdict
import com.example.whisperserver.ui.DownloadUiState
import com.example.whisperserver.ui.ModelUiState
import com.example.whisperserver.ui.components.CompactCard
import com.example.whisperserver.ui.components.MiniToggle
import com.example.whisperserver.ui.components.ScreenHeader
import com.example.whisperserver.ui.theme.appColors

@Composable
fun ModelManagerScreen(
    models: List<ModelUiState>,
    onSelect: (ModelUiState) -> Unit,
    onDownload: (ModelUiState) -> Unit,
    onPause: (ModelUiState) -> Unit,
    onCancel: (ModelUiState) -> Unit,
    onDelete: (ModelUiState) -> Unit,
) {
    val c = appColors
    Column(Modifier.background(c.screen)) {
        ScreenHeader("Models") {
            val used = models.filter { it.isDownloaded }.sumOf { it.model.downloadSizeBytes }
            val free = models.firstOrNull()?.guard?.availableStorageBytes ?: 0L
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(c.accentChip)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.SdCard, contentDescription = null, tint = c.primary, modifier = Modifier.size(15.dp))
                Text(
                    "${MemoryGuard.humanBytes(used)} · ${MemoryGuard.humanBytes(free)} free",
                    color = c.accentChipText, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(models.size) { i ->
                ModelCard(models[i], onSelect, onDownload, onPause, onCancel, onDelete)
            }
        }
    }
}

@Composable
private fun ModelCard(
    row: ModelUiState,
    onSelect: (ModelUiState) -> Unit,
    onDownload: (ModelUiState) -> Unit,
    onPause: (ModelUiState) -> Unit,
    onCancel: (ModelUiState) -> Unit,
    onDelete: (ModelUiState) -> Unit,
) {
    val c = appColors
    val blocked = !row.guard.canProceed && !row.isDownloaded && row.download is DownloadUiState.Idle
    val highlighted = row.isSelected
    val border = when {
        highlighted -> c.primary.copy(alpha = 0.55f)
        else -> c.cardBorder
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card)
            .border(1.dp, border, RoundedCornerShape(12.dp)).padding(11.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            row.model.displayName,
                            color = if (blocked) c.textMuted else c.textPrimary,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                        if (row.isSelected) Badge("IN USE")
                    }
                    Text(
                        modelMeta(row),
                        color = if (blocked) c.textMuted else c.textSecondary,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                TopRightControl(row, onSelect, onDownload)
            }
            Spacer(Modifier.height(9.dp))
            BottomControls(row, onDownload, onPause, onCancel, onDelete)
        }
    }
}

@Composable
private fun TopRightControl(
    row: ModelUiState,
    onSelect: (ModelUiState) -> Unit,
    onDownload: (ModelUiState) -> Unit,
) {
    val c = appColors
    when (val dl = row.download) {
        is DownloadUiState.Downloading ->
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.primary)
        DownloadUiState.Idle -> {
            if (row.isDownloaded) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MiniToggle(checked = row.isSelected, onToggle = { onSelect(row) }, enabled = !row.isSelected)
                    Text(
                        if (row.isSelected) "Active" else "Off",
                        color = if (row.isSelected) c.primary else c.textMuted,
                        fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            } else if (row.guard.canProceed) {
                OutlinedPill("Download", Icons.Filled.Download, enabled = true) { onDownload(row) }
            } else {
                OutlinedPill("Unavailable", null, enabled = false) {}
            }
        }
        else -> Unit // Paused / Failed render their controls below.
    }
}

@Composable
private fun BottomControls(
    row: ModelUiState,
    onDownload: (ModelUiState) -> Unit,
    onPause: (ModelUiState) -> Unit,
    onCancel: (ModelUiState) -> Unit,
    onDelete: (ModelUiState) -> Unit,
) {
    val c = appColors
    when (val dl = row.download) {
        is DownloadUiState.Downloading -> {
            Column {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.divider)) {
                    Box(Modifier.fillMaxWidth(dl.progress.fraction).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.primary))
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${dl.progress.percent}% · ${MemoryGuard.humanBytes(dl.progress.downloadedBytes)} / " +
                            "${MemoryGuard.humanBytes(dl.progress.totalBytes)} · ${MemoryGuard.humanBytes(dl.progress.bytesPerSecond)}/s",
                        color = c.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    TextAction("Pause", c.primary) { onPause(row) }
                    Spacer(Modifier.width(12.dp))
                    TextAction("Cancel", c.textSecondary) { onCancel(row) }
                }
            }
        }
        is DownloadUiState.Paused -> {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Paused at ${MemoryGuard.humanBytes(dl.downloadedBytes)}",
                    color = c.textSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f),
                )
                if (row.guard.canProceed) TextAction("Resume", c.primary) { onDownload(row) }
                Spacer(Modifier.width(12.dp))
                TextAction("Cancel", c.textSecondary) { onCancel(row) }
            }
        }
        is DownloadUiState.Failed -> {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusLine(Icons.Filled.Error, c.error, dl.message.take(60))
                Spacer(Modifier.weight(1f))
                if (row.guard.canProceed) TextAction("Retry", c.primary) { onDownload(row) }
            }
        }
        DownloadUiState.Idle -> {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (row.isDownloaded) {
                    val loaded = if (row.isSelected) "loaded" else "ready"
                    StatusLine(Icons.Filled.CheckCircle, c.success, downloadedStatus(row) + " · " + loaded)
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.clickable { onDelete(row) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
                        Text(" Delete", color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    when {
                        row.guard.storage == StorageVerdict.BLOCK ->
                            StatusLine(Icons.Filled.Error, c.error, "Not enough storage for this model.")
                        row.guard.memory == MemoryVerdict.HARD_BLOCK ->
                            StatusLine(Icons.Filled.Error, c.error, "Needs more RAM than this device can allocate.")
                        row.guard.memory == MemoryVerdict.SOFT_WARNING ->
                            StatusLine(Icons.Filled.Warning, c.warn, "Tight on RAM — may slow other apps.")
                        else ->
                            StatusLine(Icons.Filled.CheckCircle, c.success, "Fits comfortably in memory.")
                    }
                }
            }
        }
    }
}

// ---- small building blocks --------------------------------------------------

@Composable
private fun Badge(text: String) {
    val c = appColors
    Text(
        text,
        color = c.runningChipText, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(c.runningChip).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun OutlinedPill(text: String, icon: ImageVector?, enabled: Boolean, onClick: () -> Unit) {
    val c = appColors
    val color = if (enabled) c.primary else c.textMuted
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, if (enabled) c.primary else c.chipOutline, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onClick))
}

@Composable
private fun StatusLine(icon: ImageVector, tint: Color, text: String) {
    val c = appColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(text, color = c.textSecondary, fontSize = 11.sp)
    }
}

private fun modelMeta(row: ModelUiState): String {
    val lang = if (row.model.multilingual) "multilingual" else "en"
    return "${MemoryGuard.humanBytes(row.model.downloadSizeBytes)} · " +
        "~${MemoryGuard.humanBytes(row.model.requiredRamBytes)} RAM · $lang"
}

private fun downloadedStatus(row: ModelUiState): String = when (row.guard.memory) {
    MemoryVerdict.OK -> "Fits comfortably"
    MemoryVerdict.SOFT_WARNING -> "Tight on RAM"
    MemoryVerdict.HARD_BLOCK -> "Over RAM budget"
}
