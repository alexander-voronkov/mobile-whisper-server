package com.example.whisperserver

import com.example.whisperserver.data.MemoryGuard
import com.example.whisperserver.data.MemoryVerdict
import com.example.whisperserver.data.ModelRegistry
import com.example.whisperserver.data.StorageVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGuardTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    // ---- Memory thresholds --------------------------------------------------

    @Test
    fun hardBlock_whenRequiredExceeds70PercentOfTotal() {
        // 2.5 GB model on a 3 GB device: 2.5 > 0.7 * 3 = 2.1 -> HARD_BLOCK
        val verdict = MemoryGuard.evaluateMemory(
            totalRamBytes = 3 * gb,
            availableRamBytes = 2 * gb,
            requiredRamBytes = 2560 * mb,
        )
        assertEquals(MemoryVerdict.HARD_BLOCK, verdict)
    }

    @Test
    fun softWarning_whenFitsTotalButNotEnoughFreeNow() {
        // 1.5 GB model on 4 GB total (ok vs total) but only 800 MB free:
        // 1536 > 1.5 * 800 = 1200 -> SOFT_WARNING
        val verdict = MemoryGuard.evaluateMemory(
            totalRamBytes = 4 * gb,
            availableRamBytes = 800 * mb,
            requiredRamBytes = 1536 * mb,
        )
        assertEquals(MemoryVerdict.SOFT_WARNING, verdict)
    }

    @Test
    fun ok_whenPlentyOfTotalAndFreeRam() {
        val verdict = MemoryGuard.evaluateMemory(
            totalRamBytes = 8 * gb,
            availableRamBytes = 5 * gb,
            requiredRamBytes = 600 * mb,
        )
        assertEquals(MemoryVerdict.OK, verdict)
    }

    @Test
    fun hardBlockTakesPrecedenceOverSoftWarning() {
        // Fails both the total check and the available check; hard block wins.
        val verdict = MemoryGuard.evaluateMemory(
            totalRamBytes = 3 * gb,
            availableRamBytes = 300 * mb,
            requiredRamBytes = 2560 * mb,
        )
        assertEquals(MemoryVerdict.HARD_BLOCK, verdict)
    }

    // ---- Acceptance #8: 3 GB device can run base model ----------------------

    @Test
    fun lowEnd3gbDevice_canRunBaseModel() {
        val base = ModelRegistry.byId("base")!!
        val verdict = MemoryGuard.evaluateMemory(
            totalRamBytes = 3 * gb,
            availableRamBytes = 1500L * mb,
            requiredRamBytes = base.requiredRamBytes,
        )
        assertEquals(MemoryVerdict.OK, verdict)
    }

    @Test
    fun lowEnd3gbDevice_blocksLargeV3() {
        val large = ModelRegistry.byId("large-v3")!!
        val result = MemoryGuard.evaluate(
            model = large,
            totalRamBytes = 3 * gb,
            availableRamBytes = 1500L * mb,
            availableStorageBytes = 20 * gb,
        )
        assertEquals(MemoryVerdict.HARD_BLOCK, result.memory)
        assertFalse(result.canProceed)
    }

    // ---- Storage ------------------------------------------------------------

    @Test
    fun storageBlock_whenDownloadExceeds90PercentOfFree() {
        val verdict = MemoryGuard.evaluateStorage(
            downloadSizeBytes = 1000 * mb,
            availableStorageBytes = 1000 * mb, // 1000 > 0.9 * 1000 = 900 -> BLOCK
        )
        assertEquals(StorageVerdict.BLOCK, verdict)
    }

    @Test
    fun storageOk_whenEnoughFreeSpace() {
        val verdict = MemoryGuard.evaluateStorage(
            downloadSizeBytes = 244 * mb,
            availableStorageBytes = 5 * gb,
        )
        assertEquals(StorageVerdict.OK, verdict)
    }

    @Test
    fun canProceed_falseWhenStorageBlockedEvenIfRamOk() {
        val small = ModelRegistry.byId("small")!!
        val result = MemoryGuard.evaluate(
            model = small,
            totalRamBytes = 8 * gb,
            availableRamBytes = 6 * gb,
            availableStorageBytes = 100 * mb, // too little for a 244 MB download
        )
        assertEquals(StorageVerdict.BLOCK, result.storage)
        assertFalse(result.canProceed)
        assertTrue(result.message.contains("storage", ignoreCase = true))
    }

    @Test
    fun canProceed_trueWhenEverythingHealthy() {
        val tiny = ModelRegistry.byId("tiny")!!
        val result = MemoryGuard.evaluate(
            model = tiny,
            totalRamBytes = 4 * gb,
            availableRamBytes = 3 * gb,
            availableStorageBytes = 10 * gb,
        )
        assertTrue(result.canProceed)
        assertEquals(MemoryVerdict.OK, result.memory)
    }

    // ---- Formatting ---------------------------------------------------------

    @Test
    fun humanBytes_formatsGigabytes() {
        assertEquals("1.5 GB", MemoryGuard.humanBytes(1536 * mb))
    }

    @Test
    fun humanBytes_formatsMegabytes() {
        assertEquals("244 MB", MemoryGuard.humanBytes(244 * mb))
    }
}
