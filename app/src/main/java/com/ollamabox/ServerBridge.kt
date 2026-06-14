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
        threadCount: Int,
        nativeLibDir: String,
        stderrPath: String,
        chatTemplateKwargs: String,
        timeoutSec: Int,
        useMmap: Boolean,
        nGpuLayers: Int
    ): Int

    external fun nativeStopServer(): Boolean
}
