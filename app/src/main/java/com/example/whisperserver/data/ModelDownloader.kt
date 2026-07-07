package com.example.whisperserver.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Progress snapshot emitted during a download. */
data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

    val percent: Int
        get() = (fraction * 100).toInt()
}

/** Terminal result of a download attempt. */
sealed interface DownloadResult {
    data class Success(val file: File) : DownloadResult
    data class Failure(val message: String, val cause: Throwable? = null) : DownloadResult
    /** Emitted when the download was paused (coroutine cancelled) with a partial file kept. */
    data object Paused : DownloadResult
}

/**
 * Downloads whisper.cpp ggml models from HuggingFace into app-private storage,
 * with resumable HTTP Range support, progress + speed reporting, and optional
 * SHA-256 verification.
 *
 * Storage layout (app-private, no permissions needed):
 *   filesDir/models/ggml-<model>.bin        <- completed model
 *   filesDir/models/ggml-<model>.bin.part   <- in-progress partial download
 */
class ModelDownloader(context: Context) {

    private val appContext = context.applicationContext

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // no overall cap; large files
        .retryOnConnectionFailure(true)
        .build()

    fun modelsDir(): File = File(appContext.filesDir, "models").apply { mkdirs() }

    fun modelFile(model: WhisperModel): File = File(modelsDir(), model.fileName)

    private fun partFile(model: WhisperModel): File = File(modelsDir(), model.fileName + ".part")

    fun isDownloaded(model: WhisperModel): Boolean = modelFile(model).let { it.exists() && it.length() > 0 }

    /** Bytes already fetched for a partial (paused) download, or 0 if none. */
    fun partialBytes(model: WhisperModel): Long = partFile(model).let { if (it.exists()) it.length() else 0 }

    fun installedModels(): List<WhisperModel> = ModelRegistry.models.filter { isDownloaded(it) }

    fun deleteModel(model: WhisperModel): Boolean {
        partFile(model).delete()
        return modelFile(model).delete()
    }

    /** Discards a paused/partial download for [model]. */
    fun clearPartial(model: WhisperModel) {
        partFile(model).delete()
    }

    /**
     * Downloads [model], resuming from any existing `.part` file. Reports progress
     * via [onProgress]. Cancel the calling coroutine to pause (partial kept) — the
     * result will be [DownloadResult.Paused]. To hard-cancel, call [clearPartial].
     *
     * @param hfToken optional HuggingFace token for rate-limit relief.
     */
    suspend fun download(
        model: WhisperModel,
        hfToken: String? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (isDownloaded(model)) return@withContext DownloadResult.Success(modelFile(model))

        val part = partFile(model)
        var existing = if (part.exists()) part.length() else 0L

        val requestBuilder = Request.Builder().url(model.downloadUrl)
        if (!hfToken.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $hfToken")
        }
        if (existing > 0) {
            requestBuilder.header("Range", "bytes=$existing-")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DownloadResult.Failure(
                        "Download failed: HTTP ${response.code} ${response.message}",
                    )
                }

                // If the server ignored our Range (200 instead of 206), restart clean.
                val resumed = response.code == 206
                if (existing > 0 && !resumed) {
                    part.delete()
                    existing = 0
                }

                val body = response.body
                    ?: return@withContext DownloadResult.Failure("Empty response body")

                // Content-Length is the *remaining* bytes; add what we already have.
                val remaining = body.contentLength()
                val total = when {
                    remaining >= 0 -> existing + remaining
                    model.downloadSizeBytes > 0 -> model.downloadSizeBytes
                    else -> -1
                }

                FileOutputStream(part, /* append = */ existing > 0).use { out ->
                    val source = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = existing

                    var windowStart = System.nanoTime()
                    var windowBytes = 0L
                    var speed = 0L
                    var lastEmit = 0L

                    while (true) {
                        ensureActive() // throws if paused/cancelled
                        val read = source.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        windowBytes += read

                        val now = System.nanoTime()
                        val elapsedNs = now - windowStart
                        if (elapsedNs >= 500_000_000L) { // recompute speed every 0.5s
                            speed = (windowBytes * 1_000_000_000L / elapsedNs)
                            windowStart = now
                            windowBytes = 0
                        }
                        // Throttle UI updates to ~10/s.
                        if (now - lastEmit >= 100_000_000L) {
                            onProgress(DownloadProgress(downloaded, total, speed))
                            lastEmit = now
                        }
                    }
                    out.flush()
                    onProgress(DownloadProgress(downloaded, total, speed))
                }
            }

            // Verify checksum if known.
            val expectedSha = model.sha256
            if (expectedSha != null) {
                val actual = sha256Of(part)
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    part.delete()
                    return@withContext DownloadResult.Failure(
                        "Checksum mismatch — file corrupted. Expected $expectedSha, got $actual.",
                    )
                }
            } else {
                Log.w(TAG, "No SHA-256 recorded for ${model.id}; skipping verification")
            }

            // Atomically promote the completed part file.
            val dest = modelFile(model)
            dest.delete()
            if (!part.renameTo(dest)) {
                return@withContext DownloadResult.Failure("Failed to finalize downloaded file")
            }
            DownloadResult.Success(dest)
        } catch (e: CancellationException) {
            // Paused: keep the .part file so a later call can resume.
            DownloadResult.Paused
        } catch (e: Exception) {
            Log.e(TAG, "Download error for ${model.id}", e)
            DownloadResult.Failure(e.message ?: "Unknown download error", e)
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ModelDownloader"
    }
}
