package com.example.whisperserver.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** High-level lifecycle state of the whisper server. */
sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data class Running(val host: String, val port: Int, val modelId: String) : ServerState
    data object Restarting : ServerState
    data class Error(val message: String) : ServerState

    val isActive: Boolean
        get() = this is Starting || this is Running || this is Restarting
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogLine(
    val timestampMillis: Long,
    val level: LogLevel,
    val message: String,
)

/** Basic runtime statistics surfaced in the Stats screen. */
data class ServerStats(
    val requestsServed: Long = 0,
    val lastRequestAtMillis: Long = 0,
    val avgProcessingMillis: Long = 0,
    val memoryUsageBytes: Long = 0,
    val startedAtMillis: Long = 0,
) {
    val uptimeMillis: Long
        get() = if (startedAtMillis == 0L) 0 else System.currentTimeMillis() - startedAtMillis
}

/**
 * Process-wide holder for server state, logs and stats. Both the foreground
 * service (writer) and the UI/ViewModel (reader) observe these flows. Safe to
 * use as a singleton because the service runs in the app's main process.
 */
object ServerController {

    private const val MAX_LOG_LINES = 500

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _stats = MutableStateFlow(ServerStats())
    val stats: StateFlow<ServerStats> = _stats.asStateFlow()

    // Rolling accumulator for average processing time.
    private var processingSamples = 0L
    private var processingTotalMillis = 0L

    fun setState(state: ServerState) {
        _state.value = state
    }

    fun appendLog(level: LogLevel, message: String) {
        if (message.isBlank()) return
        val line = LogLine(System.currentTimeMillis(), level, message.trimEnd())
        _logs.update { current ->
            val next = if (current.size >= MAX_LOG_LINES) {
                current.subList(current.size - MAX_LOG_LINES + 1, current.size) + line
            } else {
                current + line
            }
            next
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun onServerStarted() {
        processingSamples = 0
        processingTotalMillis = 0
        _stats.value = ServerStats(startedAtMillis = System.currentTimeMillis())
    }

    fun onServerStopped() {
        _stats.update { it.copy(startedAtMillis = 0) }
    }

    fun recordRequest(processingMillis: Long?) {
        _stats.update { current ->
            val newAvg = if (processingMillis != null) {
                processingSamples += 1
                processingTotalMillis += processingMillis
                processingTotalMillis / processingSamples
            } else {
                current.avgProcessingMillis
            }
            current.copy(
                requestsServed = current.requestsServed + 1,
                lastRequestAtMillis = System.currentTimeMillis(),
                avgProcessingMillis = newAvg,
            )
        }
    }

    fun updateMemoryUsage(bytes: Long) {
        _stats.update { it.copy(memoryUsageBytes = bytes) }
    }
}
