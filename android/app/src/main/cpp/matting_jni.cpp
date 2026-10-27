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

JNIEXPORT jboolean JNICALL
Java_com_veilframe_app_cv_segmentation_ncnn_NcnnMattingNative_isModelLoaded(
    JNIEnv* env, jclass clazz,
    jint modelType
) {
    std::lock_guard<std::mutex> lock(g_matting_mutex);
    ncnn::Net* net = (modelType == MODEL_U2NETP) ? g_u2netp_net : g_modnet_net;
    return (net != nullptr) ? JNI_TRUE : JNI_FALSE;
}

#include <cmath>

JNIEXPORT jobject JNICALL
Java_com_veilframe_app_cv_segmentation_ncnn_NcnnMattingNative_nativeProcess(
    JNIEnv* env, jclass clazz,
    jobject srcBitmap,
    jint modelType,
    jboolean useGpu,
    jboolean letterbox
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

    const int target = (modelType == MODEL_U2NETP) ? 320 : 512;

    int scaled_w = target;
    int scaled_h = target;
    int pad_left = 0, pad_right = 0, pad_top = 0, pad_bottom = 0;

    if (letterbox) {
        // Aspect-preserving letterbox scale with constant border padding
        const float scale = std::min(static_cast<float>(target) / src_w, static_cast<float>(target) / src_h);
        scaled_w = std::min(target, std::max(1, static_cast<int>(std::round(src_w * scale))));
        scaled_h = std::min(target, std::max(1, static_cast<int>(std::round(src_h * scale))));

        pad_left = (target - scaled_w) / 2;
        pad_right = target - scaled_w - pad_left;
        pad_top = (target - scaled_h) / 2;
        pad_bottom = target - scaled_h - pad_top;

        LOGI("Letterbox matting: src=%dx%d, scaled=%dx%d, pad=[top=%d, bot=%d, left=%d, right=%d], target=%d",
             src_w, src_h, scaled_w, scaled_h, pad_top, pad_bottom, pad_left, pad_right, target);
    } else {
        LOGI("Direct resize matting: src=%dx%d -> %dx%d (full tensor utilization)",
             src_w, src_h, target, target);
    }

    ncnn::Mat in_scaled = ncnn::Mat::from_pixels_resize(
        reinterpret_cast<const unsigned char*>(src_pixels),
        ncnn::Mat::PIXEL_RGBA2RGB,
        src_w, src_h,
        scaled_w, scaled_h
    );

    AndroidBitmap_unlockPixels(env, srcBitmap);

    if (modelType == MODEL_U2NETP) {
        // ImageNet mean/std
        const float mean_vals[3] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
        const float norm_vals[3] = {1.0f / (0.229f * 255.f), 1.0f / (0.224f * 255.f), 1.0f / (0.225f * 255.f)};
        in_scaled.substract_mean_normalize(mean_vals, norm_vals);
    } else {
        // MODNet [-1.0, 1.0]
        const float mean_vals[3] = {127.5f, 127.5f, 127.5f};
        const float norm_vals[3] = {0.0078431f, 0.0078431f, 0.0078431f};
        in_scaled.substract_mean_normalize(mean_vals, norm_vals);
    }

    ncnn::Mat in;
    if (letterbox && (pad_top > 0 || pad_bottom > 0 || pad_left > 0 || pad_right > 0)) {
        // 0.0f in normalized space is exact neutral gray / ImageNet mean
        ncnn::copy_make_border(
            in_scaled, in,
            pad_top, pad_bottom, pad_left, pad_right,
            ncnn::BORDER_CONSTANT, 0.0f
        );
    } else {
        in = in_scaled;
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

    // Unpad output to recover active aspect region when letterboxing
    ncnn::Mat out_unpadded;
    if (letterbox && (pad_top > 0 || pad_bottom > 0 || pad_left > 0 || pad_right > 0)) {
        ncnn::copy_cut_border(
            out, out_unpadded,
            pad_top, pad_bottom, pad_left, pad_right
        );
    } else {
        out_unpadded = out;
    }

    // Activation & Normalization handling:
    if (modelType == MODEL_U2NETP) {
        // For U2-Net-P: Saliency networks output uncalibrated relative energy scores.
        // We apply sigmoid and official U2-Net dynamic range normalization (normPRED from Qin et al. 2020)
        // so that secondary subjects and thin extremities (legs, limbs) are scaled into visible alpha.
        float* ptr = out_unpadded.channel(0);
        float min_val = 1e9f;
        float max_val = -1e9f;
        const int size = out_unpadded.w * out_unpadded.h;
        for (int i = 0; i < size; i++) {
            float s = 1.0f / (1.0f + expf(-ptr[i]));
            ptr[i] = s;
            if (s < min_val) min_val = s;
            if (s > max_val) max_val = s;
        }
        float range = max_val - min_val;
        if (range > 1e-4f) {
            for (int i = 0; i < size; i++) {
                ptr[i] = (ptr[i] - min_val) / range;
            }
        }
    } else {
        // MODNet already outputs continuous alpha matte in [0.0, 1.0]
        float* ptr = out_unpadded.channel(0);
        const int size = out_unpadded.w * out_unpadded.h;
        #pragma omp parallel for
        for (int i = 0; i < size; i++) {
            if (ptr[i] < 0.0f) ptr[i] = 0.0f;
            if (ptr[i] > 1.0f) ptr[i] = 1.0f;
        }
    }

    // Upscale output alpha matte directly to source dimensions
    ncnn::Mat alpha_full;
    ncnn::resize_bilinear(out_unpadded, alpha_full, src_w, src_h);

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

    const float* alpha_data = reinterpret_cast<const float*>(alpha_full.data);
    unsigned char* rgba_out = reinterpret_cast<unsigned char*>(mask_pixels);
    const int total_pixels = src_w * src_h;

    #pragma omp parallel for
    for (int i = 0; i < total_pixels; i++) {
        float val = alpha_data[i];
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

JNIEXPORT jobject JNICALL
Java_com_veilframe_app_cv_segmentation_ncnn_NcnnMattingNative_nativeProcessLegacy(
    JNIEnv* env, jclass clazz,
    jobject srcBitmap,
    jint modelType,
    jboolean useGpu
) {
    std::lock_guard<std::mutex> lock(g_matting_mutex);

    ncnn::Net* net = (modelType == MODEL_U2NETP) ? g_u2netp_net : g_modnet_net;
    if (!net) {
        LOGE("Model %d not initialized in nativeProcessLegacy", modelType);
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

    const int target_w = (modelType == MODEL_U2NETP) ? 320 : 512;
    const int target_h = (modelType == MODEL_U2NETP) ? 320 : 512;

    // UNPATCHED / LEGACY: Anamorphic direct stretch to square without preserving aspect ratio
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
        reinterpret_cast<const unsigned char*>(src_pixels),
        ncnn::Mat::PIXEL_RGBA2RGB,
        src_w, src_h,
        target_w, target_h
    );

    AndroidBitmap_unlockPixels(env, srcBitmap);

    if (modelType == MODEL_U2NETP) {
        const float mean_vals[3] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
        const float norm_vals[3] = {1.0f / (0.229f * 255.f), 1.0f / (0.224f * 255.f), 1.0f / (0.225f * 255.f)};
        in.substract_mean_normalize(mean_vals, norm_vals);
    } else {
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

    // UNPATCHED / LEGACY: Double-sigmoid + min-max stretch on U2NETP
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

    // Direct bilinear stretch back to src_w x src_h (anamorphic)
    ncnn::Mat alpha_full;
    ncnn::resize_bilinear(out, alpha_full, src_w, src_h);

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmap = env->GetStaticMethodID(
        bitmapClass,
        "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );

    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject configObj = env->GetStaticObjectField(configClass, argb8888Field);

    jobject maskBitmap = env->CallStaticObjectMethod(bitmapClass, createBitmap, src_w, src_h, configObj);
    if (!maskBitmap) {
        LOGE("Failed to allocate legacy mask bitmap");
        return nullptr;
    }

    void* mask_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, maskBitmap, &mask_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_lockPixels failed on legacy maskBitmap");
        return nullptr;
    }

    const float* alpha_data = reinterpret_cast<const float*>(alpha_full.data);
    unsigned char* rgba_out = reinterpret_cast<unsigned char*>(mask_pixels);
    const int total_pixels = src_w * src_h;

    #pragma omp parallel for
    for (int i = 0; i < total_pixels; i++) {
        float val = alpha_data[i];
        if (val < 0.0f) val = 0.0f;
        if (val > 1.0f) val = 1.0f;
        const unsigned char a = static_cast<unsigned char>(val * 255.0f);
        rgba_out[i * 4 + 0] = 255;
        rgba_out[i * 4 + 1] = 255;
        rgba_out[i * 4 + 2] = 255;
        rgba_out[i * 4 + 3] = a;
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
