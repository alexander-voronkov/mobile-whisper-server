package com.example.whisperserver

import com.example.whisperserver.data.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    @Test
    fun hasAllNineModelsFromSpec() {
        val ids = ModelRegistry.models.map { it.id }.toSet()
        val expected = setOf(
            "tiny", "tiny.en", "base", "base.en",
            "small", "small.en", "medium", "medium.en", "large-v3",
        )
        assertEquals(expected, ids)
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
        ModelRegistry.models.filter { it.id.endsWith(".en") }.forEach {
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
