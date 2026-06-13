package com.ollamabox

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val TRIM_TO_BYTES = 1L * 1024L * 1024L
    private val lock = Any()

    fun append(context: Context, message: String): String {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$timestamp] $message"
        synchronized(lock) {
            val file = file(context)
            if (file.length() > MAX_FILE_BYTES) {
                val bytes = file.readBytes()
                file.writeBytes(bytes.copyOfRange((bytes.size - TRIM_TO_BYTES.toInt()).coerceAtLeast(0), bytes.size))
            }
            file.appendText("$line\n")
        }
        return line
    }

    fun recent(context: Context, maxLines: Int = 300): String = synchronized(lock) {
        val file = file(context)
        if (!file.exists()) return@synchronized ""
        file.useLines { lines -> lines.toList().takeLast(maxLines).joinToString("\n") }
    }

    fun file(context: Context): File = File(context.filesDir, "debug.log")
}
