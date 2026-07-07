package com.example.whisperserver

import com.example.whisperserver.native.CapturingInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class CapturingInputStreamTest {

    @Test
    fun capturesBytesWhenUnderCap() {
        val data = ByteArray(1000) { (it % 256).toByte() }
        val stream = CapturingInputStream(ByteArrayInputStream(data), cap = 4096)

        val read = stream.readBytes()

        assertArrayEquals("bytes forwarded unchanged", data, read)
        assertArrayEquals("bytes captured", data, stream.captured())
    }

    @Test
    fun forwardsButDropsCaptureWhenOverCap() {
        val data = ByteArray(5000) { (it % 256).toByte() }
        val stream = CapturingInputStream(ByteArrayInputStream(data), cap = 1024)

        val read = stream.readBytes()

        assertArrayEquals("bytes still forwarded unchanged", data, read)
        assertNull("capture dropped once over cap", stream.captured())
    }

    @Test
    fun capturedIsNullWhenNothingRead() {
        val stream = CapturingInputStream(ByteArrayInputStream(ByteArray(0)), cap = 16)
        assertEquals(-1, stream.read())
        assertNull(stream.captured())
    }
}
