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

    override fun onCreate() {
        super.onCreate()
        createChannel()
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
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: run { stopSelf(); return }

        try {
            startForeground(NOTIFICATION_ID, buildNotification(port))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            // Try without channel-specific features
            try { startForeground(NOTIFICATION_ID, buildNotification(port)) } catch (_: Exception) {}
            stopSelf(); return
        }

        Thread { runServer(modelPath, port) }.start()
    }

    private fun handleStop() {
        running = false
        try { serverProcess?.destroyForcibly() } catch (_: Exception) {}
        serverProcess = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun runServer(modelPath: String, port: Int) {
        try {
            val binDir = File(filesDir, "bin").also { it.mkdirs() }
            val bin = File(binDir, "llama-server")
            if (!bin.exists()) {
                assets.open("llama-server").use { i ->
                    FileOutputStream(bin).use { o -> i.copyTo(o) }
                }
                bin.setExecutable(true)
            }

            val env = mutableMapOf<String, String>(
                "LD_LIBRARY_PATH" to applicationInfo.nativeLibraryDir
            )

            val cmd = arrayOf(
                bin.absolutePath,
                "--model", modelPath,
                "--host", "127.0.0.1",
                "--port", port.toString(),
                "--ctx-size", "2048",
                "--cont-batching",
                "--log-disable"
            )

            logInfo("Starting: ${cmd.joinToString(" ")}")
            serverProcess = Runtime.getRuntime().exec(cmd, env.map { "${it.key}=${it.value}" }.toTypedArray(), binDir)
            running = true

            serverProcess!!.inputStream.bufferedReader().use { r ->
                var l: String?
                while (r.readLine().also { l = it } != null && running)
                    logDebug(l!!)
            }
            logInfo("Exited: ${serverProcess!!.waitFor()}")
        } catch (e: Exception) {
            logError("Server error", e)
        } finally {
            handleStop()
        }
    }

    private fun buildNotification(port: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OllamaBox")
            .setContentText("Running: localhost:$port")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }, PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()

    private fun logInfo(m: String) = Log.i(TAG, m)
    private fun logDebug(m: String) = Log.d(TAG, m)
    private fun logError(m: String, e: Exception) = Log.e(TAG, m, e)

    companion object {
        const val TAG = "OllamaBox"
        const val ACTION_START = "com.ollamabox.START"
        const val ACTION_STOP = "com.ollamabox.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_MODEL_PATH = "model_path"
        const val CHANNEL_ID = "ollamabox_channel"
        const val NOTIFICATION_ID = 1
    }
}
