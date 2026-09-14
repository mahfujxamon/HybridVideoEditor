cmake_minimum_required(VERSION 3.22.1)

project(hybrid_opencl)

add_library(
        hybrid_opencl
        SHARED
        opencl_bridge.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        hybrid_opencl
        ${log-lib}
        ${CMAKE_DL_LIBS}
)

target_compile_features(
        hybrid_opencl
        PRIVATE
        cxx_std_17
)