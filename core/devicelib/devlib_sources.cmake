# Source lists shared by the host CMake build (core/devicelib/CMakeLists.txt)
# and the Android JNI build (app/src/main/cpp/CMakeLists.txt).
#
# Include this file from a CMakeLists that already set CMAKE_CURRENT_LIST_DIR
# semantics; the paths below are absolute (based on this file's directory).

set(DEVLIB_DIR ${CMAKE_CURRENT_LIST_DIR})
set(REPO_ROOT ${DEVLIB_DIR}/../..)

set(DEVLIB_SOURCES
    ${DEVLIB_DIR}/src/devlib_util.c
    ${DEVLIB_DIR}/src/devlib_profile.c
    ${DEVLIB_DIR}/src/devlib_identity.c
    ${DEVLIB_DIR}/src/devlib_zip.c
    ${DEVLIB_DIR}/src/devlib_ota.c
    ${DEVLIB_DIR}/src/devlib_factory.c
    ${DEVLIB_DIR}/src/devlib_romfs.c
    ${DEVLIB_DIR}/src/devlib_yaffs2.c
    ${DEVLIB_DIR}/src/devlib_nand.c
    ${DEVLIB_DIR}/src/devlib_emmc.c
    ${DEVLIB_DIR}/src/devlib_device.c
    ${DEVLIB_DIR}/src/devlib_bind_resources.c
)

set(DEVLIB_LITTLEFS_SOURCES
    ${REPO_ROOT}/core/littlefs/lfs.c
    ${REPO_ROOT}/core/littlefs/lfs_util.c
    ${REPO_ROOT}/core/littlefs/devlib_lfs.c
)

set(DEVLIB_MINIZ_SOURCES
    ${DEVLIB_DIR}/third_party/miniz/miniz.c
    ${DEVLIB_DIR}/third_party/miniz/miniz_tdef.c
    ${DEVLIB_DIR}/third_party/miniz/miniz_tinfl.c
    ${DEVLIB_DIR}/third_party/miniz/miniz_zip.c
)

set(DEVLIB_INCLUDE_DIRS
    ${DEVLIB_DIR}/include
    ${DEVLIB_DIR}/src
    ${REPO_ROOT}/core/littlefs
    ${DEVLIB_DIR}/third_party/miniz
)

set(DEVLIB_COMPILE_DEFINITIONS
    LFS_MULTIVERSION
    _FILE_OFFSET_BITS=64
    MINIZ_NO_TIME
)
