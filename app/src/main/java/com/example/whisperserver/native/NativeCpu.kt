package com.example.whisperserver.native

import android.os.Build
import java.io.File

/**
 * Best-effort detection of whether this device's CPU can run the tuned `arm64-v8a`
 * `whisper-server` binary. That build is compiled with
 * `-march=armv8.2-a+fp16+dotprod` (see `scripts/build-whisper.sh`) for a large
 * speedup; a CPU whose cores lack FEAT_FP16 (`asimdhp`) or FEAT_DotProd
 * (`asimddp`) faults with SIGILL the first time one of those instructions runs.
 *
 * This check is **advisory only** — it must never block launch. A self-built
 * baseline binary (via the `ARM64_CPU_ARCH=` escape hatch) runs fine on such a
 * CPU and would be wrongly blocked, and we can't tell a tuned binary from a
 * baseline one at runtime. The definitive signal is an actual SIGILL exit, which
 * [WhisperBridge] turns into a clear, non-looping error.
 */
object NativeCpu {

    /** Half-precision arith + int8 dot-product: the extensions the arm64 build needs. */
    val REQUIRED_ARM64_FEATURES = listOf("asimdhp", "asimddp")

    /**
     * Given the raw text of `/proc/cpuinfo`, returns the required features absent on
     * at least one core — a thread scheduled onto that core would fault. Returns
     * empty when there are no `Features` lines to read (unknown → don't warn).
     */
    fun missingArm64Features(cpuInfo: String): List<String> {
        val perCoreFeatures = cpuInfo.lineSequence()
            .filter { it.trimStart().startsWith("Features") }
            .map { line ->
                line.substringAfter(':', "")
                    .trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
            .toList()
        if (perCoreFeatures.isEmpty()) return emptyList()
        return REQUIRED_ARM64_FEATURES.filter { feat -> perCoreFeatures.any { feat !in it } }
    }

    /**
     * Required features missing on this device. Empty when the primary ABI is not
     * `arm64-v8a` (the `armeabi-v7a` build is the portable baseline) or when
     * `/proc/cpuinfo` can't be read.
     */
    fun missingArm64Features(): List<String> {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return emptyList()
        val text = try {
            File("/proc/cpuinfo").readText()
        } catch (e: Exception) {
            return emptyList()
        }
        return missingArm64Features(text)
    }
}
