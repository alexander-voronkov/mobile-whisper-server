package com.example.whisperserver

import com.example.whisperserver.native.NativeCpu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCpuTest {

    // A Cortex-A76/A55 ARMv8.2 core: has both asimdhp (fp16) and asimddp (dotprod).
    private val armv82Core =
        "processor\t: 0\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp " +
            "cpuid asimdrdm lrcpc dcpop asimddp\n"

    // A Cortex-A53/A73 ARMv8.0 core: NEON but no fp16 arith, no dotprod.
    private val armv80Core =
        "processor\t: 0\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n"

    @Test
    fun modernArmv82CpuSupportsTunedBuild() {
        assertTrue(NativeCpu.missingArm64Features(armv82Core + armv82Core).isEmpty())
    }

    @Test
    fun armv80OnlyCpuReportsBothFeaturesMissing() {
        val missing = NativeCpu.missingArm64Features(armv80Core)
        assertTrue("asimdhp" in missing)
        assertTrue("asimddp" in missing)
    }

    @Test
    fun anyCoreLackingAFeatureCountsAsMissing() {
        // One capable core + one old core: a thread could be scheduled onto the old
        // one and fault, so the whole device must be treated as unsupported.
        val missing = NativeCpu.missingArm64Features(armv82Core + armv80Core)
        assertEquals(NativeCpu.REQUIRED_ARM64_FEATURES.toSet(), missing.toSet())
    }

    @Test
    fun reportsOnlyTheSpecificMissingFeature() {
        val dotprodButNoFp16 = "Features\t: fp asimd asimddp\n"
        assertEquals(listOf("asimdhp"), NativeCpu.missingArm64Features(dotprodButNoFp16))
    }

    @Test
    fun unreadableOrFeaturelessCpuinfoDoesNotFalselyReportMissing() {
        // Unknown -> advisory check stays silent rather than blocking a good device.
        assertTrue(NativeCpu.missingArm64Features("no feature lines here\n").isEmpty())
        assertTrue(NativeCpu.missingArm64Features("").isEmpty())
    }

    @Test
    fun featureMatchingIsWholeToken() {
        // Guard against substring false-positives (e.g. a hypothetical "asimddpx").
        val core = "Features\t: fp asimd asimdhpx asimddpx\n"
        assertFalse(NativeCpu.missingArm64Features(core).isEmpty())
    }
}
