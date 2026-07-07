package com.example.whisperserver.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.whisperserver.data.MemoryGuard
import com.example.whisperserver.service.ServerState
import com.example.whisperserver.service.ServerStats
import com.example.whisperserver.ui.components.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun StatsScreen(
    stats: ServerStats,
    serverState: ServerState,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        SectionCard("Runtime") {
            StatRow("Status", statusLabel(serverState))
            StatRow("Uptime", if (stats.startedAtMillis > 0) formatDuration(stats.uptimeMillis) else "—")
            StatRow("Memory in use", if (stats.memoryUsageBytes > 0) MemoryGuard.humanBytes(stats.memoryUsageBytes) else "—")
        }
        SectionCard("Requests") {
            StatRow("Requests served", stats.requestsServed.toString())
            StatRow("Last request", formatTimestamp(stats.lastRequestAtMillis))
            StatRow(
                "Avg processing time",
                if (stats.avgProcessingMillis > 0) "${stats.avgProcessingMillis} ms" else "—",
            )
        }
        Text(
            "Request stats are parsed best-effort from server logs and reset when the server restarts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun statusLabel(state: ServerState): String = when (state) {
    is ServerState.Running -> "Running (${state.host}:${state.port})"
    ServerState.Starting -> "Starting"
    ServerState.Restarting -> "Restarting"
    is ServerState.Error -> "Error"
    ServerState.Stopped -> "Stopped"
}

private val dateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)

private fun formatTimestamp(millis: Long): String =
    if (millis <= 0) "—" else dateFormat.format(Date(millis))

private fun formatDuration(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return when {
        hours > 0 -> "%dh %02dm %02ds".format(hours, minutes, seconds)
        minutes > 0 -> "%dm %02ds".format(minutes, seconds)
        else -> "%ds".format(seconds)
    }
}
