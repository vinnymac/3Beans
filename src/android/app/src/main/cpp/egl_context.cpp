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

#include "egl_context.h"
#include <android/log.h>

#ifndef EGL_OPENGL_ES3_BIT
#define EGL_OPENGL_ES3_BIT 0x00000040 // EGL_OPENGL_ES3_BIT_KHR, for pre-1.5 headers
#endif

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "3beans-egl", __VA_ARGS__)

bool EglContext::init() {
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }
    if (!eglInitialize(display, nullptr, nullptr)) {
        LOGE("eglInitialize failed: 0x%X", eglGetError());
        display = EGL_NO_DISPLAY;
        return false;
    }

    // A pbuffer-capable ES3 config; the renderer draws into its own FBO, so the
    // surface itself is never presented and an RGBA8 1x1 pbuffer is plenty.
    const EGLint cfgAttrs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs = 0;
    if (!eglChooseConfig(display, cfgAttrs, &config, 1, &numConfigs) || numConfigs < 1) {
        LOGE("eglChooseConfig found no ES3 pbuffer config");
        eglTerminate(display);
        display = EGL_NO_DISPLAY;
        return false;
    }

    const EGLint ctxAttrs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE };
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttrs);
    if (context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%X", eglGetError());
        eglTerminate(display);
        display = EGL_NO_DISPLAY;
        return false;
    }

    const EGLint surfAttrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    surface = eglCreatePbufferSurface(display, config, surfAttrs);
    if (surface == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface failed: 0x%X", eglGetError());
        eglDestroyContext(display, context);
        eglTerminate(display);
        display = EGL_NO_DISPLAY;
        context = EGL_NO_CONTEXT;
        return false;
    }

    func = [this]() { toggle(); };
    return true;
}

void EglContext::toggle() {
    // Alternate between binding and releasing the context on the calling thread,
    // matching the balanced make/release calls the core makes around GL work.
    if ((current = !current)) {
        if (!eglMakeCurrent(display, surface, surface, context))
            LOGE("eglMakeCurrent bind failed: 0x%X", eglGetError());
    }
    else {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
}

void EglContext::destroy() {
    if (display == EGL_NO_DISPLAY) return;
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
    if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
    eglTerminate(display);
    display = EGL_NO_DISPLAY;
    context = EGL_NO_CONTEXT;
    surface = EGL_NO_SURFACE;
    current = false;
    func = nullptr;
}
