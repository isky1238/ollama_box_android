package com.ollamabox

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor

/**
 * Ollama-compatible HTTP gateway for Android.
 *
 * Architecture:
 *   Chatbox → :11434 (this gateway, Ollama-native API)
 *               → :11435 (llama.cpp HTTP server, internal inference engine)
 *
 * This is a PURE pass-through gateway — it translates Ollama API paths
 * to OpenAI-compatible paths and translates response formats back.
 * It does NOT inject or override any request parameters (max_tokens,
 * reasoning_format, etc.). All parameters are passed through as-is.
 * Server-level config (enable_thinking, n_ctx, threads) is set at
 * llama.cpp startup time via JNI.
 *
 * Lifecycle:
 *   start()  — begins accepting connections (non-blocking)
 *   stop()   — closes server socket, stops accepting
 *   join()   — waits for the server thread to exit
 */
class OllamaServer(
    private val backendHost: String = "127.0.0.1",
    private val backendPort: Int = 11435,
    private val listenPort: Int = 11434,
    private val modelName: String = "",
    private val log: (String) -> Unit = {}
) {
    @Volatile var running = false
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16)
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ── Public API ──────────────────────────────────────────────────

    fun start() {
        if (running) return
        running = true
        serverThread = Thread(this::runLoop, "OllamaServer")
        serverThread!!.isDaemon = true
        serverThread!!.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        executor.shutdownNow()
        try { executor.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun join(timeout: Long = 2000) {
        try { serverThread?.join(timeout) } catch (_: Exception) {}
    }

    // ── Server loop ─────────────────────────────────────────────────

    private fun runLoop() {
        try {
            serverSocket = ServerSocket(listenPort, 50)
            serverSocket!!.reuseAddress = true
            log("Ollama 服务启动 http://127.0.0.1:$listenPort (模型: $modelName)")

            while (running) {
                val client: Socket
                try {
                    client = serverSocket!!.accept()
                } catch (_: Exception) {
                    if (!running) break else continue
                }
                clients.add(client)
                try {
                    executor.execute { handleClient(client) }
                } catch (_: Exception) {
                    clients.remove(client)
                    try { client.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            log("OllamaServer 启动失败: ${e.message}")
        } finally {
            try { serverSocket?.close() } catch (_: Exception) {}
            log("Ollama 服务已停止")
        }
    }

    // ── HTTP utilities ──────────────────────────────────────────────

    /** Read a CRLF-terminated line. Returns "" on EOF. */
    private fun readLine(input: InputStream, maxBytes: Int = 8192): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) break
            if (b == '\r'.code) {
                input.read() // consume \n
                break
            }
            sb.append(b.toChar())
            if (sb.length > maxBytes) throw IllegalArgumentException("HTTP line too long")
        }
        return sb.toString()
    }

    /** Read exactly [count] bytes (or fewer on EOF). */
    private fun readBytes(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var total = 0
        while (total < count) {
            val n = input.read(buf, total, count - total)
            if (n < 0) break
            total += n
        }
        return buf
    }

    /** Read a chunked transfer-encoding body. */
    private fun readChunked(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input)
            val chunkSize = sizeLine.trim().toIntOrNull(16) ?: 0
            if (chunkSize == 0) break
            if (chunkSize > MAX_BODY_BYTES || out.size() + chunkSize > MAX_BODY_BYTES) {
                throw IllegalArgumentException("request body too large")
            }
            out.write(readBytes(input, chunkSize))
            readLine(input) // trailing CRLF
        }
        readLine(input) // final empty line
        return out.toByteArray()
    }

    private fun writeResponse(
        out: OutputStream, code: Int, body: ByteArray,
        contentType: String = "application/json; charset=utf-8"
    ) {
        val status = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 413 -> "Payload Too Large"
            500 -> "Internal Error"; 502 -> "Bad Gateway"; 503 -> "Unavailable"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        try {
            out.write(header.toByteArray())
            out.write(body)
            out.flush()
        } catch (_: Exception) {}
    }

    // ── JSON helpers (no org.json) ──────────────────────────────────

    /** Navigate into a JSON string by key path. Returns the tail starting
     *  after the colon following the last key, trimmed. */
    private fun jsonFind(s: String, vararg keys: String): String? {
        var cur = s
        for (k in keys) {
            val idx = cur.indexOf("\"$k\"")
            if (idx < 0) return null
            cur = cur.substring(idx + k.length + 2)
            val colon = cur.indexOf(':')
            if (colon < 0) return null
            cur = cur.substring(colon + 1).trimStart()
        }
        return cur.trimStart()
    }

    /** Extract the single JSON value at the start of [s] — a quoted
     *  string, a nested {…} or […], or an unquoted token. */
    private fun jsonValue(s: String): Pair<String, Int>? {
        if (s.isEmpty()) return null
        return when (s[0]) {
            '"' -> {
                val sb = StringBuilder()
                var i = 1
                while (i < s.length) {
                    val ch = s[i]
                    if (ch == '\\') { sb.append(s.getOrElse(++i) { '\\' }); i++ }
                    else if (ch == '"') { i++; break }
                    else { sb.append(ch); i++ }
                }
                sb.toString() to i
            }
            '{' -> extractBrace(s, '{', '}')
            '[' -> extractBrace(s, '[', ']')
            else -> {
                val end = s.indexOfFirst { it.isWhitespace() || it == ',' || it == '}' || it == ']' }
                val len = if (end < 0) s.length else end
                s.substring(0, len) to len
            }
        }
    }

    private fun extractBrace(s: String, open: Char, close: Char): Pair<String, Int>? {
        var depth = 0
        var inString = false
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (inString) {
                if (ch == '\\') i++
                else if (ch == '"') inString = false
            } else {
                if (ch == '"') inString = true
                else if (ch == open) depth++
                else if (ch == close) { depth--; if (depth == 0) { i++; break } }
            }
            i++
        }
        return s.substring(0, i) to i
    }

    private fun jsonString(s: String, vararg keys: String): String {
        val tail = jsonFind(s, *keys) ?: return ""
        val v = jsonValue(tail) ?: return ""
        return v.first
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"'  -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code))
                         else sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    // ── Backend round-trip ──────────────────────────────────────────

    /**
     * Send [reqBody] to the backend [path], return (statusCode, responseBody).
     * Pure pass-through — no parameter injection or modification.
     */
    private fun backendRequest(method: String, path: String, reqBody: ByteArray): Pair<Int, ByteArray> {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(backendHost, backendPort), 5000)
            sock.soTimeout = 300000 // 5 min timeout for slow generation
            val beIn = sock.inputStream
            val beOut = sock.outputStream

            // Send request
            val header = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: $backendHost:$backendPort\r\n")
                if (reqBody.isNotEmpty()) {
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${reqBody.size}\r\n")
                }
                append("Connection: close\r\n")
                append("\r\n")
            }
            beOut.write(header.toByteArray())
            if (reqBody.isNotEmpty()) beOut.write(reqBody)
            beOut.flush()

            // Read status line
            val statusLine = readLine(beIn)
            if (statusLine.isBlank()) return 502 to """{"error":"empty backend response"}""".toByteArray()
            val parts = statusLine.split(" ")
            val code = parts.getOrElse(1) { "500" }.toIntOrNull() ?: 500

            // Read headers
            var cl = 0; var chunked = false
            while (true) {
                val line = readLine(beIn)
                if (line.isEmpty()) break
                val col = line.indexOf(':')
                if (col > 0) {
                    val k = line.substring(0, col).trim().lowercase()
                    val v = line.substring(col + 1).trim()
                    if (k == "content-length") cl = v.toIntOrNull() ?: 0
                    if (k == "transfer-encoding" && v.contains("chunked")) chunked = true
                }
            }

            // Read body
            val respBytes = when {
                chunked -> readChunked(beIn)
                cl > 0  -> readBytes(beIn, cl)
                else    -> ByteArray(0)
            }
            sock.close()
            return code to respBytes
        } catch (e: Exception) {
            try { sock.close() } catch (_: Exception) {}
            log("backend ${e.javaClass.simpleName}: ${e.message}")
            return 502 to """{"error":"backend unreachable: ${e.message}"}""".toByteArray()
        }
    }

    /**
     * Override the "model" field value to match the loaded GGUF filename.
     * This is a necessary gateway function — llama.cpp backend requires the
     * model name in the request to match the loaded model filename.
     */
    private fun overrideModelName(body: ByteArray): ByteArray {
        if (modelName.isEmpty()) return body
        val s = String(body, Charsets.UTF_8)
        val replacement = "\"model\":${jsonEscape(modelName)}"
        return Regex("\"model\"\\s*:\\s*\"[^\"]*\"").replace(s) { replacement }.toByteArray()
    }

    /** Current time in ISO-8601 format (Ollama created_at format). */
    private fun createdAt(): String = synchronized(dateFormat) { dateFormat.format(Date()) }

    /** Stream proxy: sends raw bytes bidirectionally for SSE streaming.
     *  Writes backend response headers + body directly to client. */
    private fun streamProxy(client: Socket, path: String, reqBody: ByteArray) {
        val beSock = Socket()
        try {
            beSock.connect(InetSocketAddress(backendHost, backendPort), 5000)
            beSock.soTimeout = 300000
            val beIn = beSock.inputStream
            val beOut = beSock.outputStream
            val clOut = client.outputStream

            // Forward request to backend — pure pass-through
            val header = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: $backendHost:$backendPort\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${reqBody.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            beOut.write(header.toByteArray())
            beOut.write(reqBody)
            beOut.flush()

            // Read backend response headers and forward to client
            val buf = ByteArray(8192)
            var headerDone = false
            val headerBuf = StringBuilder()
            while (!headerDone) {
                val b = beIn.read()
                if (b < 0) break
                headerBuf.append(b.toChar())
                clOut.write(b)
                // Detect end of headers: \r\n\r\n
                val s = headerBuf.toString()
                if (s.endsWith("\r\n\r\n")) {
                    clOut.flush()
                    headerDone = true
                }
            }

            // Stream body bytes until backend closes
            var n: Int
            while (beIn.read(buf).also { n = it } > 0) {
                clOut.write(buf, 0, n)
                clOut.flush()
            }
        } catch (e: Exception) {
            log("stream: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { beSock.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    // ── API handlers ────────────────────────────────────────────────

    private fun handleClient(client: Socket) {
        val startedAt = System.currentTimeMillis()
        var method = "?"
        var rawPath = "?"
        var status = 500
        try {
            client.soTimeout = 30000
            val input = client.inputStream
            val output = client.outputStream

            // Parse request line
            val requestLine = readLine(input)
            if (requestLine.isBlank()) { client.close(); return }
            val parts = requestLine.split(" ")
            if (parts.size < 2) { client.close(); return }
            method = parts[0].uppercase()
            rawPath = parts[1]

            // Read headers
            var contentLength = 0
            var isChunked = false
            var headerCount = 0
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                if (++headerCount > MAX_HEADER_COUNT) throw IllegalArgumentException("too many headers")
                val col = line.indexOf(':')
                if (col > 0) {
                    val k = line.substring(0, col).trim().lowercase()
                    val v = line.substring(col + 1).trim()
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                    if (k == "transfer-encoding" && v.contains("chunked")) isChunked = true
                }
            }
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                status = 413
                writeResponse(output, status, """{"error":"request body too large"}""".toByteArray())
                return
            }

            // Read body
            val bodyBytes = when {
                contentLength > 0 -> readBytes(input, contentLength)
                isChunked -> readChunked(input)
                else -> ByteArray(0)
            }
            val body = String(bodyBytes, Charsets.UTF_8)

            // Detect streaming OpenAI requests — proxy raw bytes
            val isStreaming = rawPath == "/v1/chat/completions" &&
                method == "POST" &&
                STREAM_TRUE.containsMatchIn(body)

            if (isStreaming) {
                status = 200
                streamProxy(client, rawPath, overrideModelName(bodyBytes))
            } else {
                val response = route(method, rawPath, bodyBytes, body)
                status = response.first
                writeResponse(output, response.first, response.second)
            }
        } catch (e: IllegalArgumentException) {
            status = 400
            try {
                writeResponse(client.outputStream, status, """{"error":${jsonEscape(e.message ?: "bad request")}}""".toByteArray())
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            log("handle: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            clients.remove(client)
            try { client.close() } catch (_: Exception) {}
            log("API: $method $rawPath status=$status durationMs=${System.currentTimeMillis() - startedAt}")
        }
    }

    /** Route a request to the correct handler. Returns (status, body). */
    private fun route(method: String, path: String, bodyBytes: ByteArray, body: String): Pair<Int, ByteArray> {
        return when {
            method == "GET" && (path == "/" || path == "") ->
                200 to statusPage()

            method == "GET" && path == "/health" -> {
                val (code, resp) = backendRequest("GET", "/health", ByteArray(0))
                code to resp
            }

            method == "GET" && path == "/api/version" ->
                200 to """{"version":"2.0.0-ollamabox"}""".toByteArray()

            method == "GET" && path == "/api/tags" -> {
                val (code, resp) = backendRequest("GET", "/v1/models", ByteArray(0))
                code to translateTags(String(resp, Charsets.UTF_8))
            }

            // Ollama-native chat: translate path + response format, pass body unchanged
            method == "POST" && path == "/api/chat" -> {
                val req = overrideModelName(bodyBytes)
                val (code, resp) = backendRequest("POST", "/v1/chat/completions", req)
                if (code >= 400) code to resp
                else code to translateChat(String(resp, Charsets.UTF_8))
            }

            method == "POST" && (path == "/api/generate" || path == "/v1/completions") -> {
                val req = overrideModelName(bodyBytes)
                val (code, resp) = backendRequest("POST", "/v1/completions", req)
                if (code >= 400) code to resp
                else code to translateGenerate(String(resp, Charsets.UTF_8))
            }

            // OpenAI-compatible /v1/chat/completions: passthrough with model override
            method == "POST" && path == "/v1/chat/completions" -> {
                val isStreaming = STREAM_TRUE.containsMatchIn(body)
                if (isStreaming) {
                    // Stream passthrough — handled separately via streamProxy
                    200 to """{"error":"internal: use stream handler"}""".toByteArray()
                } else {
                    val req = overrideModelName(bodyBytes)
                    val (code, resp) = backendRequest("POST", "/v1/chat/completions", req)
                    code to resp // raw OpenAI response, no translation
                }
            }

            // Pass-through for other backend paths
            path.startsWith("/v1/") || path == "/slots" || path == "/metrics" -> {
                val (code, resp) = backendRequest(method, path, bodyBytes)
                code to resp
            }

            else -> 404 to """{"error":"not found: $method $path"}""".toByteArray()
        }
    }

    // ── Status page ─────────────────────────────────────────────────

    private fun statusPage(): ByteArray {
        val body = buildString {
            append("{")
            append("\"service\":\"OllamaBox v2.0.0\",")
            append("\"status\":\"running\",")
            append("\"model\":\"$modelName\",")
            append("\"endpoints\":{")
            append("\"chat\":\"POST /api/chat\",")
            append("\"tags\":\"GET /api/tags\",")
            append("\"version\":\"GET /api/version\",")
            append("\"health\":\"GET /health\"")
            append("},")
            append("\"connect\":\"http://127.0.0.1:$listenPort\"")
            append("}")
        }
        return body.toByteArray()
    }

    companion object {
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private const val MAX_HEADER_COUNT = 100
        private val STREAM_TRUE = Regex("\"stream\"\\s*:\\s*true")
    }

    // ── Response translators ────────────────────────────────────────

    /**
     * Translate OpenAI chat response → Ollama chat response.
     *
     * OpenAI:
     *   {"choices":[{"message":{"role":"assistant","content":"hello"}}],"model":"foo",...}
     *
     * Ollama:
     *   {"model":"foo","message":{"role":"assistant","content":"hello"},"done":true}
     */
    private fun translateChat(backendBody: String): ByteArray {
        // If already Ollama format (has "message" at top level), return as-is
        if (backendBody.contains("\"message\"") &&
            jsonFind(backendBody, "choices") == null) {
            return backendBody.toByteArray()
        }

        val model = jsonString(backendBody, "model").ifEmpty { modelName }
        val choices = jsonFind(backendBody, "choices") ?: ""

        var content = ""
        var role = "assistant"

        if (choices.startsWith("[")) {
            val firstObj = jsonValue(choices.substring(1))?.first ?: ""
            if (firstObj.startsWith("{")) {
                val msg = jsonFind(firstObj, "message")
                if (msg != null) {
                    role = jsonString(firstObj, "message", "role").ifEmpty { "assistant" }
                    content = jsonString(firstObj, "message", "content")
                }
            }
        }

        val result = buildString {
            append("{")
            append("\"model\":\"$model\",")
            append("\"created_at\":\"${createdAt()}\",")
            append("\"message\":{\"role\":\"$role\",\"content\":${jsonEscape(content)}},")
            append("\"done\":true,")
            append("\"done_reason\":\"stop\"")
            append("}")
        }
        return result.toByteArray()
    }

    /**
     * Translate OpenAI /v1/completions → Ollama /api/generate.
     *
     * OpenAI:
     *   {"choices":[{"text":"hello world","index":0,...}],"model":"foo","usage":{...}}
     *
     * Ollama:
     *   {"model":"foo","created_at":"...","response":"hello world","done":true,"done_reason":"stop"}
     */
    private fun translateGenerate(backendBody: String): ByteArray {
        // If already Ollama format (has "response" at top level), return as-is
        if (backendBody.contains("\"response\"") &&
            jsonFind(backendBody, "choices") == null) {
            return backendBody.toByteArray()
        }

        val model = jsonString(backendBody, "model").ifEmpty { modelName }
        val choices = jsonFind(backendBody, "choices") ?: ""

        var response = ""

        if (choices.startsWith("[")) {
            val firstObj = jsonValue(choices.substring(1))?.first ?: ""
            if (firstObj.startsWith("{")) {
                response = jsonString(firstObj, "text")
                if (response.isEmpty()) response = jsonString(firstObj, "message", "content")
            }
        }

        val result = buildString {
            append("{")
            append("\"model\":\"$model\",")
            append("\"created_at\":\"${createdAt()}\",")
            append("\"response\":${jsonEscape(response)},")
            append("\"done\":true,")
            append("\"done_reason\":\"stop\"")
            append("}")
        }
        return result.toByteArray()
    }

    /**
     * Translate OpenAI /v1/models → Ollama /api/tags.
     *
     * llama.cpp b9596+ returns a hybrid response containing both
     * "models" (Ollama keys with empty values) and "data" (OpenAI keys
     * with real values). We always rebuild from the "data" array so
     * Chatbox gets valid modified_at / size / digest fields.
     */
    private fun translateTags(backendBody: String): ByteArray {
        val sb = StringBuilder()
        sb.append("{\"models\":[")

        // llama.cpp hybrid response has "data" alongside "models";
        // pure OpenAI format also uses "data".  Parse that array.
        val data = jsonFind(backendBody, "data")
        if (data != null && data.startsWith("[")) {
            var s = data.substring(1).trimStart()
            var first = true
            while (s.isNotEmpty() && !s.startsWith("]")) {
                val objAndLen = jsonValue(s)
                if (objAndLen == null || !objAndLen.first.startsWith("{")) break
                val obj = objAndLen.first
                s = s.substring(objAndLen.second).trimStart()
                if (s.startsWith(",")) s = s.substring(1).trimStart()

                val id = jsonString(obj, "id")
                val createdUnix = jsonString(obj, "created")
                val meta = jsonFind(obj, "meta")
                val size = if (meta != null) jsonString(obj, "meta", "size") else "0"
                val details = if (meta != null) jsonValue(meta)?.first ?: "{}" else "{}"

                // Convert Unix timestamp to ISO-8601 for Ollama compatibility
                val modifiedAt = if (createdUnix.isNotEmpty()) {
                    try {
                        val millis = createdUnix.toLong() * 1000L
                        synchronized(dateFormat) { dateFormat.format(Date(millis)) }
                    } catch (_: NumberFormatException) {
                        ""
                    }
                } else ""

                // Compute a stable model digest from id + size
                val digest = if (id.isNotEmpty() && size.isNotEmpty() && size != "0") {
                    try {
                        val raw = "$id:$size"
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        val hash = md.digest(raw.toByteArray())
                        val hex = hash.joinToString("") { "%02x".format(it) }
                        "sha256:$hex"
                    } catch (_: Exception) {
                        ""
                    }
                } else ""

                if (!first) sb.append(",")
                first = false
                sb.append("{")
                sb.append("\"name\":\"$id\",")
                sb.append("\"model\":\"$id\",")
                sb.append("\"modified_at\":\"$modifiedAt\",")
                sb.append("\"size\":$size,")
                sb.append("\"digest\":\"$digest\",")
                sb.append("\"details\":$details")
                sb.append("}")
            }
        }
        sb.append("]}")
        return sb.toString().toByteArray()
    }
}
