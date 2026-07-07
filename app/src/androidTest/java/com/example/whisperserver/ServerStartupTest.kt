package com.example.whisperserver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whisperserver.data.MemoryChecker
import com.example.whisperserver.data.ModelDownloader
import com.example.whisperserver.data.ServerConfig
import com.example.whisperserver.native.LaunchSpec
import com.example.whisperserver.native.WhisperBridge
import com.example.whisperserver.service.ServerController
import com.example.whisperserver.service.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented startup tests. CI devices have neither the native whisper-server
 * binary nor a downloaded model, so we assert that the startup path runs and
 * reports back through [ServerController] (state + logs + fatal-error callback)
 * rather than failing silently. Driving [WhisperBridge] directly keeps this
 * deterministic and free of foreground-service start restrictions.
 */
@RunWith(AndroidJUnit4::class)
class ServerStartupTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun deviceMemoryIsReadable() {
        val checker = MemoryChecker(context)
        val memory = checker.readMemory()
        assertTrue("totalMem should be positive", memory.totalBytes > 0)
        assertTrue("availMem should be positive", memory.availableBytes > 0)
        assertTrue("storage should be readable", checker.availableStorageBytes() > 0)
    }

    @Test
    fun modelsDirectoryIsCreated() {
        val dir = ModelDownloader(context).modelsDir()
        assertTrue(dir.exists() && dir.isDirectory)
    }

    @Test
    fun startupWithoutBinaryReportsError() {
        ServerController.clearLogs()
        ServerController.setState(ServerState.Stopped)

        var fatal: String? = null
        val bridge = WhisperBridge(
            context = context,
            scope = CoroutineScope(Dispatchers.Default),
            onFatalError = { fatal = it },
        )
        val spec = LaunchSpec(
            config = ServerConfig(),
            apiKey = "",
            modelPath = "/nonexistent/ggml-model.bin",
        )

        bridge.start(spec)

        // Missing binary/model must surface as an Error state, a log line, and a
        // fatal-error callback — never a silent no-op.
        assertTrue("expected an Error state", ServerController.state.value is ServerState.Error)
        assertTrue("expected log output", ServerController.logs.value.isNotEmpty())
        assertTrue("expected onFatalError callback", fatal != null)
    }
}
