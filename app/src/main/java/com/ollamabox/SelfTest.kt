package com.ollamabox

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

class SelfTest(
    private val host: String = "127.0.0.1",
    private val port: Int = 11434,
    private val progress: (String) -> Unit = {}
) {
    data class TestResult(
        val name: String,
        val passed: Boolean,
        val durationMs: Long,
        val detail: String
    )

    fun runAll(modelName: String? = null): List<TestResult> {
        val results = mutableListOf<TestResult>()
        val model = modelName ?: run {
            progress("获取模型列表...")
            val modelsResult = testModels()
            results.add(modelsResult)
            extractFirstModel(modelsResult)
        }
        if (model == null) {
            results.add(TestResult("chat (non-streaming)", false, 0, "无模型可用"))
            results.add(TestResult("chat (streaming)", false, 0, "无模型可用"))
            return results
        }
        progress("测试健康检查...")
        results.add(testHealth())
        progress("测试非流式对话...")
        results.add(testChatNonStreaming(model))
        progress("测试流式对话...")
        results.add(testChatStreaming(model))
        return results
    }

    private fun extractFirstModel(modelsResult: TestResult): String? {
        if (!modelsResult.passed) return null
        val body = modelsResult.detail
        val idMatch = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)
        if (idMatch != null) return idMatch.groupValues[1]
        val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(body)
        return nameMatch?.groupValues?.get(1)
    }

    fun testHealth(): TestResult {
        val t0 = System.currentTimeMillis()
        return try {
            val (code, body) = httpGet("/health")
            val ok = code == 200 && body.contains("\"status\"")
            TestResult("health", ok, System.currentTimeMillis() - t0,
                if (ok) body.take(200) else "code=$code")
        } catch (e: Exception) {
            TestResult("health", false, System.currentTimeMillis() - t0,
                "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun testModels(): TestResult {
        val t0 = System.currentTimeMillis()
        return try {
            val (code, body) = httpGet("/v1/models")
            val ok = code == 200 && (body.contains("\"data\"") || body.contains("\"models\""))
            TestResult("models", ok, System.currentTimeMillis() - t0,
                if (ok) body.take(300) else "code=$code")
        } catch (e: Exception) {
            TestResult("models", false, System.currentTimeMillis() - t0,
                "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun testChatNonStreaming(model: String): TestResult {
        val t0 = System.currentTimeMillis()
        return try {
            val reqBody = "{\"model\":\"$model\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],\"max_tokens\":128,\"stream\":false}"
            val (code, body) = httpPost("/v1/chat/completions", reqBody)
            val ok = code == 200 && body.contains("\"choices\"") && body.contains("\"content\"")
            TestResult("chat (non-streaming)", ok, System.currentTimeMillis() - t0,
                if (ok) extractContent(body) else "code=$code body=${body.take(150)}")
        } catch (e: Exception) {
            TestResult("chat (non-streaming)", false, System.currentTimeMillis() - t0,
                "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun testChatStreaming(model: String): TestResult {
        val t0 = System.currentTimeMillis()
        return try {
            val reqBody = "{\"model\":\"$model\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],\"max_tokens\":128,\"stream\":true}"
            val sse = httpPostStream("/v1/chat/completions", reqBody)
            val events = sse.lines().count { it.startsWith("data: ") }
            val ok = events > 0 && sse.contains("\"content\"")
            TestResult("chat (streaming)", ok, System.currentTimeMillis() - t0,
                if (ok) "$events SSE events" else "events=$events body=${sse.take(100)}")
        } catch (e: Exception) {
            TestResult("chat (streaming)", false, System.currentTimeMillis() - t0,
                "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun httpGet(path: String, timeoutMs: Int = 15000): Pair<Int, String> {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(host, port), 3000)
            sock.soTimeout = timeoutMs
            sock.outputStream.write("GET $path HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n".toByteArray())
            return readResponse(sock)
        } finally { try { sock.close() } catch (_: Exception) {} }
    }

    private fun httpPost(path: String, body: String, timeoutMs: Int = 120000): Pair<Int, String> {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(host, port), 3000)
            sock.soTimeout = timeoutMs
            val bodyBytes = body.toByteArray()
            val header = "POST $path HTTP/1.1\r\nHost: $host:$port\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
            sock.outputStream.write(header.toByteArray())
            sock.outputStream.write(bodyBytes)
            return readResponse(sock)
        } finally { try { sock.close() } catch (_: Exception) {} }
    }

    private fun httpPostStream(path: String, body: String, timeoutMs: Int = 120000): String {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(host, port), 3000)
            sock.soTimeout = timeoutMs
            val bodyBytes = body.toByteArray()
            val header = "POST $path HTTP/1.1\r\nHost: $host:$port\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
            sock.outputStream.write(header.toByteArray())
            sock.outputStream.write(bodyBytes)
            val input = sock.inputStream
            var contentLength = 0; var chunked = false
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val col = line.indexOf(':')
                if (col > 0) {
                    val k = line.substring(0, col).trim().lowercase()
                    val v = line.substring(col + 1).trim()
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                    if (k == "transfer-encoding" && v.contains("chunked")) chunked = true
                }
            }
            val bodyBytes2 = when { chunked -> readChunked(input); contentLength > 0 -> readBytes(input, contentLength); else -> readAll(input) }
            return String(bodyBytes2, Charsets.UTF_8)
        } finally { try { sock.close() } catch (_: Exception) {} }
    }

    private fun readResponse(sock: Socket): Pair<Int, String> {
        val input = sock.inputStream
        val statusLine = readLine(input) ?: return 502 to "empty"
        val code = statusLine.split(" ").getOrElse(1) { "500" }.toIntOrNull() ?: 500
        var contentLength = 0; var chunked = false
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val col = line.indexOf(':')
            if (col > 0) {
                val k = line.substring(0, col).trim().lowercase()
                val v = line.substring(col + 1).trim()
                if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                if (k == "transfer-encoding" && v.contains("chunked")) chunked = true
            }
        }
        val body = when { chunked -> readChunked(input); contentLength > 0 -> readBytes(input, contentLength); else -> readAll(input) }
        return code to String(body, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) { input.read(); break }
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readBytes(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count); var total = 0
        while (total < count) { val n = input.read(buf, total, count - total); if (n < 0) break; total += n }
        return if (total == count) buf else buf.copyOf(total)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val chunkSize = sizeLine.trim().toIntOrNull(16) ?: 0
            if (chunkSize == 0) break
            out.write(readBytes(input, chunkSize))
            readLine(input)
        }
        readLine(input)
        return out.toByteArray()
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096); var n: Int
        while (input.read(buf).also { n = it } > 0) out.write(buf, 0, n)
        return out.toByteArray()
    }

    private fun extractContent(json: String): String {
        val m = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
        return m?.groupValues?.get(1)?.take(100) ?: json.take(100)
    }
}
