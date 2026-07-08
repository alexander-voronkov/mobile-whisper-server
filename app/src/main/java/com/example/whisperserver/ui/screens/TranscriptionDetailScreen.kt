package com.example.whisperserver.ui.screens

import android.media.MediaPlayer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.whisperserver.data.ModelRegistry
import com.example.whisperserver.service.TranscriptionRecord
import com.example.whisperserver.ui.components.CompactCard
import com.example.whisperserver.ui.components.RowDivider
import com.example.whisperserver.ui.theme.appColors
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun TranscriptionDetailScreen(
    record: TranscriptionRecord,
    audioFile: File?,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = appColors
    Column(Modifier.fillMaxSize().background(c.screen)) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = c.textPrimary,
                modifier = Modifier.size(22.dp).clickable(onClick = onBack),
            )
            Text("Transcription", color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = c.textSecondary,
                modifier = Modifier.size(20.dp).clickable(onClick = onDelete),
            )
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Status line
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val modelName = ModelRegistry.byId(record.modelId)?.displayName ?: record.modelId.ifBlank { "unknown" }
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (record.success) c.runningChip else c.accentChip)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        if (record.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = null,
                        tint = if (record.success) c.runningChipText else c.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        if (record.success) "Success" else "Failed",
                        color = if (record.success) c.runningChipText else c.error,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "${formatFullTimestamp(record.timestampMillis)} · $modelName",
                    color = c.textSecondary,
                    fontSize = 11.sp,
                )
            }

            AudioPlayerCard(record, audioFile)

            if (record.success) {
                TranscriptCard(record.text)
            } else {
                CompactCard(Modifier.fillMaxWidth()) {
                    Column {
                        SectionCaption("Error")
                        Text(
                            record.errorMessage ?: "Transcription failed",
                            color = c.error,
                            fontSize = 12.5.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            MetricsCard(record)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AudioPlayerCard(record: TranscriptionRecord, audioFile: File?) {
    val c = appColors
    val available = audioFile != null && audioFile.exists()

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(record.audioDurationMillis.toInt()) }

    DisposableEffect(audioFile) {
        var mp: MediaPlayer? = null
        if (available) {
            mp = runCatching {
                MediaPlayer().apply {
                    setDataSource(audioFile!!.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        playing = false
                        seekTo(0)
                        positionMs = 0
                    }
                }
            }.getOrNull()
            mp?.duration?.let { if (it > 0) durationMs = it }
            player = mp
        }
        onDispose {
            runCatching { mp?.release() }
            player = null
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            player?.let { positionMs = runCatching { it.currentPosition }.getOrDefault(positionMs) }
            delay(200)
        }
    }

    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    CompactCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (available) c.primary else c.chipOutline)
                    .clickable(enabled = available) {
                        val p = player ?: return@clickable
                        if (playing) {
                            runCatching { p.pause() }
                            playing = false
                        } else {
                            runCatching { p.start() }
                            playing = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Waveform(record.id, progress, Modifier.fillMaxWidth().height(30.dp))
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlayerClock(positionMs.toLong()), color = c.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        if (available && durationMs > 0) formatPlayerClock(durationMs.toLong())
                        else if (record.audioDurationMillis > 0) formatPlayerClock(record.audioDurationMillis)
                        else "--:--",
                        color = c.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    )
                }
                if (!available) {
                    Text("Audio not retained", color = c.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp))
                }
            }
        }
    }
}

@Composable
private fun Waveform(seed: Long, progress: Float, modifier: Modifier) {
    val c = appColors
    val bars = 26
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until bars) {
            // Deterministic pseudo-heights so a clip always looks the same.
            val h = 4 + ((seed * 31 + i * 37) % 24).toInt().let { if (it < 0) it + 24 else it }
            val active = i < (progress * bars)
            Box(
                Modifier.weight(1f).height(h.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (active) c.waveformActive else c.waveformInactive),
            )
        }
    }
}

@Composable
private fun TranscriptCard(text: String) {
    val c = appColors
    val clipboard = LocalClipboardManager.current
    CompactCard(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "RECOGNIZED TEXT",
                    color = c.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy",
                    tint = c.primary,
                    modifier = Modifier.size(17.dp).clickable {
                        clipboard.setText(AnnotatedString(text))
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text.ifBlank { "(empty transcription)" },
                color = c.textPrimary,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun MetricsCard(record: TranscriptionRecord) {
    CompactCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
        Column {
            MetricRow(Icons.Filled.Person, "Requested by", requesterLabel(record.remoteAddress), mono = true)
            RowDivider()
            MetricRow(Icons.Filled.GraphicEq, "Audio length", if (record.audioDurationMillis > 0) formatDuration(record.audioDurationMillis) else "—")
            RowDivider()
            MetricRow(Icons.Filled.HourglassEmpty, "Queue wait", formatDuration(record.queueWaitMillis))
            RowDivider()
            MetricRow(Icons.Filled.Schedule, "Processing time", formatDuration(record.processingMillis))
            RowDivider()
            MetricRow(Icons.Filled.Notes, "Text length", "${record.textLength} chars")
        }
    }
}

@Composable
private fun MetricRow(icon: ImageVector, label: String, value: String, mono: Boolean = false) {
    val c = appColors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(16.dp))
            Text(label, color = c.textSecondary, fontSize = 12.sp)
        }
        Text(
            value,
            color = c.textPrimary,
            fontSize = if (mono) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text.uppercase(),
        color = appColors.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
    )
}

/** Append "· Tailscale" for addresses in the 100.64.0.0/10 CGNAT range Tailscale uses. */
private fun requesterLabel(address: String): String {
    val octets = address.split('.').mapNotNull { it.toIntOrNull() }
    val tailscale = octets.size == 4 && octets[0] == 100 && octets[1] in 64..127
    return if (tailscale) "$address · Tailscale" else address
}
