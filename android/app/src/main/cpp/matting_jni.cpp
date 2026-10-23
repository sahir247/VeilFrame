#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <android/asset_manager_jni.h>
#include "net.h"
#include <algorithm>
#include <mutex>

#define TAG "VeilFrame.MattingNcnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex g_matting_mutex;
static ncnn::Net* g_u2netp_net = nullptr;
static ncnn::Net* g_modnet_net = nullptr;

static ncnn::UnlockedPoolAllocator g_matting_blob_allocator;
static ncnn::PoolAllocator g_matting_workspace_allocator;

enum MattingModelType {
    MODEL_U2NETP = 0,
    MODEL_MODNET = 1
};

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_veilframe_app_cv_segmentation_ncnn_NcnnMattingNative_nativeInit(
    JNIEnv* env, jclass clazz,
    jobject assetManager,
    jstring paramPath,
    jstring binPath,
    jint modelType,
    jboolean useGpu,
    jboolean isAsset
) {
    std::lock_guard<std::mutex> lock(g_matting_mutex);

    ncnn::Net** targetNet = (modelType == MODEL_U2NETP) ? &g_u2netp_net : &g_modnet_net;
    if (*targetNet != nullptr) {
        delete *targetNet;
        *targetNet = nullptr;
    }

    ncnn::Net* net = new ncnn::Net();
    net->opt.lightmode = true;
    net->opt.num_threads = 4;
    net->opt.blob_allocator = &g_matting_blob_allocator;
    net->opt.workspace_allocator = &g_matting_workspace_allocator;

    if (useGpu && ncnn::get_gpu_count() > 0) {
        net->opt.use_vulkan_compute = true;
    } else {
        net->opt.use_vulkan_compute = false;
    }

    const char* param_c = env->GetStringUTFChars(paramPath, nullptr);
    const char* bin_c = env->GetStringUTFChars(binPath, nullptr);
    int ret_param = -1;
    int ret_bin = -1;

    if (isAsset) {
        AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
        if (mgr == nullptr) {
            LOGE("AAssetManager_fromJava returned null");
            env->ReleaseStringUTFChars(paramPath, param_c);
            env->ReleaseStringUTFChars(binPath, bin_c);
            delete net;
            return JNI_FALSE;
        }
        ret_param = net->load_param(mgr, param_c);
        ret_bin = net->load_model(mgr, bin_c);
    } else {
        ret_param = net->load_param(param_c);
        ret_bin = net->load_model(bin_c);
    }

    env->ReleaseStringUTFChars(paramPath, param_c);
    env->ReleaseStringUTFChars(binPath, bin_c);

    if (ret_param != 0 || ret_bin != 0) {
        LOGE("Failed to load matting model: param=%d, bin=%d (type=%d, isAsset=%d)",
             ret_param, ret_bin, modelType, isAsset ? 1 : 0);
        delete net;
        return JNI_FALSE;
    }

    *targetNet = net;
    LOGI("Matting model %d loaded successfully (GPU: %d)", modelType, net->opt.use_vulkan_compute ? 1 : 0);
    return JNI_TRUE;
}

#include <cmath>
#include <vector>

// Fast Separable Box Filter (O(1) per pixel moving sum)
static void box_filter_separable(const float* src, float* dst, int w, int h, int r) {
    std::vector<float> tmp(w * h);
    #pragma omp parallel for
    for (int y = 0; y < h; y++) {
        const float* row_src = src + y * w;
        float* row_tmp = tmp.data() + y * w;
        float sum = 0.0f;
        for (int x = -r; x <= r; x++) {
            int cx = std::max(0, std::min(w - 1, x));
            sum += row_src[cx];
        }
        row_tmp[0] = sum;
        for (int x = 1; x < w; x++) {
            int prev_x = std::max(0, x - 1 - r);
            int next_x = std::min(w - 1, x + r);
            sum += row_src[next_x] - row_src[prev_x];
            row_tmp[x] = sum;
        }
    }

    const float scale = 1.0f / ((2 * r + 1) * (2 * r + 1));
    #pragma omp parallel for
    for (int x = 0; x < w; x++) {
        float sum = 0.0f;
        for (int y = -r; y <= r; y++) {
            int cy = std::max(0, std::min(h - 1, y));
            sum += tmp[cy * w + x];
        }
        dst[0 * w + x] = sum * scale;
        for (int y = 1; y < h; y++) {
            int prev_y = std::max(0, y - 1 - r);
            int next_y = std::min(h - 1, y + r);
            sum += tmp[next_y * w + x] - tmp[prev_y * w + x];
            dst[y * w + x] = sum * scale;
        }
    }
}

// Guided Filter edge refinement: aligns alpha mask boundaries to photographic color edges
static void guided_filter(
    const float* I,
    const float* p,
    float* q,
    int w, int h,
    int r, float eps
) {
    const int n = w * h;
    std::vector<float> mean_I(n), mean_p(n), corr_I(n), corr_Ip(n);
    std::vector<float> a(n), b(n), mean_a(n), mean_b(n);

    for (int i = 0; i < n; i++) {
        corr_I[i] = I[i] * I[i];
        corr_Ip[i] = I[i] * p[i];
    }

    box_filter_separable(I, mean_I.data(), w, h, r);
    box_filter_separable(p, mean_p.data(), w, h, r);
    box_filter_separable(corr_I.data(), corr_I.data(), w, h, r);
    box_filter_separable(corr_Ip.data(), corr_Ip.data(), w, h, r);

    for (int i = 0; i < n; i++) {
        float var = corr_I[i] - mean_I[i] * mean_I[i];
        float cov = corr_Ip[i] - mean_I[i] * mean_p[i];
        a[i] = cov / (var + eps);
        b[i] = mean_p[i] - a[i] * mean_I[i];
    }

    box_filter_separable(a.data(), mean_a.data(), w, h, r);
    box_filter_separable(b.data(), mean_b.data(), w, h, r);

    for (int i = 0; i < n; i++) {
        float val = mean_a[i] * I[i] + mean_b[i];
        if (val < 0.0f) val = 0.0f;
        if (val > 1.0f) val = 1.0f;
        // Smoothstep contrast enhancement for clean separation
        val = val * val * (3.0f - 2.0f * val);
        q[i] = val;
    }
}

JNIEXPORT jobject JNICALL
Java_com_veilframe_app_cv_segmentation_ncnn_NcnnMattingNative_nativeProcess(
    JNIEnv* env, jclass clazz,
    jobject srcBitmap,
    jint modelType,
    jboolean useGpu
) {
    std::lock_guard<std::mutex> lock(g_matting_mutex);

    ncnn::Net* net = (modelType == MODEL_U2NETP) ? g_u2netp_net : g_modnet_net;
    if (net == nullptr) {
        LOGE("Model %d is not initialized", modelType);
        return nullptr;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, srcBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return nullptr;
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Source bitmap format must be RGBA_8888");
        return nullptr;
    }

    const int src_w = info.width;
    const int src_h = info.height;

    void* src_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &src_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_lockPixels failed");
        return nullptr;
    }

    // Extract guidance luminance [0, 1] before unlocking source
    std::vector<float> guidance(src_w * src_h);
    const unsigned char* src_u8 = reinterpret_cast<const unsigned char*>(src_pixels);
    for (int i = 0; i < src_w * src_h; i++) {
        guidance[i] = (src_u8[i * 4 + 0] * 0.299f + src_u8[i * 4 + 1] * 0.587f + src_u8[i * 4 + 2] * 0.114f) * (1.0f / 255.0f);
    }

    const int target_w = (modelType == MODEL_U2NETP) ? 320 : 512;
    const int target_h = (modelType == MODEL_U2NETP) ? 320 : 512;

    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
        reinterpret_cast<const unsigned char*>(src_pixels),
        ncnn::Mat::PIXEL_RGBA2RGB,
        src_w, src_h,
        target_w, target_h
    );

    AndroidBitmap_unlockPixels(env, srcBitmap);

    if (modelType == MODEL_U2NETP) {
        // ImageNet mean/std
        const float mean_vals[3] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
        const float norm_vals[3] = {1.0f / (0.229f * 255.f), 1.0f / (0.224f * 255.f), 1.0f / (0.225f * 255.f)};
        in.substract_mean_normalize(mean_vals, norm_vals);
    } else {
        // MODNet [-1.0, 1.0]
        const float mean_vals[3] = {127.5f, 127.5f, 127.5f};
        const float norm_vals[3] = {0.0078431f, 0.0078431f, 0.0078431f};
        in.substract_mean_normalize(mean_vals, norm_vals);
    }

    ncnn::Extractor ex = net->create_extractor();
    ex.set_light_mode(true);

    const char* in_blob = (modelType == MODEL_U2NETP) ? "in0" : "input";
    const char* out_blob = (modelType == MODEL_U2NETP) ? "out0" : "output";

    ex.input(in_blob, in);
    ncnn::Mat out;
    int ret_extract = ex.extract(out_blob, out);
    if (ret_extract != 0) {
        LOGE("NCNN extract failed with code %d", ret_extract);
        return nullptr;
    }

    // For U2NETP, out0 contains raw logits before sigmoid.
    // Apply sigmoid and min-max normalization across the probability map.
    if (modelType == MODEL_U2NETP) {
        float* ptr = out.channel(0);
        float min_val = 1e9f;
        float max_val = -1e9f;
        const int out_size = out.w * out.h;
        for (int i = 0; i < out_size; i++) {
            float s = 1.0f / (1.0f + expf(-ptr[i]));
            ptr[i] = s;
            if (s < min_val) min_val = s;
            if (s > max_val) max_val = s;
        }
        float range = max_val - min_val;
        if (range < 1e-6f) range = 1e-6f;
        for (int i = 0; i < out_size; i++) {
            ptr[i] = (ptr[i] - min_val) / range;
        }
    }

    // Upscale output alpha matte to source dimensions
    ncnn::Mat alpha_full;
    ncnn::resize_bilinear(out, alpha_full, src_w, src_h);

    // Apply photographic Guided Filter refinement
    std::vector<float> refined_alpha(src_w * src_h);
    guided_filter(
        guidance.data(),
        reinterpret_cast<const float*>(alpha_full.data),
        refined_alpha.data(),
        src_w, src_h,
        4, 1e-3f
    );

    // Create Java Bitmap for the mask (ARGB_8888)
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
    jobject maskBitmap = env->CallStaticObjectMethod(
        bitmapClass, createBitmapMethod,
        src_w, src_h, argbConfig
    );
    if (!maskBitmap) {
        LOGE("Failed to allocate mask bitmap");
        return nullptr;
    }

    void* mask_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, maskBitmap, &mask_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock mask bitmap pixels");
        return nullptr;
    }

    unsigned char* rgba_out = reinterpret_cast<unsigned char*>(mask_pixels);
    const int total_pixels = src_w * src_h;

    for (int i = 0; i < total_pixels; i++) {
        float val = refined_alpha[i];
        if (val < 0.0f) val = 0.0f;
        if (val > 1.0f) val = 1.0f;
        const unsigned char a = static_cast<unsigned char>(val * 255.0f);

        rgba_out[i * 4 + 0] = 255; // R
        rgba_out[i * 4 + 1] = 255; // G
        rgba_out[i * 4 + 2] = 255; // B
        rgba_out[i * 4 + 3] = a;   // Alpha
    }

    AndroidBitmap_unlockPixels(env, maskBitmap);
    return maskBitmap;
}

JNIEXPORT void JNICALL
Java_com_veilframe_app_cv_segmentation_ncnn_NcnnMattingNative_nativeRelease(
    JNIEnv* env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_matting_mutex);
    if (g_u2netp_net != nullptr) {
        delete g_u2netp_net;
        g_u2netp_net = nullptr;
    }
    if (g_modnet_net != nullptr) {
        delete g_modnet_net;
        g_modnet_net = nullptr;
    }
    LOGI("Matting nets released");
}

} // extern "C"
