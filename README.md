# OllamaBox

[![Release](https://img.shields.io/badge/release-v1.1.0-blue)](https://github.com/isky1238/ollama_box_android/releases)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/arch-arm64--v8a-orange)]()

Run [llama.cpp](https://github.com/ggml-org/llama.cpp) natively on Android and expose an **Ollama + OpenAI-compatible HTTP API** on `localhost:11434`. Import your own GGUF models from storage.

## Features

- 🚀 **Native performance** — llama.cpp runs in-process via JNI
- 🔌 **Dual API** — Ollama (`/api/chat`, `/api/tags`) + OpenAI (`/v1/chat/completions`, `/v1/models`)
- 📡 **SSE Streaming** — Real-time token streaming for OpenAI-compatible clients (Chatbox, etc.)
- 🛡️ **Foreground Service** — Persistent notification prevents ColorOS/HyperOS from freezing the server
- ⚙️ **Configurable** — ctxSize and thread count adjustable from UI
- 📦 **No model bundling** — Import GGUF files from phone storage
- ⚡ **Dimensity 9300/9400 optimized** — Custom ggml threading patches

## Quick Start

1. Download [latest APK](https://github.com/isky1238/ollama_box_android/releases) and install
2. Download a GGUF model (e.g. Qwen2.5-1.5B-Instruct Q6_K)
3. Open OllamaBox → Import Model → select your .gguf file
4. Adjust ctxSize/threads → tap **Start Server**
5. Connect from Chatbox: set API URL to `http://127.0.0.1:11434`, API type **OpenAI**

## API Endpoints

| Endpoint | Type | Description |
|----------|------|-------------|
| `GET /` | Status | Server status + model info |
| `POST /api/chat` | Ollama | Chat completion (Ollama format) |
| `GET /api/tags` | Ollama | Model list (Ollama format) |
| `GET /api/version` | Ollama | Server version |
| `POST /v1/chat/completions` | OpenAI | Chat completion (SSE streaming) |
| `GET /v1/models` | OpenAI | Model list |
| `GET /api/log` | Debug | Server debug log |
| `GET /api/stderr` | Debug | llama.cpp stderr log |

## Requirements

- Android 8.0+ (API 26)
- ARM64 device (arm64-v8a)
- ~2GB free RAM for 1.5B models

## Performance

| Device | Model | threads | tok/s |
|--------|-------|---------|-------|
| Dimensity 9400 | 1.5B Q6_K | 4 | **11.04** |
| Dimensity 9400 | 1.5B Q6_K | 8 | 3.96 |
| Dimensity 9300+ | 1.5B Q6_K | 4 | 4.87 |

> **Note:** Fewer threads often perform better on all-big-core SoCs due to reduced cache contention.

## Architecture

```
Chatbox / Ollama Client
        │
        ▼
┌─────────────────────────┐
│  ServerService.kt        │  Foreground Service (WakeLock)
│  OllamaServer.kt (:11434)│  HTTP proxy + format translation
│  ServerBridge.kt → JNI   │  llama.cpp (:11435)
│  jnibridge.c              │  Native loader
└─────────────────────────┘
        │
        ▼
    llama.cpp HTTP server
    (port 11435, internal)
```

The Kotlin HTTP server on `:11434` proxies to llama.cpp's internal server on `:11435`, translating between Ollama and OpenAI formats. Streaming requests are byte-piped without buffering.

## Building

```bash
# Build libjnibridge.so (use Termux aarch64-linux-android-clang on ARM64 devices)
clang --target=aarch64-linux-android26 \
  -shared -fPIC -O2 -I$NDK_INC \
  -ldl -llog \
  -o app/src/main/jniLibs/arm64-v8a/libjnibridge.so \
  app/src/main/cpp/jnibridge.c

# Build APK
ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug
```

## License

MIT
