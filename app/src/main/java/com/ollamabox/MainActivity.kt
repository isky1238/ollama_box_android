package com.ollamabox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.ollamabox.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var modelPath: String? = null
    private var modelName: String = ""
    private lateinit var logFile: File

    private val pickModel = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { copyModel(it) }
    }

    private val serverStartedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateServerUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ── Crash handler ─────────────────────────────────────────
        val crashLog = File(filesDir, "crash.log")
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                crashLog.appendText("CRASH ${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}: ${e.javaClass.name}: ${e.message}\n")
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                crashLog.appendText("$sw\n")
            } catch (_: Exception) {}
            prevHandler?.uncaughtException(t, e)
        }

        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            logFile = File(filesDir, "debug.log")
            logFile.appendText("=== OllamaBox v2.2.0 ===\n")
            log("Log: ${logFile.absolutePath}")

            binding.btnImportModel.setOnClickListener { pickModel.launch("application/octet-stream") }
            binding.btnStartServer.setOnClickListener { startServer() }
            binding.btnStopServer.setOnClickListener { stopServer() }

            // Listen for server start/stop from the foreground service
            registerReceiver(serverStartedReceiver,
                IntentFilter("com.ollamabox.SERVER_STARTED"),
                Context.RECEIVER_NOT_EXPORTED)

            // Restore UI if service is already running
            updateServerUI()

            log("OllamaBox ready — 导入模型后点击启动")
        } catch (e: Exception) {
            try {
                crashLog.appendText("onCreate CRASH: ${e.javaClass.name}: ${e.message}\n")
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                crashLog.appendText("$sw\n")
            } catch (_: Exception) {}
            throw RuntimeException("onCreate failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(serverStartedReceiver) } catch (_: Exception) {}
    }

    private fun copyModel(uri: Uri) {
        try {
            val name = getFileName(uri) ?: "model.gguf"
            val dest = File(filesDir, "models/$name").also { it.parentFile?.mkdirs() }
            contentResolver.openInputStream(uri)?.use { i ->
                FileOutputStream(dest).use { o -> i.copyTo(o) }
            }
            modelPath = dest.absolutePath
            modelName = name
            binding.tvModelName.text = name
            log("模型导入: $name (${dest.length()} bytes)")
            Toast.makeText(this, "已导入: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            log("导入失败: ${e.message}")
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
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

    // ═══════════════════════════════════════════════════════════════
    //  Server — delegates to foreground service
    // ═══════════════════════════════════════════════════════════════

    private fun startServer() {
        val path = modelPath
        if (path == null) {
            Snackbar.make(binding.root, "请先导入模型文件 (.gguf)", Snackbar.LENGTH_SHORT).show()
            return
        }
        val svc = ServerService.instance
        if (svc?.serverRunning == true) {
            Snackbar.make(binding.root, "服务已在运行中", Snackbar.LENGTH_SHORT).show()
            return
        }

        binding.btnStartServer.isEnabled = false
        binding.btnStopServer.isEnabled = true
        binding.tvServerStatus.text = "正在启动前台服务..."

        val ctxSize = binding.etCtxSize.text.toString().toIntOrNull() ?: 2048
        val threads = binding.etThreads.text.toString().toIntOrNull()
            ?: Runtime.getRuntime().availableProcessors()

        val intent = Intent(this, ServerService::class.java).apply {
            action = ServerService.ACTION_START
            putExtra(ServerService.EXTRA_MODEL_PATH, path)
            putExtra(ServerService.EXTRA_MODEL_NAME, modelName)
            putExtra(ServerService.EXTRA_CTX_SIZE, ctxSize)
            putExtra(ServerService.EXTRA_THREADS, threads)
        }
        startForegroundService(intent)
        log("══════ 启动前台服务 ══════")
        log("模型: $modelName")
    }

    private fun stopServer() {
        log("══════ 停止服务 ══════")
        val intent = Intent(this, ServerService::class.java).apply {
            action = ServerService.ACTION_STOP
        }
        startService(intent) // Service handles its own stop
        binding.tvServerStatus.text = "释放资源中..."
        binding.btnStartServer.isEnabled = false
        binding.btnStopServer.isEnabled = false
        Snackbar.make(binding.root, "正在终止进程以释放模型内存…", Snackbar.LENGTH_SHORT).show()
    }

    /** Refresh UI based on service state. Called by broadcast receiver. */
    private fun updateServerUI() {
        val svc = ServerService.instance
        if (svc?.serverRunning == true) {
            binding.tvServerStatus.text = "● 服务运行中"
            binding.tvApiEndpoint.text = "http://127.0.0.1:11434"
            binding.btnStartServer.isEnabled = false
            binding.btnStopServer.isEnabled = true
        } else if (svc != null) {
            binding.tvServerStatus.text = "正在加载模型..."
            binding.btnStartServer.isEnabled = false
            binding.btnStopServer.isEnabled = true
        } else {
            binding.tvServerStatus.text = "服务未启动"
            binding.btnStartServer.isEnabled = true
            binding.btnStopServer.isEnabled = false
        }
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts] $msg"
        try { logFile.appendText("$line\n") } catch (_: Exception) {}
        // Also try writing to /data/local/tmp so Termux can read without Shizuku
        try { java.io.File("/data/local/tmp/ollamabox_startup.txt").appendText("$line\n") } catch (_: Exception) {}
        runOnUiThread {
            val cur = binding.tvLog.text
            binding.tvLog.text = if (cur.isEmpty()) line else "$cur\n$line"
            binding.tvLog.post { binding.tvLog.scrollTo(0, binding.tvLog.bottom) }
        }
    }
}
