package com.example.whisperserver.native

import com.example.whisperserver.service.TranscriptionRecord

/**
 * Sink the [LocalProxyServer] uses to journal each transcription request and,
 * when [captureAudio] is on, to persist a replayable copy of the upload. Kept as
 * a small interface so the proxy stays decoupled from the app/service layer and
 * testable without Android.
 */
interface TranscriptionRecorder {

    /** Whether the proxy should snapshot upload bodies to cache the audio. */
    val captureAudio: Boolean

    /** A unique id for the next record (also names the saved clip). */
    fun nextId(): Long

    /** Append a completed record to the history. */
    fun record(record: TranscriptionRecord)

    /** Persist audio [bytes] for [id] with extension [ext]; return the stored name or null. */
    fun saveAudio(id: Long, ext: String, bytes: ByteArray): String?
}
