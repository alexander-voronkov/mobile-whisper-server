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
 * [ServerProcess]) plus a small [LocalProxyServer] that fronts it. Layout:
 *
 *   client --> LocalProxyServer (config.host:config.port, Bearer auth,
 *              /health + /v1/models) --> whisper-server (127.0.0.1:internalPort)
 *
 * Responsibilities:
 *  - locate the executable (shipped as a native `lib*.so` so it lands in the
 *    read-only, exec-allowed nativeLibraryDir — required on Android 10+),
 *  - translate a [LaunchSpec] into command-line arguments,
 *  - own the process + proxy lifecycle and the crash auto-restart policy
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
    private var proxy: LocalProxyServer? = null
    private var spec: LaunchSpec? = null
    private var internalPort: Int = 0

    @Volatile
    private var intentionalStop = false

    // Monotonic id of the current process launch. Exit callbacks carry the id
    // they were created for; a callback whose id != [generation] belongs to a
    // superseded process (from a restart/quick stop-start) and is ignored.
    private var generation = 0

    // Sliding window of recent crash timestamps for the restart budget.
    private val crashTimestamps = ArrayDeque<Long>()

    /**
     * The server executable. Packaged as `libwhisper-server.so` in jniLibs so
     * that at install time it lands in nativeLibraryDir, which is one of the few
     * app-associated locations Android still permits executing from.
     */
    fun binaryFile(): File = File(context.applicationInfo.nativeLibraryDir, BINARY_SO)

    fun isBinaryAvailable(): Boolean = binaryFile().let { it.exists() && it.canExecute() }

    private fun ffmpegBinary(): File = File(context.applicationInfo.nativeLibraryDir, FFMPEG_SO)

    fun start(launchSpec: LaunchSpec) {
        if (process?.isRunning == true) {
            ServerController.appendLog(LogLevel.WARN, "Server already running; ignoring start request")
            return
        }
        val binary = binaryFile()
        if (!binary.exists()) {
            val msg = "whisper-server binary not found at ${binary.absolutePath}. " +
                "Build it with ./gradlew buildWhisperNative (see README)."
            fail(msg)
            return
        }
        if (!File(launchSpec.modelPath).exists()) {
            fail("Model file not found: ${launchSpec.modelPath}. Download a model first.")
            return
        }

        spec = launchSpec
        internalPort = internalPortFor(launchSpec.config.port)
        intentionalStop = false
        crashTimestamps.clear()
        ServerController.setState(ServerState.Starting)
        ServerController.onServerStarted()

        // Start the native server (bound to localhost), then the public proxy.
        // Each helper reports its own fatal error (via fail()) and tears down on
        // failure, so we simply stop here.
        if (!launchProcess(launchSpec)) return
        if (!startProxy(launchSpec)) return
    }

    /** Returns true if the process was launched (false = fatal error already reported). */
    private fun launchProcess(launchSpec: LaunchSpec): Boolean {
        val gen = ++generation
        val command = buildCommand(launchSpec, internalPort)
        val proc = ServerProcess(
            command = command,
            workingDir = context.filesDir,
            environment = mapOf(
                // Make bundled ffmpeg (if shipped) discoverable for --convert.
                "PATH" to "${context.applicationInfo.nativeLibraryDir}:${System.getenv("PATH")}",
                "LD_LIBRARY_PATH" to context.applicationInfo.nativeLibraryDir,
            ),
            onLog = ::handleLog,
            onExit = { code -> handleExit(gen, code) },
        )
        process = proc
        return try {
            proc.start()
            ServerController.setState(
                ServerState.Running(launchSpec.config.host, launchSpec.config.port, launchSpec.config.selectedModelId),
            )
            true
        } catch (e: Exception) {
            // ProcessBuilder.start() can throw (exec format error, permission denied).
            Log.e(TAG, "Failed to start server process", e)
            fail("Failed to start whisper-server: ${e.message}")
            false
        }
    }

    private fun startProxy(launchSpec: LaunchSpec): Boolean {
        return try {
            proxy = LocalProxyServer(
                bindHost = launchSpec.config.host,
                bindPort = launchSpec.config.port,
                upstreamPort = internalPort,
                apiKey = launchSpec.apiKey,
                onLog = ::handleLog,
            ).also { it.start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind proxy on ${launchSpec.config.host}:${launchSpec.config.port}", e)
            fail("Cannot bind ${launchSpec.config.host}:${launchSpec.config.port}: ${e.message}. Port in use?")
            false
        }
    }

    /** Build the whisper-server argument list from config (bound to localhost). */
    fun buildCommand(launchSpec: LaunchSpec, port: Int): List<String> {
        val c = launchSpec.config
        val args = mutableListOf(
            binaryFile().absolutePath,
            "--model", launchSpec.modelPath,
            "--host", "127.0.0.1", // public exposure is handled by the proxy
            "--port", port.toString(),
            "--threads", c.threads.toString(),
            // Map the inference endpoint to the OpenAI-compatible path.
            "--inference-path", inferencePath,
        )
        val language = if (c.language.isBlank()) "auto" else c.language
        args += listOf("--language", language)
        if (c.translate) args += "--translate"
        if (c.convertAudio) {
            if (ffmpegBinary().exists()) {
                args += "--convert"
            } else {
                ServerController.appendLog(
                    LogLevel.WARN,
                    "Convert-audio is enabled but no ffmpeg (libffmpeg.so) is bundled; skipping --convert. " +
                        "Uploads must be 16 kHz WAV.",
                )
            }
        }
        // NOTE: --vad is intentionally NOT passed. The pinned whisper.cpp server
        // build does not parse it (it would exit via the unknown-argument path),
        // and VAD additionally needs a separate --vad-model file we don't ship.
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

    private fun handleExit(gen: Int, code: Int) {
        // Ignore exits from a process we've already superseded (restart / stop).
        if (gen != generation) {
            ServerController.appendLog(LogLevel.DEBUG, "Ignoring exit from superseded process (code $code)")
            return
        }
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
            fail(msg)
        }
    }

    fun stop() {
        intentionalStop = true
        // Invalidate any in-flight exit callback from the current process.
        generation++
        proxy?.stop()
        proxy = null
        process?.stop() // returns immediately; teardown happens off-thread
        process = null
        ServerController.setState(ServerState.Stopped)
        ServerController.onServerStopped()
    }

    /** Report a fatal error: tear down and notify the service. */
    private fun fail(message: String) {
        generation++
        ServerController.appendLog(LogLevel.ERROR, message)
        ServerController.setState(ServerState.Error(message))
        ServerController.onServerStopped()
        proxy?.stop()
        proxy = null
        process?.stop()
        process = null
        onFatalError(message)
    }

    val isRunning: Boolean
        get() = process?.isRunning == true

    private fun internalPortFor(publicPort: Int): Int =
        if (publicPort < 65535) publicPort + 1 else publicPort - 1

    companion object {
        private const val TAG = "WhisperBridge"
        private const val BINARY_SO = "libwhisper-server.so"
        private const val FFMPEG_SO = "libffmpeg.so"
        private const val MAX_RESTARTS = 3
        private const val RESTART_WINDOW_MS = 5 * 60 * 1000L
        private const val RESTART_DELAY_MS = 2_000L
        private val TIME_MS_REGEX = Regex("""(\d+(?:\.\d+)?)\s*ms""")
    }
}
