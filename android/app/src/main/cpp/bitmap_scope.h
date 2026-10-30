#pragma once

#include <jni.h>
#include <android/bitmap.h>
#include <cstdint>

namespace veilframe {

constexpr uint64_t MAX_BITMAP_PIXELS = 100000000ULL; // 100 Megapixels

struct BitmapPixelScope {
    JNIEnv* env = nullptr;
    jobject bitmap = nullptr;
    void* pixels = nullptr;
    bool locked = false;

    BitmapPixelScope(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (env && bitmap) {
            locked = (AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS);
        }
    }

    ~BitmapPixelScope() {
        unlock();
    }

    void unlock() {
        if (locked && env && bitmap) {
            AndroidBitmap_unlockPixels(env, bitmap);
            locked = false;
            pixels = nullptr;
        }
    }

    // Non-copyable
    BitmapPixelScope(const BitmapPixelScope&) = delete;
    BitmapPixelScope& operator=(const BitmapPixelScope&) = delete;
};

} // namespace veilframe
