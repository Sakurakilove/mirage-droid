# MirageDroid devlib: OTA import, device profiles and storage-image
# builders (NOR / SPI NAND / eMMC GPT), plus the mkdevice CLI.
#
#   cmake -S core/devicelib -B build/devlib && cmake --build build/devlib
#
# This build is host-only (Termux clang / Ubuntu gcc); the Android JNI
# library is built by app/src/main/cpp/CMakeLists.txt.
cmake_minimum_required(VERSION 3.16)
project(mirage_devlib C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

include(${CMAKE_CURRENT_LIST_DIR}/devlib_sources.cmake)

add_library(mirage_devlib STATIC
    ${DEVLIB_SOURCES}
    ${DEVLIB_LITTLEFS_SOURCES}
    ${DEVLIB_MINIZ_SOURCES}
)
target_include_directories(mirage_devlib PUBLIC ${DEVLIB_INCLUDE_DIRS})
target_compile_definitions(mirage_devlib PRIVATE ${DEVLIB_COMPILE_DEFINITIONS})
if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(mirage_devlib PRIVATE -Wall -Wextra -Wno-unused-parameter)
endif()

add_executable(mkdevice ${DEVLIB_DIR}/src/mkdevice.c)
target_link_libraries(mkdevice PRIVATE mirage_devlib)
if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(mkdevice PRIVATE -Wall -Wextra)
endif()
