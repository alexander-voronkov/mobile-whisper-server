package com.example.whisperserver.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.whisperserver.data.MemoryGuard
import com.example.whisperserver.ui.DownloadUiState
import com.example.whisperserver.ui.ModelUiState
import com.example.whisperserver.ui.components.MemoryGuardBanner

@Composable
fun ModelManagerScreen(
    models: List<ModelUiState>,
    onSelect: (ModelUiState) -> Unit,
    onDownload: (ModelUiState) -> Unit,
    onPause: (ModelUiState) -> Unit,
    onCancel: (ModelUiState) -> Unit,
    onDelete: (ModelUiState) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text(
                "Select and download a model. The active model (radio) is used when the server starts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(models, key = { it.model.id }) { row ->
            ModelRow(row, onSelect, onDownload, onPause, onCancel, onDelete)
        }
    }
}

@Composable
private fun ModelRow(
    row: ModelUiState,
    onSelect: (ModelUiState) -> Unit,
    onDownload: (ModelUiState) -> Unit,
    onPause: (ModelUiState) -> Unit,
    onCancel: (ModelUiState) -> Unit,
    onDelete: (ModelUiState) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = row.isSelected,
                    enabled = row.isDownloaded,
                    onClick = { onSelect(row) },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        row.model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${MemoryGuard.humanBytes(row.model.downloadSizeBytes)} download • " +
                            "~${MemoryGuard.humanBytes(row.model.requiredRamBytes)} RAM • " +
                            if (row.model.multilingual) "multilingual" else "English only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            MemoryGuardBanner(row.guard, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))

            when (val dl = row.download) {
                is DownloadUiState.Downloading -> DownloadingRow(dl, onPause = { onPause(row) }, onCancel = { onCancel(row) })
                is DownloadUiState.Paused -> PausedRow(dl, row, onResume = { onDownload(row) }, onCancel = { onCancel(row) })
                is DownloadUiState.Failed -> FailedRow(dl, row, onRetry = { onDownload(row) })
                DownloadUiState.Idle -> IdleRow(row, onDownload = { onDownload(row) }, onDelete = { onDelete(row) })
            }
        }
    }
}

@Composable
private fun DownloadingRow(dl: DownloadUiState.Downloading, onPause: () -> Unit, onCancel: () -> Unit) {
    Column {
        LinearProgressIndicator(
            progress = { dl.progress.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${dl.progress.percent}% • ${MemoryGuard.humanBytes(dl.progress.bytesPerSecond)}/s",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = onPause) { Text("Pause") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun PausedRow(dl: DownloadUiState.Paused, row: ModelUiState, onResume: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Paused at ${MemoryGuard.humanBytes(dl.downloadedBytes)}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onResume, enabled = row.guard.canProceed) { Text("Resume") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun FailedRow(dl: DownloadUiState.Failed, row: ModelUiState, onRetry: () -> Unit) {
    Column {
        Text(dl.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry, enabled = row.guard.canProceed) { Text("Retry") }
    }
}

@Composable
private fun IdleRow(row: ModelUiState, onDownload: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (row.isDownloaded) {
            Text(
                "Downloaded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).padding(top = 12.dp),
            )
            OutlinedButton(onClick = onDelete) { Text("Delete") }
        } else {
            OutlinedButton(
                onClick = onDownload,
                enabled = row.guard.canProceed,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (row.guard.canProceed) "Download" else "Unavailable")
            }
        }
    }
}
