# Inkora's native part: the MyPaint brush engine (libmypaint, ISC license) with json-c (MIT
# license) for reading brush files, and the bridge to the app.
cmake_minimum_required(VERSION 3.18)
project(inkora_paint C)

set(CMAKE_C_STANDARD 99)

# json-c: only the static library
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_APPS OFF CACHE BOOL "" FORCE)
set(DISABLE_WERROR ON CACHE BOOL "" FORCE)
set(DISABLE_EXTRA_LIBS ON CACHE BOOL "" FORCE)
set(DISABLE_BSYMBOLIC ON CACHE BOOL "" FORCE)
add_subdirectory(json-c)

add_library(inkora_paint SHARED
    libmypaint_unity.c
    inkora_surface.c
    inkora_jni.c)

target_include_directories(inkora_paint PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libmypaint
    ${CMAKE_CURRENT_SOURCE_DIR}/json-c
    ${CMAKE_CURRENT_BINARY_DIR}/json-c)

target_compile_options(inkora_paint PRIVATE -O3 -Wno-unused-parameter -Wno-sign-compare)
# smaller library: only what the app calls stays
target_link_options(inkora_paint PRIVATE -Wl,--gc-sections)
# Google Play: native code must work on devices with 16 KB memory pages
target_link_options(inkora_paint PRIVATE -Wl,-z,max-page-size=16384)

target_link_libraries(inkora_paint PRIVATE json-c jnigraphics log m)
