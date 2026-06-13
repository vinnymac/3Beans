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

#include <cstring>
#include <jni.h>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "../../../../../core/core.h"
#include "egl_context.h"

// The Android frontend mirrors the desktop one: it owns the core object, a
// run thread, and a mutex guarding the core pointer across the UI, GL, and
// audio threads.
namespace {
    Core *core = nullptr;
    std::atomic<bool> running{false};
    std::thread *coreThread = nullptr;
    std::mutex coreMutex;
    std::string cartPath;
    EglContext eglContext;

    void runCore() {
        while (running.load())
            core->runFrame();
    }

    void stopThread() {
        if (!running.load()) return;
        running.store(false);
        coreThread->join();
        delete coreThread;
        coreThread = nullptr;
    }

    void startThread() {
        if (running.load() || !core) return;
        running.store(true);
        coreThread = new std::thread(runCore);
    }

    // Look up a numeric setting by name; nullptr if unknown
    int *intSetting(const char *name) {
        if (!strcmp(name, "fpsLimiter")) return &Settings::fpsLimiter;
        if (!strcmp(name, "cartAutoBoot")) return &Settings::cartAutoBoot;
        if (!strcmp(name, "dspBackend")) return &Settings::dspBackend;
        if (!strcmp(name, "threadedGpu")) return &Settings::threadedGpu;
        if (!strcmp(name, "gpuRenderer")) return &Settings::gpuRenderer;
        if (!strcmp(name, "gpuVtxShader")) return &Settings::gpuVtxShader;
        if (!strcmp(name, "gpuFragShader")) return &Settings::gpuFragShader;
        if (!strcmp(name, "unitType")) return &Settings::unitType;
        return nullptr;
    }

    // Look up a path setting by name; nullptr if unknown
    std::string *stringSetting(const char *name) {
        if (!strcmp(name, "boot11Path")) return &Settings::boot11Path;
        if (!strcmp(name, "boot9Path")) return &Settings::boot9Path;
        if (!strcmp(name, "nandPath")) return &Settings::nandPath;
        if (!strcmp(name, "sdPath")) return &Settings::sdPath;
        return nullptr;
    }

    // Fall back to the software renderer and software shaders. Used when the
    // user hasn't enabled the hardware renderer, or when bringing up the GLES
    // context fails, so the core is never given a renderer it can't drive.
    void forceSoftRenderer() {
        Settings::gpuRenderer = 0;
        Settings::gpuVtxShader = 0;
        Settings::gpuFragShader = 0;
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_hydra_threebeans_NativeLibrary_loadSettings(JNIEnv *env, jobject, jstring basePath) {
    const char *path = env->GetStringUTFChars(basePath, nullptr);
    bool loaded = Settings::load(path);
    env->ReleaseStringUTFChars(basePath, path);
    return loaded;
}

JNIEXPORT jboolean JNICALL Java_com_hydra_threebeans_NativeLibrary_saveSettings(JNIEnv*, jobject) {
    return Settings::save();
}

JNIEXPORT jint JNICALL Java_com_hydra_threebeans_NativeLibrary_getSettingInt(JNIEnv *env, jobject, jstring name) {
    const char *str = env->GetStringUTFChars(name, nullptr);
    int *setting = intSetting(str);
    env->ReleaseStringUTFChars(name, str);
    return setting ? *setting : 0;
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_setSettingInt(JNIEnv *env, jobject, jstring name, jint value) {
    const char *str = env->GetStringUTFChars(name, nullptr);
    if (int *setting = intSetting(str))
        *setting = value;
    env->ReleaseStringUTFChars(name, str);
}

JNIEXPORT jstring JNICALL Java_com_hydra_threebeans_NativeLibrary_getSettingString(JNIEnv *env, jobject, jstring name) {
    const char *str = env->GetStringUTFChars(name, nullptr);
    std::string *setting = stringSetting(str);
    env->ReleaseStringUTFChars(name, str);
    return env->NewStringUTF(setting ? setting->c_str() : "");
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_setSettingString(JNIEnv *env, jobject, jstring name, jstring value) {
    const char *str = env->GetStringUTFChars(name, nullptr);
    std::string *setting = stringSetting(str);
    env->ReleaseStringUTFChars(name, str);
    if (!setting) return;
    const char *val = env->GetStringUTFChars(value, nullptr);
    *setting = val;
    env->ReleaseStringUTFChars(value, val);
}

JNIEXPORT jint JNICALL Java_com_hydra_threebeans_NativeLibrary_startCore(JNIEnv *env, jobject, jstring path) {
    // Stop and destroy any previous core
    stopThread();
    std::lock_guard<std::mutex> guard(coreMutex);
    delete core;
    core = nullptr;
    eglContext.destroy();

    // Create a new core, reporting boot ROM errors
    const char *str = env->GetStringUTFChars(path, nullptr);
    cartPath = str;
    env->ReleaseStringUTFChars(path, str);

    // Honor the chosen GPU renderer, but only commit to the hardware path if an
    // offscreen GLES context actually comes up; otherwise fall back to software
    // so the core is never handed a context function it can't call.
    std::function<void()> *contextFunc = nullptr;
    if (Settings::gpuRenderer == 1 && eglContext.init())
        contextFunc = &eglContext.func;
    else
        forceSoftRenderer();

    try {
        core = new Core(cartPath, contextFunc);
    }
    catch (CoreError e) {
        eglContext.destroy();
        return 1 + e;
    }
    return 0;
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_resumeCore(JNIEnv*, jobject) {
    startThread();
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_pauseCore(JNIEnv*, jobject) {
    stopThread();
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_stopCore(JNIEnv*, jobject) {
    stopThread();
    std::lock_guard<std::mutex> guard(coreMutex);
    delete core;
    core = nullptr;
    eglContext.destroy();
}

JNIEXPORT jboolean JNICALL Java_com_hydra_threebeans_NativeLibrary_isRunning(JNIEnv*, jobject) {
    return running.load();
}

JNIEXPORT jint JNICALL Java_com_hydra_threebeans_NativeLibrary_getFps(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(coreMutex);
    return core ? core->fps : 0;
}

JNIEXPORT jboolean JNICALL Java_com_hydra_threebeans_NativeLibrary_copyFrame(JNIEnv *env, jobject, jobject out) {
    // Get a new frame from the core if one is ready
    coreMutex.lock();
    uint32_t *frame = core ? core->pdc.getFrame() : nullptr;
    coreMutex.unlock();
    if (!frame) return false;

    // Copy the frame to the output buffer and free it
    if (void *dst = env->GetDirectBufferAddress(out))
        memcpy(dst, frame, 400 * 480 * sizeof(uint32_t));
    delete[] frame;
    return true;
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_readAudio(JNIEnv *env, jobject, jshortArray out, jint count) {
    // Get samples from the core if available
    coreMutex.lock();
    uint32_t *samples = core ? core->csnd.getSamples(48000, count) : nullptr;

    // Copy samples to the output buffer or fill it with silence
    if (samples) {
        env->SetShortArrayRegion(out, 0, count * 2, (jshort*)samples);
        coreMutex.unlock();
    }
    else {
        coreMutex.unlock();
        std::vector<jshort> zeros(count * 2, 0);
        env->SetShortArrayRegion(out, 0, count * 2, zeros.data());
    }
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_pressKey(JNIEnv*, jobject, jint key) {
    std::lock_guard<std::mutex> guard(coreMutex);
    if (core) core->input.pressKey(key);
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_releaseKey(JNIEnv*, jobject, jint key) {
    std::lock_guard<std::mutex> guard(coreMutex);
    if (core) core->input.releaseKey(key);
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_pressHome(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(coreMutex);
    if (core) core->input.pressHome();
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_releaseHome(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(coreMutex);
    if (core) core->input.releaseHome();
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_setLStick(JNIEnv*, jobject, jint x, jint y) {
    std::lock_guard<std::mutex> guard(coreMutex);
    if (core) core->input.setLStick(x, y);
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_pressScreen(JNIEnv*, jobject, jint x, jint y) {
    std::lock_guard<std::mutex> guard(coreMutex);
    if (core) core->input.pressScreen(x, y);
}

JNIEXPORT void JNICALL Java_com_hydra_threebeans_NativeLibrary_releaseScreen(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(coreMutex);
    if (core) core->input.releaseScreen();
}

} // extern "C"
