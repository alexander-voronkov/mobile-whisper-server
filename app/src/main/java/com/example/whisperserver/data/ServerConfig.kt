package com.example.whisperserver.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A selectable language for transcription. `auto` lets whisper detect it. */
data class LanguageOption(val code: String, val label: String)

object Languages {
    val options: List<LanguageOption> = listOf(
        LanguageOption("auto", "Auto-detect"),
        LanguageOption("en", "English"),
        LanguageOption("ru", "Russian"),
        LanguageOption("es", "Spanish"),
        LanguageOption("fr", "French"),
        LanguageOption("de", "German"),
        LanguageOption("zh", "Chinese"),
        LanguageOption("ja", "Japanese"),
        LanguageOption("ko", "Korean"),
        LanguageOption("hi", "Hindi"),
        LanguageOption("pt", "Portuguese"),
        LanguageOption("it", "Italian"),
        LanguageOption("nl", "Dutch"),
        LanguageOption("pl", "Polish"),
        LanguageOption("tr", "Turkish"),
        LanguageOption("uk", "Ukrainian"),
        LanguageOption("ar", "Arabic"),
    )

    fun labelFor(code: String): String =
        options.firstOrNull { it.code == code }?.label ?: code
}

/**
 * Non-secret, persisted server configuration. Secrets (API key, HF token) live
 * in [SecureStore] and are merged in only when a launch command is built.
 */
data class ServerConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val language: String = "auto",
    val translate: Boolean = false,
    // Off by default: --convert requires an ffmpeg executable on PATH, which this
    // build does not bundle. Enabling it without ffmpeg makes whisper-server exit
    // at startup. Turn on only once an ffmpeg binary is packaged.
    val convertAudio: Boolean = false,
    val vad: Boolean = false,
    // Defaults to the count of high-performance ("big") cores. See DEFAULT_THREADS.
    val threads: Int = DEFAULT_THREADS,
    val autostart: Boolean = false,
    val selectedModelId: String = "base.en",
) {
    val selectedModel: WhisperModel?
        get() = ModelRegistry.byId(selectedModelId)

    companion object {
        const val DEFAULT_HOST = "0.0.0.0"
        const val DEFAULT_PORT = 8080
        // 2, not the core count: phone SoCs are big.LITTLE (e.g. Dimensity 800U =
        // 2×Cortex-A76 + 6×Cortex-A55). whisper.cpp/ggml splits each op into equal
        // chunks and barrier-syncs after every one, so a thread landing on a slow
        // A55 stalls the fast A76 threads at every barrier. Matching the big-core
        // count (typically 2) is usually faster than using all 8 and avoids heating
        // the little cores. Users can raise it in Settings (capped at core count).
        const val DEFAULT_THREADS = 2
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "server_config")

/**
 * DataStore-backed persistence for [ServerConfig]. Exposes a reactive [config]
 * flow (used by the UI/ViewModel) and suspend setters for each field.
 */
class ConfigRepository(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val LANGUAGE = stringPreferencesKey("language")
        val TRANSLATE = booleanPreferencesKey("translate")
        val CONVERT = booleanPreferencesKey("convert_audio")
        val VAD = booleanPreferencesKey("vad")
        val THREADS = intPreferencesKey("threads")
        val AUTOSTART = booleanPreferencesKey("autostart")
        val MODEL_ID = stringPreferencesKey("selected_model_id")
    }

    val config: Flow<ServerConfig> = context.dataStore.data.map { prefs -> prefs.toConfig() }

    suspend fun update(transform: (ServerConfig) -> ServerConfig) {
        context.dataStore.edit { prefs ->
            val updated = transform(prefs.toConfig())
            prefs[Keys.HOST] = updated.host
            prefs[Keys.PORT] = updated.port
            prefs[Keys.LANGUAGE] = updated.language
            prefs[Keys.TRANSLATE] = updated.translate
            prefs[Keys.CONVERT] = updated.convertAudio
            prefs[Keys.VAD] = updated.vad
            prefs[Keys.THREADS] = updated.threads
            prefs[Keys.AUTOSTART] = updated.autostart
            prefs[Keys.MODEL_ID] = updated.selectedModelId
        }
    }

    private fun Preferences.toConfig(): ServerConfig {
        val defaults = ServerConfig()
        return ServerConfig(
            host = this[Keys.HOST] ?: defaults.host,
            port = this[Keys.PORT] ?: defaults.port,
            language = this[Keys.LANGUAGE] ?: defaults.language,
            translate = this[Keys.TRANSLATE] ?: defaults.translate,
            convertAudio = this[Keys.CONVERT] ?: defaults.convertAudio,
            vad = this[Keys.VAD] ?: defaults.vad,
            threads = this[Keys.THREADS] ?: defaults.threads,
            autostart = this[Keys.AUTOSTART] ?: defaults.autostart,
            selectedModelId = this[Keys.MODEL_ID] ?: defaults.selectedModelId,
        )
    }
}
