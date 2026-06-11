# OllamaBox

[![Release](https://img.shields.io/badge/release-v1.0.2-blue)](https://github.com/isky1238/ollama_box_android/releases)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/arch-arm64--v8a-orange)]()
[![Speed](https://img.shields.io/badge/perf-+76%25_tok/s-brightgreen)]()

Run [llama.cpp](https://github.com/ggml-org/llama.cpp) natively on Android and expose an **Ollama-compatible HTTP API** on `localhost:11434`. Import your own GGUF models from storage — no bundling required.

## Features

- 🚀 **Native performance** — llama.cpp runs in-process via JNI, no binary execution needed
- 🔌 **Ollama-compatible** — Works with Chatbox, Open WebUI, Continue.dev, and any Ollama client
- 📦 **No model bundling** — Import GGUF files from phone storage
- ⚡ **Optimized for Dimensity 9300/9400** — Custom ggml threading patches (see below)
- 🧵 **Lightweight** — APK ~53MB, model memory ~1.3GB for 1.5B Q6_K

## Quick Start

1. Download [latest APK](https://github.com/isky1238/ollama_box_android/releases) and install
2. Download a GGUF model (e.g. [OpenThinker-1.5B-Q6_K](https://huggingface.co/models?search=OpenThinker3))
3. Open OllamaBox → Import Model → select your .gguf file
4. Tap **Start Server** → wait for "Running" status
5. Connect from Chatbox or: `curl http://127.0.0.1:11434/v1/chat/completions`

## Requirements

- Android 8.0+ (API 26)
- ARM64 device
- ~2GB free RAM for 1.5B models

## Performance

| Device | Model | tok/s | Notes |
|--------|-------|-------|-------|
| Dimensity 9300+ | 1.5B Q6_K | **4.87** | v1.0.2 optimized |
| Dimensity 9300+ | 1.5B Q6_K | 2.76 | v1.0.0 baseline |

### D9300/D9400 Threading Fixes (v1.0.2)

Custom patches to ggml CPU backend for MediaTek all-big-core SoCs:
- **Barrier**: `memory_order_seq_cst` → `acq_rel` + WFE (ARMv9 native low-power wait)
- **Spin-wait**: `yield` → `isb` (deeper pipeline flush) + master/worker differentiation
- **NUMA**: Filter out pseudo-NUMA from CPU cluster exposure (8 cores → all used)
- **i8mm**: Enable `nrows=2` for Q5_0/Q5_1/Q3_K/Q5_K quantization types
- **SVE**: `__attribute__((constructor))` for early SVE2 width detection
- **chunk_size**: Dynamic adaptation for X925 64KB L1-D cache

## Documentation

- [Project Overview](docs/README.md) — Architecture, build instructions, file structure
- [API Reference](docs/API.md) — Endpoints, parameters, client compatibility

## Building

```bash
# Build libjnibridge.so with NDK
clang-19 --target=aarch64-linux-android26 --sysroot=$NDK_SYSROOT \
  -shared -fPIC -O2 -nodefaultlibs \
  -llog -ldl -lc -lm \
  -o app/src/main/jniLibs/arm64-v8a/libjnibridge.so \
  app/src/main/cpp/jnibridge.c

# Build APK
./gradlew assembleDebug
```

See [docs/README.md](docs/README.md) for detailed instructions including ggml-cpu backend rebuild.

## How It Works

```
Kotlin App → System.loadLibrary("jnibridge")
    → JNI nativeStartServer()
        → ggml_backend_load()  # load CPU backend
        → dlopen("libllama-server-impl.so")
        → llama_server(argc, argv)  # blocking HTTP server on :11434
```

The key trick: Android 16+ blocks fork/exec of binaries from app data. We bypass this by calling `llama_server()` directly through JNI, running entirely in-process.

## Credits

- [llama.cpp](https://github.com/ggml-org/llama.cpp) — The incredible LLM inference engine
- Built with ❤️ using Kotlin, Gradle, and Android NDK

## License

MIT
