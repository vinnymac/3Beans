/*
    Copyright 2023-2026 Hydr8gon

    This file is part of 3Beans.

    3Beans is free software: you can redistribute it and/or modify it
    under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    3Beans is distributed in the hope that it will be useful, but
    WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
    General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with 3Beans. If not, see <https://www.gnu.org/licenses/>.
*/

// Android stand-in for libepoxy's <epoxy/gl.h>.
//
// The shared GPU headers (gpu_render_ogl.h, gpu_shader_glsl.h) and gpu.cpp are
// compiled unmodified. They only need GL types and a handful of standard
// constants (GL_TRIANGLES, GL_NEVER, GL_FALSE), all of which come straight from
// the platform GLES 3 headers.
//
// The desktop OpenGL renderer (gpu_render_ogl.cpp, gpu_shader_glsl.cpp) is NOT
// compiled on Android. Instead the GLES-adapted forks
// gpu_render_ogl_gles.cpp / gpu_shader_glsl_gles.cpp implement the same
// GpuRenderOgl / GpuShaderGlsl classes against real GLES 3.0, and the build
// selects them in CMakeLists.txt. That keeps all desktop-only GL entry points
// (glTexImage1D, GL_UNSIGNED_INT_8_8_8_8, glDrawBuffer, ...) out of this shim;
// the forks call native GLES directly.

#pragma once

#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
