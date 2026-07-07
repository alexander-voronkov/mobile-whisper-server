package com.example.whisperserver.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.whisperserver.data.MemoryGuard
import com.example.whisperserver.data.ServerConfig
import com.example.whisperserver.service.ServerState
import com.example.whisperserver.service.ServerStats
import com.example.whisperserver.service.TranscriptionRecord
import com.example.whisperserver.ui.components.CompactCard
import com.example.whisperserver.ui.components.RowDivider
import com.example.whisperserver.ui.components.ScreenHeader
import com.example.whisperserver.ui.theme.appColors

@Composable
fun DashboardScreen(
    serverState: ServerState,
    stats: ServerStats,
    records: List<TranscriptionRecord>,
    tailscaleIp: String?,
    config: ServerConfig,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenRecord: (TranscriptionRecord) -> Unit,
    onOpenJournal: () -> Unit,
) {
    val c = appColors
    Column(Modifier.fillMaxSize().background(c.screen)) {
        ScreenHeader("Dashboard") {
            ServerStatusChip(serverState, config, onStart, onStop)
        }
        EndpointRow(serverState, config, tailscaleIp, onRestart)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Show the actionable reason on failure (missing model/binary, bind
            // error, crash budget exhausted) — otherwise only the "Error" chip
            // would appear and the user couldn't tell why startup failed.
            (serverState as? ServerState.Error)?.let { err -> ErrorBanner(err.message) }

            // KPI tiles (2x2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KpiTile("Requests", stats.requestsServed.toString(), Modifier.weight(1f))
                KpiTile(
                    "Success",
                    stats.successRatePercent?.let { "$it%" } ?: "—",
                    Modifier.weight(1f),
                    valueColor = if (stats.successRatePercent != null) c.success else c.textPrimary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KpiTile(
                    "Avg time",
                    if (stats.avgProcessingMillis > 0) formatDuration(stats.avgProcessingMillis) else "—",
                    Modifier.weight(1f),
                )
                KpiTile(
                    "Memory",
                    if (stats.memoryUsageBytes > 0) MemoryGuard.humanBytes(stats.memoryUsageBytes) else "—",
                    Modifier.weight(1f),
                )
            }

            RequestsChartCard(records, config.selectedModelId)
            RecentCard(records, onOpenRecord, onOpenJournal)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ServerStatusChip(
    serverState: ServerState,
    config: ServerConfig,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val c = appColors
    val running = serverState as? ServerState.Running
    val port = running?.port ?: config.port
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val (label, dotColor, bg, fg) = when (serverState) {
            is ServerState.Running -> Quad("Running", c.runningDot, c.runningChip, c.runningChipText)
            ServerState.Starting -> Quad("Starting", c.warn, c.accentChip, c.accentChipText)
            ServerState.Restarting -> Quad("Restarting", c.warn, c.accentChip, c.accentChipText)
            is ServerState.Error -> Quad("Error", c.error, c.accentChip, c.error)
            ServerState.Stopped -> Quad("Stopped", c.textMuted, c.accentChip, c.textSecondary)
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (running != null) {
                Text(":$port", color = fg, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        if (serverState.isActive) {
            PillButton("Stop", filled = false, onClick = onStop)
        } else {
            PillButton("Start", filled = true, onClick = onStart)
        }
    }
}

@Composable
private fun EndpointRow(
    serverState: ServerState,
    config: ServerConfig,
    tailscaleIp: String?,
    onRestart: () -> Unit,
) {
    val c = appColors
    val running = serverState as? ServerState.Running
    val host = running?.host ?: config.host
    val port = running?.port ?: config.port
    val reachable = when {
        host != "0.0.0.0" -> host
        tailscaleIp != null -> tailscaleIp
        else -> "<phone-ip>"
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "http://$reachable:$port/v1/audio/transcriptions",
            color = c.textSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (serverState.isActive) {
            Text(
                "Restart",
                color = c.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onRestart).padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun KpiTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: androidx.compose.ui.graphics.Color = appColors.textPrimary) {
    CompactCard(modifier, padding = androidx.compose.foundation.layout.PaddingValues(11.dp)) {
        Column {
            Text(
                label.uppercase(),
                color = appColors.textSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
            )
            Text(value, color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    val c = appColors
    CompactCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Filled.Error,
                contentDescription = null,
                tint = c.error,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("Server error", color = c.error, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    message,
                    color = c.textPrimary,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun RequestsChartCard(records: List<TranscriptionRecord>, modelId: String) {
    val c = appColors
    CompactCard(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    "Requests / hour",
                    color = c.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "24h · ${modelId.ifBlank { "—" }}",
                    color = c.textSecondary,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            val buckets = hourlyBuckets(records)
            Canvas(Modifier.fillMaxWidth().height(66.dp)) {
                val max = (buckets.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
                val n = buckets.size
                val stepX = if (n > 1) size.width / (n - 1) else size.width
                val pts = buckets.mapIndexed { i, v ->
                    Offset(i * stepX, size.height - (v / max) * size.height)
                }
                val line = Path().apply {
                    pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(area, c.primary.copy(alpha = 0.12f))
                drawPath(line, c.primary, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun RecentCard(
    records: List<TranscriptionRecord>,
    onOpenRecord: (TranscriptionRecord) -> Unit,
    onOpenJournal: () -> Unit,
) {
    val c = appColors
    CompactCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "RECENT",
                    color = c.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "All →",
                    color = c.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onOpenJournal),
                )
            }
            if (records.isEmpty()) {
                Text(
                    "No requests yet.",
                    color = c.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            } else {
                records.take(4).forEach { r ->
                    RowDivider()
                    RecentRow(r) { onOpenRecord(r) }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(record: TranscriptionRecord, onClick: () -> Unit) {
    val c = appColors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (record.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (record.success) c.success else c.error,
            modifier = Modifier.size(16.dp),
        )
        Text(
            record.summary,
            color = c.textPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${shortIp(record.remoteAddress)} · ${if (record.success) formatDuration(record.processingMillis) else "—"}",
            color = c.textSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

// ---- helpers ----------------------------------------------------------------

private data class Quad(
    val label: String,
    val dot: androidx.compose.ui.graphics.Color,
    val bg: androidx.compose.ui.graphics.Color,
    val fg: androidx.compose.ui.graphics.Color,
)

@Composable
private fun PillButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val c = appColors
    if (filled) {
        Text(
            text,
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(c.primary)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    } else {
        Text(
            text,
            color = c.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(c.card)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Count of requests in each of the last 24 hourly buckets (oldest first). */
private fun hourlyBuckets(records: List<TranscriptionRecord>): IntArray {
    val now = System.currentTimeMillis()
    val hourMs = 3_600_000L
    val buckets = IntArray(24)
    records.forEach { r ->
        val age = now - r.timestampMillis
        if (age in 0 until 24 * hourMs) {
            val idx = 23 - (age / hourMs).toInt()
            if (idx in 0..23) buckets[idx]++
        }
    }
    return buckets
}

/** "100.98.12.4" -> ".12.4" (last two octets); other forms passed through short. */
private fun shortIp(address: String): String {
    val parts = address.split('.')
    return if (parts.size == 4) "." + parts.takeLast(2).joinToString(".") else address.take(14)
}
