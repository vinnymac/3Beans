cmake_minimum_required(VERSION 3.22.1)
project(3beans CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The emulator core is shared with the desktop build. Most of it is compiled
# unmodified; the two desktop-OpenGL renderer translation units are swapped for
# GLES-adapted forks below that implement the same classes.
get_filename_component(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core ABSOLUTE)

file(GLOB CORE_SOURCES CONFIGURE_DEPENDS
    ${CORE_DIR}/*.cpp
    ${CORE_DIR}/arm/*.cpp
    ${CORE_DIR}/convert/*.cpp
    ${CORE_DIR}/dsp/*.cpp
    ${CORE_DIR}/gpu/*.cpp
    ${CORE_DIR}/io/*.cpp
    ${CORE_DIR}/memory/*.cpp)

# Drop the desktop OpenGL renderer + shader JIT; Android compiles the GLES forks
# (gpu_render_ogl_gles.cpp, gpu_shader_glsl_gles.cpp) instead, which define the
# same GpuRenderOgl / GpuShaderGlsl classes against GLES 3.0.
list(REMOVE_ITEM CORE_SOURCES
    ${CORE_DIR}/gpu/gpu_render_ogl.cpp
    ${CORE_DIR}/gpu/gpu_shader_glsl.cpp)

add_library(3beans SHARED
    interface.cpp
    egl_context.cpp
    gpu_render_ogl_gles.cpp
    gpu_shader_glsl_gles.cpp
    ${CORE_SOURCES})

target_compile_definitions(3beans PRIVATE LOG_LEVEL=0)

# Match the desktop Makefile's -O3 -flto. ThinLTO lets the hot memory fast path
# and interpreter dispatch helpers inline across translation units, which the
# per-file Android build otherwise can't do. Both ABIs (arm64-v8a, x86_64) use
# clang/lld, which support ThinLTO out of the box.
target_compile_options(3beans PRIVATE -O3 -flto=thin)
target_link_options(3beans PRIVATE -flto=thin)

# shim/ provides a thin <epoxy/gl.h> (GLES 3 types + constants). Adding the core
# gpu/ dir lets the GLES renderer forks keep their upstream "../core.h" /
# "gpu_*.h" includes unchanged, so they stay easy to re-sync with upstream.
target_include_directories(3beans PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/shim
    ${CORE_DIR}/gpu)

target_link_libraries(3beans log EGL GLESv3)
