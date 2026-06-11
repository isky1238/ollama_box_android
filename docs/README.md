# OllamaBox

Run [llama.cpp](https://github.com/ggml-org/llama.cpp) on your Android phone and expose an **Ollama-compatible HTTP API** on `localhost:11434`. Other apps (Chatbox, Open WebUI, custom clients) can connect to it just like a local Ollama instance.

## How It Works

```
┌──────────────────────────────────────┐
│  Android App (OllamaBox)             │
│                                      │
│  Kotlin ──► JNI (libjnibridge.so)    │
│                  │                   │
│                  ▼                   │
│  llama_server() ── in-process call   │
│  (from libllama-server-impl.so)      │
│                  │                   │
│                  ▼                   │
│  HTTP Server on 127.0.0.1:11434     │
│  ┌─────────────────────────────┐     │
│  │ /health  /v1/models         │     │
│  │ /v1/chat/completions        │     │
│  │ /v1/completions             │     │
│  └─────────────────────────────┘     │
└──────────────────────────────────────┘
         ▲
         │ HTTP
         ▼
┌─────────────────┐
│  Chatbox / other │
│  (Ollama client) │
└─────────────────┘
```

### Why JNI instead of running a binary?

Android 16+ (especially OPPO ColorOS) blocks `fork/exec` of binaries from app data directories (`noexec` mount). The solution is to load llama.cpp's libraries directly and call `llama_server()` in-process via JNI.

### Architecture

- **`libjnibridge.so`** — Thin C JNI bridge (7KB). Loads all llama.cpp .so files with `RTLD_GLOBAL`, loads the CPU backend via `ggml_backend_load()`, and calls `llama_server()`.
- **`libllama-server-impl.so`** — Full llama.cpp HTTP server (13.6MB). Provides the Ollama-compatible REST API.
- **`libggml-cpu-android_*.so`** — CPU backend variants for different ARM ISA levels (v8.0, v8.2, v8.6, v9.0, v9.2).
- **Kotlin `ServerBridge`** — Loads `libjnibridge.so` and exposes `nativeStartServer()` as a blocking JNI call.
- **`MainActivity`** — UI with model import, server start/stop, and health-check polling.

## Requirements

- Android 8.0+ (API 26+)
- ARM64 device (arm64-v8a)
- ~2GB free RAM for a 1.5B Q6_K model
- GGUF model file (user provides, imported from storage)

## Building

### Prerequisites
- Android SDK (platform 35, build-tools 35.0.1)
- Android NDK 27+
- On ARM64 Linux host: `clang-19` and `lld-19` packages

### Build jnibridge.so
```bash
NDK=/path/to/android-sdk/ndk/27.0.12077973
SYSROOT=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot
JNILIBS=app/src/main/jniLibs/arm64-v8a

clang-19 --target=aarch64-linux-android26 --sysroot=$SYSROOT \
  -shared -fPIC -O2 \
  -I$SYSROOT/usr/include \
  -nodefaultlibs \
  -L$SYSROOT/usr/lib/aarch64-linux-android/26 \
  -llog -ldl -lc -lm -Wl,-rpath,'$ORIGIN' \
  -o $JNILIBS/libjnibridge.so \
  app/src/main/cpp/jnibridge.c
```

### Build APK
```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. **Import model**: Tap "Import Model" and select a GGUF file from storage
2. **Start server**: Tap "Start Server". The UI shows "Running" when ready.
3. **Connect**: Point any Ollama-compatible client to `http://127.0.0.1:11434`

```bash
# Test from Termux
curl http://127.0.0.1:11434/health
curl http://127.0.0.1:11434/v1/models
curl http://127.0.0.1:11434/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"your-model.gguf","messages":[{"role":"user","content":"Hello"}],"max_tokens":50}'
```

## Known Issues

### Thread deadlock on some SoCs
On MediaTek Dimensity 9300+ (and possibly other ARM SoCs), `llama.cpp`'s default auto-detected thread count (8) can cause a thread pool deadlock. **Workaround**: Set `--threads 2` in `jnibridge.c` (already configured).

### Memory pressure
Android may kill the app under heavy memory load. Close background apps before running large models. 1.5B Q6_K needs ~1.3GB RSS.

## File Structure

```
research/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   └── jnibridge.c          # JNI bridge (pure C)
│       ├── java/com/ollamabox/
│       │   ├── MainActivity.kt      # UI + server management
│       │   ├── ServerBridge.kt      # JNI loader
│       │   └── OllamaServerService.kt  # (unused, kept for reference)
│       ├── jniLibs/arm64-v8a/       # Prebuilt .so files
│       │   ├── libjnibridge.so      # Our JNI bridge
│       │   ├── libllama-server-impl.so
│       │   ├── libllama.so
│       │   ├── libllama-common.so
│       │   ├── libggml.so
│       │   ├── libggml-base.so
│       │   ├── libggml-cpu-android_*.so  # CPU backends
│       │   ├── libmtmd.so
│       │   └── libc++_shared.so
│       └── res/
├── native/
│   └── llama.cpp/                   # Git submodule (llama.cpp source)
├── docs/
│   ├── README.md                    # This file
│   └── API.md                       # API reference
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Credits

- [llama.cpp](https://github.com/ggml-org/llama.cpp) — LLM inference engine
- Built with Gradle, Android NDK, and Kotlin
