package com.ollamabox

enum class ServerState {
    STOPPED,
    STARTING_NATIVE,
    STARTING_GATEWAY,
    RUNNING,
    STOPPING,
    FAILED
}
