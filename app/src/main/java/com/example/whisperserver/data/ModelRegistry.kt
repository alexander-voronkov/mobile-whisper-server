package com.example.whisperserver.data

/**
 * Metadata for a single whisper.cpp ggml model.
 *
 * @param id              stable identifier / HuggingFace model tag, e.g. "tiny.en".
 * @param displayName     human readable name shown in the UI.
 * @param fileName        on-disk / remote file name, e.g. "ggml-tiny.en.bin".
 * @param downloadSizeBytes approximate download size in bytes.
 * @param requiredRamBytes approximate peak RAM the model needs while loaded.
 * @param multilingual    true for multilingual models, false for `.en` variants.
 * @param sha256          expected SHA-256 of the downloaded file, or null if unknown.
 *                        When null, checksum verification is skipped (with a warning).
 */
data class WhisperModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadSizeBytes: Long,
    val requiredRamBytes: Long,
    val multilingual: Boolean,
    val sha256: String? = null,
) {
    /** Public HuggingFace download URL for this model. */
    val downloadUrl: String
        get() = "${ModelRegistry.HF_BASE_URL}$fileName"
}

/**
 * Static registry of the whisper.cpp models the app knows how to download.
 *
 * Sizes / RAM figures come from the whisper.cpp documentation and are the
 * numbers surfaced by the memory guard (see [MemoryChecker]).
 *
 * NOTE ON CHECKSUMS: upstream does not publish a stable SHA-256 manifest, so
 * [WhisperModel.sha256] is left null here. To harden downloads, compute the
 * SHA-256 of each `ggml-*.bin` once and paste it in below; [ModelDownloader]
 * will then enforce it and reject corrupted / tampered files.
 */
object ModelRegistry {

    const val HF_BASE_URL: String =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB

    val models: List<WhisperModel> = listOf(
        WhisperModel(
            id = "tiny",
            displayName = "Tiny",
            fileName = "ggml-tiny.bin",
            downloadSizeBytes = 39 * MB,
            requiredRamBytes = 200 * MB,
            multilingual = true,
        ),
        WhisperModel(
            id = "tiny.en",
            displayName = "Tiny (English)",
            fileName = "ggml-tiny.en.bin",
            downloadSizeBytes = 39 * MB,
            requiredRamBytes = 200 * MB,
            multilingual = false,
        ),
        WhisperModel(
            id = "base",
            displayName = "Base",
            fileName = "ggml-base.bin",
            downloadSizeBytes = 74 * MB,
            requiredRamBytes = 300 * MB,
            multilingual = true,
        ),
        WhisperModel(
            id = "base.en",
            displayName = "Base (English)",
            fileName = "ggml-base.en.bin",
            downloadSizeBytes = 74 * MB,
            requiredRamBytes = 300 * MB,
            multilingual = false,
        ),
        WhisperModel(
            id = "small",
            displayName = "Small",
            fileName = "ggml-small.bin",
            downloadSizeBytes = 244 * MB,
            requiredRamBytes = 600 * MB,
            multilingual = true,
        ),
        WhisperModel(
            id = "small.en",
            displayName = "Small (English)",
            fileName = "ggml-small.en.bin",
            downloadSizeBytes = 244 * MB,
            requiredRamBytes = 600 * MB,
            multilingual = false,
        ),
        WhisperModel(
            id = "medium",
            displayName = "Medium",
            fileName = "ggml-medium.bin",
            downloadSizeBytes = 769 * MB,
            requiredRamBytes = 1536 * MB, // ~1.5 GB
            multilingual = true,
        ),
        WhisperModel(
            id = "medium.en",
            displayName = "Medium (English)",
            fileName = "ggml-medium.en.bin",
            downloadSizeBytes = 769 * MB,
            requiredRamBytes = 1536 * MB, // ~1.5 GB
            multilingual = false,
        ),
        WhisperModel(
            id = "large-v3",
            displayName = "Large v3",
            fileName = "ggml-large-v3.bin",
            downloadSizeBytes = 1500 * MB, // ~1.5 GB
            requiredRamBytes = 2560 * MB,  // ~2.5 GB
            multilingual = true,
        ),
    )

    /** The model selected by default on first launch — safe for low-end devices. */
    val default: WhisperModel
        get() = byId("base.en") ?: models.first()

    fun byId(id: String?): WhisperModel? =
        if (id == null) null else models.firstOrNull { it.id == id }
}
