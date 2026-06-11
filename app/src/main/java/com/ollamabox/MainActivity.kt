package com.ollamabox

import android.content.Intent
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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var modelPath: String? = null
    private val pickModel = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { copyModel(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnImportModel.setOnClickListener { pickModel.launch("application/octet-stream") }
        binding.btnStartServer.setOnClickListener {
            val path = modelPath ?: run {
                Snackbar.make(binding.root, "请先导入模型", Snackbar.LENGTH_SHORT).show(); return@setOnClickListener
            }
            startServer(path)
        }
        binding.btnStopServer.setOnClickListener { stopServer() }
        log("OllamaBox ready")
    }

    private fun copyModel(uri: Uri) {
        try {
            val name = getFileName(uri) ?: "model.gguf"
            val dest = File(filesDir, "models/$name").also { it.parentFile?.mkdirs() }
            contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
            modelPath = dest.absolutePath
            binding.tvModelName.text = name; log("模型导入: $name")
            Toast.makeText(this, "已导入: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            log("导入失败: ${e.message}")
            Snackbar.make(binding.root, "导入失败: ${e.message}", Snackbar.LENGTH_LONG).show()
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
        startForegroundService(Intent(this, OllamaServerService::class.java).apply {
            action = OllamaServerService.ACTION_START
            putExtra(OllamaServerService.EXTRA_PORT, 11434)
            putExtra(OllamaServerService.EXTRA_MODEL_PATH, path)
        })
        binding.btnStartServer.isEnabled = false
        binding.btnStopServer.isEnabled = true
        binding.tvServerStatus.text = "服务启动中…"
        binding.tvApiEndpoint.text = "http://localhost:11434"
        log("启动: localhost:11434")
    }

    private fun stopServer() {
        stopService(Intent(this, OllamaServerService::class.java))
        binding.btnStartServer.isEnabled = true
        binding.btnStopServer.isEnabled = false
        binding.tvServerStatus.text = getString(R.string.server_stopped)
        binding.tvApiEndpoint.text = ""; log("服务已停止")
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val cur = binding.tvLog.text
        binding.tvLog.text = if (cur.isEmpty()) "[$ts] $msg" else "$cur\n[$ts] $msg"
        binding.tvLog.post { binding.tvLog.scrollTo(0, binding.tvLog.bottom) }
    }
}
