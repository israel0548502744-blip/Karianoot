#!/bin/bash
# Builds libmagpiejni.so (magpie-tts.cpp + the Hebrew patch + the JNI bridge).
#   android/magpie/build_android.sh <ndk dir>   -> arm64-v8a into app/src/main/jniLibs   (CI: build-apk.yml)
#   android/magpie/build_android.sh --host      -> host libmagpiejni.so + magpie-cli in $BUILD/host (magpie-jni.yml)
# BUILD (default android/magpie/build) holds the checkout and the build trees.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="${MAGPIE_BUILD_DIR:-$HERE/build}"
SRC="$BUILD/magpie-tts.cpp"
mkdir -p "$BUILD"
"$HERE/patch_magpie.sh" "$SRC"
if [ "${1:-}" = "--host" ]; then
  cmake -S "$HERE" -B "$BUILD/host" -G Ninja -DMAGPIE_SRC="$SRC" -DCMAKE_BUILD_TYPE=Release \
    -DGGML_NATIVE=ON -DGGML_OPENMP=OFF -DGGML_BLAS=OFF -DMAGPIE_JNI_HOST=ON -DMAGPIE_JNI_CLI=ON > /dev/null
  cmake --build "$BUILD/host" --target magpiejni magpie-cli -j "$(nproc)"
  ls -la "$BUILD/host/libmagpiejni.so" "$BUILD/host/magpie-cli"
  exit 0
fi
NDK="${1:-${ANDROID_NDK_HOME:-}}"
cmake -S "$HERE" -B "$BUILD/android" -G Ninja -DMAGPIE_SRC="$SRC" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_BLAS=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16+i8mm > /dev/null
cmake --build "$BUILD/android" --target magpiejni -j "$(nproc)"
OUT="$HERE/../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
cp "$BUILD/android/libmagpiejni.so" "$OUT/"
"$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip "$OUT/libmagpiejni.so"
# Only the JNI entry points may be exported (the app also carries qwentts' ggml).
if "$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm -D --defined-only "$OUT/libmagpiejni.so" | grep -v " Java_com_karianoot_engine_MagpieNative_" | grep -q " [TDB] "; then
  echo "unexpected exported symbols:"; "$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm -D --defined-only "$OUT/libmagpiejni.so" | head; exit 1
fi
ls -la "$OUT/libmagpiejni.so"
