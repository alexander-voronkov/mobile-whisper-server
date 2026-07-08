package com.example.whisperserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.whisperserver.data.Languages
import com.example.whisperserver.data.ServerConfig
import com.example.whisperserver.service.ServerState
import com.example.whisperserver.ui.MainUiState
import com.example.whisperserver.ui.components.CompactCard
import com.example.whisperserver.ui.components.MiniToggle
import com.example.whisperserver.ui.components.RowDivider
import com.example.whisperserver.ui.components.ScreenHeader
import com.example.whisperserver.ui.theme.appColors

@Composable
fun SettingsScreen(
    state: MainUiState,
    serverState: ServerState,
    maxThreads: Int,
    onHost: (String) -> Unit,
    onPort: (Int) -> Unit,
    onLanguage: (String) -> Unit,
    onTranslate: (Boolean) -> Unit,
    onConvert: (Boolean) -> Unit,
    onThreads: (Int) -> Unit,
    onAutostart: (Boolean) -> Unit,
    onApiKey: (String) -> Unit,
    onHfToken: (String) -> Unit,
) {
    val c = appColors
    val config = state.config

    var editPort by remember { mutableStateOf(false) }
    var editThreads by remember { mutableStateOf(false) }
    var editApiKey by remember { mutableStateOf(false) }
    var editHfToken by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.screen)) {
        ScreenHeader("Settings")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            GroupLabel("Network")
            GroupCard {
                HostRow(config, state, onHost)
                RowDivider()
                ValueRow("Port", config.port.toString(), mono = true) { editPort = true }
                val running = serverState as? ServerState.Running
                if (running != null && (running.host != config.host || running.port != config.port)) {
                    RowDivider()
                    PendingHint("Serving ${running.host}:${running.port} — restart to apply changes")
                }
            }

            GroupLabel("Security")
            GroupCard {
                SecretRow(
                    "API key", "Bearer token required on requests",
                    isSet = state.hasApiKey, isNew = false,
                ) { editApiKey = true }
                RowDivider()
                SecretRow(
                    "HuggingFace token", "Rate-limit relief for downloads",
                    isSet = state.hasHfToken, isNew = true,
                ) { editHfToken = true }
            }

            GroupLabel("Transcription")
            GroupCard {
                LanguageRow(config, onLanguage)
                RowDivider()
                ToggleRow("Translate to English", null, config.translate, onTranslate)
                RowDivider()
                ToggleRow(
                    "Convert audio (ffmpeg)",
                    "Needs a build with ffmpeg bundled; otherwise uploads must be 16 kHz WAV",
                    config.convertAudio,
                    onConvert,
                )
                RowDivider()
                ValueRow("Threads", config.threads.toString()) { editThreads = true }
            }

            GroupLabel("Startup")
            GroupCard {
                ToggleRow("Start on phone boot", "Uses the last-saved config", config.autostart, onAutostart)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (editPort) {
        NumberDialog("Port", config.port, ServerConfig.MIN_PORT, ServerConfig.MAX_PORT, onDismiss = { editPort = false }) {
            onPort(it); editPort = false
        }
    }
    if (editThreads) {
        NumberDialog("Threads", config.threads, 1, maxThreads, onDismiss = { editThreads = false }) {
            onThreads(it); editThreads = false
        }
    }
    if (editApiKey) {
        SecretDialog("API key", state.hasApiKey, onDismiss = { editApiKey = false }, onSave = { onApiKey(it); editApiKey = false }, onClear = { onApiKey(""); editApiKey = false })
    }
    if (editHfToken) {
        SecretDialog("HuggingFace token", state.hasHfToken, onDismiss = { editHfToken = false }, onSave = { onHfToken(it); editHfToken = false }, onClear = { onHfToken(""); editHfToken = false })
    }
}

// ---- rows -------------------------------------------------------------------

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        color = appColors.primary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    CompactCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
        Column { content() }
    }
}

@Composable
private fun PendingHint(text: String) {
    Text(
        text,
        color = appColors.textSecondary,
        fontSize = 10.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

@Composable
private fun ValueRow(title: String, value: String, subtitle: String? = null, mono: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = appColors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.textPrimary, fontSize = 13.sp)
            if (subtitle != null) Text(subtitle, color = c.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp))
        }
        Text(
            value,
            color = c.textSecondary,
            fontSize = if (mono) 11.sp else 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = appColors
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = c.textPrimary, fontSize = 13.sp)
            if (subtitle != null) Text(subtitle, color = c.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp))
        }
        MiniToggle(checked = checked, onToggle = { onChange(!checked) })
    }
}

@Composable
private fun SecretRow(title: String, subtitle: String, isSet: Boolean, isNew: Boolean, onClick: () -> Unit) {
    val c = appColors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = c.textPrimary, fontSize = 13.sp)
                if (isNew) {
                    Text(
                        "NEW",
                        color = androidx.compose.ui.graphics.Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(c.primary).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(subtitle, color = c.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp))
        }
        if (isSet) {
            Text("•••••• set", color = c.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        } else {
            Text("Add", color = c.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HostRow(config: ServerConfig, state: MainUiState, onHost: (String) -> Unit) {
    val c = appColors
    var expanded by remember { mutableStateOf(false) }
    val label = state.hostOptions.firstOrNull { it.address == config.host }?.label ?: config.host
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Host", color = c.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(label, color = c.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.hostOptions.forEach { opt ->
                DropdownMenuItem(text = { Text(opt.label) }, onClick = { onHost(opt.address); expanded = false })
            }
        }
    }
}

@Composable
private fun LanguageRow(config: ServerConfig, onLanguage: (String) -> Unit) {
    val c = appColors
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Language", color = c.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(Languages.labelFor(config.language), color = c.textSecondary, fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Languages.options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt.label) }, onClick = { onLanguage(opt.code); expanded = false })
            }
        }
    }
}

// ---- dialogs ----------------------------------------------------------------

@Composable
private fun NumberDialog(title: String, initial: Int, min: Int, max: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in min..max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() }.take(5) },
                singleLine = true,
                isError = !valid,
                supportingText = { Text("$min–$max") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { parsed?.let(onConfirm) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SecretDialog(title: String, isSet: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit, onClear: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(if (isSet) "•••• saved — type to replace" else "Enter token") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = {
            if (isSet) TextButton(onClick = onClear) { Text("Clear") } else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
