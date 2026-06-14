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
BUILD_VULKAN="${BUILD_VULKAN:-0}"
echo "BUILD_VULKAN=${BUILD_VULKAN} (set BUILD_VULKAN=1 to enable Vulkan GPU backend)"
LINKER_DIR="$BUILD_DIR/android-linker"
LINKER_WRAPPER="$LINKER_DIR/ld.lld"
GLSLC_WRAPPER="$LINKER_DIR/glslc"
HOST_CC_WRAPPER="$LINKER_DIR/host-cc"
HOST_CXX_WRAPPER="$LINKER_DIR/host-cxx"
HOST_TOOLS_DIR="$LINKER_DIR/host-tools"
VULKAN_HEADERS_DIR="$BUILD_DIR/android-vulkan-headers"
TARGET=35
COMMON_FLAGS="--target=aarch64-linux-android$TARGET --sysroot=$SYSROOT -rtlib=compiler-rt -stdlib=libc++ -fuse-ld=$LINKER_WRAPPER"
LINK_FLAGS="$COMMON_FLAGS -L$SYSROOT/usr/lib/aarch64-linux-android/$TARGET"

mkdir -p "$LINKER_DIR"
cat > "$LINKER_WRAPPER" <<EOF
#!/bin/sh
exec qemu-x86_64 -0 ld.lld "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/lld" "\$@"
EOF
chmod +x "$LINKER_WRAPPER"
cat > "$HOST_CC_WRAPPER" <<EOF
#!/bin/sh
exec /usr/bin/gcc -fuse-ld=bfd "\$@"
EOF
cat > "$HOST_CXX_WRAPPER" <<EOF
#!/bin/sh
exec /usr/bin/g++ -fuse-ld=bfd "\$@"
EOF
chmod +x "$HOST_CC_WRAPPER" "$HOST_CXX_WRAPPER"
mkdir -p "$HOST_TOOLS_DIR"
cat > "$HOST_TOOLS_DIR/ld.lld" <<EOF
#!/bin/sh
exec /usr/bin/ld.bfd "\$@"
EOF
chmod +x "$HOST_TOOLS_DIR/ld.lld"

VULKAN_FLAGS=""
if [[ "$BUILD_VULKAN" == "1" ]]; then
  GLSLC="$(command -v glslc 2>/dev/null || true)"
  if [[ -z "$GLSLC" ]]; then
    GLSLC="$NDK/shader-tools/linux-x86_64/glslc"
  fi
  if [[ ! -x "$GLSLC" ]]; then
    echo "glslc not found. Cannot build Vulkan backend." >&2
    exit 1
  fi
  if [[ "$GLSLC" == *"/linux-x86_64/"* ]]; then
    cat > "$GLSLC_WRAPPER" <<EOF
#!/bin/sh
exec qemu-x86_64 "$GLSLC" "\$@"
EOF
  else
    cat > "$GLSLC_WRAPPER" <<EOF
#!/bin/sh
exec "$GLSLC" "\$@"
EOF
  fi
  chmod +x "$GLSLC_WRAPPER"
  "$GLSLC_WRAPPER" --version >/dev/null
  echo "glslc: $GLSLC"
  if [[ ! -f /usr/include/vulkan/vulkan_hpp_macros.hpp ]]; then
    echo "Complete Vulkan headers not found. Install libvulkan-dev." >&2
    exit 1
  fi
  rm -rf "$VULKAN_HEADERS_DIR"
  mkdir -p "$VULKAN_HEADERS_DIR"
  cp -a /usr/include/vulkan "$VULKAN_HEADERS_DIR/"
  cp -a /usr/include/vk_video "$VULKAN_HEADERS_DIR/"
  cp -a /usr/include/spirv "$VULKAN_HEADERS_DIR/"
  VULKAN_FLAGS="-DGGML_VULKAN=ON -DGGML_OPENMP=OFF \
    -DVulkan_GLSLC_EXECUTABLE=$GLSLC_WRAPPER \
    -DVulkan_INCLUDE_DIR=$VULKAN_HEADERS_DIR \
    -DVulkan_LIBRARY=$SYSROOT/usr/lib/aarch64-linux-android/$TARGET/libvulkan.so"
  rm -rf "$BUILD_DIR/ggml/src/ggml-vulkan/vulkan-shaders-gen-prefix"
fi

JNI_SOURCE="$ROOT/app/src/main/cpp/jnibridge.c"
JNI_LIBRARY="$JNILIBS/libjnibridge.so"
if [[ ! -f "$JNI_LIBRARY" || "$JNI_SOURCE" -nt "$JNI_LIBRARY" ]]; then
  "$TOOLCHAIN/bin/aarch64-linux-android26-clang" \
    -shared -fPIC -O2 -I"$SYSROOT/usr/include" -fuse-ld="$LINKER_WRAPPER" \
    -llog -ldl -lm -Wl,-rpath,'$ORIGIN' \
    -o "$JNI_LIBRARY" "$JNI_SOURCE"
fi

for arg in --model --host --port --ctx-size --threads --threads-batch --parallel --ubatch-size --no-mmap --n-gpu-layers --timeout --chat-template-kwargs; do
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
  -UCMAKE_HAVE_PTHREADS_CREATE \
  -DLLAMA_BUILD_UI=OFF \
  -DLLAMA_OPENSSL=OFF \
  -DGGML_BACKEND_DL=ON \
  -DGGML_CPU_ALL_VARIANTS=ON \
  -DGGML_NATIVE=OFF \
  -DCMAKE_HAVE_LIBC_PTHREAD=1 \
  $VULKAN_FLAGS \
  -DCMAKE_LINKER="$LINKER_WRAPPER" \
  -DCMAKE_C_COMPILER=/usr/bin/clang-19 \
  -DCMAKE_CXX_COMPILER=/usr/bin/clang++-19 \
  -DCMAKE_C_FLAGS="$COMMON_FLAGS" \
  -DCMAKE_CXX_FLAGS="$COMMON_FLAGS" \
  -DCMAKE_EXE_LINKER_FLAGS="$LINK_FLAGS" \
  -DCMAKE_SHARED_LINKER_FLAGS="$LINK_FLAGS -Wl,-rpath,'\$ORIGIN'" \
  -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY

grep -Rq -- "-fuse-ld=$LINKER_WRAPPER" "$BUILD_DIR/ggml/src/CMakeFiles" || {
  echo "CMake did not propagate the reliable Android linker wrapper." >&2
  exit 1
}
if find "$BUILD_DIR" -path "$BUILD_DIR/tests" -prune -o -name link.txt -exec grep -l -- '-lpthreads' {} + | grep -q .; then
  echo "CMake incorrectly selected the host-only -lpthreads library." >&2
  exit 1
fi

# The host shader generator must use the native linker. The system lld may be
# an x86_64 compatibility wrapper and is not suitable for this host tool.
CC="$HOST_CC_WRAPPER" CXX="$HOST_CXX_WRAPPER" \
  PATH="$HOST_TOOLS_DIR:$PATH" \
  cmake --build "$BUILD_DIR" --target llama-server-impl -j2

copy_versioned_library() {
  local name="$1"
  local source
  source="$(find "$BUILD_DIR/bin" -maxdepth 1 -type f -name "$name.so.*" | sort -V | tail -n 1)"
  if [[ -z "$source" && -f "$BUILD_DIR/bin/$name.so" ]]; then
    source="$BUILD_DIR/bin/$name.so"
  fi
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

if [[ "$BUILD_VULKAN" == "1" ]]; then
  copy_versioned_library libggml-vulkan
else
  rm -f "$JNILIBS/libggml-vulkan.so"
fi

rm -f "$JNILIBS"/libggml-cpu-android_*.so
for cpu in "$BUILD_DIR"/bin/libggml-cpu-arm*.so; do
  [[ -f "$cpu" ]] || continue
  name="$(basename "$cpu")"
  name="${name/libggml-cpu-arm/libggml-cpu-android_arm}"
  cp "$cpu" "$JNILIBS/$name"
done

libraries=(
  "$JNILIBS/libggml-base.so" \
  "$JNILIBS/libggml.so" \
  "$JNILIBS/libllama.so" \
  "$JNILIBS/libllama-common.so" \
  "$JNILIBS/libmtmd.so" \
  "$JNILIBS/libllama-server-impl.so"
)
if [[ -f "$JNILIBS/libggml-vulkan.so" ]]; then
  libraries+=("$JNILIBS/libggml-vulkan.so")
fi
for cpu in "$JNILIBS"/libggml-cpu-android_*.so; do
  [[ -f "$cpu" ]] && libraries+=("$cpu")
done
for library in "${libraries[@]}"; do
  normalize_android_library "$library"
  llvm-strip --strip-unneeded "$library"
done

readelf -Ws "$JNILIBS/libjnibridge.so" | grep 'Java_com_ollamabox_ServerBridge_nativeStopServer' >/dev/null
readelf -Ws "$JNILIBS/libllama-server-impl.so" | grep 'ollamabox_server_request_shutdown' >/dev/null
versioned_dependencies="$(
  for library in "$JNILIBS"/*.so; do
    patchelf --print-needed "$library" | grep -E '\.so\.[0-9]' || true
  done
)"
if [[ -n "$versioned_dependencies" ]]; then
  printf '%s\n' "$versioned_dependencies" >&2
  echo "Versioned shared-library dependency remains in Android JNI libraries." >&2
  exit 1
fi
echo "Native libraries rebuilt and verified."
