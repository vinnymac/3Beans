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

package com.hydra.threebeans

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// Draws the combined 400x480 framebuffer from the core as two textured
// quads, one per 3DS screen, positioned by ScreenLayout
class EmulationRenderer(private val onLayout: (ScreenLayout.Layout) -> Unit) : GLSurfaceView.Renderer {
    private val frame: ByteBuffer =
        ByteBuffer.allocateDirect(NativeLibrary.FRAME_WIDTH * NativeLibrary.FRAME_HEIGHT * 4)
            .order(ByteOrder.nativeOrder())

    private var program = 0
    private var texture = 0
    private var aPos = 0
    private var aTex = 0
    private var topVerts: FloatBuffer = floatBuffer(16)
    private var bottomVerts: FloatBuffer = floatBuffer(16)
    private var hasFrame = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val vertexShader = compile(
            GLES20.GL_VERTEX_SHADER,
            """
            attribute vec2 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTex = aTex;
            }
            """
        )
        val fragmentShader = compile(
            GLES20.GL_FRAGMENT_SHADER,
            """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTex;
            void main() {
                gl_FragColor = texture2D(uTexture, vTex);
            }
            """
        )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, NativeLibrary.FRAME_WIDTH,
            NativeLibrary.FRAME_HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        hasFrame = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val layout = ScreenLayout.compute(width, height)

        // Top screen occupies the upper half of the texture; the bottom
        // screen sits in the lower half with a 40-texel margin on each side
        setQuad(topVerts, layout.top, width, height, 0f, 0f, 1f, 0.5f)
        setQuad(bottomVerts, layout.bottom, width, height, 40f / 400, 0.5f, 360f / 400, 1f)
        onLayout(layout)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Upload a new frame from the core if one is ready
        if (NativeLibrary.copyFrame(frame)) {
            frame.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, NativeLibrary.FRAME_WIDTH,
                NativeLibrary.FRAME_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frame
            )
            hasFrame = true
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!hasFrame) return

        GLES20.glUseProgram(program)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        drawQuad(topVerts)
        drawQuad(bottomVerts)
    }

    private fun drawQuad(verts: FloatBuffer) {
        verts.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, verts)
        GLES20.glEnableVertexAttribArray(aPos)
        verts.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, verts)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setQuad(
        verts: FloatBuffer, rect: android.graphics.Rect, width: Int, height: Int,
        u0: Float, v0: Float, u1: Float, v1: Float
    ) {
        val l = 2f * rect.left / width - 1
        val r = 2f * rect.right / width - 1
        val t = 1 - 2f * rect.top / height
        val b = 1 - 2f * rect.bottom / height
        verts.position(0)
        verts.put(floatArrayOf(
            l, t, u0, v0,
            r, t, u1, v0,
            l, b, u0, v1,
            r, b, u1, v1
        ))
    }

    private fun floatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source.trimIndent())
        GLES20.glCompileShader(shader)
        return shader
    }
}
