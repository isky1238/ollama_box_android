package com.ollamabox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.*

class OllamaServerService : Service() {

    private var serverProcess: Process? = null
    private var running = false
    private lateinit var logFile: File

    override fun onCreate() {
        super.onCreate()
        createChannel()
        logFile = File(filesDir, "server.log")
        logFile.appendText("[${now()}] Service created\n")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { handleStop(); super.onDestroy() }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(CHANNEL_ID, "OllamaBox",
                NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
    }

    private fun handleStart(intent: Intent) {
        val port = intent.getIntExtra(EXTRA_PORT, 11434)
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        log("[start] port=$port model=$modelPath")
        if (modelPath.isNullOrBlank()) { log("[start] no model path"); stopSelf(); return }

        val modelFile = File(modelPath)
        if (!modelFile.exists()) { log("[start] model not found: $modelPath"); stopSelf(); return }
        log("[start] model exists: ${modelFile.length()} bytes")

        // Foreground notification
        try {
            startForeground(NOTIFICATION_ID, buildNotification(port))
            log("[start] startForeground OK")
        } catch (e: Exception) {
            log("[start] startForeground FAILED: $e")
            stopSelf(); return
        }

        Thread { runServer(modelPath, port) }.start()
    }

    private fun handleStop() {
        log("[stop] stopping...")
        running = false
        try { serverProcess?.destroyForcibly() } catch (_: Exception) {}
        serverProcess = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun runServer(modelPath: String, port: Int) {
        try {
            // Extract binary
            val binDir = File(filesDir, "bin").also { it.mkdirs() }
            val bin = File(binDir, "llama-server")

            if (!bin.exists()) {
                log("[extract] extracting llama-server from assets...")
                assets.open("llama-server").use { i ->
                    FileOutputStream(bin).use { o -> i.copyTo(o) }
                }
                bin.setExecutable(true)
                log("[extract] done: ${bin.length()} bytes")
            } else {
                log("[extract] already exists: ${bin.length()} bytes")
            }

            // Verify binary
            if (!bin.canExecute()) {
                bin.setExecutable(true)
                log("[extract] re-set executable bit")
            }

            // Check model file
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                log("[run] model file vanished: $modelPath")
                return
            }
            log("[run] model: ${modelFile.length()} bytes")

            // Environment
            val libDir = applicationInfo.nativeLibraryDir
            log("[run] nativeLibDir: $libDir")

            // Check libs exist
            val libs = File(libDir).listFiles()
            log("[run] libs in $libDir: ${libs?.size ?: 0} files")

            // Build command
            val cmd = listOf(
                bin.absolutePath,
                "--model", modelPath,
                "--host", "127.0.0.1",
                "--port", port.toString(),
                "--ctx-size", "2048",
                "--cont-batching",
                "--log-disable"
            )
            log("[run] cmd: ${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd)
            pb.environment()["LD_LIBRARY_PATH"] = libDir
            pb.environment().remove("LD_PRELOAD")
            pb.directory(binDir)
            pb.redirectErrorStream(true)

            serverProcess = pb.start()
            running = true
            log("[run] process started")

            // Read output (in a separate thread to not block)
            Thread {
                serverProcess!!.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null && running) {
                        log("[llama-server] $line")
                    }
                }
            }.start()

            val exitCode = serverProcess!!.waitFor()
            log("[run] process exited: $exitCode")
            running = false

        } catch (e: Exception) {
            log("[run] ERROR: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace(java.io.PrintWriter(logFile.writer().apply {
                write("\n[${now()}] "); flush()
            }))
        } finally {
            handleStop()
        }
    }

    private fun buildNotification(port: Int): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OllamaBox")
            .setContentText("Running: localhost:$port")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun log(msg: String) {
        val line = "[${now()}] $msg\n"
        logFile.appendText(line)
    }

    private fun now(): String {
        val df = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        df.timeZone = java.util.TimeZone.getDefault()
        return df.format(java.util.Date())
    }

    companion object {
        const val ACTION_START = "com.ollamabox.START"
        const val ACTION_STOP = "com.ollamabox.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_MODEL_PATH = "model_path"
        const val CHANNEL_ID = "ollamabox_channel"
        const val NOTIFICATION_ID = 1
    }
}
