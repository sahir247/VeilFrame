cmake_minimum_required(VERSION 3.22.1)
project(veilframe_ncnn LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fexceptions")

# 16 KB ELF page alignment is set on the app's native shared target below.

set(ncnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)
# NCNN's imported target may inject -fno-exceptions, but clearing its entire
# interface option list silently drops other required flags. Remove only that
# incompatible option and preserve every other upstream interface setting.
get_target_property(_ncnn_interface_compile_options ncnn INTERFACE_COMPILE_OPTIONS)
if(_ncnn_interface_compile_options)
    list(FILTER _ncnn_interface_compile_options EXCLUDE REGEX "^-fno-exceptions$")
    set_target_properties(ncnn PROPERTIES
        INTERFACE_COMPILE_OPTIONS "${_ncnn_interface_compile_options}"
    )
endif()

add_library(veilframe_ncnn SHARED
    realsr_jni.cpp
    realsr.cpp
    matting_jni.cpp
    pii_ncnn_jni.cpp
    rife_jni.cpp
    rife/rife.cpp
    rife/warp.cpp
    veilnet_jni.cpp
)

target_compile_options(veilframe_ncnn PRIVATE -fexceptions)
target_link_options(veilframe_ncnn PRIVATE "-Wl,-z,max-page-size=16384")

target_include_directories(veilframe_ncnn PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/rife
    ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}/include
)

target_link_libraries(veilframe_ncnn PRIVATE
    ncnn
    vulkan
    log
    jnigraphics
)

# Isolated upstream build; libjpeg-turbo explicitly rejects add_subdirectory.
include(ExternalProject)
set(VF_JPEG_SOURCE "${CMAKE_CURRENT_BINARY_DIR}/jpeg-upstream")
set(VF_JPEG_BUILD "${CMAKE_CURRENT_BINARY_DIR}/jpeg-build")
ExternalProject_Add(vf_jpeg_dependency
    GIT_REPOSITORY https://github.com/libjpeg-turbo/libjpeg-turbo.git
    GIT_TAG 7b4993786d856cbccabfc0ed2f923ba000e326cc
    UPDATE_DISCONNECTED TRUE
    SOURCE_DIR "${VF_JPEG_SOURCE}"
    BINARY_DIR "${VF_JPEG_BUILD}"
    CMAKE_ARGS
        "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
        "-DANDROID_ABI=${ANDROID_ABI}"
        "-DANDROID_PLATFORM=${ANDROID_PLATFORM}"
        "-DCMAKE_BUILD_TYPE=Release"
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
        "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        -DENABLE_SHARED=OFF -DENABLE_STATIC=ON
        -DWITH_TURBOJPEG=OFF -DWITH_TOOLS=OFF -DWITH_TESTS=OFF
        -DWITH_ARITH_ENC=OFF -DWITH_ARITH_DEC=OFF
        -DWITH_SIMD=ON -DREQUIRE_SIMD=ON
    BUILD_COMMAND "${CMAKE_COMMAND}" --build <BINARY_DIR> --target jpeg-static --parallel 2
    BUILD_BYPRODUCTS "${VF_JPEG_BUILD}/libjpeg.a"
    INSTALL_COMMAND ""
)
add_library(vf_jpeg_static STATIC IMPORTED)
set_target_properties(vf_jpeg_static PROPERTIES IMPORTED_LOCATION "${VF_JPEG_BUILD}/libjpeg.a")
add_dependencies(vf_jpeg_static vf_jpeg_dependency)
add_library(veilframe_jpeg SHARED streaming_jpeg_jni.cpp)
add_dependencies(veilframe_jpeg vf_jpeg_dependency)
target_include_directories(veilframe_jpeg PRIVATE "${VF_JPEG_SOURCE}/src" "${VF_JPEG_BUILD}")
target_link_libraries(veilframe_jpeg PRIVATE vf_jpeg_static)
target_link_options(veilframe_jpeg PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
set_target_properties(veilframe_jpeg PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
