package com.example.whisperserver.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Bounded on-disk cache of recently uploaded audio clips so the transcription
 * detail screen can replay them. Kept small and self-evicting — this is a
 * convenience cache, not durable storage. Cleared whenever the server (re)starts
 * so it stays in sync with the in-memory record history.
 */
class AudioStore(context: Context) {

    private val dir: File = File(context.cacheDir, DIR_NAME).apply { mkdirs() }

    /**
     * Persist [bytes] as the clip for record [id] with the given file [ext]
     * (e.g. "wav"). Returns the stored file name, or null if it couldn't be
     * saved. Evicts the oldest clips once the count / total-size caps are hit.
     */
    @Synchronized
    fun save(id: Long, ext: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        return try {
            val name = "$id.$ext"
            File(dir, name).writeBytes(bytes)
            evict()
            name
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache audio for #$id: ${e.message}")
            null
        }
    }

    /** The cached file for [name], or null if it's no longer present. */
    fun file(name: String?): File? {
        if (name.isNullOrBlank()) return null
        val f = File(dir, name)
        return if (f.exists()) f else null
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** Delete a single cached clip by name (no-op if absent). */
    @Synchronized
    fun delete(name: String?) {
        if (name.isNullOrBlank()) return
        runCatching { File(dir, name).delete() }
    }

    /** Drop oldest files until under both the count and total-byte caps. */
    private fun evict() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEachIndexed { index, f ->
            total += f.length()
            if (index >= MAX_FILES || total > MAX_TOTAL_BYTES) {
                runCatching { f.delete() }
            }
        }
    }

    companion object {
        private const val TAG = "AudioStore"
        private const val DIR_NAME = "transcriptions"
        // Match the durable journal's row cap so restored records keep their clips
        // where disk allows; the total-bytes cap below is the real bound (clips are
        // up to 8 MB each, so 200 MB is reached well before 500 files for big clips).
        private const val MAX_FILES = 500 // TranscriptionStore.MAX_PERSISTED
        private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024 // 200 MB
    }
}
