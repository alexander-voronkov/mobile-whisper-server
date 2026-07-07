package com.example.whisperserver.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.whisperserver.WhisperApp
import com.example.whisperserver.data.DownloadProgress
import com.example.whisperserver.data.DownloadResult
import com.example.whisperserver.data.MemoryGuardResult
import com.example.whisperserver.data.ModelRegistry
import com.example.whisperserver.data.ServerConfig
import com.example.whisperserver.data.WhisperModel
import com.example.whisperserver.network.HostOption
import com.example.whisperserver.network.TailscaleDetector
import com.example.whisperserver.service.LogLevel
import com.example.whisperserver.service.ServerController
import com.example.whisperserver.service.WhisperServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Per-model download status shown in the model manager. */
sealed interface DownloadUiState {
    data object Idle : DownloadUiState
    data class Downloading(val progress: DownloadProgress) : DownloadUiState
    data class Paused(val downloadedBytes: Long) : DownloadUiState
    data class Failed(val message: String) : DownloadUiState
}

/** Row state for one model in the manager list. */
data class ModelUiState(
    val model: WhisperModel,
    val isDownloaded: Boolean,
    val isSelected: Boolean,
    val guard: MemoryGuardResult,
    val download: DownloadUiState,
)

/** Aggregated state for the whole screen. */
data class MainUiState(
    val config: ServerConfig = ServerConfig(),
    val hostOptions: List<HostOption> = emptyList(),
    val models: List<ModelUiState> = emptyList(),
    val hasApiKey: Boolean = false,
    val hasHfToken: Boolean = false,
    val tailscaleIp: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = WhisperApp.container(app)

    // Server / logs / stats come straight from the process-wide controller.
    val serverState = ServerController.state
    val logs = ServerController.logs
    val stats = ServerController.stats

    private val downloadStates = MutableStateFlow<Map<String, DownloadUiState>>(emptyMap())
    private val refreshTick = MutableStateFlow(0)
    private val secrets = MutableStateFlow(
        SecretsState(container.secureStore.hasApiKey, container.secureStore.hasHfToken),
    )
    private val hostOptions = MutableStateFlow(TailscaleDetector.hostOptions())

    private val downloadJobs = mutableMapOf<String, Job>()

    // Ids whose in-flight download was hard-cancelled, so its cancellation
    // (delivered as DownloadResult.Paused) is not mistaken for a pause.
    private val canceledIds = mutableSetOf<String>()

    val uiState: StateFlow<MainUiState> = combine(
        container.configRepository.config,
        downloadStates,
        secrets,
        hostOptions,
        refreshTick,
    ) { config, downloads, secretsState, hosts, _ ->
        MainUiState(
            config = config,
            hostOptions = hosts,
            hasApiKey = secretsState.hasApiKey,
            hasHfToken = secretsState.hasHfToken,
            tailscaleIp = TailscaleDetector.tailscaleAddress(),
            models = ModelRegistry.models.map { model ->
                ModelUiState(
                    model = model,
                    isDownloaded = container.modelDownloader.isDownloaded(model),
                    isSelected = config.selectedModelId == model.id,
                    // Storage guard accounts for a partial (paused) download so it
                    // only requires the remaining bytes.
                    guard = container.memoryChecker.evaluate(
                        model,
                        alreadyDownloadedBytes = container.modelDownloader.partialBytes(model),
                    ),
                    download = downloads[model.id] ?: defaultDownloadState(model),
                )
            },
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private fun defaultDownloadState(model: WhisperModel): DownloadUiState {
        val partial = container.modelDownloader.partialBytes(model)
        return if (!container.modelDownloader.isDownloaded(model) && partial > 0) {
            DownloadUiState.Paused(partial)
        } else {
            DownloadUiState.Idle
        }
    }

    // ---- Config setters -----------------------------------------------------

    fun setHost(host: String) = updateConfig { it.copy(host = host) }
    fun setPort(port: Int) = updateConfig { it.copy(port = port) }
    fun setLanguage(code: String) = updateConfig { it.copy(language = code) }
    fun setTranslate(on: Boolean) = updateConfig { it.copy(translate = on) }
    fun setConvertAudio(on: Boolean) = updateConfig { it.copy(convertAudio = on) }
    fun setVad(on: Boolean) = updateConfig { it.copy(vad = on) }
    fun setThreads(threads: Int) = updateConfig { it.copy(threads = threads) }
    fun setAutostart(on: Boolean) = updateConfig { it.copy(autostart = on) }
    fun selectModel(model: WhisperModel) = updateConfig { it.copy(selectedModelId = model.id) }

    private fun updateConfig(transform: (ServerConfig) -> ServerConfig) {
        viewModelScope.launch { container.configRepository.update(transform) }
    }

    // ---- Secrets ------------------------------------------------------------

    fun setApiKey(value: String) {
        container.secureStore.apiKey = value
        secrets.update { it.copy(hasApiKey = container.secureStore.hasApiKey) }
    }

    fun setHfToken(value: String) {
        container.secureStore.hfToken = value
        secrets.update { it.copy(hasHfToken = container.secureStore.hasHfToken) }
    }

    // ---- Server control -----------------------------------------------------

    fun startServer() = WhisperServerService.start(getApplication())

    fun stopServer() = WhisperServerService.stop(getApplication())

    fun restartServer() {
        val intent = Intent(getApplication(), WhisperServerService::class.java)
            .setAction(WhisperServerService.ACTION_RESTART)
        getApplication<Application>().startService(intent)
    }

    fun clearLogs() = ServerController.clearLogs()

    /** Force a recompute of model / guard / host state (e.g. after returning to screen). */
    fun refresh() {
        hostOptions.value = TailscaleDetector.hostOptions()
        refreshTick.update { it + 1 }
    }

    // ---- Downloads ----------------------------------------------------------

    fun downloadModel(model: WhisperModel) {
        if (downloadJobs[model.id]?.isActive == true) return
        canceledIds.remove(model.id)
        val partial = container.modelDownloader.partialBytes(model)
        val guard = container.memoryChecker.evaluate(model, alreadyDownloadedBytes = partial)
        if (!guard.canProceed) {
            ServerController.appendLog(LogLevel.WARN, "Download blocked for ${model.id}: ${guard.message}")
            return
        }
        val token = container.secureStore.hfToken.ifBlank { null }
        val job = viewModelScope.launch {
            setDownload(model, DownloadUiState.Downloading(DownloadProgress(partial, model.downloadSizeBytes, 0)))
            val result = container.modelDownloader.download(model, token) { progress ->
                setDownload(model, DownloadUiState.Downloading(progress))
            }
            when (result) {
                is DownloadResult.Success -> {
                    clearDownload(model)
                    refresh()
                }
                is DownloadResult.Failure -> setDownload(model, DownloadUiState.Failed(result.message))
                DownloadResult.Paused -> {
                    // A hard cancel also surfaces as Paused (coroutine cancellation);
                    // don't resurrect the row that cancel already cleared.
                    if (canceledIds.remove(model.id)) {
                        clearDownload(model)
                    } else {
                        setDownload(model, DownloadUiState.Paused(container.modelDownloader.partialBytes(model)))
                    }
                }
            }
        }
        downloadJobs[model.id] = job
    }

    /** Pause keeps the partial file so the download can resume. */
    fun pauseDownload(model: WhisperModel) {
        downloadJobs.remove(model.id)?.cancel()
        setDownload(model, DownloadUiState.Paused(container.modelDownloader.partialBytes(model)))
    }

    /** Cancel discards the partial file entirely. */
    fun cancelDownload(model: WhisperModel) {
        canceledIds.add(model.id)
        downloadJobs.remove(model.id)?.cancel()
        container.modelDownloader.clearPartial(model)
        clearDownload(model)
    }

    fun deleteModel(model: WhisperModel) {
        canceledIds.add(model.id)
        downloadJobs.remove(model.id)?.cancel()
        container.modelDownloader.deleteModel(model)
        clearDownload(model)
        refresh()
    }

    private fun setDownload(model: WhisperModel, state: DownloadUiState) {
        downloadStates.update { it + (model.id to state) }
    }

    private fun clearDownload(model: WhisperModel) {
        downloadStates.update { it - model.id }
    }

    private data class SecretsState(val hasApiKey: Boolean, val hasHfToken: Boolean)
}
