package com.example.whisperserver.native

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Passes an [InputStream] through unchanged while keeping a copy of the bytes
 * read, up to [cap] bytes. The bytes handed to the reader are never altered, so
 * wrapping the proxy's request-body stream to snapshot an upload is safe: if the
 * body exceeds [cap] the capture is dropped ([captured] returns null) but the
 * stream keeps forwarding normally.
 */
class CapturingInputStream(
    source: InputStream,
    private val cap: Int,
) : FilterInputStream(source) {

    private var buffer: ByteArrayOutputStream? = ByteArrayOutputStream()
    private var size = 0

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) capture(byteArrayOf(b.toByte()), 0, 1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) capture(b, off, n)
        return n
    }

    private fun capture(src: ByteArray, off: Int, len: Int) {
        val buf = buffer ?: return
        if (size + len > cap) {
            // Over budget — give up capturing entirely, keep forwarding.
            buffer = null
            return
        }
        buf.write(src, off, len)
        size += len
    }

    /** The captured bytes, or null if nothing was captured or the cap was exceeded. */
    fun captured(): ByteArray? = buffer?.takeIf { size > 0 }?.toByteArray()
}
