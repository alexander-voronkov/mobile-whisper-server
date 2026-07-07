package com.example.whisperserver.native

import android.content.Context
import android.util.Log
import com.example.whisperserver.data.ServerConfig
import com.example.whisperserver.service.LogLevel
import com.example.whisperserver.service.ServerController
import com.example.whisperserver.service.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Everything needed to launch the native server: settings + resolved secrets + model path. */
data class LaunchSpec(
    val config: ServerConfig,
    val apiKey: String,
    val modelPath: String,
)

/**
 * Façade over the native whisper.cpp HTTP server (MVP: subprocess via
 * [ServerProcess]). Responsibilities:
 *  - locate the executable (shipped as a native `lib*.so` so it lands in the
 *    read-only, exec-allowed nativeLibraryDir — required on Android 10+),
 *  - translate a [LaunchSpec] into command-line arguments,
 *  - own the process lifecycle and the crash auto-restart policy
 *    (up to [MAX_RESTARTS] within [RESTART_WINDOW_MS], then give up),
 *  - forward output to [ServerController] and derive best-effort stats.
 *
 * A JNI implementation (Option A) can replace this class wholesale without
 * touching the service/UI, since callers only depend on start/stop + state.
 */
class WhisperBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFatalError: (String) -> Unit,
) {
    /** The OpenAI-compatible transcription path the server is mapped to. */
    val inferencePath = "/v1/audio/transcriptions"

    private var process: ServerProcess? = null
    private var spec: LaunchSpec? = null

    @Volatile
    private var intentionalStop = false

    // Sliding window of recent crash timestamps for the restart budget.
    private val crashTimestamps = ArrayDeque<Long>()

    /**
     * The server executable. Packaged as `libwhisper-server.so` in jniLibs so
     * that at install time it lands in nativeLibraryDir, which is one of the few
     * app-associated locations Android still permits executing from.
     */
    fun binaryFile(): File = File(context.applicationInfo.nativeLibraryDir, BINARY_SO)

    fun isBinaryAvailable(): Boolean = binaryFile().let { it.exists() && it.canExecute() }

    fun start(launchSpec: LaunchSpec) {
        if (process?.isRunning == true) {
            ServerController.appendLog(LogLevel.WARN, "Server already running; ignoring start request")
            return
        }
        val binary = binaryFile()
        if (!binary.exists()) {
            val msg = "whisper-server binary not found at ${binary.absolutePath}. " +
                "Build it with ./gradlew buildWhisperNative (see README)."
            ServerController.appendLog(LogLevel.ERROR, msg)
            ServerController.setState(ServerState.Error(msg))
            onFatalError(msg)
            return
        }
        if (!File(launchSpec.modelPath).exists()) {
            val msg = "Model file not found: ${launchSpec.modelPath}. Download a model first."
            ServerController.appendLog(LogLevel.ERROR, msg)
            ServerController.setState(ServerState.Error(msg))
            onFatalError(msg)
            return
        }

        spec = launchSpec
        intentionalStop = false
        crashTimestamps.clear()
        ServerController.setState(ServerState.Starting)
        ServerController.onServerStarted()
        launchProcess(launchSpec)
    }

    private fun launchProcess(launchSpec: LaunchSpec) {
        val command = buildCommand(launchSpec)
        val proc = ServerProcess(
            command = command,
            workingDir = context.filesDir,
            environment = mapOf(
                // Make bundled ffmpeg (if shipped) discoverable for --convert.
                "PATH" to "${context.applicationInfo.nativeLibraryDir}:${System.getenv("PATH")}",
                "LD_LIBRARY_PATH" to context.applicationInfo.nativeLibraryDir,
            ),
            onLog = ::handleLog,
            onExit = ::handleExit,
        )
        process = proc
        proc.start()
        // We optimistically move to Running; a fast crash will flip us back.
        ServerController.setState(
            ServerState.Running(launchSpec.config.host, launchSpec.config.port, launchSpec.config.selectedModelId),
        )
    }

    /** Build the whisper-server argument list from config. */
    fun buildCommand(launchSpec: LaunchSpec): List<String> {
        val c = launchSpec.config
        val args = mutableListOf(
            binaryFile().absolutePath,
            "--model", launchSpec.modelPath,
            "--host", c.host,
            "--port", c.port.toString(),
            "--threads", c.threads.toString(),
            // Map the inference endpoint to the OpenAI-compatible path.
            "--inference-path", inferencePath,
        )
        if (c.language.isNotBlank() && c.language != "auto") {
            args += listOf("--language", c.language)
        } else {
            args += listOf("--language", "auto")
        }
        if (c.translate) args += "--translate"
        if (c.convertAudio) args += "--convert"
        if (c.vad) args += "--vad"
        return args
    }

    private fun handleLog(level: LogLevel, line: String) {
        ServerController.appendLog(level, line)
        maybeRecordStats(line)
    }

    /**
     * Best-effort stats extraction from the server's log output. Formats vary by
     * whisper.cpp version, so this is heuristic: it counts requests hitting the
     * inference path and, when present, parses a processing/total time in ms.
     */
    private fun maybeRecordStats(line: String) {
        val lower = line.lowercase()
        val isRequest = inferencePath in line ||
            (("post" in lower || "request" in lower) && "audio" in lower)
        if (isRequest) {
            val ms = TIME_MS_REGEX.find(lower)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toLong()
            ServerController.recordRequest(ms)
        }
    }

    private fun handleExit(code: Int) {
        if (intentionalStop) {
            ServerController.setState(ServerState.Stopped)
            ServerController.onServerStopped()
            return
        }
        // Unexpected exit -> apply restart budget.
        val now = System.currentTimeMillis()
        crashTimestamps.addLast(now)
        while (crashTimestamps.isNotEmpty() && now - crashTimestamps.first() > RESTART_WINDOW_MS) {
            crashTimestamps.removeFirst()
        }

        if (crashTimestamps.size <= MAX_RESTARTS) {
            val attempt = crashTimestamps.size
            ServerController.appendLog(
                LogLevel.WARN,
                "Server crashed (code $code). Auto-restarting ($attempt/$MAX_RESTARTS)…",
            )
            ServerController.setState(ServerState.Restarting)
            val toLaunch = spec
            if (toLaunch != null) {
                scope.launch {
                    delay(RESTART_DELAY_MS)
                    if (!intentionalStop) launchProcess(toLaunch)
                }
            }
        } else {
            val msg = "Server crashed $MAX_RESTARTS times within " +
                "${RESTART_WINDOW_MS / 1000}s. Giving up."
            Log.e(TAG, msg)
            ServerController.appendLog(LogLevel.ERROR, msg)
            ServerController.setState(ServerState.Error(msg))
            ServerController.onServerStopped()
            onFatalError(msg)
        }
    }

    fun stop() {
        intentionalStop = true
        process?.stop()
        process = null
        ServerController.setState(ServerState.Stopped)
        ServerController.onServerStopped()
    }

    val isRunning: Boolean
        get() = process?.isRunning == true

    companion object {
        private const val TAG = "WhisperBridge"
        private const val BINARY_SO = "libwhisper-server.so"
        private const val MAX_RESTARTS = 3
        private const val RESTART_WINDOW_MS = 5 * 60 * 1000L
        private const val RESTART_DELAY_MS = 2_000L
        private val TIME_MS_REGEX = Regex("""(\d+(?:\.\d+)?)\s*ms""")
    }
}
