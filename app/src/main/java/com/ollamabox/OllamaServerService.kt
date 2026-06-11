package com.ollamabox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.*

class OllamaServerService : Service() {

    private var serverProcess: Process? = null
    private var running = false
    var isRunning: Boolean = false; private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer(intent)
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "OllamaBox Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "OllamaBox server status" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
            Log.i(TAG, "Notification channel created")
        }
    }

    private fun startServer(intent: Intent) {
        val port = intent.getIntExtra(EXTRA_PORT, 11434)
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        if (modelPath.isNullOrBlank()) {
            Log.e(TAG, "No model path provided")
            stopSelf()
            return
        }

        val notification = buildNotification(port)
        startForeground(NOTIFICATION_ID, notification)
        isRunning = true

        // Use Thread instead of coroutines to avoid dependency issues
        Thread {
            launchServer(modelPath, port)
        }.start()
    }

    private fun stopServer() {
        running = false
        try { serverProcess?.destroyForcibly() } catch (_: Exception) {}
        serverProcess = null
        isRunning = false
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun launchServer(modelPath: String, port: Int) {
        try {
            val binDir = File(filesDir, "bin").also { it.mkdirs() }
            val serverBin = File(binDir, "llama-server")

            if (!serverBin.exists()) {
                assets.open("llama-server").use { i ->
                    FileOutputStream(serverBin).use { o -> i.copyTo(o) }
                }
                serverBin.setExecutable(true)
                Log.i(TAG, "Extracted llama-server")
            }

            val libDir = applicationInfo.nativeLibraryDir

            val cmd = listOf(
                serverBin.absolutePath,
                "--model", modelPath,
                "--host", "127.0.0.1",
                "--port", port.toString(),
                "--ctx-size", "2048",
                "--cont-batching",
                "--log-disable"
            )
            val pb = ProcessBuilder(cmd)
            pb.environment().apply {
                put("LD_LIBRARY_PATH", libDir)
                remove("LD_PRELOAD")
            }
            pb.directory(binDir)
            pb.redirectErrorStream(true)

            Log.i(TAG, "Starting: ${cmd.joinToString(" ")}")
            Log.i(TAG, "LD_LIBRARY_PATH=$libDir")

            serverProcess = pb.start()
            running = true

            serverProcess!!.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null && running) {
                    Log.d(TAG, "llama-server: $line")
                }
            }

            val exitCode = serverProcess!!.waitFor()
            Log.i(TAG, "llama-server exited: $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "Server error", e)
        } finally {
            isRunning = false
            running = false
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun buildNotification(port: Int): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OllamaBox")
            .setContentText("Running · localhost:$port")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "OllamaServer"
        const val ACTION_START = "com.ollamabox.START"
        const val ACTION_STOP = "com.ollamabox.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_MODEL_PATH = "model_path"
        const val CHANNEL_ID = "ollama_box_server"
        const val NOTIFICATION_ID = 1001
    }
}
