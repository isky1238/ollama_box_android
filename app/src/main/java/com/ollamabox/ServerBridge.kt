package com.ollamabox

object ServerBridge {
    init {
        System.loadLibrary("jnibridge")
    }

    external fun nativeStartServer(
        modelPath: String,
        host: String,
        port: Int,
        ctxSize: Int,
        nativeLibDir: String
    ): Int
}
