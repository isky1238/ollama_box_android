# OllamaBox

[![Release](https://img.shields.io/badge/release-v1.1.1-blue)](https://github.com/isky1238/ollama_box_android/releases)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/arch-arm64--v8a-orange)]()

Run [llama.cpp](https://github.com/ggml-org/llama.cpp) natively on Android and expose an **Ollama + OpenAI-compatible HTTP API** on `localhost:11434`. Import your own GGUF models from storage.

## Gate Build Notice

This repository currently contains a **Gate/experimental Vulkan build**. The GPU layer control and Vulkan backend are packaged, but GPU scheduling is not yet fully verified across Android drivers. A request for GPU layers may execute through hardware Vulkan, a driver software path, or fall back to CPU while still appearing available. Current CPU and GPU benchmark results can be identical.

Use `GPU layers = 0` for predictable operation. Treat GPU mode as diagnostic until native logs confirm a Vulkan device and `offloaded N/N layers to GPU`.

## Features

- 🚀 **Native performance** — llama.cpp runs in-process via JNI, supports Vulkan GPU offload
- 🔌 **Dual API** — Ollama (`/api/chat`, `/api/tags`) + OpenAI (`/v1/chat/completions`, `/v1/models`)
- 📡 **SSE Streaming** — Real-time token streaming for OpenAI-compatible clients (Chatbox, etc.)
- 🛡️ **Foreground Service** — Persistent notification with WakeLock; tuned for ColorOS/HyperOS background survival
- ⚙️ **Configurable** — ctxSize (default 8192), threads (default 2), thinking mode toggle
- 📦 **No model bundling** — Import GGUF files from phone storage
- ⚡ **Dimensity 9300/9400 optimized** — ARM SVE + mmap model loading
- ⏱️ **Fast model loading** — mmap loads 1-2 GB GGUF files in seconds (not minutes)
- 🩺 **Health check** — Reports RUNNING only after model is fully loaded (prevents 503 errors)
- 🎮 **GPU Offload** — Optional Vulkan GPU acceleration with `--n-gpu-layers` slider (0-99, default CPU)

## Quick Start

1. Download [latest APK](https://github.com/isky1238/ollama_box_android/releases) and install
2. Download a GGUF model (e.g. Qwen3.5-2B Q4_K_M)
3. Open OllamaBox → Import Model → select your .gguf file
4. Adjust ctxSize/threads → tap **Start Server**
5. Connect from Chatbox: set API URL to `http://127.0.0.1:11434`, API type **OpenAI**

## API Endpoints

| Endpoint | Type | Description |
|----------|------|-------------|
| `GET /` | Status | Server status + model info |
| `GET /health` | Health | Backend readiness (ok/loading/error) |
| `POST /api/chat` | Ollama | Chat completion (Ollama format) |
| `GET /api/tags` | Ollama | Model list (Ollama format, valid modified_at/size/digest) |
| `GET /api/version` | Ollama | Server version |
| `POST /v1/chat/completions` | OpenAI | Chat completion (SSE streaming) |
| `GET /v1/models` | OpenAI | Model list |

## Requirements

- Android 8.0+ (API 26)
- ARM64 device (arm64-v8a)
- ~2GB free RAM for 1.5B models

## Performance

| Device | Model | threads | ctx | tok/s | Notes |
|--------|-------|---------|-----|-------|-------|
| Dimensity 9300+ | Qwen3.5-2B Q4_K_M | 2 | 8192 | **17-21** | mmap, cold start ~14 tok/s |
| Dimensity 9300+ | OpenThinker3-1.5B Q6_K | 2 | 8192 | **~20** | mmap |

> **Note:** mmap loads models in ~3 seconds (vs 30-80s with `--no-mmap`). Hot inference after page-cache warmup is identical to preloaded mode.
>
> **GPU note:** Vulkan availability only confirms that the backend and system loader are present. It does not prove that model layers are executing on hardware GPU. CPU mode is the default and recommended for this Gate build.

## Architecture

```
Chatbox / Ollama Client
        │
        ▼
┌─────────────────────────┐
│  ServerService.kt        │  Foreground Service (WakeLock)
│  OllamaServer.kt (:11434)│  HTTP proxy + format translation
│  ServerBridge.kt → JNI   │  llama.cpp (:11435)
│  jnibridge.c              │  Native loader + mmap
└─────────────────────────┘
        │
        ▼
    llama.cpp HTTP server
    (port 11435, internal)
```

The Kotlin HTTP server on `:11434` proxies to llama.cpp's internal server on `:11435`, translating between Ollama and OpenAI formats. Streaming requests are byte-piped without buffering.

## Changelog

### Gate build

- Added experimental Vulkan backend packaging and GPU-layer controls
- Added native backend/device/offload diagnostics
- Known issue: GPU scheduling may use hardware Vulkan, a software/driver path, or CPU fallback; current benchmarks do not prove hardware GPU execution

### v1.1.1

- **mmap model loading** — models load in ~3s instead of 30-80s
- **Health-aware startup** — reports RUNNING only after model is fully loaded
- **Fixed `/api/tags` compatibility** — valid `modified_at` (ISO 8601), `size`, and `digest` (SHA-256) fields for Chatbox
- **ColorOS background survival** — `IMPORTANCE_DEFAULT` notification channel, always-ongoing notification, `startForeground()` updates
- **Default ctxSize 8192, threads 2** — better out-of-the-box for 2B models
- **JNI signature stability** — linker wrapper embedded in C/C++ flags to survive CMake shared-module rules

### v1.1.0

- Initial release: llama.cpp JNI, Ollama + OpenAI dual API, foreground service, Chatbox integration

## Building

```bash
# CPU-only build (default)
./scripts/build-native.sh

# Build with Vulkan GPU support
BUILD_VULKAN=1 ./scripts/build-native.sh

# Build APK
ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug
```

## License

MIT
