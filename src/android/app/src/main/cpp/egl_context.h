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

#pragma once

#include <EGL/egl.h>
#include <functional>

// Offscreen GLES 3 context for the hardware GPU renderer on Android.
//
// The emulated PICA renderer (GpuRenderOgl, forked to GLES) renders into a
// private framebuffer and copies results back into guest memory; the finished
// 3DS screens reach the display through the PDC scanout path and a separate
// GLSurfaceView. So this context is fully standalone and never shares with or
// presents to the display surface — a 1x1 pbuffer is enough to make it current.
class EglContext {
public:
    // Bring up display/config/context/surface. Returns false (and leaves the
    // object inert) if GLES 3 is unavailable, so the caller can fall back to the
    // software renderer instead of handing the core an unusable context.
    bool init();
    void destroy();

    // Paired make-current / release toggle, mirroring the desktop contextFunc
    // (b3CanvasOgl::coreContext). The core calls this once before and once after
    // each block of GL work, on whichever thread is doing the rendering. Valid
    // only after a successful init().
    std::function<void()> func;

private:
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    bool current = false;

    void toggle();
};
