#!/bin/bash
# Builds libqwenjni.so (qwentts.cpp + JNI bridge) for arm64-v8a into app/src/main/jniLibs.
# Usage: android/qwen/build_android.sh <ndk dir>     (CI: $ANDROID_NDK_HOME)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="${1:-${ANDROID_NDK_HOME:-}}"
REV=de2ad833e9bd5c06082231d1da637789f7562816   # qwentts.cpp, MIT
SRC="$HERE/build/qwentts.cpp"
mkdir -p "$HERE/build"
if [ ! -d "$SRC/.git" ]; then
  git clone -q https://github.com/ServeurpersoCom/qwentts.cpp "$SRC"
fi
cd "$SRC" && git checkout -q $REV && git submodule update --init --depth 1 -q
"$HERE/patch_qwentts.sh" "$SRC"
cmake -S "$SRC" -B "$HERE/build/android" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_BLAS=OFF -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16+i8mm -DQWEN_JNI=ON > /dev/null
cmake --build "$HERE/build/android" --target qwenjni -j "$(nproc)"
OUT="$HERE/../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
cp "$HERE/build/android/libqwenjni.so" "$OUT/"
"$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip "$OUT/libqwenjni.so"
ls -la "$OUT/libqwenjni.so"
