#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include "rife/rife.h"
#include "gpu.h"
#include "cpu.h"
#include <mutex>
#include <string>
#include <vector>
#include <algorithm>

#define TAG "VeilFrame.RifeNcnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex g_rife_mutex;
static RIFE* g_rife = nullptr;
static int g_rife_gpuid = -1;
static std::string g_rife_model_dir;

// Cached global references for zero reflection overhead per frame
static jclass g_bitmapClass = nullptr;
static jmethodID g_createBitmapMethod = nullptr;
static jobject g_argbConfig = nullptr;

// Reusable persistent buffers across frames (mutex-protected)
static std::vector<unsigned char> g_rgb0;
static std::vector<unsigned char> g_rgb1;
static std::vector<unsigned char> g_rgb_out;

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
    g_rife_model_dir = dir_str;

    std::string param_path = dir_str + "/flownet.param";
    FILE* fp = fopen(param_path.c_str(), "rb");
    if (!fp) {
        LOGE("RIFE model files not found in %s", dir_str.c_str());
        return JNI_FALSE;
    }
    fclose(fp);

    int gpu_count = (useGpu) ? ncnn::get_gpu_count() : 0;
    if (useGpu && gpu_count > 0) {
        ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(0);
        g_rife_gpuid = (vkdev != nullptr) ? 0 : -1;
    } else {
        g_rife_gpuid = -1;
    }

    // Detect CPU big cores for maximum throughput
    int cpu_threads = std::max(2, ncnn::get_big_cpu_count());
    if (cpu_threads > 4) cpu_threads = 4; // Balance thermal headroom on mobile

    // Construct RIFE v4 instance: gpuid, tta, tta_temporal, uhd, num_threads, rife_v2, rife_v4
    g_rife = new RIFE(g_rife_gpuid, false, false, false, cpu_threads, false, true);

    int ret = g_rife->load(dir_str);
    if (ret != 0 && g_rife_gpuid >= 0) {
        LOGI("RIFE GPU load failed (ret=%d), falling back to CPU OpenMP (%d threads)", ret, cpu_threads);
        delete g_rife;
        g_rife_gpuid = -1;
        g_rife = new RIFE(-1, false, false, false, cpu_threads, false, true);
        ret = g_rife->load(dir_str);
    }
    if (ret != 0) {
        LOGE("Failed to load RIFE v4 model from %s (ret=%d)", dir_str.c_str(), ret);
        delete g_rife;
        g_rife = nullptr;
        return JNI_FALSE;
    }

    // Cache JNI reflection targets globally
    if (!g_bitmapClass) {
        jclass bCls = env->FindClass("android/graphics/Bitmap");
        if (bCls) {
            g_bitmapClass = (jclass)env->NewGlobalRef(bCls);
            g_createBitmapMethod = env->GetStaticMethodID(
                g_bitmapClass, "createBitmap",
                "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
            );
        }
    }
    if (!g_argbConfig) {
        jclass cCls = env->FindClass("android/graphics/Bitmap$Config");
        if (cCls) {
            jfieldID argbField = env->GetStaticFieldID(cCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
            if (argbField) {
                jobject cfg = env->GetStaticObjectField(cCls, argbField);
                if (cfg) {
                    g_argbConfig = env->NewGlobalRef(cfg);
                }
            }
        }
    }

    LOGI("RIFE v4 engine initialized successfully (GPU: %d, threads: %d)", g_rife_gpuid, cpu_threads);
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

    if (info0.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info1.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("RIFE requires ARGB_8888 format (got %d, %d)", info0.format, info1.format);
        return nullptr;
    }

    if (info0.width != info1.width || info0.height != info1.height) {
        LOGE("Frame dimension mismatch in RIFE");
        return nullptr;
    }

    const int w = info0.width;
    const int h = info0.height;
    const int pixel_count = w * h;
    const size_t needed_bytes = static_cast<size_t>(pixel_count) * 3;

    void* pixels0 = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap0, &pixels0) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock bitmap0 pixels");
        return nullptr;
    }

    void* pixels1 = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap1, &pixels1) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock bitmap1 pixels");
        AndroidBitmap_unlockPixels(env, bitmap0);
        return nullptr;
    }

    // Reuse persistent preallocated buffers
    if (g_rgb0.size() < needed_bytes) g_rgb0.resize(needed_bytes);
    if (g_rgb1.size() < needed_bytes) g_rgb1.resize(needed_bytes);
    if (g_rgb_out.size() < needed_bytes) g_rgb_out.resize(needed_bytes);

    const unsigned char* p0 = reinterpret_cast<const unsigned char*>(pixels0);
    const unsigned char* p1 = reinterpret_cast<const unsigned char*>(pixels1);
    unsigned char* d0 = g_rgb0.data();
    unsigned char* d1 = g_rgb1.data();

    const int stride0 = static_cast<int>(info0.stride);
    const int stride1 = static_cast<int>(info1.stride);

    for (int y = 0; y < h; y++) {
        const unsigned char* row0 = p0 + y * stride0;
        const unsigned char* row1 = p1 + y * stride1;
        unsigned char* dst0 = d0 + y * w * 3;
        unsigned char* dst1 = d1 + y * w * 3;
        for (int x = 0; x < w; x++) {
            dst0[x * 3 + 0] = row0[x * 4 + 0];
            dst0[x * 3 + 1] = row0[x * 4 + 1];
            dst0[x * 3 + 2] = row0[x * 4 + 2];
            dst1[x * 3 + 0] = row1[x * 4 + 0];
            dst1[x * 3 + 1] = row1[x * 4 + 1];
            dst1[x * 3 + 2] = row1[x * 4 + 2];
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap0);
    AndroidBitmap_unlockPixels(env, bitmap1);

    ncnn::Mat in0(w, h, reinterpret_cast<void*>(g_rgb0.data()), (size_t)3u, 1);
    ncnn::Mat in1(w, h, reinterpret_cast<void*>(g_rgb1.data()), (size_t)3u, 1);

    ncnn::Mat outimage(w, h, g_rgb_out.data(), (size_t)3u, 1);
    int ret = g_rife->process(in0, in1, timestep, outimage);
    if (ret != 0 && g_rife_gpuid >= 0 && !g_rife_model_dir.empty()) {
        LOGI("RIFE GPU process failed (%d), dynamically recovering via CPU OpenMP", ret);
        delete g_rife;
        g_rife_gpuid = -1;
        int cpu_threads = std::max(2, ncnn::get_big_cpu_count());
        if (cpu_threads > 4) cpu_threads = 4;
        g_rife = new RIFE(-1, false, false, false, cpu_threads, false, true);
        if (g_rife->load(g_rife_model_dir) == 0) {
            ret = g_rife->process(in0, in1, timestep, outimage);
        }
    }

    if (ret != 0) {
        LOGE("RIFE process failed with code %d", ret);
        return nullptr;
    }

    if (!g_bitmapClass || !g_createBitmapMethod || !g_argbConfig) {
        LOGE("Bitmap creation JNI references not initialized");
        return nullptr;
    }

    jobject outBitmap = env->CallStaticObjectMethod(
        g_bitmapClass, g_createBitmapMethod,
        w, h, g_argbConfig
    );
    if (!outBitmap) return nullptr;

    AndroidBitmapInfo out_info;
    if (AndroidBitmap_getInfo(env, outBitmap, &out_info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to get outBitmap info");
        return nullptr;
    }

    void* out_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &out_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock outBitmap pixels");
        return nullptr;
    }

    const unsigned char* rgb_out = g_rgb_out.data();
    unsigned char* p_out = reinterpret_cast<unsigned char*>(out_pixels);
    const int out_stride = static_cast<int>(out_info.stride);

    for (int y = 0; y < h; y++) {
        const unsigned char* src_rgb = rgb_out + y * w * 3;
        unsigned char* dst_row = p_out + y * out_stride;
        for (int x = 0; x < w; x++) {
            dst_row[x * 4 + 0] = src_rgb[x * 3 + 0];
            dst_row[x * 4 + 1] = src_rgb[x * 3 + 1];
            dst_row[x * 4 + 2] = src_rgb[x * 3 + 2];
            dst_row[x * 4 + 3] = 255;
        }
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
    if (g_bitmapClass) {
        env->DeleteGlobalRef(g_bitmapClass);
        g_bitmapClass = nullptr;
        g_createBitmapMethod = nullptr;
    }
    if (g_argbConfig) {
        env->DeleteGlobalRef(g_argbConfig);
        g_argbConfig = nullptr;
    }
    g_rgb0.clear();
    g_rgb0.shrink_to_fit();
    g_rgb1.clear();
    g_rgb1.shrink_to_fit();
    g_rgb_out.clear();
    g_rgb_out.shrink_to_fit();
    LOGI("RIFE engine released and buffers reclaimed");
}

} // extern "C"
