package com.example.whisperserver.data

import com.example.whisperserver.service.TranscriptionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Durable journal of transcription requests backed by Room/SQLite. Unlike the
 * in-memory [com.example.whisperserver.service.ServerController] history it
 * survives app and server restarts. Writes are fire-and-forget on [scope]; the
 * bounded audio-clip cache is pruned in lockstep so clips of dropped rows don't
 * linger on disk.
 */
class TranscriptionStore(
    private val dao: TranscriptionDao,
    private val audioStore: AudioStore,
    private val scope: CoroutineScope,
) {
    /** Newest-first history, capped at [MAX_PERSISTED]. */
    suspend fun loadRecent(limit: Int = MAX_PERSISTED): List<TranscriptionRecord> =
        dao.recent(limit).map { it.toRecord() }

    /** Persist one completed record, then prune rows (and their clips) beyond the cap. */
    fun persist(record: TranscriptionRecord) {
        scope.launch {
            runCatching {
                dao.insert(record.toEntity())
                val orphans = dao.fileNamesBeyond(MAX_PERSISTED).filterNotNull()
                if (orphans.isNotEmpty()) {
                    dao.trimTo(MAX_PERSISTED)
                    orphans.forEach { audioStore.delete(it) }
                }
            }
        }
    }

    /** Remove a single record from the journal (its clip is deleted by the caller). */
    fun remove(id: Long) {
        scope.launch { runCatching { dao.delete(id) } }
    }

    companion object {
        /** Keep the journal bounded; matches the in-memory record cap. */
        const val MAX_PERSISTED = 500
    }
}
