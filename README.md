# OllamaBox

[![Release](https://img.shields.io/badge/release-v1.0.0-blue)](https://github.com/isky1238/ollama_box_android/releases)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/arch-arm64--v8a-orange)]()

Run [llama.cpp](https://github.com/ggml-org/llama.cpp) natively on Android and expose an **Ollama-compatible HTTP API** on `localhost:11434`. Import your own GGUF models from storage — no bundling required.

## Features

- 🚀 **Native performance** — llama.cpp runs in-process via JNI, no binary execution needed
- 🔌 **Ollama-compatible** — Works with Chatbox, Open WebUI, Continue.dev, and any Ollama client
- 📦 **No model bundling** — Import GGUF files from phone storage
- 🧵 **Lightweight** — APK ~40MB (after cleanup), model memory ~1.3GB for 1.5B Q6_K

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

See [docs/README.md](docs/README.md) for detailed instructions.

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
