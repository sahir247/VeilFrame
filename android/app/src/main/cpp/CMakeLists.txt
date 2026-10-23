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
