cmake_minimum_required(VERSION 3.10)

project(whisper_engine)

set(CMAKE_CXX_STANDARD 17)

# Add Include Directories
include_directories(.)
include_directories(whisper)
include_directories(ggml)

# Source files
set(
    SOURCE_FILES
    jni.c
    whisper/whisper.cpp
    ggml/ggml.c
    ggml/ggml-cpu.c
    ggml/ggml-cpu.cpp
    ggml/ggml-quants.c
    ggml/ggml-alloc.c
    ggml/ggml-backend.cpp
)

find_library(LOG_LIB log)

find_package(OpenMP REQUIRED)

# Standard whisper library
add_library(whisper SHARED ${SOURCE_FILES})
target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_link_libraries(whisper ${LOG_LIB} android OpenMP::OpenMP_C OpenMP::OpenMP_CXX)

# ARM v8.2 FP16 specialized library
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    add_library(whisper_v8fp16_va SHARED ${SOURCE_FILES})
    target_compile_definitions(whisper_v8fp16_va PUBLIC GGML_USE_CPU)
    target_compile_options(whisper_v8fp16_va PRIVATE -march=armv8.2-a+fp16)
    target_link_libraries(whisper_v8fp16_va ${LOG_LIB} android OpenMP::OpenMP_C OpenMP::OpenMP_CXX)
endif()

# armeabi-v7a specialized library
if (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    add_library(whisper_vfpv4 SHARED ${SOURCE_FILES})
    target_compile_definitions(whisper_vfpv4 PUBLIC GGML_USE_CPU)
    target_compile_options(whisper_vfpv4 PRIVATE -mfpu=neon-vfpv4)
    target_link_libraries(whisper_vfpv4 ${LOG_LIB} android OpenMP::OpenMP_C OpenMP::OpenMP_CXX)
endif()
