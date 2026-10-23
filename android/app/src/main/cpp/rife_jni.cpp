#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include "rife/rife.h"
#include "gpu.h"
#include <mutex>
#include <string>

#define TAG "VeilFrame.RifeNcnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex g_rife_mutex;
static RIFE* g_rife = nullptr;
static int g_rife_gpuid = -1;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_veilframe_app_media_rife_RifeNcnnNative_nativeInit(
    JNIEnv* env, jclass clazz,
    jstring modelDir,
    jboolean useGpu
) {
    std::lock_guard<std::mutex> lock(g_rife_mutex);

    if (g_rife != nullptr) {
        delete g_rife;
        g_rife = nullptr;
    }

    const char* dir_c = env->GetStringUTFChars(modelDir, nullptr);
    std::string dir_str(dir_c);
    env->ReleaseStringUTFChars(modelDir, dir_c);

    std::string param_path = dir_str + "/flownet.param";
    FILE* fp = fopen(param_path.c_str(), "rb");
    if (!fp) {
        LOGE("RIFE model files not found in %s", dir_str.c_str());
        return JNI_FALSE;
    }
    fclose(fp);

    int gpu_count = (useGpu) ? ncnn::get_gpu_count() : 0;
    g_rife_gpuid = (useGpu && gpu_count > 0) ? 0 : -1;

    // Construct RIFE v4 instance: gpuid, tta, tta_temporal, uhd, num_threads, rife_v2, rife_v4
    g_rife = new RIFE(g_rife_gpuid, false, false, false, 4, false, true);

    int ret = g_rife->load(dir_str);
    if (ret != 0 && g_rife_gpuid >= 0) {
        LOGI("RIFE GPU load failed (ret=%d), falling back to CPU OpenMP", ret);
        delete g_rife;
        g_rife_gpuid = -1;
        g_rife = new RIFE(-1, false, false, false, 4, false, true);
        ret = g_rife->load(dir_str);
    }
    if (ret != 0) {
        LOGE("Failed to load RIFE v4 model from %s (ret=%d)", dir_str.c_str(), ret);
        delete g_rife;
        g_rife = nullptr;
        return JNI_FALSE;
    }

    LOGI("RIFE v4 engine initialized successfully (GPU: %d)", g_rife_gpuid);
    return JNI_TRUE;
}

JNIEXPORT jobject JNICALL
Java_com_veilframe_app_media_rife_RifeNcnnNative_nativeInterpolate(
    JNIEnv* env, jclass clazz,
    jobject bitmap0,
    jobject bitmap1,
    jfloat timestep
) {
    std::lock_guard<std::mutex> lock(g_rife_mutex);
    if (!g_rife) {
        LOGE("RIFE engine not initialized");
        return nullptr;
    }

    AndroidBitmapInfo info0, info1;
    if (AndroidBitmap_getInfo(env, bitmap0, &info0) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, bitmap1, &info1) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return nullptr;
    }

    if (info0.width != info1.width || info0.height != info1.height) {
        LOGE("Frame dimension mismatch in RIFE");
        return nullptr;
    }

    const int w = info0.width;
    const int h = info0.height;

    void* pixels0 = nullptr;
    void* pixels1 = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap0, &pixels0) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bitmap1, &pixels1) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return nullptr;
    }

    std::vector<unsigned char> rgb0(w * h * 3);
    std::vector<unsigned char> rgb1(w * h * 3);
    const unsigned char* p0 = reinterpret_cast<const unsigned char*>(pixels0);
    const unsigned char* p1 = reinterpret_cast<const unsigned char*>(pixels1);
    const int pixel_count = w * h;
    for (int i = 0; i < pixel_count; i++) {
        rgb0[i * 3 + 0] = p0[i * 4 + 0];
        rgb0[i * 3 + 1] = p0[i * 4 + 1];
        rgb0[i * 3 + 2] = p0[i * 4 + 2];
        rgb1[i * 3 + 0] = p1[i * 4 + 0];
        rgb1[i * 3 + 1] = p1[i * 4 + 1];
        rgb1[i * 3 + 2] = p1[i * 4 + 2];
    }

    AndroidBitmap_unlockPixels(env, bitmap0);
    AndroidBitmap_unlockPixels(env, bitmap1);

    ncnn::Mat in0(w, h, reinterpret_cast<void*>(rgb0.data()), (size_t)3u, 1);
    ncnn::Mat in1(w, h, reinterpret_cast<void*>(rgb1.data()), (size_t)3u, 1);

    std::vector<unsigned char> rgb_buffer(w * h * 3);
    ncnn::Mat outimage(w, h, rgb_buffer.data(), (size_t)3u, 1);
    int ret = g_rife->process(in0, in1, timestep, outimage);

    if (ret != 0) {
        LOGE("RIFE process failed with code %d", ret);
        return nullptr;
    }

    // Allocate result Bitmap
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (!bitmapClass) return nullptr;

    jmethodID createBitmapMethod = env->GetStaticMethodID(
        bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );
    if (!createBitmapMethod) return nullptr;

    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!configClass) return nullptr;

    jfieldID argbField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    if (!argbField) return nullptr;

    jobject argbConfig = env->GetStaticObjectField(configClass, argbField);
    jobject outBitmap = env->CallStaticObjectMethod(
        bitmapClass, createBitmapMethod,
        w, h, argbConfig
    );
    if (!outBitmap) return nullptr;

    void* out_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &out_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return nullptr;
    }

    const unsigned char* rgb_out = rgb_buffer.data();
    unsigned char* p_out = reinterpret_cast<unsigned char*>(out_pixels);
    for (int i = 0; i < pixel_count; i++) {
        p_out[i * 4 + 0] = rgb_out[i * 3 + 0];
        p_out[i * 4 + 1] = rgb_out[i * 3 + 1];
        p_out[i * 4 + 2] = rgb_out[i * 3 + 2];
        p_out[i * 4 + 3] = 255;
    }
    AndroidBitmap_unlockPixels(env, outBitmap);

    return outBitmap;
}

JNIEXPORT void JNICALL
Java_com_veilframe_app_media_rife_RifeNcnnNative_nativeRelease(
    JNIEnv* env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_rife_mutex);
    if (g_rife) {
        delete g_rife;
        g_rife = nullptr;
    }
    LOGI("RIFE engine released");
}

} // extern "C"
