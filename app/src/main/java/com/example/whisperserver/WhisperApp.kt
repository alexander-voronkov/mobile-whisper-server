package com.example.whisperserver

import android.app.Application
import android.content.Context
import com.example.whisperserver.data.AppDatabase
import com.example.whisperserver.data.AudioStore
import com.example.whisperserver.data.ConfigRepository
import com.example.whisperserver.data.MemoryChecker
import com.example.whisperserver.data.ModelDownloader
import com.example.whisperserver.data.SecureStore
import com.example.whisperserver.data.TranscriptionStore
import com.example.whisperserver.service.ServerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Simple manual dependency container. Hilt is intentionally skipped (per spec,
 * "DI: Hilt (optional, can skip for simplicity)") to keep the module count low.
 */
class AppContainer(context: Context) {
    val configRepository: ConfigRepository = ConfigRepository(context)
    val secureStore: SecureStore = SecureStore(context)
    val modelDownloader: ModelDownloader = ModelDownloader(context)
    val memoryChecker: MemoryChecker = MemoryChecker(context)
    val audioStore: AudioStore = AudioStore(context)

    /** App-lifetime scope for background persistence work. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database: AppDatabase = AppDatabase.build(context)
    val transcriptionStore: TranscriptionStore =
        TranscriptionStore(database.transcriptions(), audioStore, applicationScope)

    /**
     * Loads the durable journal into the in-memory history and seeds the record-id
     * counter past the highest persisted id. Exposed so the server-start path can
     * await it before the proxy can journal anything, guaranteeing new ids and
     * audio clip names never collide with persisted ones.
     */
    val journalLoad: Job = applicationScope.launch {
        val history = runCatching { transcriptionStore.loadRecent() }.getOrDefault(emptyList())
        ServerController.initializeRecords(history)
    }
}

class WhisperApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        fun container(context: Context): AppContainer =
            (context.applicationContext as WhisperApp).container
    }
}
