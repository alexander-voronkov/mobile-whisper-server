package com.example.whisperserver.data

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.util.Locale
import kotlin.math.roundToLong

/** Outcome of the RAM fitness check for a given model. */
enum class MemoryVerdict {
    /** Plenty of RAM — green checkmark. */
    OK,

    /** Model fits the device but not enough free RAM right now — yellow warning. */
    SOFT_WARNING,

    /** Model too large for the device — red block, download disabled. */
    HARD_BLOCK,
}

/** Outcome of the free-storage check for a given download. */
enum class StorageVerdict {
    OK,
    BLOCK,
}

/**
 * Full result of evaluating a model against a device snapshot.
 * [canProceed] is true only when RAM is not hard-blocked and storage is OK.
 */
data class MemoryGuardResult(
    val memory: MemoryVerdict,
    val storage: StorageVerdict,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val requiredRamBytes: Long,
    val downloadSizeBytes: Long,
    val availableStorageBytes: Long,
    val message: String,
) {
    val canProceed: Boolean
        get() = memory != MemoryVerdict.HARD_BLOCK && storage != StorageVerdict.BLOCK
}

/**
 * Pure, framework-free implementation of the memory / storage guard so it can be
 * unit tested on the JVM without Robolectric. The Android-facing [MemoryChecker]
 * reads live device numbers and delegates here.
 *
 * Thresholds mirror the product spec:
 *  - HARD BLOCK when requiredRam  > totalRam     * 0.7
 *  - SOFT WARN  when requiredRam  > availableRam * 1.5
 *  - STORAGE BLOCK when downloadSize > availableStorage * 0.9
 */
object MemoryGuard {

    const val HARD_BLOCK_TOTAL_RATIO = 0.7
    const val SOFT_WARN_AVAIL_MULTIPLIER = 1.5
    const val STORAGE_BLOCK_RATIO = 0.9

    fun evaluateMemory(
        totalRamBytes: Long,
        availableRamBytes: Long,
        requiredRamBytes: Long,
    ): MemoryVerdict = when {
        requiredRamBytes > totalRamBytes * HARD_BLOCK_TOTAL_RATIO -> MemoryVerdict.HARD_BLOCK
        requiredRamBytes > availableRamBytes * SOFT_WARN_AVAIL_MULTIPLIER -> MemoryVerdict.SOFT_WARNING
        else -> MemoryVerdict.OK
    }

    fun evaluateStorage(
        downloadSizeBytes: Long,
        availableStorageBytes: Long,
    ): StorageVerdict = when {
        downloadSizeBytes > availableStorageBytes * STORAGE_BLOCK_RATIO -> StorageVerdict.BLOCK
        else -> StorageVerdict.OK
    }

    fun evaluate(
        model: WhisperModel,
        totalRamBytes: Long,
        availableRamBytes: Long,
        availableStorageBytes: Long,
    ): MemoryGuardResult {
        val memory = evaluateMemory(totalRamBytes, availableRamBytes, model.requiredRamBytes)
        val storage = evaluateStorage(model.downloadSizeBytes, availableStorageBytes)
        return MemoryGuardResult(
            memory = memory,
            storage = storage,
            totalRamBytes = totalRamBytes,
            availableRamBytes = availableRamBytes,
            requiredRamBytes = model.requiredRamBytes,
            downloadSizeBytes = model.downloadSizeBytes,
            availableStorageBytes = availableStorageBytes,
            message = buildMessage(model, memory, storage, totalRamBytes, availableRamBytes, availableStorageBytes),
        )
    }

    private fun buildMessage(
        model: WhisperModel,
        memory: MemoryVerdict,
        storage: StorageVerdict,
        totalRamBytes: Long,
        availableRamBytes: Long,
        availableStorageBytes: Long,
    ): String {
        // Storage block is the most fatal — report it first.
        if (storage == StorageVerdict.BLOCK) {
            return "Not enough storage space. Need ~${humanBytes(model.downloadSizeBytes)}, " +
                "only ${humanBytes(availableStorageBytes)} available."
        }
        return when (memory) {
            MemoryVerdict.HARD_BLOCK ->
                "This model needs ~${humanBytes(model.requiredRamBytes)} RAM, but your device " +
                    "has only ${humanBytes(totalRamBytes)} total. It will likely crash or be unusably slow."

            MemoryVerdict.SOFT_WARNING ->
                "Not enough free RAM right now (~${humanBytes(availableRamBytes)} available). " +
                    "Close other apps and try again, or the server may crash."

            MemoryVerdict.OK ->
                "Ready to download. Requires ~${humanBytes(model.requiredRamBytes)} RAM " +
                    "(you have ${humanBytes(totalRamBytes)} total)."
        }
    }

    /** Compact binary byte formatting, e.g. 1536*MB -> "1.5 GB". */
    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.0f MB", bytes / mb)
            else -> String.format(Locale.US, "%.0f KB", bytes / kb)
        }
    }
}

/**
 * Android-facing device fitness checker. Reads live RAM and free-storage numbers
 * and delegates the actual policy to [MemoryGuard].
 */
class MemoryChecker(private val context: Context) {

    data class DeviceMemory(val totalBytes: Long, val availableBytes: Long, val lowMemory: Boolean)

    fun readMemory(): DeviceMemory {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return DeviceMemory(
            totalBytes = info.totalMem,
            availableBytes = info.availMem,
            lowMemory = info.lowMemory,
        )
    }

    /** Free bytes in the app's files directory (where models are stored). */
    fun availableStorageBytes(): Long {
        val path = context.filesDir
        val stat = StatFs(path.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    fun evaluate(model: WhisperModel): MemoryGuardResult {
        val mem = readMemory()
        return MemoryGuard.evaluate(
            model = model,
            totalRamBytes = mem.totalBytes,
            availableRamBytes = mem.availableBytes,
            availableStorageBytes = availableStorageBytes(),
        )
    }

    /** True if the system currently reports a low-memory condition. */
    fun isLowMemory(): Boolean = readMemory().lowMemory

    /** Fraction of total RAM currently in use, 0.0..1.0. */
    fun memoryPressure(): Double {
        val mem = readMemory()
        if (mem.totalBytes <= 0) return 0.0
        val used = (mem.totalBytes - mem.availableBytes).coerceAtLeast(0)
        return (used.toDouble() / mem.totalBytes).coerceIn(0.0, 1.0)
    }

    /** Estimated current app RSS in bytes (for the stats screen). */
    fun appMemoryUsageBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pids = intArrayOf(android.os.Process.myPid())
        val infos = am.getProcessMemoryInfo(pids)
        if (infos.isEmpty()) return 0
        // totalPss is in KB.
        return infos[0].totalPss.toLong() * 1024
    }

    companion object {
        fun percent(part: Long, whole: Long): Long =
            if (whole <= 0) 0 else ((part.toDouble() / whole) * 100).roundToLong()
    }
}
