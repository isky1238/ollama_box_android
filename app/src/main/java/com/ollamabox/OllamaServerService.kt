package com.ollamabox

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*

class OllamaServerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverProcess: Process? = null
    private var running = false
    var isRunning: Boolean = false; private set

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer(intent)
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { stopServer(); scope.cancel(); super.onDestroy() }

    private fun startServer(intent: Intent) {
        val port = intent.getIntExtra(EXTRA_PORT, 11434)
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: run { stopSelf(); return }
        startForeground(NOTIFICATION_ID, buildNotification(port))
        isRunning = true
        scope.launch { launchServer(modelPath, port) }
    }

    private fun stopServer() {
        running = false
        serverProcess?.destroyForcibly()
        serverProcess = null; isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun launchServer(modelPath: String, port: Int) {
        try {
            val binDir = File(filesDir, "bin").also { it.mkdirs() }
            val serverBin = File(binDir, "llama-server")
            if (!serverBin.exists()) {
                assets.open("llama-server").use { i -> FileOutputStream(serverBin).use { o -> i.copyTo(o) } }
                serverBin.setExecutable(true)
                Log.i(TAG, "Extracted llama-server")
            }
            val libDir = applicationInfo.nativeLibraryDir
            val pb = ProcessBuilder(
                serverBin.absolutePath, "--model", modelPath,
                "--host", "127.0.0.1", "--port", port.toString(),
                "--ctx-size", "2048", "--cont-batching", "--log-disable"
            )
            pb.environment().apply { put("LD_LIBRARY_PATH", libDir); remove("LD_PRELOAD") }
            pb.directory(binDir); pb.redirectErrorStream(true)
            Log.i(TAG, "Starting: ${pb.command().joinToString(" ")}")
            serverProcess = pb.start(); running = true
            serverProcess!!.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null && running)
                    Log.d(TAG, "llama-server: $line")
            }
            Log.i(TAG, "llama-server exited: ${serverProcess!!.waitFor()}")
        } catch (e: Exception) { Log.e(TAG, "Server error", e) }
        finally { isRunning = false; running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private fun buildNotification(port: Int): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OllamaBox").setContentText("运行中 · localhost:$port")
            .setSmallIcon(android.R.drawable.ic_menu_compass).setContentIntent(pi).setOngoing(true).build()
    }

    companion object {
        const val TAG = "OllamaServer"; const val ACTION_START = "com.ollamabox.START"
        const val ACTION_STOP = "com.ollamabox.STOP"; const val EXTRA_PORT = "port"
        const val EXTRA_MODEL_PATH = "model_path"; const val CHANNEL_ID = "ollama_box_server"
        const val NOTIFICATION_ID = 1001
    }
}
