package com.example.whisperserver.native

import android.util.Log
import com.example.whisperserver.service.LogLevel
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Thin wrapper around a single native `whisper-server` child process (MVP
 * subprocess option). Streams merged stdout/stderr to [onLog] line-by-line and
 * invokes [onExit] once, with the process exit code, when the process ends.
 *
 * This class manages exactly one run; restart policy lives in [WhisperBridge].
 */
class ServerProcess(
    private val command: List<String>,
    private val workingDir: File,
    private val environment: Map<String, String> = emptyMap(),
    private val onLog: (LogLevel, String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var stoppedByUs = false

    private var readerThread: Thread? = null
    private var waitThread: Thread? = null

    val isRunning: Boolean
        get() = process?.isAlive == true

    val pid: Long?
        get() = try {
            process?.pid()
        } catch (_: Throwable) {
            null
        }

    fun start() {
        val builder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
        builder.environment().putAll(environment)

        onLog(LogLevel.INFO, "\$ ${command.joinToString(" ")}")
        val proc = builder.start()
        process = proc

        readerThread = Thread({ pumpOutput(proc) }, "whisper-log-reader").apply {
            isDaemon = true
            start()
        }

        waitThread = Thread({
            val code = try {
                proc.waitFor()
            } catch (e: InterruptedException) {
                -1
            }
            if (!stoppedByUs) {
                onLog(LogLevel.ERROR, "Server process exited with code $code")
            } else {
                onLog(LogLevel.INFO, "Server process stopped (code $code)")
            }
            onExit(code)
        }, "whisper-wait").apply {
            isDaemon = true
            start()
        }
    }

    private fun pumpOutput(proc: Process) {
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: continue
                    onLog(classify(text), text)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Output pump ended: ${e.message}")
        }
    }

    private fun classify(line: String): LogLevel {
        val lower = line.lowercase()
        return when {
            "error" in lower || "failed" in lower || "cannot" in lower -> LogLevel.ERROR
            "warn" in lower -> LogLevel.WARN
            else -> LogLevel.INFO
        }
    }

    /**
     * Requests process termination and returns immediately. `destroy()` only
     * sends a signal (non-blocking); the up-to-3s wait for graceful exit (and
     * the forcible kill fallback) run on a daemon thread so callers on the main
     * thread never block. Process exit still fires [onExit] via the wait thread.
     */
    fun stop() {
        stoppedByUs = true
        val proc = process ?: return
        proc.destroy() // non-blocking SIGTERM
        readerThread?.interrupt()
        Thread({
            try {
                if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping process", e)
                try {
                    proc.destroyForcibly()
                } catch (_: Exception) {
                }
            }
        }, "whisper-stop").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        private const val TAG = "ServerProcess"
    }
}
