package com.ollamabox

import java.io.File

object InputValidation {
    const val MIN_CTX_SIZE = 256
    const val MAX_CTX_SIZE = 131072
    const val MIN_THREADS = 1
    const val MAX_THREADS = 64

    fun sanitizeModelName(rawName: String): String {
        return File(rawName).name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
    }

    fun validCtxSize(value: Int?): Boolean = value != null && value in MIN_CTX_SIZE..MAX_CTX_SIZE

    fun validThreadCount(value: Int?): Boolean = value != null && value in MIN_THREADS..MAX_THREADS
}
