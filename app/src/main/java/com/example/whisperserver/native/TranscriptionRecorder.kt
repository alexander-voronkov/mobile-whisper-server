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

    /**
     * Best-effort audio length in ms for the retained clip named [fileName], for
     * formats the pure WAV parser can't read (mp3/m4a/ogg/flac). Backed by an
     * Android media decoder in the app layer; the default returns 0 so pure/JVM
     * implementations (and tests) stay Android-free. Returns 0 when unknown.
     */
    fun probeDurationMillis(fileName: String?): Long = 0L
}
