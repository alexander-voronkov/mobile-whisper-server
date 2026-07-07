package com.example.whisperserver

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.whisperserver.ui.MainViewModel
import com.example.whisperserver.ui.screens.LogViewerScreen
import com.example.whisperserver.ui.screens.ModelManagerScreen
import com.example.whisperserver.ui.screens.ServerConfigScreen
import com.example.whisperserver.ui.screens.StatsScreen
import com.example.whisperserver.ui.theme.WhisperServerTheme

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

private enum class Tab(val label: String, val icon: ImageVector) {
    Server("Server", Icons.Filled.Dns),
    Models("Models", Icons.Filled.Download),
    Logs("Logs", Icons.Filled.Article),
    Stats("Stats", Icons.Filled.BarChart),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhisperServerApp(viewModel: MainViewModel = viewModel()) {
    // Request the runtime permissions the server needs. RECORD_AUDIO is required
    // for a microphone-typed foreground service on Android 14+.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.RECORD_AUDIO)
        }
        permissionLauncher.launch(perms.toTypedArray())
        viewModel.refresh()
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val maxThreads = remember { Runtime.getRuntime().availableProcessors() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(Tab.entries[selectedTab].label) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Surface(Modifier.padding(padding).fillMaxSize()) {
            when (Tab.entries[selectedTab]) {
                Tab.Server -> ServerConfigScreen(
                    state = uiState,
                    serverState = serverState,
                    maxThreads = maxThreads,
                    onHost = viewModel::setHost,
                    onPort = viewModel::setPort,
                    onLanguage = viewModel::setLanguage,
                    onTranslate = viewModel::setTranslate,
                    onConvert = viewModel::setConvertAudio,
                    onVad = viewModel::setVad,
                    onThreads = viewModel::setThreads,
                    onAutostart = viewModel::setAutostart,
                    onApiKey = viewModel::setApiKey,
                    onHfToken = viewModel::setHfToken,
                    onStart = viewModel::startServer,
                    onStop = viewModel::stopServer,
                    onRestart = viewModel::restartServer,
                )
                Tab.Models -> ModelManagerScreen(
                    models = uiState.models,
                    onSelect = { viewModel.selectModel(it.model) },
                    onDownload = { viewModel.downloadModel(it.model) },
                    onPause = { viewModel.pauseDownload(it.model) },
                    onCancel = { viewModel.cancelDownload(it.model) },
                    onDelete = { viewModel.deleteModel(it.model) },
                )
                Tab.Logs -> LogViewerScreen(logs = logs, onClear = viewModel::clearLogs)
                Tab.Stats -> StatsScreen(stats = stats, serverState = serverState)
            }
        }
    }
}
