package com.example.whisperserver

import android.app.Application
import android.content.Context
import com.example.whisperserver.data.ConfigRepository
import com.example.whisperserver.data.MemoryChecker
import com.example.whisperserver.data.ModelDownloader
import com.example.whisperserver.data.SecureStore

/**
 * Simple manual dependency container. Hilt is intentionally skipped (per spec,
 * "DI: Hilt (optional, can skip for simplicity)") to keep the module count low.
 */
class AppContainer(context: Context) {
    val configRepository: ConfigRepository = ConfigRepository(context)
    val secureStore: SecureStore = SecureStore(context)
    val modelDownloader: ModelDownloader = ModelDownloader(context)
    val memoryChecker: MemoryChecker = MemoryChecker(context)
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
