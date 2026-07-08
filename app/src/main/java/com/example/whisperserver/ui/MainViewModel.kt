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
import com.example.whisperserver.service.TranscriptionRecord
import com.example.whisperserver.service.WhisperServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** True when this build bundles an ffmpeg binary, so "Convert audio" works. */
    val ffmpegAvailable: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = WhisperApp.container(app)

    // Whether an ffmpeg executable is bundled (shipped as libffmpeg.so and
    // extracted to nativeLibraryDir). Stable for the process lifetime, so we
    // resolve it once. Drives the "Convert audio" toggle's availability.
    private val ffmpegAvailable: Boolean =
        java.io.File(app.applicationInfo.nativeLibraryDir, "libffmpeg.so").exists()

    // Server / logs / stats / records come straight from the process-wide controller.
    val serverState = ServerController.state
    val logs = ServerController.logs
    val stats = ServerController.stats
    val records = ServerController.records

    /** The cached audio clip for a record, or null if it wasn't retained. */
    fun audioFile(record: TranscriptionRecord): java.io.File? =
        container.audioStore.file(record.audioFileName)

    /** Remove a record from the journal (in-memory + durable) and delete its cached audio. */
    fun deleteRecord(record: TranscriptionRecord) {
        container.audioStore.delete(record.audioFileName)
        ServerController.removeRecord(record.id)
        container.transcriptionStore.remove(record.id)
    }

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

    // Heavy per-model state (disk I/O + memory/storage guard). Recomputed only
    // when config or an explicit refresh changes — NOT on download-progress
    // ticks, which fire ~10x/s. See [uiState] for the cheap overlay.
    private val modelRows: Flow<List<ModelRow>> = combine(
        container.configRepository.config,
        refreshTick,
    ) { config, _ ->
        ModelRegistry.models.map { model ->
            val partial = container.modelDownloader.partialBytes(model)
            val downloaded = container.modelDownloader.isDownloaded(model)
            ModelRow(
                model = model,
                isDownloaded = downloaded,
                isSelected = config.selectedModelId == model.id,
                // Storage guard accounts for a partial (paused) download so it
                // only requires the remaining bytes.
                guard = container.memoryChecker.evaluate(model, alreadyDownloadedBytes = partial),
                defaultDownload = if (!downloaded && partial > 0) {
                    DownloadUiState.Paused(partial)
                } else {
                    DownloadUiState.Idle
                },
            )
        }
    }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<MainUiState> = combine(
        container.configRepository.config,
        modelRows,
        downloadStates,
        secrets,
        hostOptions,
    ) { config, rows, downloads, secretsState, hosts ->
        // Cheap overlay only: no disk I/O or interface enumeration here, so
        // frequent download-progress emissions stay lightweight.
        MainUiState(
            config = config,
            hostOptions = hosts,
            hasApiKey = secretsState.hasApiKey,
            hasHfToken = secretsState.hasHfToken,
            tailscaleIp = hosts.firstOrNull { it.isTailscale }?.address,
            ffmpegAvailable = ffmpegAvailable,
            models = rows.map { row ->
                ModelUiState(
                    model = row.model,
                    isDownloaded = row.isDownloaded,
                    isSelected = row.isSelected,
                    guard = row.guard,
                    download = downloads[row.model.id] ?: row.defaultDownload,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    /** Heavy, config-derived row state (recomputed off the download hot path). */
    private data class ModelRow(
        val model: WhisperModel,
        val isDownloaded: Boolean,
        val isSelected: Boolean,
        val guard: MemoryGuardResult,
        val defaultDownload: DownloadUiState,
    )

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

    // Serializes config writes and lets startServer/restartServer wait for all
    // pending edits to be persisted before the service reads config.first().
    private val configMutex = Mutex()

    private fun updateConfig(transform: (ServerConfig) -> ServerConfig) {
        viewModelScope.launch { configMutex.withLock { container.configRepository.update(transform) } }
    }

    /** Suspends until every edit queued before this call has been persisted. */
    private suspend fun awaitConfigWrites() = configMutex.withLock { }

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

    fun startServer() {
        // Wait for any just-made config edit to persist so the service reads the
        // intended host/port/model rather than the previous value.
        viewModelScope.launch {
            awaitConfigWrites()
            WhisperServerService.start(getApplication())
        }
    }

    fun stopServer() = WhisperServerService.stop(getApplication())

    fun restartServer() {
        viewModelScope.launch {
            awaitConfigWrites()
            val intent = Intent(getApplication(), WhisperServerService::class.java)
                .setAction(WhisperServerService.ACTION_RESTART)
            getApplication<Application>().startService(intent)
        }
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
