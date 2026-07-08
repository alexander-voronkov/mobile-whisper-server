package com.example.whisperserver

import com.example.whisperserver.service.TranscriptionRecord
import com.example.whisperserver.ui.screens.aggregateProcessingRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessingRateTest {

    private var nextId = 0L
    private fun rec(success: Boolean, proc: Long, dur: Long) = TranscriptionRecord(
        id = ++nextId,
        timestampMillis = 1_000L,
        remoteAddress = "1.2.3.4",
        success = success,
        httpStatus = if (success) 200 else 500,
        modelId = "base.en",
        audioBytes = 0,
        audioDurationMillis = dur,
        queueWaitMillis = 0,
        processingMillis = proc,
        text = if (success) "hi" else "",
    )

    @Test
    fun processingRateOnRecord() {
        assertEquals(3.96, rec(success = true, proc = 39_600, dur = 10_000).processingRate!!, 1e-9)
        assertNull(rec(success = false, proc = 39_600, dur = 10_000).processingRate) // failure
        assertNull(rec(success = true, proc = 39_600, dur = 0).processingRate)        // unknown duration
        assertNull(rec(success = true, proc = 0, dur = 10_000).processingRate)        // no processing time
    }

    @Test
    fun aggregateRateNullWhenNoTimedSuccess() {
        assertNull(aggregateProcessingRate(emptyList()))
        assertNull(
            aggregateProcessingRate(
                listOf(
                    rec(success = true, proc = 1_000, dur = 0),        // unknown duration
                    rec(success = false, proc = 40_000, dur = 10_000), // failure
                    rec(success = true, proc = 0, dur = 10_000),       // no processing time
                ),
            ),
        )
    }

    @Test
    fun aggregateRateIsTotalProcessingOverTotalAudio() {
        val rate = aggregateProcessingRate(
            listOf(
                rec(success = true, proc = 40_000, dur = 10_000), // 4.0
                rec(success = true, proc = 20_000, dur = 10_000), // 2.0
            ),
        )
        // total processing / total audio = 60000 / 20000 = 3.0
        assertEquals(3.0, rate!!, 1e-9)
    }
}
