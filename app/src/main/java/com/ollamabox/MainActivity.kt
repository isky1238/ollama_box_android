package com.ollamabox

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.ollamabox.databinding.ActivityMainBinding
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var modelPath: String? = null
    private lateinit var logFile: File

    private val pickModel = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { copyModel(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ollamabox_debug.log")
        logFile.appendText("=== OllamaBox Debug Log ===\n")
        log("Log file: ${logFile.absolutePath}")

        binding.btnImportModel.setOnClickListener { pickModel.launch("application/octet-stream") }
        binding.btnStartServer.setOnClickListener {
            val path = modelPath ?: run {
                Snackbar.make(binding.root, "请先导入模型", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startServer(path)
        }
        binding.btnStopServer.setOnClickListener { stopServer() }
        log("OllamaBox ready (JNI mode)")
    }

    private fun copyModel(uri: Uri) {
        try {
            val name = getFileName(uri) ?: "model.gguf"
            val dest = File(filesDir, "models/$name").also { it.parentFile?.mkdirs() }
            contentResolver.openInputStream(uri)?.use { i ->
                FileOutputStream(dest).use { o -> i.copyTo(o) }
            }
            modelPath = dest.absolutePath
            binding.tvModelName.text = name
            log("模型导入: $name (${dest.length()} bytes)")
            Toast.makeText(this, "已导入: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            log("导入失败: ${e.message}")
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i)
        }
        return name ?: uri.lastPathSegment
    }

    private fun startServer(path: String) {
        binding.btnStartServer.isEnabled = false
        binding.btnStopServer.isEnabled = true
        binding.tvServerStatus.text = "正在启动..."
        log("=== 启动服务 (JNI) ===")
        log("模型: $path")

        Thread {
            try {
                log("调用 ServerBridge.nativeStartServer...")
                val startTime = System.currentTimeMillis()

                // Spawn a watcher thread to update UI when server is ready
                val watcher = Thread {
                    try {
                        for (i in 1..60) {
                            Thread.sleep(500)
                            try {
                                val url = java.net.URL("http://127.0.0.1:11434/health")
                                val conn = url.openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 1000
                                conn.readTimeout = 1000
                                val code = conn.responseCode
                                val body = conn.inputStream.bufferedReader().readText()
                                conn.disconnect()
                                if (code == 200 && body.contains("\"status\":\"ok\"")) {
                                    runOnUiThread {
                                        binding.tvServerStatus.text = "服务运行中"
                                        binding.tvApiEndpoint.text = "http://127.0.0.1:11434"
                                    }
                                    log("服务器已就绪！")
                                    return@Thread
                                } else if (code == 503) {
                                    log("  等待模型加载... ($i)")
                                }
                            } catch (_: Exception) {
                                // server not listening yet
                            }
                        }
                    } catch (_: Exception) {}
                }
                watcher.isDaemon = true
                watcher.start()

                val exitCode = ServerBridge.nativeStartServer(
                    modelPath = path,
                    host = "127.0.0.1",
                    port = 11434,
                    ctxSize = 512,
                    nativeLibDir = applicationInfo.nativeLibraryDir
                )
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                log("服务器退出: code=$exitCode, 运行了 ${elapsed}s")

                // Try to read stderr captured by JNI bridge
                try {
                    val stderrFile = File(filesDir, "stderr.log")
                    if (stderrFile.exists() && stderrFile.length() > 0) {
                        log("--- 服务器 stderr ---")
                        stderrFile.readLines().take(50).forEach { line ->
                            log("  E: $line")
                        }
                        log("--- stderr 结束 ---")
                    }
                } catch (_: Exception) {}

            } catch (e: UnsatisfiedLinkError) {
                log("JNI 链接失败: ${e.message}")
                log("请确认 libjnibridge.so 已打包到 APK")
            } catch (e: Exception) {
                log("CRASH: ${e.javaClass.name}: ${e.message}")
                try {
                    val sw = StringWriter()
                    e.printStackTrace(PrintWriter(sw))
                    log("STACK: ${sw.toString().take(1000)}")
                } catch (_: Exception) {}
            } finally {
                runOnUiThread {
                    binding.tvServerStatus.text = "服务已停止"
                    binding.btnStartServer.isEnabled = true
                    binding.btnStopServer.isEnabled = false
                }
            }
        }.start()
    }

    private fun stopServer() {
        log("手动停止: JNI 调用不可中断(需重启app)")
        binding.btnStartServer.isEnabled = true
        binding.btnStopServer.isEnabled = false
        binding.tvServerStatus.text = "服务需重启APP"
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts] $msg"
        try { logFile.appendText("$line\n") } catch (_: Exception) {}
        runOnUiThread {
            val cur = binding.tvLog.text
            binding.tvLog.text = if (cur.isEmpty()) line else "$cur\n$line"
            binding.tvLog.post { binding.tvLog.scrollTo(0, binding.tvLog.bottom) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Note: llama_server blocks the thread; Android will kill the
        // process eventually. This is okay for a local server.
    }
}
