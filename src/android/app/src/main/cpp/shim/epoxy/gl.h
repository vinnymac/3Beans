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
// The Android frontend always runs the software GPU renderer; the desktop
// OpenGL renderer (gpu_render_ogl.cpp, gpu_shader_glsl.cpp) is compiled but
// never instantiated because Settings::gpuRenderer is forced to 0 by the JNI
// bridge before a core is created. This header exists so those files compile
// unmodified, keeping the weekly upstream merge conflict-free. Every entry
// point is an inert stub; if upstream starts using a GL symbol that is
// missing here, the Android build fails loudly and this shim must be updated.

#pragma once

#include <cstddef>

typedef unsigned int GLenum;
typedef unsigned int GLuint;
typedef unsigned int GLbitfield;
typedef int GLint;
typedef int GLsizei;
typedef float GLfloat;
typedef double GLdouble;
typedef char GLchar;
typedef unsigned char GLboolean;
typedef ptrdiff_t GLsizeiptr;

#define GL_FALSE 0
#define GL_TRUE 1
#define GL_ZERO 0
#define GL_ONE 1

#define GL_DEPTH_BUFFER_BIT 0x00000100
#define GL_STENCIL_BUFFER_BIT 0x00000400
#define GL_COLOR_BUFFER_BIT 0x00004000

#define GL_TRIANGLES 0x0004
#define GL_TRIANGLE_STRIP 0x0005
#define GL_TRIANGLE_FAN 0x0006

#define GL_NEVER 0x0200
#define GL_LESS 0x0201
#define GL_EQUAL 0x0202
#define GL_LEQUAL 0x0203
#define GL_GREATER 0x0204
#define GL_NOTEQUAL 0x0205
#define GL_GEQUAL 0x0206
#define GL_ALWAYS 0x0207

#define GL_SRC_COLOR 0x0300
#define GL_ONE_MINUS_SRC_COLOR 0x0301
#define GL_SRC_ALPHA 0x0302
#define GL_ONE_MINUS_SRC_ALPHA 0x0303
#define GL_DST_ALPHA 0x0304
#define GL_ONE_MINUS_DST_ALPHA 0x0305
#define GL_DST_COLOR 0x0306
#define GL_ONE_MINUS_DST_COLOR 0x0307
#define GL_SRC_ALPHA_SATURATE 0x0308

#define GL_FRONT 0x0404
#define GL_BACK 0x0405
#define GL_CW 0x0900
#define GL_CCW 0x0901

#define GL_CULL_FACE 0x0B44
#define GL_DEPTH_TEST 0x0B71
#define GL_STENCIL_TEST 0x0B90
#define GL_BLEND 0x0BE2

#define GL_TEXTURE_1D 0x0DE0
#define GL_TEXTURE_2D 0x0DE1
#define GL_TEXTURE_BORDER_COLOR 0x1004

#define GL_UNSIGNED_BYTE 0x1401
#define GL_FLOAT 0x1406
#define GL_INVERT 0x150A

#define GL_RED 0x1903
#define GL_GREEN 0x1904
#define GL_BLUE 0x1905
#define GL_ALPHA 0x1906
#define GL_RGB 0x1907
#define GL_RGBA 0x1908

#define GL_KEEP 0x1E00
#define GL_REPLACE 0x1E01
#define GL_INCR 0x1E02
#define GL_DECR 0x1E03

#define GL_NEAREST 0x2600
#define GL_LINEAR 0x2601
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_WRAP_S 0x2802
#define GL_TEXTURE_WRAP_T 0x2803
#define GL_REPEAT 0x2901

#define GL_CONSTANT_COLOR 0x8001
#define GL_ONE_MINUS_CONSTANT_COLOR 0x8002
#define GL_CONSTANT_ALPHA 0x8003
#define GL_ONE_MINUS_CONSTANT_ALPHA 0x8004
#define GL_FUNC_ADD 0x8006
#define GL_MIN 0x8007
#define GL_MAX 0x8008
#define GL_FUNC_SUBTRACT 0x800A
#define GL_FUNC_REVERSE_SUBTRACT 0x800B

#define GL_UNSIGNED_SHORT_4_4_4_4 0x8033
#define GL_UNSIGNED_SHORT_5_5_5_1 0x8034
#define GL_UNSIGNED_INT_8_8_8_8 0x8035
#define GL_UNSIGNED_SHORT_5_6_5 0x8363

#define GL_CLAMP_TO_BORDER 0x812D
#define GL_CLAMP_TO_EDGE 0x812F
#define GL_MIRRORED_REPEAT 0x8370

#define GL_DEPTH_STENCIL_ATTACHMENT 0x821A
#define GL_RG 0x8227
#define GL_TEXTURE0 0x84C0
#define GL_INCR_WRAP 0x8507
#define GL_DECR_WRAP 0x8508

#define GL_ARRAY_BUFFER 0x8892
#define GL_DYNAMIC_DRAW 0x88E8
#define GL_DEPTH24_STENCIL8 0x88F0

#define GL_FRAGMENT_SHADER 0x8B30
#define GL_VERTEX_SHADER 0x8B31
#define GL_COMPILE_STATUS 0x8B81
#define GL_INFO_LOG_LENGTH 0x8B84

#define GL_TEXTURE_1D_ARRAY 0x8C18
#define GL_COLOR_ATTACHMENT0 0x8CE0
#define GL_FRAMEBUFFER 0x8D40
#define GL_RENDERBUFFER 0x8D41

#define GL_TEXTURE_SWIZZLE_R 0x8E42
#define GL_TEXTURE_SWIZZLE_G 0x8E43
#define GL_TEXTURE_SWIZZLE_B 0x8E44
#define GL_TEXTURE_SWIZZLE_A 0x8E45

inline void glActiveTexture(GLenum) {}
inline void glAttachShader(GLuint, GLuint) {}
inline void glBindBuffer(GLenum, GLuint) {}
inline void glBindFramebuffer(GLenum, GLuint) {}
inline void glBindRenderbuffer(GLenum, GLuint) {}
inline void glBindTexture(GLenum, GLuint) {}
inline void glBindVertexArray(GLuint) {}
inline void glBlendColor(GLfloat, GLfloat, GLfloat, GLfloat) {}
inline void glBlendEquationSeparate(GLenum, GLenum) {}
inline void glBlendFuncSeparate(GLenum, GLenum, GLenum, GLenum) {}
inline void glBufferData(GLenum, GLsizeiptr, const void*, GLenum) {}
inline void glClear(GLbitfield) {}
inline void glClearColor(GLfloat, GLfloat, GLfloat, GLfloat) {}
inline void glClearDepth(GLdouble) {}
inline void glClearStencil(GLint) {}
inline void glColorMask(GLboolean, GLboolean, GLboolean, GLboolean) {}
inline void glCompileShader(GLuint) {}
inline GLuint glCreateProgram() { return 1; }
inline GLuint glCreateShader(GLenum) { return 1; }
inline void glCullFace(GLenum) {}
inline void glDeleteBuffers(GLsizei, const GLuint*) {}
inline void glDeleteFramebuffers(GLsizei, const GLuint*) {}
inline void glDeleteProgram(GLuint) {}
inline void glDeleteRenderbuffers(GLsizei, const GLuint*) {}
inline void glDeleteShader(GLuint) {}
inline void glDeleteTextures(GLsizei, const GLuint*) {}
inline void glDeleteVertexArrays(GLsizei, const GLuint*) {}
inline void glDepthFunc(GLenum) {}
inline void glDepthMask(GLboolean) {}
inline void glDisable(GLenum) {}
inline void glDrawArrays(GLenum, GLint, GLsizei) {}
inline void glDrawBuffer(GLenum) {}
inline void glEnable(GLenum) {}
inline void glEnableVertexAttribArray(GLuint) {}
inline void glFinish() {}
inline void glFramebufferRenderbuffer(GLenum, GLenum, GLenum, GLuint) {}
inline void glFramebufferTexture(GLenum, GLenum, GLuint, GLint) {}
inline void glFrontFace(GLenum) {}
inline void glGenBuffers(GLsizei n, GLuint *bufs) { for (GLsizei i = 0; i < n; i++) bufs[i] = 1; }
inline void glGenFramebuffers(GLsizei n, GLuint *fbs) { for (GLsizei i = 0; i < n; i++) fbs[i] = 1; }
inline void glGenRenderbuffers(GLsizei n, GLuint *rbs) { for (GLsizei i = 0; i < n; i++) rbs[i] = 1; }
inline void glGenTextures(GLsizei n, GLuint *texs) { for (GLsizei i = 0; i < n; i++) texs[i] = 1; }
inline void glGenVertexArrays(GLsizei n, GLuint *vaos) { for (GLsizei i = 0; i < n; i++) vaos[i] = 1; }
inline void glGetShaderInfoLog(GLuint, GLsizei size, GLsizei *length, GLchar *log) {
    if (length) *length = 0;
    if (size > 0) log[0] = '\0';
}
inline void glGetShaderiv(GLuint, GLenum, GLint *params) { *params = GL_TRUE; }
inline GLint glGetUniformLocation(GLuint, const GLchar*) { return -1; }
inline void glLinkProgram(GLuint) {}
inline void glReadBuffer(GLenum) {}
inline void glReadPixels(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void*) {}
inline void glRenderbufferStorage(GLenum, GLenum, GLsizei, GLsizei) {}
inline void glShaderSource(GLuint, GLsizei, const GLchar *const*, const GLint*) {}
inline void glStencilFunc(GLenum, GLint, GLuint) {}
inline void glStencilMask(GLuint) {}
inline void glStencilOp(GLenum, GLenum, GLenum) {}
inline void glTexImage1D(GLenum, GLint, GLint, GLsizei, GLint, GLenum, GLenum, const void*) {}
inline void glTexImage2D(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void*) {}
inline void glTexParameterfv(GLenum, GLenum, const GLfloat*) {}
inline void glTexParameteri(GLenum, GLenum, GLint) {}
inline void glTexSubImage1D(GLenum, GLint, GLint, GLsizei, GLenum, GLenum, const void*) {}
inline void glTexSubImage2D(GLenum, GLint, GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, const void*) {}
inline void glUniform1f(GLint, GLfloat) {}
inline void glUniform1fv(GLint, GLsizei, const GLfloat*) {}
inline void glUniform1i(GLint, GLint) {}
inline void glUniform1iv(GLint, GLsizei, const GLint*) {}
inline void glUniform2fv(GLint, GLsizei, const GLfloat*) {}
inline void glUniform2iv(GLint, GLsizei, const GLint*) {}
inline void glUniform3fv(GLint, GLsizei, const GLfloat*) {}
inline void glUniform3i(GLint, GLint, GLint, GLint) {}
inline void glUniform3iv(GLint, GLsizei, const GLint*) {}
inline void glUniform4f(GLint, GLfloat, GLfloat, GLfloat, GLfloat) {}
inline void glUniform4fv(GLint, GLsizei, const GLfloat*) {}
inline void glUseProgram(GLuint) {}
inline void glVertexAttribPointer(GLuint, GLint, GLenum, GLboolean, GLsizei, const void*) {}
inline void glViewport(GLint, GLint, GLsizei, GLsizei) {}
