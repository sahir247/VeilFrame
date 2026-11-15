#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <android/asset_manager_jni.h>
#include "net.h"
#include "bitmap_scope.h"
#include "vulkan_gpu_lifecycle.h"
#include "jni_utf_scope.h"

#include <vector>
#include <algorithm>
#include <mutex>
#include <cmath>

#define TAG "VeilFrame.VeilNetJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex g_veilnet_mutex;
static ncnn::Net* g_veilnet = nullptr;
static veilframe::GpuInstanceLease g_veilnet_gpu_lease;
static bool g_is_gpu = false;

static inline int reflect_coord(int p, int len) {
    if (len <= 1) return 0;
    while (p < 0 || p >= len) {
        if (p < 0) {
            p = -p;
        } else if (p >= len) {
            p = 2 * len - 2 - p;
        }
    }
    return p;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_veilframe_app_cv_prnu_ncnn_VeilNetNanoNative_nativeInit(
    JNIEnv* env, jclass clazz,
    jobject assetManager,
    jstring paramPath,
    jstring binPath,
    jboolean useGpu
) {
    try {
        std::lock_guard<std::mutex> lock(g_veilnet_mutex);

        if (g_veilnet != nullptr) {
            delete g_veilnet;
            g_veilnet = nullptr;
            g_veilnet_gpu_lease.reset();
            g_is_gpu = false;
        }

        g_veilnet = new ncnn::Net();

        if (useGpu) {
            g_veilnet_gpu_lease = veilframe::GpuInstanceLease(true);
            if (g_veilnet_gpu_lease.is_acquired()) {
                g_veilnet->opt.use_vulkan_compute = true;
                g_is_gpu = true;
                LOGI("VeilNet-Nano using Vulkan GPU compute acceleration");
            } else {
                LOGW("Vulkan GPU compute unavailable; falling back to CPU");
                g_veilnet->opt.use_vulkan_compute = false;
                g_is_gpu = false;
            }
        } else {
            g_veilnet->opt.use_vulkan_compute = false;
            g_is_gpu = false;
        }

        g_veilnet->opt.lightmode = true;
        g_veilnet->opt.num_threads = 4;
        g_veilnet->opt.use_packing_layout = true;
        g_veilnet->opt.use_fp16_packed = true;
        g_veilnet->opt.use_fp16_storage = true;
        g_veilnet->opt.use_fp16_arithmetic = true;

        veilframe::JniUtfStringScope paramScope(env, paramPath);
        veilframe::JniUtfStringScope binScope(env, binPath);
        const char* pPath = paramScope.c_str();
        const char* bPath = binScope.c_str();

        if (!pPath || !bPath) {
            LOGE("paramPath or binPath is null");
            delete g_veilnet;
            g_veilnet = nullptr;
            return JNI_FALSE;
        }

        int ret_param = -1;
        int ret_bin = -1;

        if (assetManager != nullptr) {
            AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
            if (!mgr) {
                LOGE("Failed to get AAssetManager from Java object");
                delete g_veilnet;
                g_veilnet = nullptr;
                return JNI_FALSE;
            }
            ret_param = g_veilnet->load_param(mgr, pPath);
            ret_bin = g_veilnet->load_model(mgr, bPath);
        } else {
            ret_param = g_veilnet->load_param(pPath);
            ret_bin = g_veilnet->load_model(bPath);
        }

        if (ret_param != 0 || ret_bin != 0) {
            LOGE("Failed to load VeilNet-Nano NCNN model: param_ret=%d, bin_ret=%d", ret_param, ret_bin);
            delete g_veilnet;
            g_veilnet = nullptr;
            return JNI_FALSE;
        }

        LOGI("VeilNet-Nano loaded successfully (GPU: %s)", g_is_gpu ? "ON" : "OFF");
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Exception in VeilNet nativeInit: %s", e.what());
        if (g_veilnet) {
            delete g_veilnet;
            g_veilnet = nullptr;
        }
        return JNI_FALSE;
    } catch (...) {
        LOGE("Unknown exception in VeilNet nativeInit");
        if (g_veilnet) {
            delete g_veilnet;
            g_veilnet = nullptr;
        }
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_veilframe_app_cv_prnu_ncnn_VeilNetNanoNative_nativeIsLoaded(
    JNIEnv* env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_veilnet_mutex);
    return (g_veilnet != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_veilframe_app_cv_prnu_ncnn_VeilNetNanoNative_nativePurifyBitmap(
    JNIEnv* env, jclass clazz,
    jobject srcBitmap,
    jobject dstBitmap,
    jint coreSize,
    jint halo
) {
    try {
        std::lock_guard<std::mutex> lock(g_veilnet_mutex);
        if (!g_veilnet) {
            LOGE("VeilNet-Nano model is not initialized");
            return JNI_FALSE;
        }

        if (!srcBitmap || !dstBitmap) {
            LOGE("srcBitmap or dstBitmap is null");
            return JNI_FALSE;
        }

        AndroidBitmapInfo srcInfo;
        AndroidBitmapInfo dstInfo;
        if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("Failed to get srcBitmap info");
            return JNI_FALSE;
        }
        if (AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("Failed to get dstBitmap info");
            return JNI_FALSE;
        }

        if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            LOGE("Bitmap format must be RGBA_8888 (src: %d, dst: %d)", srcInfo.format, dstInfo.format);
            return JNI_FALSE;
        }

        if (srcInfo.width != dstInfo.width || srcInfo.height != dstInfo.height) {
            LOGE("Bitmap dimensions mismatch: src=(%d, %d), dst=(%d, %d)",
                 srcInfo.width, srcInfo.height, dstInfo.width, dstInfo.height);
            return JNI_FALSE;
        }

        const int width = static_cast<int>(srcInfo.width);
        const int height = static_cast<int>(srcInfo.height);
        if (width <= 0 || height <= 0) {
            LOGE("Invalid image dimensions: %dx%d", width, height);
            return JNI_FALSE;
        }

        const int cSize = (coreSize > 0 && coreSize <= 2048) ? coreSize : 480;
        const int hSize = (halo >= 0 && halo <= 64) ? halo : 16;

        veilframe::BitmapPixelScope srcScope(env, srcBitmap);
        veilframe::BitmapPixelScope dstScope(env, dstBitmap);

        if (!srcScope.locked || !dstScope.locked) {
            LOGE("Failed to lock bitmap pixels");
            return JNI_FALSE;
        }

        const int srcStride = static_cast<int>(srcInfo.stride);
        const int dstStride = static_cast<int>(dstInfo.stride);
        const uint8_t* srcPixels = static_cast<const uint8_t*>(srcScope.pixels);
        uint8_t* dstPixels = static_cast<uint8_t*>(dstScope.pixels);

        const int maxTileW = cSize + 2 * hSize;
        const int maxTileH = cSize + 2 * hSize;
        std::vector<uint8_t> rgbTileBuf(maxTileW * maxTileH * 3);
        const float normVals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};

        // Sliding seam-free overlap tiling
        for (int y0 = 0; y0 < height; y0 += cSize) {
            const int coreH = std::min(cSize, height - y0);
            const int tileH = coreH + 2 * hSize;

            for (int x0 = 0; x0 < width; x0 += cSize) {
                const int coreW = std::min(cSize, width - x0);
                const int tileW = coreW + 2 * hSize;

                // 1. Fill tile buffer with reflection border
                for (int ty = 0; ty < tileH; ++ty) {
                    const int sy = reflect_coord(y0 - hSize + ty, height);
                    const uint8_t* srcRow = srcPixels + sy * srcStride;
                    uint8_t* dstTileRow = rgbTileBuf.data() + (ty * tileW) * 3;

                    for (int tx = 0; tx < tileW; ++tx) {
                        const int sx = reflect_coord(x0 - hSize + tx, width);
                        const uint8_t* px = srcRow + sx * 4;
                        dstTileRow[tx * 3 + 0] = px[0]; // R
                        dstTileRow[tx * 3 + 1] = px[1]; // G
                        dstTileRow[tx * 3 + 2] = px[2]; // B
                    }
                }

                // 2. Convert to NCNN Mat & normalize
                ncnn::Mat in = ncnn::Mat::from_pixels(rgbTileBuf.data(), ncnn::Mat::PIXEL_RGB, tileW, tileH);
                in.substract_mean_normalize(nullptr, normVals);

                // 3. Forward pass
                ncnn::Extractor ex = g_veilnet->create_extractor();
                ex.set_light_mode(true);
                ex.input("in0", in);

                ncnn::Mat out;
                int ret = ex.extract("out0", out);
                if (ret != 0 || out.empty()) {
                    LOGE("VeilNet extractor error on tile (%d, %d)", x0, y0);
                    return JNI_FALSE;
                }

                // 4. Extract central core pixels directly into dstPixels
                const float* outR = out.channel(0);
                const float* outG = out.channel(1);
                const float* outB = out.channel(2);

                for (int cy = 0; cy < coreH; ++cy) {
                    const int ty = cy + hSize;
                    const int dy = y0 + cy;
                    const uint8_t* srcRow = srcPixels + dy * srcStride;
                    uint8_t* dstRow = dstPixels + dy * dstStride;
                    const int tileRowOffset = ty * tileW;

                    for (int cx = 0; cx < coreW; ++cx) {
                        const int tx = cx + hSize;
                        const int dx = x0 + cx;
                        const int tidx = tileRowOffset + tx;

                        int r = static_cast<int>(outR[tidx] * 255.0f + 0.5f);
                        int g = static_cast<int>(outG[tidx] * 255.0f + 0.5f);
                        int b = static_cast<int>(outB[tidx] * 255.0f + 0.5f);

                        dstRow[dx * 4 + 0] = static_cast<uint8_t>(std::clamp(r, 0, 255));
                        dstRow[dx * 4 + 1] = static_cast<uint8_t>(std::clamp(g, 0, 255));
                        dstRow[dx * 4 + 2] = static_cast<uint8_t>(std::clamp(b, 0, 255));
                        dstRow[dx * 4 + 3] = srcRow[dx * 4 + 3]; // Preserve original alpha
                    }
                }
            }
        }

        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Exception in nativePurifyBitmap: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Unknown exception in nativePurifyBitmap");
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_veilframe_app_cv_prnu_ncnn_VeilNetNanoNative_nativeRelease(
    JNIEnv* env, jclass clazz
) {
    try {
        std::lock_guard<std::mutex> lock(g_veilnet_mutex);
        if (g_veilnet) {
            delete g_veilnet;
            g_veilnet = nullptr;
        }
        g_veilnet_gpu_lease.reset();
        g_is_gpu = false;
        LOGI("VeilNet-Nano released");
    } catch (...) {
        LOGE("Exception in nativeRelease");
    }
}

} // extern "C"
