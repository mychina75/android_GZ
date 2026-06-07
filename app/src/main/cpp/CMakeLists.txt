# Native trigger pipeline for the intelligent camera system.
# Builds libcamera_native.so containing the L0 trigger rule engine
# (gate / arbitration / hysteresis) and the CV proxies (bokeh, lens corner).
cmake_minimum_required(VERSION 3.22.1)

project(camera_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(camera_native SHARED
        trigger_engine.cpp
        rules_generated.cpp
        cv_stats.cpp
        mimic_core.cpp
        jni_bridge.cpp)

target_compile_options(camera_native PRIVATE
        -O2
        -ffast-math
        -fvisibility=hidden
        -Wall)

find_library(log-lib log)
target_link_libraries(camera_native ${log-lib})
