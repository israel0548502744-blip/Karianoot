#!/bin/bash
# Builds libwhisperjni.so (whisper.cpp at a pinned commit + the JNI bridge).
#   android/whisper/build_android.sh <ndk dir>   -> arm64-v8a into app/src/main/jniLibs   (CI: build-apk.yml)
#   android/whisper/build_android.sh --host      -> host libwhisperjni.so in $BUILD/host (whisper-jni.yml)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="${WHISPER_BUILD_DIR:-$HERE/build}"
SRC="$BUILD/whisper.cpp"
COMMIT="${WHISPER_COMMIT:-$(cat "$HERE/COMMIT")}"
mkdir -p "$BUILD"
if [ ! -d "$SRC/.git" ]; then
  git clone -q https://github.com/ggml-org/whisper.cpp "$SRC"
fi
git -C "$SRC" fetch -q --depth 1 origin "$COMMIT" 2>/dev/null || true
git -C "$SRC" checkout -q "$COMMIT"
if [ "${1:-}" = "--host" ]; then
  cmake -S "$HERE" -B "$BUILD/host" -G Ninja -DWHISPER_SRC="$SRC" -DCMAKE_BUILD_TYPE=Release \
    -DGGML_NATIVE=ON -DGGML_OPENMP=OFF -DWHISPER_JNI_HOST=ON > /dev/null
  cmake --build "$BUILD/host" --target whisperjni -j "$(nproc)"
  ls -la "$BUILD/host/libwhisperjni.so"
  exit 0
fi
NDK="${1:-${ANDROID_NDK_HOME:-}}"
cmake -S "$HERE" -B "$BUILD/android" -G Ninja -DWHISPER_SRC="$SRC" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16+i8mm > /dev/null
cmake --build "$BUILD/android" --target whisperjni -j "$(nproc)"
OUT="$HERE/../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
cp "$BUILD/android/libwhisperjni.so" "$OUT/"
"$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip "$OUT/libwhisperjni.so"
if "$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm -D --defined-only "$OUT/libwhisperjni.so" | grep -v " Java_com_karianoot_engine_WhisperNative_" | grep -q " [TDB] "; then
  echo "unexpected exported symbols:"; "$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm -D --defined-only "$OUT/libwhisperjni.so" | head; exit 1
fi
ls -la "$OUT/libwhisperjni.so"
