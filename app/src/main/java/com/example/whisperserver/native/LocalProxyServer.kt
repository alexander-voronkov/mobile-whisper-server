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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
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
    private val inferencePath: String,
    private val onLog: (LogLevel, String) -> Unit,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private var acceptThread: Thread? = null
    // Bounded pool AND bounded queue so a burst of (possibly stalled) connections
    // can't spawn unbounded threads or hold unbounded open sockets. When both are
    // full, execute() throws RejectedExecutionException and the accept loop closes
    // the excess socket instead of leaking it.
    private val workers = ThreadPoolExecutor(
        MAX_WORKERS, MAX_WORKERS, 60L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(MAX_QUEUE),
    )

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
            // Arm the read timeout before queueing so even a socket that waits in
            // the queue can't later pin a worker forever.
            try {
                conn.soTimeout = SOCKET_TIMEOUT_MS
            } catch (_: Exception) {
            }
            try {
                workers.execute { handleConnection(conn) }
            } catch (e: Exception) {
                // Pool + queue saturated: drop this connection rather than leak it.
                onLog(LogLevel.WARN, "Proxy overloaded; dropping connection")
                try {
                    conn.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use {
            try {
                // soTimeout was armed in acceptLoop before this was queued.
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
                val pathOnly = path.substringBefore('?')
                when {
                    method == "GET" && pathOnly == "/health" -> {
                        writeResponse(output, 200, "OK", HEALTH_JSON)
                        return
                    }
                    method == "GET" && pathOnly == "/v1/models" -> {
                        writeResponse(output, 200, "OK", MODELS_JSON)
                        return
                    }
                }

                // Only the transcription endpoint is forwarded. Everything else is
                // rejected so whisper.cpp's localhost-only admin routes (e.g.
                // POST /load) are never reachable from the LAN/Tailscale.
                if (pathOnly != inferencePath) {
                    writeResponse(output, 404, "Not Found", ERR_NOT_FOUND)
                    return
                }
                if (method != "POST") {
                    writeResponse(output, 405, "Method Not Allowed", ERR_METHOD_NOT_ALLOWED)
                    return
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
                    l == "content-length" || l == "content-type" || l == "expect"
            }
            .toMap()
            .toHeaders()

        val hasBody = contentLength > 0

        // Honour Expect: 100-continue (curl uses it for multi-MB -F uploads): the
        // client withholds the body until it sees an interim 100, so send it
        // before we start reading the body — otherwise the upload deadlocks.
        val expectsContinue = headers.any {
            it.first.equals("Expect", true) && it.second.contains("100-continue", true)
        }
        if (hasBody && expectsContinue) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

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
        private const val MAX_QUEUE = 32
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
        private const val ERR_NOT_FOUND = """{"error":{"message":"Not found","type":"invalid_request_error"}}"""
        private const val ERR_METHOD_NOT_ALLOWED = """{"error":{"message":"Method not allowed","type":"invalid_request_error"}}"""
    }
}
