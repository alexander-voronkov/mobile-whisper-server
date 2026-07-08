package com.example.whisperserver.native

/**
 * Minimal, dependency-free helpers for pulling the uploaded audio out of a
 * buffered `multipart/form-data` request body (what the OpenAI-compatible
 * `/v1/audio/transcriptions` endpoint receives) so it can be cached and
 * replayed. Pure byte manipulation — unit-tested on the JVM.
 *
 * This is deliberately best-effort: any malformed input yields null rather than
 * throwing, and the caller simply records the request without a playable clip.
 */
object MultipartAudio {

    /** The extracted file part: its raw bytes plus the declared name/type. */
    data class FilePart(
        val bytes: ByteArray,
        val filename: String?,
        val contentType: String?,
    )

    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

    // RFC 2046 caps a multipart boundary at 70 characters. The proxy accepts
    // header lines up to 16 KB, so without this cap a crafted Content-Type could
    // feed a very long boundary to the naive O(body x boundary) scanner below and
    // pin a worker on an 8 MB near-match body. Audio capture is best-effort, so
    // refusing an over-long boundary just skips journaling the clip.
    private const val MAX_BOUNDARY_LEN = 70

    /** Parse the `boundary=...` token from a `multipart/form-data` Content-Type. */
    fun boundaryOf(contentType: String?): String? {
        if (contentType == null) return null
        val marker = contentType.indexOf("boundary=", ignoreCase = true)
        if (marker < 0) return null
        var value = contentType.substring(marker + "boundary=".length).trim()
        // A boundary may be quoted, and other params may follow after ';'.
        value = value.substringBefore(';').trim().trim('"')
        return value.ifBlank { null }
    }

    /**
     * Return the first form-data part that carries a filename (the uploaded
     * audio), or null if the body can't be parsed as multipart with [boundary].
     */
    fun extractFile(body: ByteArray, boundary: String): FilePart? {
        if (body.isEmpty() || boundary.isBlank() || boundary.length > MAX_BOUNDARY_LEN) return null
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)

        var searchFrom = 0
        while (true) {
            val delimStart = indexOf(body, delimiter, searchFrom)
            if (delimStart < 0) return null
            val afterDelim = delimStart + delimiter.size
            // "--boundary--" marks the end of the body.
            if (afterDelim + 1 < body.size && body[afterDelim] == '-'.code.toByte() &&
                body[afterDelim + 1] == '-'.code.toByte()
            ) {
                return null
            }
            val headerStart = afterDelim + CRLF.size
            if (headerStart >= body.size) return null

            val headerEnd = indexOf(body, DOUBLE_CRLF, headerStart)
            if (headerEnd < 0) return null
            val contentStart = headerEnd + DOUBLE_CRLF.size

            val headerText = String(body, headerStart, headerEnd - headerStart, Charsets.ISO_8859_1)
            val nextDelimStart = indexOf(body, delimiter, contentStart)
            if (nextDelimStart < 0) return null
            // The part content ends at the CRLF that precedes the next delimiter.
            val contentEnd = (nextDelimStart - CRLF.size).coerceAtLeast(contentStart)

            val filename = headerParam(headerText, "filename")
            if (filename != null) {
                val partBytes = body.copyOfRange(contentStart, contentEnd)
                return FilePart(
                    bytes = partBytes,
                    filename = filename,
                    contentType = headerValue(headerText, "content-type"),
                )
            }
            searchFrom = nextDelimStart
        }
    }

    /** A safe file extension for the clip, derived from filename then content-type. */
    fun extensionFor(filename: String?, contentType: String?): String {
        filename?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() && it.length <= 5 }
            ?.let { return it }
        return when {
            contentType == null -> "bin"
            contentType.contains("wav", true) || contentType.contains("wave", true) -> "wav"
            contentType.contains("mpeg", true) || contentType.contains("mp3", true) -> "mp3"
            contentType.contains("mp4", true) || contentType.contains("m4a", true) -> "m4a"
            contentType.contains("ogg", true) -> "ogg"
            contentType.contains("flac", true) -> "flac"
            contentType.contains("webm", true) -> "webm"
            else -> "bin"
        }
    }

    /**
     * Estimate the clip length in milliseconds from a PCM WAV header. Returns 0
     * for anything that isn't a parseable little-endian WAV (other formats need
     * a real decoder, which we don't do here).
     */
    fun durationMillis(bytes: ByteArray): Long {
        if (bytes.size < 44) return 0
        if (!matchesAscii(bytes, 0, "RIFF") || !matchesAscii(bytes, 8, "WAVE")) return 0

        var offset = 12
        var byteRate = 0L
        var dataSize = 0L
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = readLeUInt(bytes, offset + 4)
            val body = offset + 8
            when (chunkId) {
                "fmt " -> if (body + 16 <= bytes.size) byteRate = readLeUInt(bytes, body + 8)
                "data" -> {
                    dataSize = minOf(chunkSize, (bytes.size - body).toLong().coerceAtLeast(0))
                    if (byteRate > 0) return dataSize * 1000 / byteRate
                }
            }
            // Chunks are word-aligned (padded to even length). Compute the next
            // offset in Long space and bail on any malformed size that wouldn't
            // strictly advance or would run past the buffer — otherwise a crafted
            // WAV (e.g. chunkSize 0xfffffff8) could spin here forever on a worker.
            val next = body.toLong() + chunkSize + (chunkSize and 1L)
            if (next <= offset || next > bytes.size) break
            offset = next.toInt()
        }
        return 0
    }

    // ---- byte helpers -------------------------------------------------------

    private val DOUBLE_CRLF = byteArrayOf(
        '\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte(),
    )

    /** First index of [pattern] in [source] at or after [from], or -1. */
    private fun indexOf(source: ByteArray, pattern: ByteArray, from: Int): Int {
        if (pattern.isEmpty() || from < 0) return -1
        val last = source.size - pattern.size
        var i = from
        while (i <= last) {
            var j = 0
            while (j < pattern.size && source[i + j] == pattern[j]) j++
            if (j == pattern.size) return i
            i++
        }
        return -1
    }

    /** Extract a `name="value"` (or `name=value`) parameter from a header block. */
    private fun headerParam(headers: String, name: String): String? {
        val idx = headers.indexOf("$name=", ignoreCase = true)
        if (idx < 0) return null
        var rest = headers.substring(idx + name.length + 1).trimStart()
        return if (rest.startsWith('"')) {
            rest.substring(1).substringBefore('"').takeIf { it.isNotBlank() }
        } else {
            rest.substringBefore(';').substringBefore('\r').substringBefore('\n').trim().takeIf { it.isNotBlank() }
        }
    }

    /** Extract a full header value by name, e.g. "content-type". */
    private fun headerValue(headers: String, name: String): String? {
        for (line in headers.split("\r\n", "\n")) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals(name, ignoreCase = true)) {
                return line.substring(idx + 1).trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun matchesAscii(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (offset + text.length > bytes.size) return false
        for (i in text.indices) if (bytes[offset + i] != text[i].code.toByte()) return false
        return true
    }

    private fun readLeUInt(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}
