package com.example.whisperserver

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.whisperserver.service.ServerState
import com.example.whisperserver.ui.MainViewModel
import com.example.whisperserver.ui.components.CompactBottomBar
import com.example.whisperserver.ui.components.NavDest
import com.example.whisperserver.ui.screens.DashboardScreen
import com.example.whisperserver.ui.screens.JournalScreen
import com.example.whisperserver.ui.screens.ModelManagerScreen
import com.example.whisperserver.ui.screens.SettingsScreen
import com.example.whisperserver.ui.screens.TranscriptionDetailScreen
import com.example.whisperserver.ui.theme.WhisperServerTheme
import com.example.whisperserver.ui.theme.appColors

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WhisperServerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WhisperServerApp()
                }
            }
        }
    }
}

@Composable
private fun WhisperServerApp(viewModel: MainViewModel = viewModel()) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        viewModel.refresh()
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableLongStateOf(-1L) }
    val maxThreads = remember { Runtime.getRuntime().availableProcessors() }

    val detailRecord = if (detailId >= 0) records.firstOrNull { it.id == detailId } else null
    // A record can disappear (deleted / evicted); fall back to the list.
    if (detailId >= 0 && detailRecord == null) detailId = -1L

    if (detailRecord != null) {
        BackHandler { detailId = -1L }
        TranscriptionDetailScreen(
            record = detailRecord,
            audioFile = viewModel.audioFile(detailRecord),
            onBack = { detailId = -1L },
            onDelete = {
                viewModel.deleteRecord(detailRecord)
                detailId = -1L
            },
        )
        return
    }

    val current = NavDest.entries[tabIndex]
    Scaffold(
        bottomBar = { CompactBottomBar(current, onSelect = { tabIndex = it.ordinal }) },
    ) { padding ->
        Surface(
            Modifier.padding(padding).fillMaxSize().background(appColors.screen),
            color = appColors.screen,
        ) {
            when (current) {
                NavDest.Dashboard -> DashboardScreen(
                    serverState = serverState,
                    stats = stats,
                    records = records,
                    tailscaleIp = uiState.tailscaleIp,
                    config = uiState.config,
                    onStart = viewModel::startServer,
                    onStop = viewModel::stopServer,
                    onRestart = viewModel::restartServer,
                    onOpenRecord = { detailId = it.id },
                    onOpenJournal = { tabIndex = NavDest.Journal.ordinal },
                )

                NavDest.Journal -> JournalScreen(
                    records = records,
                    onOpenRecord = { detailId = it.id },
                )

                NavDest.Models -> ModelManagerScreen(
                    models = uiState.models,
                    runningModelId = (serverState as? ServerState.Running)?.modelId,
                    onSelect = { viewModel.selectModel(it.model) },
                    onDownload = { viewModel.downloadModel(it.model) },
                    onPause = { viewModel.pauseDownload(it.model) },
                    onCancel = { viewModel.cancelDownload(it.model) },
                    onDelete = { viewModel.deleteModel(it.model) },
                )

                NavDest.Settings -> SettingsScreen(
                    state = uiState,
                    maxThreads = maxThreads,
                    onHost = viewModel::setHost,
                    onPort = viewModel::setPort,
                    onLanguage = viewModel::setLanguage,
                    onTranslate = viewModel::setTranslate,
                    onConvert = viewModel::setConvertAudio,
                    onThreads = viewModel::setThreads,
                    onAutostart = viewModel::setAutostart,
                    onApiKey = viewModel::setApiKey,
                    onHfToken = viewModel::setHfToken,
                )
            }
        }
    }
}
