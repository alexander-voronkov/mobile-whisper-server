package com.example.whisperserver

import com.example.whisperserver.data.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    @Test
    fun hasAllNineFullPrecisionModelsFromSpec() {
        val ids = ModelRegistry.models.map { it.id }.toSet()
        val expected = setOf(
            "tiny", "tiny.en", "base", "base.en",
            "small", "small.en", "medium", "medium.en", "large-v3",
        )
        assertTrue("all full-precision models present", ids.containsAll(expected))
    }

    @Test
    fun offersAQuantizedVariantForEverySize() {
        val ids = ModelRegistry.models.map { it.id }.toSet()
        val expectedQuant = setOf(
            "tiny-q5_1", "tiny.en-q5_1", "base-q5_1", "base.en-q5_1",
            "small-q5_1", "small.en-q5_1", "medium-q5_0", "medium.en-q5_0",
            "large-v3-q5_0",
        )
        assertTrue("all quantized variants present", ids.containsAll(expectedQuant))
    }

    @Test
    fun idsAreUnique() {
        val ids = ModelRegistry.models.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun quantizedVariantsWeighLessThanTheirFullPrecisionSibling() {
        // e.g. base-q5_1 must download smaller and need less RAM than base.
        ModelRegistry.models
            .filter { it.id.contains("-q") }
            .forEach { quant ->
                val fullId = quant.id.substringBefore("-q")
                val full = ModelRegistry.byId(fullId)
                    ?: error("quantized ${quant.id} has no full-precision sibling $fullId")
                assertTrue("${quant.id} should download smaller than $fullId",
                    quant.downloadSizeBytes < full.downloadSizeBytes)
                assertTrue("${quant.id} should need <= RAM of $fullId",
                    quant.requiredRamBytes <= full.requiredRamBytes)
            }
    }

    @Test
    fun downloadUrlsPointAtHuggingFace() {
        ModelRegistry.models.forEach { model ->
            assertTrue(
                "URL for ${model.id} should be an HF resolve link",
                model.downloadUrl.startsWith(
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-",
                ),
            )
            assertTrue(model.downloadUrl.endsWith(".bin"))
        }
    }

    @Test
    fun englishVariantsAreNotMultilingual() {
        // `.contains` (not `.endsWith`) so quantized ids like "base.en-q5_1" count.
        ModelRegistry.models.filter { it.id.contains(".en") }.forEach {
            assertTrue("${it.id} should be English-only", !it.multilingual)
        }
    }

    @Test
    fun ramRequirementsIncreaseWithSize() {
        val tiny = ModelRegistry.byId("tiny")!!
        val base = ModelRegistry.byId("base")!!
        val small = ModelRegistry.byId("small")!!
        val large = ModelRegistry.byId("large-v3")!!
        assertTrue(tiny.requiredRamBytes < base.requiredRamBytes)
        assertTrue(base.requiredRamBytes < small.requiredRamBytes)
        assertTrue(small.requiredRamBytes < large.requiredRamBytes)
    }

    @Test
    fun defaultModelExistsAndIsDownloadable() {
        assertNotNull(ModelRegistry.default)
        assertNotNull(ModelRegistry.byId(ModelRegistry.default.id))
    }

    @Test
    fun byId_returnsNullForUnknown() {
        assertNull(ModelRegistry.byId("nonexistent"))
        assertNull(ModelRegistry.byId(null))
    }
}
