package com.example.whisperserver.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.example.whisperserver.data.Languages
import com.example.whisperserver.data.ServerConfig
import com.example.whisperserver.network.HostOption
import com.example.whisperserver.service.ServerState
import com.example.whisperserver.ui.MainUiState
import com.example.whisperserver.ui.components.LabeledDropdown
import com.example.whisperserver.ui.components.SecretField
import com.example.whisperserver.ui.components.SectionCard
import com.example.whisperserver.ui.components.SwitchRow

@Composable
fun ServerConfigScreen(
    state: MainUiState,
    serverState: ServerState,
    maxThreads: Int,
    onHost: (String) -> Unit,
    onPort: (Int) -> Unit,
    onLanguage: (String) -> Unit,
    onTranslate: (Boolean) -> Unit,
    onConvert: (Boolean) -> Unit,
    onVad: (Boolean) -> Unit,
    onThreads: (Int) -> Unit,
    onAutostart: (Boolean) -> Unit,
    onApiKey: (String) -> Unit,
    onHfToken: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val config = state.config
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        StatusCard(config, serverState, state.tailscaleIp, onStart, onStop, onRestart)

        SectionCard("Network") {
            LabeledDropdown(
                label = "Host",
                options = state.hostOptions,
                selected = state.hostOptions.firstOrNull { it.address == config.host }
                    ?: HostOption(config.host, config.host),
                optionLabel = { it.label },
                onSelect = { onHost(it.address) },
            )
            Spacer(Modifier.height(12.dp))
            PortField(config.port, onPort)
        }

        SectionCard("Security") {
            SecretField(
                label = "API key (optional)",
                value = if (state.hasApiKey) MASK else "",
                onValueChange = onApiKey,
                placeholder = "Bearer token required on requests if set",
            )
            Text(
                if (state.hasApiKey) "A key is set — clients must send Authorization: Bearer <key>."
                else "No key set — rely on network isolation (e.g. Tailscale).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        SectionCard("Transcription") {
            LabeledDropdown(
                label = "Language",
                options = Languages.options,
                selected = Languages.options.firstOrNull { it.code == config.language },
                optionLabel = { it.label },
                onSelect = { onLanguage(it.code) },
            )
            SwitchRow("Translate to English", config.translate, onTranslate)
            SwitchRow(
                "Convert audio (ffmpeg)",
                config.convertAudio,
                onConvert,
                subtitle = "Accept mp3/m4a/etc. Requires bundled ffmpeg.",
            )
            SwitchRow("Voice Activity Detection", config.vad, onVad)
            Spacer(Modifier.height(8.dp))
            ThreadsField(config.threads, maxThreads, onThreads)
        }

        SectionCard("Downloads") {
            SecretField(
                label = "HuggingFace token (optional)",
                value = if (state.hasHfToken) MASK else "",
                onValueChange = onHfToken,
                placeholder = "For rate-limit relief on model downloads",
            )
        }

        SectionCard("Startup") {
            SwitchRow(
                "Start server on phone boot",
                config.autostart,
                onAutostart,
                subtitle = "Uses the last-saved config and selected model.",
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

private const val MASK = "••••••••"

@Composable
private fun StatusCard(
    config: ServerConfig,
    serverState: ServerState,
    tailscaleIp: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    SectionCard("Server") {
        val statusText = when (serverState) {
            is ServerState.Running -> "Running on ${serverState.host}:${serverState.port}"
            ServerState.Starting -> "Starting…"
            ServerState.Restarting -> "Restarting…"
            is ServerState.Error -> "Error: ${serverState.message}"
            ServerState.Stopped -> "Stopped"
        }
        Text(statusText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))

        val reachableHost = when {
            config.host != "0.0.0.0" -> config.host
            tailscaleIp != null -> tailscaleIp
            else -> "<phone-ip>"
        }
        Text(
            "http://$reachableHost:${config.port}/v1/audio/transcriptions",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (serverState.isActive) {
                Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
                OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) { Text("Restart") }
            } else {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Start server") }
            }
        }
    }
}

@Composable
private fun PortField(port: Int, onPort: (Int) -> Unit) {
    var text by remember(port) { mutableStateOf(port.toString()) }
    val parsed = text.toIntOrNull()
    val isError = parsed == null || !ServerConfig.isValidPort(parsed)
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.let { if (ServerConfig.isValidPort(it)) onPort(it) }
        },
        label = { Text("Port") },
        isError = isError,
        singleLine = true,
        supportingText = { if (isError) Text("Must be ${ServerConfig.MIN_PORT}-${ServerConfig.MAX_PORT}") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ThreadsField(threads: Int, maxThreads: Int, onThreads: (Int) -> Unit) {
    var text by remember(threads) { mutableStateOf(threads.toString()) }
    val parsed = text.toIntOrNull()
    val isError = parsed == null || parsed < 1 || parsed > maxThreads
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter { it.isDigit() }.take(3)
            text.toIntOrNull()?.let { if (it in 1..maxThreads) onThreads(it) }
        },
        label = { Text("Threads") },
        isError = isError,
        singleLine = true,
        supportingText = { Text("1-$maxThreads (device cores)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
