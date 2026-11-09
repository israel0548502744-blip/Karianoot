# libmagpiejni.so: magpie-tts.cpp (MIT, with the Hebrew patch) + the JNI bridge, in one self-contained library
# (ggml linked in statically). Built for the phone by build_android.sh, and for the host JVM tests (magpie-jni.yml).
#
# Only the JNI entry points are exported. The app also loads libqwenjni.so, which carries its own, different ggml:
# hidden visibility + a version script keep the two from ever binding to each other's symbols.
cmake_minimum_required(VERSION 3.16)
project(karianoot_magpie C CXX)

set(MAGPIE_SRC "" CACHE PATH "magpie-tts.cpp checkout (pinned, patched by patch_magpie.sh)")
option(MAGPIE_JNI_HOST "Build for the host JVM (JNI headers from the JDK)" OFF)
option(MAGPIE_JNI_CLI "Also build magpie-cli from the same sources (the host test compares the bridge with it)" OFF)
if(NOT EXISTS "${MAGPIE_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "MAGPIE_SRC must point at a magpie-tts.cpp checkout")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
add_compile_options(-ffunction-sections -fdata-sections)

# The library and nothing else: no CLI/tests from magpie's own CMake (its examples use CMAKE_SOURCE_DIR paths).
set(MAGPIE_BUILD_CLI OFF CACHE BOOL "" FORCE)
set(MAGPIE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(MAGPIE_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory("${MAGPIE_SRC}" magpie EXCLUDE_FROM_ALL)

add_library(magpiejni SHARED magpie_jni.cpp)
target_link_libraries(magpiejni PRIVATE magpie-tts)
# Same tensor-name size as magpie's own targets (the bridge only uses opaque ggml pointers, but keep the ABI identical).
target_compile_definitions(magpiejni PRIVATE GGML_MAX_NAME=128)
if(MAGPIE_JNI_HOST)
    find_package(JNI REQUIRED)
    target_include_directories(magpiejni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
if(ANDROID)
    target_link_libraries(magpiejni PRIVATE log)
endif()

# Export the JNI functions only; --wrap lets the bridge stop a running synthesis between graph computations (cancel).
set(_map "${CMAKE_CURRENT_BINARY_DIR}/magpiejni.map")
file(WRITE "${_map}" "{\n  global: Java_com_karianoot_engine_MagpieNative_*;\n  local: *;\n};\n")
target_link_options(magpiejni PRIVATE
    "-Wl,--version-script=${_map}" "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections"
    "-Wl,--wrap=ggml_backend_graph_compute")
set_target_properties(magpiejni PROPERTIES LINK_DEPENDS "${_map}")

if(MAGPIE_JNI_CLI)
    add_executable(magpie-cli "${MAGPIE_SRC}/examples/cli/main.cpp")
    target_link_libraries(magpie-cli PRIVATE magpie-tts)
    target_include_directories(magpie-cli PRIVATE "${MAGPIE_SRC}/src" "${MAGPIE_SRC}/third_party")
    target_compile_definitions(magpie-cli PRIVATE GGML_MAX_NAME=128)
endif()
