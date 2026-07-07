package com.example.whisperserver.native

import com.example.whisperserver.service.LogLevel
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tiny HTTP front for the native whisper-server. whisper.cpp's server has no
 * built-in auth and doesn't expose `/health` or `/v1/models`, so we bind the
 * user-facing `host:port` here and:
 *  - enforce `Authorization: Bearer <apiKey>` when a key is configured,
 *  - answer `GET /health` and `GET /v1/models` directly,
 *  - forward everything else (notably `POST /v1/audio/transcriptions`) to the
 *    native server on `127.0.0.1:upstreamPort`.
 *
 * This is a deliberately small HTTP/1.1 implementation: it supports
 * Content-Length request bodies (what multipart uploads use) and closes the
 * connection after each response (`Connection: close`). It is not a general
 * proxy — it exists only to satisfy the app's auth + compatibility endpoints.
 */
class LocalProxyServer(
    private val bindHost: String,
    private val bindPort: Int,
    private val upstreamPort: Int,
    private val apiKey: String,
    private val onLog: (LogLevel, String) -> Unit,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private var acceptThread: Thread? = null
    // Bounded pool so a burst of (possibly stalled) connections can't spawn
    // unbounded threads. Stalled sockets are reaped by the read timeout below.
    private val workers = Executors.newFixedThreadPool(MAX_WORKERS)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES) // transcription of long audio can be slow
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        val address = if (bindHost == "0.0.0.0") {
            InetSocketAddress(bindPort)
        } else {
            InetSocketAddress(InetAddress.getByName(bindHost), bindPort)
        }
        socket.bind(address)
        serverSocket = socket
        running = true

        acceptThread = Thread({ acceptLoop(socket) }, "whisper-proxy-accept").apply {
            isDaemon = true
            start()
        }
        onLog(LogLevel.INFO, "Proxy listening on $bindHost:$bindPort -> 127.0.0.1:$upstreamPort" +
            if (apiKey.isNotBlank()) " (API key required)" else "")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val conn = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) onLog(LogLevel.WARN, "Proxy accept error: ${e.message}")
                break
            }
            workers.execute { handleConnection(conn) }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use {
            try {
                // Per-read inactivity timeout: a client that connects and never
                // sends (or stalls mid-stream) is dropped instead of pinning a
                // worker thread forever. A steadily-uploading client won't trip it.
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 3) {
                    writeResponse(output, 400, "Bad Request", ERR_BAD_REQUEST)
                    return
                }
                val method = parts[0].uppercase()
                val path = parts[1]

                val headers = readHeaders(input)

                // Auth (applies to all routes when a key is configured).
                if (apiKey.isNotBlank() && !isAuthorized(headers)) {
                    writeResponse(output, 401, "Unauthorized", ERR_UNAUTHORIZED)
                    return
                }

                // App-served endpoints.
                when {
                    method == "GET" && path == "/health" -> {
                        writeResponse(output, 200, "OK", HEALTH_JSON)
                        return
                    }
                    method == "GET" && path == "/v1/models" -> {
                        writeResponse(output, 200, "OK", MODELS_JSON)
                        return
                    }
                }

                forward(method, path, headers, input, output)
            } catch (e: Exception) {
                onLog(LogLevel.WARN, "Proxy request error: ${e.message}")
            }
        }
    }

    private fun forward(
        method: String,
        path: String,
        headers: List<Pair<String, String>>,
        input: InputStream,
        output: BufferedOutputStream,
    ) {
        val contentLength = headers.firstOrNull { it.first.equals("Content-Length", true) }
            ?.second?.trim()?.toLongOrNull() ?: 0L
        val contentType = headers.firstOrNull { it.first.equals("Content-Type", true) }?.second

        val forwardHeaders = headers
            .filterNot { (name, _) ->
                val l = name.lowercase()
                l in HOP_BY_HOP || l == "host" || l == "authorization" ||
                    l == "content-length" || l == "content-type"
            }
            .toMap()
            .toHeaders()

        val hasBody = contentLength > 0
        val body: RequestBody? = when {
            method == "GET" || method == "HEAD" -> null
            hasBody -> streamingBody(input, contentLength, contentType)
            else -> EMPTY_BODY
        }

        val request = Request.Builder()
            .url("http://127.0.0.1:$upstreamPort$path")
            .headers(forwardHeaders)
            .method(method, body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyBytes = response.body?.bytes() ?: ByteArray(0)
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ${response.code} ${response.message}\r\n")
                for ((name, value) in response.headers) {
                    val l = name.lowercase()
                    if (l in HOP_BY_HOP || l == "content-length" || l == "transfer-encoding") continue
                    sb.append("$name: $value\r\n")
                }
                sb.append("Content-Length: ${bodyBytes.size}\r\n")
                sb.append("Connection: close\r\n\r\n")
                output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                output.write(bodyBytes)
                output.flush()
            }
        } catch (e: Exception) {
            // Upstream not reachable (server still starting or crashed).
            writeResponse(output, 502, "Bad Gateway", ERR_BAD_GATEWAY)
        }
    }

    private fun streamingBody(input: InputStream, length: Long, contentTypeHeader: String?): RequestBody =
        object : RequestBody() {
            override fun contentType() = contentTypeHeader?.toMediaTypeOrNull()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }

    private fun isAuthorized(headers: List<Pair<String, String>>): Boolean {
        val auth = headers.firstOrNull { it.first.equals("Authorization", true) }?.second?.trim()
            ?: return false
        return auth == "Bearer $apiKey"
    }

    /** Reads a single CRLF-terminated line as ASCII, or null at EOF. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) return sb.toString() // guard against abuse
        }
    }

    private fun readHeaders(input: InputStream): List<Pair<String, String>> {
        val headers = mutableListOf<Pair<String, String>>()
        while (headers.size < MAX_HEADERS) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break // end of headers
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers.add(line.substring(0, idx).trim() to line.substring(idx + 1).trim())
        }
        return headers
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        code: Int,
        statusText: String,
        json: String,
    ) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $statusText\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        workers.shutdownNow()
        onLog(LogLevel.INFO, "Proxy stopped")
    }

    companion object {
        private const val MAX_LINE = 16 * 1024
        private const val MAX_HEADERS = 100
        private const val MAX_WORKERS = 16
        private const val SOCKET_TIMEOUT_MS = 30_000

        private val HOP_BY_HOP = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade",
        )

        private val EMPTY_BODY: RequestBody =
            object : RequestBody() {
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength() = 0L
                override fun writeTo(sink: BufferedSink) {}
            }

        private const val HEALTH_JSON = """{"status":"ok"}"""
        private const val MODELS_JSON = """{"data":[{"id":"whisper-1","object":"model"}]}"""
        private const val ERR_UNAUTHORIZED = """{"error":{"message":"Invalid or missing API key","type":"invalid_request_error"}}"""
        private const val ERR_BAD_REQUEST = """{"error":{"message":"Bad request","type":"invalid_request_error"}}"""
        private const val ERR_BAD_GATEWAY = """{"error":{"message":"Whisper server not reachable","type":"server_error"}}"""
    }
}
