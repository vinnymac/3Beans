cmake_minimum_required(VERSION 3.22.1)
project(3beans CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The emulator core is shared with the desktop build and compiled unmodified
set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core)

file(GLOB CORE_SOURCES CONFIGURE_DEPENDS
    ${CORE_DIR}/*.cpp
    ${CORE_DIR}/arm/*.cpp
    ${CORE_DIR}/convert/*.cpp
    ${CORE_DIR}/dsp/*.cpp
    ${CORE_DIR}/gpu/*.cpp
    ${CORE_DIR}/io/*.cpp
    ${CORE_DIR}/memory/*.cpp)

add_library(3beans SHARED interface.cpp ${CORE_SOURCES})

target_compile_definitions(3beans PRIVATE LOG_LEVEL=0)
target_compile_options(3beans PRIVATE -O3)

# The shim directory provides an inert <epoxy/gl.h>; see shim/epoxy/gl.h
target_include_directories(3beans PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/shim)

target_link_libraries(3beans log)
