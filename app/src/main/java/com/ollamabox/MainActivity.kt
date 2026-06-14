package com.ollamabox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
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
    private lateinit var prefs: android.content.SharedPreferences
    private var savedThreads: Int = 0
    private var savedCtxSize: Int = 8192
    private var savedNGpuLayers: Int = 0
    private var savedEnableThinking: Boolean = false
    private var receiverRegistered = false
    private val logHandler = Handler(Looper.getMainLooper())
    private val logRefresh = object : Runnable {
        override fun run() {
            refreshLog()
            logHandler.postDelayed(this, 750)
        }
    }

    companion object {
        private const val PREF_NAME = "ollamabox"
        private const val KEY_MODEL_PATH = "modelPath"
        private const val KEY_MODEL_NAME = "modelName"
        private const val KEY_CTX_SIZE = "ctxSize"
        private const val KEY_THREADS = "threads"
        private const val KEY_ENABLE_THINKING = "enableThinking"
        private const val KEY_AUTO_START = "autoStart"
        private const val KEY_N_GPU_LAYERS = "nGpuLayers"
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { copyModel(it) }
    }

    private val serverStartedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateServerUI()
            refreshLog()
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

            logFile = AppLog.file(this)
            log("Log: ${logFile.absolutePath}")

            // ── SharedPreferences ──────────────────────────────────
            prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            restoreSettings()

            binding.btnImportModel.setOnClickListener { pickModel.launch("application/octet-stream") }
            binding.btnStartServer.setOnClickListener { startServer() }
            binding.btnStopServer.setOnClickListener { stopServer() }
            binding.btnApply.setOnClickListener { applySettings() }
            binding.btnSelfTest.setOnClickListener { runSelfTest() }
            binding.swAutoStart.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_AUTO_START, checked).apply()
            }

            // Enable Apply button only when threads/ctxSize change while server is running
            val settingsWatcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { updateApplyButton() }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            }
            binding.etCtxSize.addTextChangedListener(settingsWatcher)
            binding.etThreads.addTextChangedListener(settingsWatcher)
            binding.swEnableThinking.setOnCheckedChangeListener { _, _ -> updateApplyButton() }

            // Vulkan GPU detection: distinguish device support from APK packaging.
            val vulkanAvailability = VulkanDetector.availability(this)
            log("Vulkan 状态：$vulkanAvailability")
            if (vulkanAvailability != VulkanDetector.Availability.AVAILABLE) {
                binding.sliderGpuLayers.isEnabled = false
                binding.tvGpuLayersValue.text = "不可用"
                binding.tvGpuLayersInfo.text = when (vulkanAvailability) {
                    VulkanDetector.Availability.BACKEND_NOT_PACKAGED ->
                        "当前 APK 未包含 Vulkan GPU 后端"
                    VulkanDetector.Availability.DEVICE_UNSUPPORTED ->
                        "此设备不支持 GPU 加速 (Vulkan)"
                    VulkanDetector.Availability.AVAILABLE -> ""
                }
            }
            binding.sliderGpuLayers.addOnChangeListener { _, value, _ ->
                val layers = value.toInt()
                binding.tvGpuLayersValue.text = gpuLayersLabel(layers)
                updateApplyButton()
            }

            // Restore UI
            updateServerUI()
            updateApplyButton()
            refreshLog()

            log("OllamaBox ready — 导入模型后点击启动")

            // ── Auto-start if model was loaded and pref exists ─────
            if (binding.swAutoStart.isChecked && modelPath != null && ServerService.instance == null) {
                log("检测到上次模型，自动启动服务…")
                binding.root.postDelayed({ startServer() }, 500)
            }
        } catch (e: Exception) {
            try {
                crashLog.appendText("onCreate CRASH: ${e.javaClass.name}: ${e.message}\n")
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                crashLog.appendText("$sw\n")
            } catch (_: Exception) {}
            throw RuntimeException("onCreate failed", e)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            registerReceiver(
                serverStartedReceiver,
                IntentFilter(ServerService.ACTION_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        updateServerUI()
        logHandler.post(logRefresh)
    }

    override fun onStop() {
        logHandler.removeCallbacks(logRefresh)
        if (receiverRegistered) {
            unregisterReceiver(serverStartedReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    // ── Settings persistence ──────────────────────────────────────

    private fun restoreSettings() {
        savedCtxSize = prefs.getInt(KEY_CTX_SIZE, 8192)
        savedThreads = prefs.getInt(KEY_THREADS, 2)
        savedEnableThinking = prefs.getBoolean(KEY_ENABLE_THINKING, false)
        savedNGpuLayers = prefs.getInt(KEY_N_GPU_LAYERS, 0)
        binding.etCtxSize.setText(savedCtxSize.toString())
        binding.etThreads.setText(savedThreads.toString())
        binding.swEnableThinking.isChecked = savedEnableThinking
        binding.swAutoStart.isChecked = prefs.getBoolean(KEY_AUTO_START, false)
        binding.sliderGpuLayers.value = savedNGpuLayers.toFloat()
        binding.tvGpuLayersValue.text = gpuLayersLabel(savedNGpuLayers)

        // Restore model if file still exists
        val savedPath = prefs.getString(KEY_MODEL_PATH, null)
        val savedName = prefs.getString(KEY_MODEL_NAME, "") ?: ""
        if (savedPath != null) {
            val f = File(savedPath)
            if (f.exists()) {
                modelPath = savedPath
                modelName = savedName.ifEmpty { f.name }
                binding.tvModelName.text = modelName
                log("恢复模型: $modelName")
            } else {
                prefs.edit().remove(KEY_MODEL_PATH).remove(KEY_MODEL_NAME).apply()
                log("模型文件已删除，清除设置")
            }
        }
    }

    private fun saveSettings() {
        val prefsEdit = prefs.edit()
        if (modelPath != null) {
            prefsEdit.putString(KEY_MODEL_PATH, modelPath)
            prefsEdit.putString(KEY_MODEL_NAME, modelName)
        }
        savedCtxSize = binding.etCtxSize.text.toString().toIntOrNull() ?: 8192
        savedThreads = binding.etThreads.text.toString().toIntOrNull() ?: 2
        savedNGpuLayers = binding.sliderGpuLayers.value.toInt()
        savedEnableThinking = binding.swEnableThinking.isChecked
        prefsEdit.putInt(KEY_CTX_SIZE, savedCtxSize)
        prefsEdit.putInt(KEY_THREADS, savedThreads)
        prefsEdit.putInt(KEY_N_GPU_LAYERS, savedNGpuLayers)
        prefsEdit.putBoolean(KEY_ENABLE_THINKING, savedEnableThinking)
        prefsEdit.apply()
    }

    private fun applySettings() {
        val settings = readValidatedSettings() ?: return
        binding.etCtxSize.setText(settings.first.toString())
        binding.etThreads.setText(settings.second.toString())
        saveSettings()
        val svc = ServerService.instance
        if (svc?.serverRunning == true) {
            // Restart with new settings
            log("══════ 应用新设置，重启服务 ══════")
            val intent = Intent(this, ServerService::class.java).apply {
                action = ServerService.ACTION_RESTART
                putExtra(ServerService.EXTRA_MODEL_PATH, modelPath)
                putExtra(ServerService.EXTRA_MODEL_NAME, modelName)
                putExtra(ServerService.EXTRA_CTX_SIZE, savedCtxSize)
                putExtra(ServerService.EXTRA_THREADS, savedThreads)
                putExtra(ServerService.EXTRA_ENABLE_THINKING, savedEnableThinking)
                putExtra(ServerService.EXTRA_N_GPU_LAYERS, savedNGpuLayers)
            }
            startService(intent)
            binding.tvServerStatus.text = "正在重启服务..."
            binding.btnApply.isEnabled = false
            Toast.makeText(this, "正在重启服务…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "设置已保存，启动服务时生效", Toast.LENGTH_SHORT).show()
            binding.btnApply.isEnabled = false
        }
    }

    private fun updateApplyButton() {
        val currentCtx = binding.etCtxSize.text.toString().toIntOrNull() ?: savedCtxSize
        val currentThreads = binding.etThreads.text.toString().toIntOrNull() ?: savedThreads
        val currentGpuLayers = binding.sliderGpuLayers.value.toInt()
        val changed = currentCtx != savedCtxSize ||
            currentThreads != savedThreads ||
            currentGpuLayers != savedNGpuLayers ||
            binding.swEnableThinking.isChecked != savedEnableThinking
        val svc = ServerService.instance
        binding.btnApply.isEnabled = changed && svc?.serverRunning == true
    }

    // ── Model import ──────────────────────────────────────────────

    private fun copyModel(uri: Uri) {
        try {
            val rawName = getFileName(uri) ?: "model.gguf"
            val name = InputValidation.sanitizeModelName(rawName)
            require(name.endsWith(".gguf", ignoreCase = true)) { "请选择 GGUF 模型文件" }
            val dest = File(filesDir, "models/$name").also { it.parentFile?.mkdirs() }
            val temp = File(dest.parentFile, "${dest.name}.part")
            val input = contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
            input.use { i ->
                FileOutputStream(temp).use { o -> i.copyTo(o) }
            }
            require(temp.length() > 0) { "模型文件为空" }
            if (dest.exists() && !dest.delete()) error("无法替换现有模型")
            if (!temp.renameTo(dest)) error("无法保存模型文件")
            modelPath = dest.absolutePath
            modelName = name
            binding.tvModelName.text = name
            log("模型导入: $name (${dest.length()} bytes)")

            // Save model choice to preferences
            prefs.edit()
                .putString(KEY_MODEL_PATH, modelPath)
                .putString(KEY_MODEL_NAME, modelName)
                .apply()

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

        val (ctxSize, threads) = readValidatedSettings() ?: return
        binding.btnStartServer.isEnabled = false
        binding.btnStopServer.isEnabled = true
        binding.tvServerStatus.text = "正在启动前台服务..."

        // Save current settings
        saveSettings()

        val intent = Intent(this, ServerService::class.java).apply {
            action = ServerService.ACTION_START
            putExtra(ServerService.EXTRA_MODEL_PATH, path)
            putExtra(ServerService.EXTRA_MODEL_NAME, modelName)
            putExtra(ServerService.EXTRA_CTX_SIZE, ctxSize)
            putExtra(ServerService.EXTRA_THREADS, threads)
            putExtra(ServerService.EXTRA_ENABLE_THINKING, savedEnableThinking)
            putExtra(ServerService.EXTRA_N_GPU_LAYERS, savedNGpuLayers)
        }
        startForegroundService(intent)
        log("══════ 启动前台服务 ══════")
        log("模型: $modelName")
        binding.btnApply.isEnabled = false
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
        binding.btnApply.isEnabled = false
        Snackbar.make(binding.root, "正在停止服务并释放模型内存…", Snackbar.LENGTH_SHORT).show()
    }

    private fun readValidatedSettings(): Pair<Int, Int>? {
        val ctxSize = binding.etCtxSize.text.toString().toIntOrNull()
        val threads = binding.etThreads.text.toString().toIntOrNull()
        if (!InputValidation.validCtxSize(ctxSize)) {
            binding.etCtxSize.error = "范围 ${InputValidation.MIN_CTX_SIZE}..${InputValidation.MAX_CTX_SIZE}"
            return null
        }
        if (!InputValidation.validThreadCount(threads)) {
            binding.etThreads.error = "范围 ${InputValidation.MIN_THREADS}..${InputValidation.MAX_THREADS}"
            return null
        }
        return ctxSize!! to threads!!
    }

    /** Refresh UI based on service state. Called by broadcast receiver. */
    private fun updateServerUI() {
        val svc = ServerService.instance
        when (svc?.state ?: ServerState.STOPPED) {
            ServerState.RUNNING -> {
                binding.tvServerStatus.text = "● 服务运行中"
                binding.tvApiEndpoint.text = "http://127.0.0.1:11434"
                binding.btnStartServer.isEnabled = false
                binding.btnStopServer.isEnabled = true
            }
            ServerState.STARTING_NATIVE -> setBusyUi("正在加载模型...")
            ServerState.STARTING_GATEWAY -> setBusyUi("正在启动 API 服务...")
            ServerState.STOPPING -> setBusyUi("正在停止服务...")
            ServerState.FAILED -> {
                binding.tvServerStatus.text = "启动失败: ${svc?.lastError ?: "未知错误"}"
                binding.btnStartServer.isEnabled = true
                binding.btnStopServer.isEnabled = true
            }
            ServerState.STOPPED -> {
                binding.tvServerStatus.text = "服务未启动"
                binding.tvApiEndpoint.text = ""
                binding.btnStartServer.isEnabled = true
                binding.btnStopServer.isEnabled = false
            }
        }
        updateApplyButton()
    }

    private fun setBusyUi(text: String) {
        binding.tvServerStatus.text = text
        binding.btnStartServer.isEnabled = false
        binding.btnStopServer.isEnabled = true
    }

    private fun log(msg: String) {
        try { AppLog.append(this, msg) } catch (_: Exception) {}
        runOnUiThread { refreshLog() }
    }

    private fun refreshLog() {
        val text = try { AppLog.recent(this) } catch (_: Exception) { "" }
        if (binding.tvLog.text.toString() != text) {
            val child = binding.scrollLog.getChildAt(0)
            val wasAtBottom = child == null ||
                binding.scrollLog.scrollY + binding.scrollLog.height >= child.height - 32
            binding.tvLog.text = text
            if (wasAtBottom) {
                binding.scrollLog.post { binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
    }

    private fun gpuLayersLabel(layers: Int): String = when {
        layers <= 0 -> "CPU (0)"
        layers >= 99 -> "GPU (99)"
        else -> "$layers 层"
    }

    // ── Self-test ─────────────────────────────────────────────────────

    private fun runSelfTest() {
        val svc = ServerService.instance
        if (svc?.serverRunning != true) {
            Toast.makeText(this, "请先启动服务", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnSelfTest.isEnabled = false
        binding.tvSelfTestResult.visibility = android.view.View.VISIBLE
        binding.tvSelfTestResult.text = "⏳ 自测运行中..."
        log("══════ 开始自测 ══════")

        Thread({
            val tester = SelfTest(progress = { msg ->
                log("自测: $msg")
                runOnUiThread { binding.tvSelfTestResult.text = "⏳ $msg" }
            })
            val results = tester.runAll(modelName)

            val sb = StringBuilder()
            var allPassed = true
            for (r in results) {
                val icon = if (r.passed) "✅" else "❌"
                if (!r.passed) allPassed = false
                sb.appendLine("$icon ${r.name} (${r.durationMs}ms)")
                if (!r.passed) sb.appendLine("   ${r.detail.take(120)}")
            }
            sb.appendLine(if (allPassed) "✅ 全部通过" else "❌ 存在问题")

            runOnUiThread {
                binding.tvSelfTestResult.text = sb.toString()
                binding.btnSelfTest.isEnabled = true
                log("══════ 自测完成: ${if (allPassed) "全部通过" else "有问题"} ══════")
            }
        }, "selftest").start()
    }
}
