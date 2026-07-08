package com.example.whisperserver.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

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

/** Basic runtime statistics surfaced on the Dashboard. */
data class ServerStats(
    val requestsServed: Long = 0,
    val successCount: Long = 0,
    val failureCount: Long = 0,
    val lastRequestAtMillis: Long = 0,
    val avgProcessingMillis: Long = 0,
    val memoryUsageBytes: Long = 0,
    val startedAtMillis: Long = 0,
    /** Total audio duration (ms) across successful, duration-known requests. */
    val ratedAudioMillis: Long = 0,
    /** Total processing time (ms) for the same requests folded into [ratedAudioMillis]. */
    val ratedProcessingMillis: Long = 0,
) {
    val uptimeMillis: Long
        get() = if (startedAtMillis == 0L) 0 else System.currentTimeMillis() - startedAtMillis

    /** Success rate in 0..100, or null when nothing has been served yet. */
    val successRatePercent: Int?
        get() = if (requestsServed <= 0) null else ((successCount.toDouble() / requestsServed) * 100).toInt()

    /**
     * Average processing cost per second of audio (seconds of compute / second of
     * audio) across all requests with a known audio length. Null until at least
     * one such request has completed. Lower is better.
     */
    val avgRate: Double?
        get() = if (ratedAudioMillis > 0) ratedProcessingMillis.toDouble() / ratedAudioMillis else null
}

/**
 * Process-wide holder for server state, logs and stats. Both the foreground
 * service (writer) and the UI/ViewModel (reader) observe these flows. Safe to
 * use as a singleton because the service runs in the app's main process.
 */
object ServerController {

    private const val MAX_LOG_LINES = 500
    private const val MAX_RECORDS = 500

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _stats = MutableStateFlow(ServerStats())
    val stats: StateFlow<ServerStats> = _stats.asStateFlow()

    /** Newest-first history of transcription requests fed by the proxy. */
    private val _records = MutableStateFlow<List<TranscriptionRecord>>(emptyList())
    val records: StateFlow<List<TranscriptionRecord>> = _records.asStateFlow()

    private val recordIds = AtomicLong(0)

    // Set once the durable journal has been loaded into memory at process start.
    private var recordsInitialized = false

    // Rolling accumulator for average processing time.
    private var processingSamples = 0L
    private var processingTotalMillis = 0L

    // Rolling accumulators for the average processing rate (compute / audio).
    private var ratedAudioTotalMillis = 0L
    private var ratedProcessingTotalMillis = 0L

    /** A unique, increasing id for the next transcription record / audio clip. */
    fun nextRecordId(): Long = recordIds.incrementAndGet()

    /**
     * Seed the in-memory history from the durable journal at process start.
     * Idempotent (first call wins). Merges any records already captured this
     * session, and advances the id counter past the highest persisted id so new
     * records and their audio clip names never collide with stored ones.
     */
    @Synchronized
    fun initializeRecords(loaded: List<TranscriptionRecord>) {
        if (recordsInitialized) return
        recordsInitialized = true
        val seen = HashSet<Long>()
        val merged = ArrayList<TranscriptionRecord>(_records.value.size + loaded.size)
        for (r in _records.value) if (seen.add(r.id)) merged.add(r)
        for (r in loaded) if (seen.add(r.id)) merged.add(r)
        merged.sortByDescending { it.id }
        _records.value = if (merged.size > MAX_RECORDS) ArrayList(merged.subList(0, MAX_RECORDS)) else merged
        val maxId = merged.maxOfOrNull { it.id } ?: 0L
        do {
            val cur = recordIds.get()
            if (cur >= maxId) break
        } while (!recordIds.compareAndSet(cur, maxId))
    }

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

    @Synchronized
    fun onServerStarted() {
        // Dashboard KPI stats are per-session and reset on each (re)start; the
        // record history is durable now (persisted to the journal DB) and is
        // deliberately NOT cleared here so it survives restarts.
        processingSamples = 0
        processingTotalMillis = 0
        ratedAudioTotalMillis = 0
        ratedProcessingTotalMillis = 0
        _stats.value = ServerStats(startedAtMillis = System.currentTimeMillis())
    }

    fun onServerStopped() {
        _stats.update { it.copy(startedAtMillis = 0) }
    }

    /**
     * Record one transcription request (success or failure). Appends it to the
     * newest-first [records] history and folds it into the aggregate [stats]
     * (count, success rate, and — for successful requests — the running average
     * processing time).
     */
    @Synchronized
    fun recordTranscription(record: TranscriptionRecord) {
        _records.update { current ->
            val next = listOf(record) + current
            if (next.size > MAX_RECORDS) next.subList(0, MAX_RECORDS) else next
        }
        // Fold the rolling average OUTSIDE the stats.update lambda: update() can
        // re-run its lambda under contention (CAS retry / a concurrent
        // updateMemoryUsage), so mutating the accumulators there would double-count
        // and drift the average upward. @Synchronized + a precomputed value keep it
        // exactly-once; the lambda stays a pure function of `current`.
        if (record.success && record.processingMillis > 0) {
            processingSamples += 1
            processingTotalMillis += record.processingMillis
        }
        // Fold the rate accumulators only for successful requests whose audio
        // length is known — same exactly-once discipline as the average above.
        if (record.success && record.processingMillis > 0 && record.audioDurationMillis > 0) {
            ratedAudioTotalMillis += record.audioDurationMillis
            ratedProcessingTotalMillis += record.processingMillis
        }
        val avg = if (processingSamples > 0) processingTotalMillis / processingSamples else 0L
        val ratedAudio = ratedAudioTotalMillis
        val ratedProcessing = ratedProcessingTotalMillis
        _stats.update { current ->
            current.copy(
                requestsServed = current.requestsServed + 1,
                successCount = current.successCount + if (record.success) 1 else 0,
                failureCount = current.failureCount + if (record.success) 0 else 1,
                lastRequestAtMillis = record.timestampMillis,
                avgProcessingMillis = if (record.success && record.processingMillis > 0) avg else current.avgProcessingMillis,
                ratedAudioMillis = ratedAudio,
                ratedProcessingMillis = ratedProcessing,
            )
        }
    }

    /** Drop a single record from the history (aggregate stats are left intact). */
    fun removeRecord(id: Long) {
        _records.update { current -> current.filterNot { it.id == id } }
    }

    fun updateMemoryUsage(bytes: Long) {
        _stats.update { it.copy(memoryUsageBytes = bytes) }
    }
}
