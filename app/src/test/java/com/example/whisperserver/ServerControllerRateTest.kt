package com.example.whisperserver

import com.example.whisperserver.service.ServerController
import com.example.whisperserver.service.TranscriptionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ServerControllerRateTest {

    @Before
    fun reset() {
        // Resets the rolling accumulators, records and stats to a clean run.
        ServerController.onServerStarted()
    }

    private fun rec(success: Boolean, proc: Long, dur: Long) = TranscriptionRecord(
        id = ServerController.nextRecordId(),
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
    fun avgRateNullUntilTimedSuccess() {
        assertNull(ServerController.stats.value.avgRate)
        // Success but unknown audio length -> excluded.
        ServerController.recordTranscription(rec(success = true, proc = 1_000, dur = 0))
        assertNull(ServerController.stats.value.avgRate)
    }

    @Test
    fun avgRateIsAggregateOfProcessingOverAudio() {
        ServerController.recordTranscription(rec(success = true, proc = 40_000, dur = 10_000)) // 4.0
        ServerController.recordTranscription(rec(success = true, proc = 20_000, dur = 10_000)) // 2.0
        // Aggregate = total processing / total audio = 60000 / 20000 = 3.0
        assertEquals(3.0, ServerController.stats.value.avgRate!!, 1e-9)
    }

    @Test
    fun failuresAndUnknownDurationExcludedFromRate() {
        ServerController.recordTranscription(rec(success = false, proc = 40_000, dur = 10_000)) // failure
        ServerController.recordTranscription(rec(success = true, proc = 0, dur = 10_000))       // no processing time
        assertNull(ServerController.stats.value.avgRate)
        ServerController.recordTranscription(rec(success = true, proc = 30_000, dur = 10_000))  // 3.0
        assertEquals(3.0, ServerController.stats.value.avgRate!!, 1e-9)
    }

    @Test
    fun processingRateOnRecord() {
        assertEquals(3.96, rec(success = true, proc = 39_600, dur = 10_000).processingRate!!, 1e-9)
        assertNull(rec(success = false, proc = 39_600, dur = 10_000).processingRate)
        assertNull(rec(success = true, proc = 39_600, dur = 0).processingRate)
    }
}
