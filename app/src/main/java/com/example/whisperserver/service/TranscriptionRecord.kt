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
) {
    val textLength: Int get() = text.length

    /** Short one-line summary for list rows: transcript preview or the error. */
    val summary: String
        get() = when {
            !success -> errorMessage?.takeIf { it.isNotBlank() } ?: "Transcription failed"
            text.isNotBlank() -> text
            else -> "(empty transcription)"
        }
}
