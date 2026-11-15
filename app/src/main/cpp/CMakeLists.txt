# libmiragedroid.so — devlib + littlefs + miniz, built as the Android JNI
# library. Gradle's externalNativeBuild points here (android-app wires it).
#
# The library is self-contained: no external headers or libraries beyond
# libc/libm, and the BindTemplate databases are compiled in.
cmake_minimum_required(VERSION 3.16)
project(miragedroid C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

include(${CMAKE_CURRENT_LIST_DIR}/../../../../core/devicelib/devlib_sources.cmake)

add_library(miragedroid SHARED
    ${CMAKE_CURRENT_LIST_DIR}/miragedroid_jni.c
    ${DEVLIB_SOURCES}
    ${DEVLIB_LITTLEFS_SOURCES}
    ${DEVLIB_MINIZ_SOURCES}
)
target_include_directories(miragedroid PRIVATE ${DEVLIB_INCLUDE_DIRS})
target_compile_definitions(miragedroid PRIVATE ${DEVLIB_COMPILE_DEFINITIONS})
target_compile_options(miragedroid PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(miragedroid PRIVATE log m)
