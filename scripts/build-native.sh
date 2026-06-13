#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/27.0.12077973}"
if [[ ! -d "$NDK/toolchains/llvm" ]]; then
  NDK="$(find "$NDK" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-aarch64"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
JNILIBS="$ROOT/app/src/main/jniLibs/arm64-v8a"
BUILD_DIR="$ROOT/native/llama.cpp/build-android"
LINKER_DIR="$BUILD_DIR/android-linker"
LINKER_WRAPPER="$LINKER_DIR/ld.lld"
TARGET=35
COMMON_FLAGS="--target=aarch64-linux-android$TARGET --sysroot=$SYSROOT -rtlib=compiler-rt -stdlib=libc++ -fuse-ld=$LINKER_WRAPPER"
LINK_FLAGS="$COMMON_FLAGS -L$SYSROOT/usr/lib/aarch64-linux-android/$TARGET"

mkdir -p "$LINKER_DIR"
cat > "$LINKER_WRAPPER" <<EOF
#!/bin/sh
exec qemu-x86_64 -0 ld.lld "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/lld" "\$@"
EOF
chmod +x "$LINKER_WRAPPER"

JNI_SOURCE="$ROOT/app/src/main/cpp/jnibridge.c"
JNI_LIBRARY="$JNILIBS/libjnibridge.so"
if [[ ! -f "$JNI_LIBRARY" || "$JNI_SOURCE" -nt "$JNI_LIBRARY" ]]; then
  "$TOOLCHAIN/bin/aarch64-linux-android26-clang" \
    -shared -fPIC -O2 -I"$SYSROOT/usr/include" -fuse-ld="$LINKER_WRAPPER" \
    -llog -ldl -lm -Wl,-rpath,'$ORIGIN' \
    -o "$JNI_LIBRARY" "$JNI_SOURCE"
fi

for arg in --model --host --port --ctx-size --threads --threads-batch --parallel --ubatch-size --no-mmap --timeout --chat-template-kwargs; do
  grep -Fq -- "$arg" "$ROOT/native/llama.cpp/common/arg.cpp" || {
    echo "JNI argument is unsupported by llama.cpp: $arg" >&2
    exit 1
  }
done
if grep -Eq -- '--timeout-read|--timeout-write' "$JNI_SOURCE"; then
  echo "JNI contains obsolete llama.cpp timeout arguments." >&2
  exit 1
fi

cmake -S "$ROOT/native/llama.cpp" -B "$BUILD_DIR" \
  -DLLAMA_BUILD_UI=OFF \
  -DCMAKE_C_COMPILER=/usr/bin/clang-19 \
  -DCMAKE_CXX_COMPILER=/usr/bin/clang++-19 \
  -DCMAKE_C_FLAGS="$COMMON_FLAGS" \
  -DCMAKE_CXX_FLAGS="$COMMON_FLAGS" \
  -DCMAKE_EXE_LINKER_FLAGS="$LINK_FLAGS" \
  -DCMAKE_SHARED_LINKER_FLAGS="$LINK_FLAGS -Wl,-rpath,'\$ORIGIN'"

grep -Rq -- "-fuse-ld=$LINKER_WRAPPER" "$BUILD_DIR/ggml/src/CMakeFiles" || {
  echo "CMake did not propagate the reliable Android linker wrapper." >&2
  exit 1
}

cmake --build "$BUILD_DIR" --target llama-server-impl -j2

copy_versioned_library() {
  local name="$1"
  local source
  source="$(find "$BUILD_DIR/bin" -maxdepth 1 -type f -name "$name.so.*" | sort -V | tail -n 1)"
  if [[ -z "$source" ]]; then
    echo "Missing built library: $name" >&2
    exit 1
  fi
  cp "$source" "$JNILIBS/$name.so"
}

normalize_android_library() {
  local library="$1"
  local soname
  soname="$(basename "$library")"
  patchelf --set-soname "$soname" "$library"
  while read -r needed; do
    case "$needed" in
      libggml-base.so.*) patchelf --replace-needed "$needed" libggml-base.so "$library" ;;
      libggml.so.*) patchelf --replace-needed "$needed" libggml.so "$library" ;;
      libllama.so.*) patchelf --replace-needed "$needed" libllama.so "$library" ;;
      libllama-common.so.*) patchelf --replace-needed "$needed" libllama-common.so "$library" ;;
      libmtmd.so.*) patchelf --replace-needed "$needed" libmtmd.so "$library" ;;
    esac
  done < <(patchelf --print-needed "$library")
}

# Android only packages libraries ending in .so. Keep all linked llama libraries
# from the same build and remove desktop-style .so.0 dependencies.
copy_versioned_library libggml-base
copy_versioned_library libggml
copy_versioned_library libllama
copy_versioned_library libllama-common
copy_versioned_library libmtmd
cp "$BUILD_DIR/bin/libllama-server-impl.so" "$JNILIBS/libllama-server-impl.so"

for cpu in "$BUILD_DIR"/bin/libggml-cpu-arm*.so; do
  [[ -f "$cpu" ]] || continue
  cp "$cpu" "$JNILIBS/$(basename "${cpu/libggml-cpu-/libggml-cpu-android_}")"
done

for library in \
  "$JNILIBS/libggml-base.so" \
  "$JNILIBS/libggml.so" \
  "$JNILIBS/libllama.so" \
  "$JNILIBS/libllama-common.so" \
  "$JNILIBS/libmtmd.so" \
  "$JNILIBS/libllama-server-impl.so" \
  "$JNILIBS"/libggml-cpu-android_*.so; do
  normalize_android_library "$library"
done

readelf -Ws "$JNILIBS/libjnibridge.so" | grep 'Java_com_ollamabox_ServerBridge_nativeStopServer' >/dev/null
readelf -Ws "$JNILIBS/libllama-server-impl.so" | grep 'ollamabox_server_request_shutdown' >/dev/null
if find "$JNILIBS" -maxdepth 1 -name '*.so' -exec patchelf --print-needed {} \; | grep -Eq '\.so\.[0-9]'; then
  echo "Versioned shared-library dependency remains in Android JNI libraries." >&2
  exit 1
fi
echo "Native libraries rebuilt and verified."
