#!/bin/bash
# Small, idempotent patches to qwentts.cpp: a thread-count override (ARM has no SMT, so "half the cores" is too few)
# and the JNI target (QWEN_JNI=ON).
set -euo pipefail
SRC="$1"; HERE="$(cd "$(dirname "$0")" && pwd)"
grep -q QT_N_THREADS "$SRC/src/backend.h" || sed -i 's|    int n = (int) std::thread::hardware_concurrency() / 2;|    if (const char * e = getenv("QT_N_THREADS")) { int v = atoi(e); if (v > 0) return v; }\n    int n = (int) std::thread::hardware_concurrency() / 2;|' "$SRC/src/backend.h"
grep -q "<cstdlib>" "$SRC/src/backend.h" || sed -i '0,/#include/s//#include <cstdlib>\n#include/' "$SRC/src/backend.h"
if ! grep -q QWEN_JNI "$SRC/CMakeLists.txt"; then
cat >> "$SRC/CMakeLists.txt" <<CMAKE

option(QWEN_JNI "Build the Android JNI bridge (Karianoot)" OFF)
if(QWEN_JNI OR QWEN_JNI_HOST)
    add_library(qwenjni SHARED $HERE/qwen_jni.cpp)
    if(QWEN_JNI_HOST)
        find_package(JNI REQUIRED)
        target_include_directories(qwenjni PRIVATE \${JNI_INCLUDE_DIRS})
    endif()
    target_link_libraries(qwenjni PRIVATE qwen-core)
    if(ANDROID)
        target_link_libraries(qwenjni PRIVATE log)
    endif()
    link_ggml_backends(qwenjni)
endif()
CMAKE
fi
