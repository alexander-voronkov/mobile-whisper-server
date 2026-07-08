package com.example.whisperserver.service

/**
 * A single transcription request as it flowed through the public proxy: who
 * asked, how long it waited and took, the recognized text, and (best-effort) a
 * saved copy of the uploaded audio so it can be replayed.
 *
 * Records are held in memory by [ServerController] (like [LogLine]) and reset
 * when the server restarts. Populated from [com.example.whisperserver.native.LocalProxyServer].
 */
data class TranscriptionRecord(
    /** Monotonic id, also used to name the saved audio clip. */
    val id: Long,
    val timestampMillis: Long,
    /** Requester IP / host as seen by the proxy socket. */
    val remoteAddress: String,
    val success: Boolean,
    val httpStatus: Int,
    val modelId: String,
    /** Size of the uploaded audio part in bytes, or 0 when unknown. */
    val audioBytes: Long,
    /** Estimated audio length in ms (parsed from a WAV header), or 0 when unknown. */
    val audioDurationMillis: Long,
    /** Time the connection waited before a worker picked it up. */
    val queueWaitMillis: Long,
    /** Wall-clock time spent in the upstream transcription call. */
    val processingMillis: Long,
    /** Recognized text (empty on failure). */
    val text: String,
    /** Human-readable failure reason, or null on success. */
    val errorMessage: String? = null,
    /** File name of the retained audio clip in the audio cache, or null if none. */
    val audioFileName: String? = null,
    /**
     * Language code detected by whisper (e.g. "en", "ru"), or blank when unknown.
     * Only populated when the response carries a `language` field — i.e. the
     * client requested `response_format=verbose_json`; the default `json` format
     * returns only the text, so this stays blank.
     */
    val detectedLanguage: String = "",
) {
    /**
     * Processing cost relative to the audio length: seconds of compute per second
     * of audio (e.g. 39.6 s to transcribe a 10 s clip -> ~3.96). Null when the
     * audio length is unknown or the request failed. Lower is better.
     */
    val processingRate: Double?
        get() = if (success && audioDurationMillis > 0 && processingMillis > 0) {
            processingMillis.toDouble() / audioDurationMillis
        } else {
            null
        }

    val textLength: Int get() = text.length

    /** Short one-line summary for list rows: transcript preview or the error. */
    val summary: String
        get() = when {
            !success -> errorMessage?.takeIf { it.isNotBlank() } ?: "Transcription failed"
            text.isNotBlank() -> text
            else -> "(empty transcription)"
        }
}
