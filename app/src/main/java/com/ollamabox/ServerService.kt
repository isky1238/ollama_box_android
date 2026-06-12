package com.ollamabox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that keeps the Ollama server alive.
 *
 * On ColorOS/Android 16, background processes are aggressively frozen.
 * A foreground service with an ongoing notification prevents this,
 * keeping the llama.cpp inference engine and HTTP server alive even
 * when the user switches to other apps (e.g. Chatbox).
 */
class ServerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var ollamaServer: OllamaServer? = null
    private var serverStarting = false
    @Volatile var serverRunning = false
    private var modelPath: String? = null
    private var modelName: String = ""
    private lateinit var logFile: File

    companion object {
        const val CHANNEL_ID = "ollama_server"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.ollamabox.action.START_SERVER"
        const val ACTION_STOP = "com.ollamabox.action.STOP_SERVER"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_CTX_SIZE = "ctx_size"
        const val EXTRA_THREADS = "threads"

        @Volatile var instance: ServerService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        logFile = File(filesDir, "debug.log")
        // Acquire partial WakeLock to prevent ColorOS freeze
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OllamaBox:server"
        ).apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24h max
        }
        createNotificationChannel()
        log("ForegroundService onCreate (WakeLock acquired)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Show the persistent notification immediately — Android requires
        // startForeground() within 5 seconds, otherwise the service is killed.
        val notif = buildNotification("OllamaBox 准备中…", "等待启动")
        startForeground(NOTIFICATION_ID, notif)

        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_MODEL_PATH)
                val name = intent.getStringExtra(EXTRA_MODEL_NAME) ?: ""
                val ctx = intent.getIntExtra(EXTRA_CTX_SIZE, 2048)
                val th = intent.getIntExtra(EXTRA_THREADS, Runtime.getRuntime().availableProcessors())
                if (path != null) {
                    modelPath = path
                    modelName = name
                    startServer(ctx, th)
                }
            }
            ACTION_STOP -> stopServer()
        }

        // Restart if killed by system (unlikely with foreground, but robust)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        ollamaServer?.stop()
        ollamaServer = null
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        log("ForegroundService onDestroy (WakeLock released)")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Don't stop — keep running even if user swipes app away
    }

    // ─── Notification ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ollama 服务",
            NotificationManager.IMPORTANCE_LOW // LOW = no sound, just icon
        ).apply {
            description = "保持推理引擎运行"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val notif = buildNotification(title, content)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notif)
    }

    // ─── Server lifecycle ───────────────────────────────────────────

    private fun startServer(ctxSize: Int, threadCount: Int) {
        val path = modelPath ?: return
        if (serverStarting || serverRunning) return
        if (portInUse(11434) || portInUse(11435)) {
            log("ERROR: 端口占用")
            updateNotification("OllamaBox 错误", "端口被占用")
            return
        }

        serverStarting = true
        log("══════ 启动服务 (foreground) ══════")
        log("模型: $modelName ctxSize=$ctxSize threads=$threadCount")
        log("路径: $path")

        // Thread 1: JNI inference engine
        Thread({
            try {
                log("JNI: 启动推理引擎 (端口 11435)...")
                val t0 = System.currentTimeMillis()
                val exitCode = ServerBridge.nativeStartServer(
                    modelPath = path,
                    host = "127.0.0.1",
                    port = 11435,
                    ctxSize = ctxSize,
                    threadCount = threadCount,
                    nativeLibDir = applicationInfo.nativeLibraryDir
                )
                val elapsed = (System.currentTimeMillis() - t0) / 1000
                log("JNI: llama_server 退出 code=$exitCode 运行 ${elapsed}s")

                val sf = File(filesDir, "stderr.log")
                if (sf.exists() && sf.length() > 0) {
                    log("── JNI stderr ──")
                    sf.readLines().take(30).forEach { log("  $it") }
                    log("── stderr end ──")
                }
            } catch (e: UnsatisfiedLinkError) {
                log("JNI 链接失败: ${e.message}")
            } catch (e: Exception) {
                log("JNI CRASH: ${e.javaClass.name}: ${e.message}")
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                log("STACK: ${sw.toString().take(800)}")
            } finally {
                log("JNI 线程结束，清理")
                ollamaServer?.stop()
                ollamaServer = null
                serverRunning = false
                serverStarting = false
                updateNotification("OllamaBox 已停止", "服务已结束")
            }
        }, "llama-jni").start()

        // Thread 2: Wait for backend, then start HTTP server
        Thread({
            try {
                // Wait for backend port, then give it time to load model
                if (!waitForPort(11435, 120_000)) {
                    log("ERROR: 推理引擎未就绪")
                    serverStarting = false
                    updateNotification("OllamaBox 失败", "引擎启动超时")
                    return@Thread
                }
                log("引擎端口已开，等待模型加载...")
                Thread.sleep(5000) // let model finish loading
                log("引擎就绪，启动 Ollama HTTP 服务...")

                val svr = OllamaServer(
                    backendHost = "127.0.0.1",
                    backendPort = 11435,
                    listenPort = 11434,
                    modelName = modelName,
                    log = { msg -> log(msg) }
                ).also {
                    it.logFilePath = logFile.absolutePath
                    it.stderrPath = File(filesDir, "stderr.log").absolutePath
                }
                ollamaServer = svr
                svr.start()
                Thread.sleep(400)

                if (!portInUse(11434)) {
                    log("ERROR: HTTP 服务未能绑定 11434")
                    svr.stop()
                    serverStarting = false
                    updateNotification("OllamaBox 失败", "HTTP端口绑定失败")
                    return@Thread
                }

                serverStarting = false
                serverRunning = true
                updateNotification(
                    "OllamaBox 运行中",
                    "模型: $modelName | http://127.0.0.1:11434"
                )
                log("══════ 服务已启动 (foreground) http://127.0.0.1:11434 ══════")

                // Send broadcast so MainActivity can update UI
                sendBroadcast(Intent("com.ollamabox.SERVER_STARTED"))
            } catch (e: Exception) {
                log("启动异常: ${e.message}")
                serverStarting = false
            }
        }, "starter").start()
    }

    private fun stopServer() {
        log("══════ 停止服务 ══════")
        ollamaServer?.stop()
        ollamaServer = null
        serverRunning = false
        serverStarting = false
        log("HTTP 服务已停止 — 终止进程释放模型内存")
        updateNotification("OllamaBox 已停止", "释放资源中…")
        // Kill process to free model memory (JNI is uninterruptible)
        Thread({
            Thread.sleep(1500)
            Process.killProcess(Process.myPid())
        }, "kill").start()
    }

    // ─── Utils ──────────────────────────────────────────────────────

    private fun portInUse(port: Int): Boolean {
        return try {
            val s = java.net.Socket()
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
            s.close()
            true
        } catch (_: Exception) { false }
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (portInUse(port)) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts] $msg"
        try { logFile.appendText("$line\n") } catch (_: Exception) {}
        // Also try writing to /data/local/tmp so Termux can read without Shizuku
        try { java.io.File("/data/local/tmp/ollamabox_startup.txt").appendText("$line\n") } catch (_: Exception) {}
    }
}
