package com.ollamabox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File

class ServerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var ollamaServer: OllamaServer? = null
    private var nativeThread: Thread? = null
    @Volatile private var lifecycleId = 0L
    private var modelPath: String? = null
    private var modelName: String = ""

    @Volatile var state: ServerState = ServerState.STOPPED
        private set
    @Volatile var lastError: String? = null
        private set

    val serverRunning: Boolean
        get() = state == ServerState.RUNNING

    companion object {
        const val CHANNEL_ID = "ollama_server"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.ollamabox.action.START_SERVER"
        const val ACTION_STOP = "com.ollamabox.action.STOP_SERVER"
        const val ACTION_RESTART = "com.ollamabox.action.RESTART_SERVER"
        const val ACTION_STATE_CHANGED = "com.ollamabox.action.STATE_CHANGED"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_CTX_SIZE = "ctx_size"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_ENABLE_THINKING = "enable_thinking"
        const val EXTRA_STATE = "state"
        const val EXTRA_ERROR = "error"

        @Volatile var instance: ServerService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        log("ForegroundService 已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("OllamaBox 准备中…", "等待启动"))
        when (intent?.action) {
            ACTION_START -> startFromIntent(intent)
            ACTION_STOP -> Thread({ stopServer(stopService = true) }, "server-stop").start()
            ACTION_RESTART -> restartFromIntent(intent)
            else -> {
                setState(ServerState.STOPPED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleId++
        ollamaServer?.stop()
        ollamaServer = null
        releaseWakeLock()
        instance = null
        log("ForegroundService 已销毁")
        super.onDestroy()
    }

    private fun startFromIntent(intent: Intent) {
        val path = intent.getStringExtra(EXTRA_MODEL_PATH)
        if (path.isNullOrBlank()) {
            fail("模型路径为空")
            return
        }
        startServer(
            path = path,
            name = intent.getStringExtra(EXTRA_MODEL_NAME) ?: File(path).name,
            ctxSize = intent.getIntExtra(EXTRA_CTX_SIZE, 8192),
            threadCount = intent.getIntExtra(EXTRA_THREADS, 2),
            enableThinking = intent.getBooleanExtra(EXTRA_ENABLE_THINKING, false)
        )
    }

    private fun restartFromIntent(intent: Intent) {
        val path = intent.getStringExtra(EXTRA_MODEL_PATH) ?: modelPath
        if (path.isNullOrBlank()) {
            fail("没有可重启的模型")
            return
        }
        val name = intent.getStringExtra(EXTRA_MODEL_NAME) ?: modelName
        val ctx = intent.getIntExtra(EXTRA_CTX_SIZE, 8192)
        val threads = intent.getIntExtra(EXTRA_THREADS, Runtime.getRuntime().availableProcessors())
        val thinking = intent.getBooleanExtra(EXTRA_ENABLE_THINKING, false)
        Thread({
            if (stopServer(stopService = false)) {
                startServer(path, name, ctx, threads, thinking)
            }
        }, "server-restart").start()
    }

    @Synchronized
    private fun startServer(
        path: String,
        name: String,
        ctxSize: Int,
        threadCount: Int,
        enableThinking: Boolean
    ) {
        if (state in setOf(ServerState.STARTING_NATIVE, ServerState.STARTING_GATEWAY, ServerState.RUNNING, ServerState.STOPPING)) {
            return
        }
        if (!InputValidation.validCtxSize(ctxSize) || !InputValidation.validThreadCount(threadCount)) {
            fail("无效参数: ctxSize=$ctxSize threads=$threadCount")
            return
        }
        if (!File(path).isFile) {
            fail("模型文件不存在")
            return
        }
        if (portInUse(11434) || portInUse(11435)) {
            fail("端口 11434 或 11435 被占用")
            return
        }

        acquireWakeLock()
        modelPath = path
        modelName = name
        lastError = null
        val runId = ++lifecycleId
        setState(ServerState.STARTING_NATIVE)
        log("启动模型: $name ctxSize=$ctxSize threads=$threadCount enableThinking=$enableThinking")

        nativeThread = Thread({
            val exitCode = try {
                ServerBridge.nativeStartServer(
                    modelPath = path,
                    host = "127.0.0.1",
                    port = 11435,
                    ctxSize = ctxSize,
                    threadCount = threadCount,
                    nativeLibDir = applicationInfo.nativeLibraryDir,
                    stderrPath = File(filesDir, "stderr.log").absolutePath,
                    chatTemplateKwargs = """{"enable_thinking":$enableThinking}""",
                    timeoutSec = 300,
                    useMmap = true   // mmap loads near-instantly; set false for legacy --no-mmap
                )
            } catch (e: Throwable) {
                log("JNI 启动异常: ${e.javaClass.simpleName}: ${e.message}")
                -1
            }
            log("JNI 服务退出 code=$exitCode")
            synchronized(this) {
                if (runId == lifecycleId && state != ServerState.STOPPING && state != ServerState.STOPPED) {
                    ollamaServer?.stop()
                    ollamaServer = null
                    val nativeDetail = readNativeError()
                    val reason = when (exitCode) {
                        -1 -> "无法加载 GGML 后端"
                        -2 -> "无法加载推理服务原生库"
                        -3 -> "推理服务入口不存在"
                        -4 -> "原生内存分配失败"
                        -5 -> "JNI 参数或字符串读取失败"
                        else -> "推理引擎已退出"
                    }
                    fail("$reason，code=$exitCode${nativeDetail?.let { "，$it" } ?: ""}")
                }
            }
        }, "llama-jni").also { it.start() }

        Thread({
            if (!waitForPort(11435, 120_000, runId)) {
                if (runId == lifecycleId && state != ServerState.STOPPING) {
                    fail("推理引擎启动超时")
                }
                return@Thread
            }
            if (runId != lifecycleId || state == ServerState.STOPPING) return@Thread
            setState(ServerState.STARTING_GATEWAY)

            val gateway = OllamaServer(
                backendHost = "127.0.0.1",
                backendPort = 11435,
                listenPort = 11434,
                modelName = name,
                log = ::log
            )
            ollamaServer = gateway
            gateway.start()
            if (!waitForPort(11434, 5_000, runId)) {
                gateway.stop()
                if (runId == lifecycleId) fail("HTTP 服务未能绑定 11434")
                return@Thread
            }
            // Wait for model to finish loading before reporting RUNNING.
            // llama.cpp binds the port immediately but loads the model
            // asynchronously (can take 30+ seconds for 1-2 GB GGUF files).
            if (!waitForModelReady(120_000, runId)) {
                gateway.stop()
                if (runId == lifecycleId && state != ServerState.STOPPING) {
                    fail("模型加载超时")
                }
                return@Thread
            }
            if (runId == lifecycleId && state != ServerState.STOPPING) {
                setState(ServerState.RUNNING)
                log("服务已启动: http://127.0.0.1:11434")
            }
        }, "server-starter").start()
    }

    private fun stopServer(stopService: Boolean): Boolean {
        if (state == ServerState.STOPPED) {
            if (stopService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return true
        }
        val stoppingRun = synchronized(this) { ++lifecycleId }
        setState(ServerState.STOPPING)
        ollamaServer?.stop()
        ollamaServer = null

        val requested = try {
            nativeThread?.isAlive != true || ServerBridge.nativeStopServer()
        } catch (e: Throwable) {
            log("请求 JNI 停止失败: ${e.message}")
            false
        }
        if (!requested) {
            fail("推理引擎不支持优雅停止")
            return false
        }

        try {
            nativeThread?.join(20_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (nativeThread?.isAlive == true) {
            fail("推理引擎停止超时")
            return false
        }
        nativeThread = null
        if (stoppingRun == lifecycleId) {
            setState(ServerState.STOPPED)
            releaseWakeLock()
            updateNotification("OllamaBox 已停止", "服务已结束")
            if (stopService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return true
    }

    private fun setState(newState: ServerState, error: String? = null) {
        state = newState
        lastError = error
        val status = when (newState) {
            ServerState.STOPPED -> "服务未启动"
            ServerState.STARTING_NATIVE -> "正在加载模型"
            ServerState.STARTING_GATEWAY -> "正在启动 API 服务"
            ServerState.RUNNING -> "服务运行中"
            ServerState.STOPPING -> "正在停止服务"
            ServerState.FAILED -> error ?: "启动失败"
        }
        if (newState != ServerState.RUNNING) updateNotification("OllamaBox", status)
        else updateNotification("OllamaBox 运行中", "$modelName | http://127.0.0.1:11434")
        sendBroadcast(Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, newState.name)
            putExtra(EXTRA_ERROR, error)
        })
    }

    private fun fail(message: String) {
        log("ERROR: $message")
        setState(ServerState.FAILED, message)
    }

    private fun readNativeError(): String? = try {
        File(filesDir, "stderr.log")
            .takeIf { it.isFile }
            ?.readLines()
            ?.asReversed()
            ?.firstOrNull { line ->
                line.contains("error", ignoreCase = true) ||
                    line.contains("fatal", ignoreCase = true) ||
                    line.contains("failed", ignoreCase = true)
            }
            ?.take(240)
    } catch (_: Exception) {
        null
    }

    private fun waitForPort(port: Int, timeoutMs: Long, runId: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && runId == lifecycleId) {
            if (portInUse(port)) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /**
     * Poll the llama.cpp /health endpoint until the model finishes loading
     * (status == "ok" or "no slot available").  Returns false on timeout.
     *
     * llama.cpp binds the HTTP port immediately, but loads the model
     * asynchronously — requests during this window return 503 "Loading model".
     */
    private fun waitForModelReady(timeoutMs: Long, runId: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && runId == lifecycleId) {
            try {
                val url = java.net.URL("http://127.0.0.1:11435/health")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2_000
                conn.readTimeout = 2_000
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    if (body.contains("\"status\":\"ok\"") ||
                        body.contains("\"status\":\"no slot available\"") ||
                        body.contains("\"status\":\"no_slots_available\"")) {
                        return true
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // Backend not ready yet — keep polling
            }
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun portInUse(port: Int): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 300) }
        true
    } catch (_: Exception) {
        false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OllamaBox:server").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun log(message: String) {
        try {
            AppLog.append(this, message)
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        // IMPORTANCE_DEFAULT (not LOW) so ColorOS treats this as a real
        // foreground service instead of freezing it in the background.
        val channel = NotificationChannel(CHANNEL_ID, "Ollama 服务", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "保持本机模型推理服务运行"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, content: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)  // Always ongoing — prevents ColorOS from dismissing
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        // Use startForeground (not NotificationManager.notify) so ColorOS
        // recognizes the updated notification as still-foreground.
        startForeground(NOTIFICATION_ID, buildNotification(title, content))
    }
}
