cmake_minimum_required(VERSION 3.10)

project(whisper_lib)

set(CMAKE_CXX_STANDARD 17)

# Version definitions
add_definitions(-DWHISPER_VERSION="1.5.4")
add_definitions(-DGGML_VERSION="1.0.0")
add_definitions(-DGGML_COMMIT="unknown")

# Internal paths
set(WHISPER_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper)
set(GGML_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ggml)

# Include directories
include_directories(${WHISPER_SRC_DIR})
include_directories(${GGML_SRC_DIR}/include)
include_directories(${GGML_SRC_DIR}/src)
include_directories(${GGML_SRC_DIR}/src/ggml-cpu)
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# Manual source list to avoid including incompatible NPUs (CANN, SYCL, etc.)
set(SOURCE_FILES
    jni.c
    whisper/whisper.cpp
    ggml/src/ggml.c
    ggml/src/ggml.cpp
    ggml/src/ggml-alloc.c
    ggml/src/ggml-backend.cpp
    ggml/src/ggml-backend-meta.cpp
    ggml/src/ggml-backend-reg.cpp
    ggml/src/ggml-backend-dl.cpp
    ggml/src/ggml-quants.c
    ggml/src/ggml-threading.cpp
    ggml/src/gguf.cpp
    ggml/src/ggml-opt.cpp
    ggml/src/ggml-cpu/ggml-cpu.c
    ggml/src/ggml-cpu/ggml-cpu.cpp
    ggml/src/ggml-cpu/quants.c
    ggml/src/ggml-cpu/ops.cpp
    ggml/src/ggml-cpu/traits.cpp
    ggml/src/ggml-cpu/vec.cpp
    ggml/src/ggml-cpu/unary-ops.cpp
    ggml/src/ggml-cpu/binary-ops.cpp
    ggml/src/ggml-cpu/hbm.cpp
    ggml/src/ggml-cpu/repack.cpp
)

if (${ANDROID_ABI} MATCHES "arm")
    list(APPEND SOURCE_FILES
        ggml/src/ggml-cpu/arch/arm/quants.c
        ggml/src/ggml-cpu/arch/arm/repack.cpp
        ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp
    )
elseif (${ANDROID_ABI} MATCHES "x86")
    list(APPEND SOURCE_FILES
        ggml/src/ggml-cpu/arch/x86/quants.c
        ggml/src/ggml-cpu/arch/x86/repack.cpp
        ggml/src/ggml-cpu/arch/x86/cpu-feats.cpp
    )
endif()

find_library(LOG_LIB log)

function(build_whisper target_name)
    add_library(${target_name} SHARED ${SOURCE_FILES})

    # Core definitions
    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)

    # Enable exceptions and RTTI for C++ parts
    target_compile_options(${target_name} PRIVATE -fexceptions -frtti)

    # Link necessary libraries
    target_link_libraries(${target_name}
        ${LOG_LIB}
        android
        c++
        "-Wl,-z,max-page-size=16384"
    )

    # Architecture-specific optimizations
    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
    endif ()
endfunction()

# Build targets
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_whisper("whisper_v8fp16_va")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_whisper("whisper_vfpv4")
endif ()

build_whisper("whisper")
