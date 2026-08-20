# CMake for YaesuCATControlADV CW Denoiser
# Based on HamNoise core (C)

cmake_minimum_required(VERSION 3.22.1)
project(hamnoise_cw C)

set(CMAKE_C_STANDARD 11)

add_library(hamnoise_cw SHARED
    hamnoise/denoise_audio.c
    hamnoise/denoise_gru.c
    hamnoise/jni_bridge.c
)

target_include_directories(hamnoise_cw PRIVATE hamnoise)

# Force 16 KB alignment for Android 15 compatibility
target_link_options(hamnoise_cw PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
target_link_libraries(hamnoise_cw ${log-lib} m)
