package com.example.whisperserver

import com.example.whisperserver.native.MultipartAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class MultipartAudioTest {

    @Test
    fun boundaryOf_parsesQuotedAndBareTokens() {
        assertEquals("abc123", MultipartAudio.boundaryOf("multipart/form-data; boundary=abc123"))
        assertEquals("abc123", MultipartAudio.boundaryOf("multipart/form-data; boundary=\"abc123\""))
        assertEquals("xyz", MultipartAudio.boundaryOf("multipart/form-data; boundary=xyz; charset=utf-8"))
        assertNull(MultipartAudio.boundaryOf("application/json"))
        assertNull(MultipartAudio.boundaryOf(null))
    }

    @Test
    fun extractFile_returnsAudioPartSkippingTextFields() {
        val boundary = "Boundary123"
        val wav = wav(seconds = 2)
        val body = multipart(
            boundary,
            textPart("model", "whisper-1"),
            filePart("file", "clip.wav", "audio/wav", wav),
        )

        val part = MultipartAudio.extractFile(body, boundary)
            ?: error("expected a file part")

        assertEquals("clip.wav", part.filename)
        assertEquals("audio/wav", part.contentType)
        assertArrayEquals(wav, part.bytes)
        assertEquals("wav", MultipartAudio.extensionFor(part.filename, part.contentType))
    }

    @Test
    fun durationMillis_readsWavHeader() {
        assertEquals(2000L, MultipartAudio.durationMillis(wav(seconds = 2)))
        assertEquals(0L, MultipartAudio.durationMillis(byteArrayOf(1, 2, 3)))
        // Non-WAV payloads can't be measured without a decoder.
        assertEquals(0L, MultipartAudio.durationMillis(ByteArray(1000) { 7 }))
    }

    @Test
    fun extractFile_returnsNullForNonMultipart() {
        assertNull(MultipartAudio.extractFile("not multipart".toByteArray(), "Boundary123"))
    }

    @Test
    fun extractFile_refusesOverlongBoundary() {
        // A boundary well past RFC 2046's 70-char cap must be rejected up front
        // so the naive scanner can't be driven into O(body x boundary) on a
        // crafted body (worker-starvation guard).
        val boundary = "x".repeat(5000)
        val body = multipart(boundary, filePart("file", "clip.wav", "audio/wav", wav(seconds = 1)))
        assertNull(MultipartAudio.extractFile(body, boundary))
    }

    // ---- helpers ------------------------------------------------------------

    private fun textPart(name: String, value: String): ByteArray =
        ("Content-Disposition: form-data; name=\"$name\"\r\n\r\n$value").toByteArray(Charsets.ISO_8859_1)

    private fun filePart(name: String, filename: String, contentType: String, bytes: ByteArray): ByteArray {
        val header = "Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n" +
            "Content-Type: $contentType\r\n\r\n"
        return header.toByteArray(Charsets.ISO_8859_1) + bytes
    }

    private fun multipart(boundary: String, vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) {
            out.write("--$boundary\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(part)
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        out.write("--$boundary--\r\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    /** A minimal 16 kHz mono 16-bit PCM WAV of [seconds] length (byteRate 32000). */
    private fun wav(seconds: Int): ByteArray {
        val byteRate = 32000
        val dataSize = byteRate * seconds
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        ascii("RIFF"); le32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1); le32(16000); le32(byteRate); le16(2); le16(16)
        ascii("data"); le32(dataSize); out.write(ByteArray(dataSize))
        return out.toByteArray()
    }
}
