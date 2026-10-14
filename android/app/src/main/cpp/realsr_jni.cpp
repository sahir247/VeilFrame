// VeilFrame NCNN Upscaler JNI Bridge
// Connects Kotlin NcnnNative / NcnnUpscaleEngine to native RealSR NCNN C++ engine

#include <jni.h>
#include <string>
#include <android/log.h>
#include "realsr.h"

#define TAG "VeilFrame.NcnnJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static jclass g_row_callback_class = nullptr;
static jmethodID g_on_row_method = nullptr;

static jclass g_progress_callback_class = nullptr;
static jmethodID g_on_progress_method = nullptr;

struct CallbackUserData {
    JNIEnv* env;
    jobject row_callback;
    jobject progress_callback;
};

static int jni_row_callback(const unsigned char* row_data, int row_index, int row_width, int channels, void* user_data) {
    CallbackUserData* cb = static_cast<CallbackUserData*>(user_data);
    if (!cb || !cb->env || !cb->row_callback) {
        return 0;
    }

    jobject direct_buf = cb->env->NewDirectByteBuffer(const_cast<unsigned char*>(row_data), (jlong)row_width * channels);
    if (!direct_buf) {
        LOGE("Failed to allocate DirectByteBuffer for row %d", row_index);
        return -1;
    }

    jint ret = cb->env->CallIntMethod(cb->row_callback, g_on_row_method, direct_buf, row_index, row_width, channels);
    cb->env->DeleteLocalRef(direct_buf);

    if (cb->env->ExceptionCheck()) {
        cb->env->ExceptionClear();
        return -1; // Abort on Java exception (e.g. cancellation or I/O failure)
    }

    return ret;
}

static void jni_progress_callback(int current_tile, int total_tiles, int percent, void* user_data) {
    CallbackUserData* cb = static_cast<CallbackUserData*>(user_data);
    if (!cb || !cb->env || !cb->progress_callback) {
        return;
    }

    cb->env->CallVoidMethod(cb->progress_callback, g_on_progress_method, current_tile, total_tiles, percent);
    if (cb->env->ExceptionCheck()) {
        cb->env->ExceptionClear();
    }
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeGlobalInit(JNIEnv* env, jclass clazz) {
    ncnn::create_gpu_instance();

    jclass row_cb_cls = env->FindClass("com/veilframe/app/upscale/inference/NcnnRowCallback");
    if (row_cb_cls) {
        g_row_callback_class = (jclass)env->NewGlobalRef(row_cb_cls);
        g_on_row_method = env->GetMethodID(g_row_callback_class, "onRow", "(Ljava/nio/ByteBuffer;III)I");
    }

    jclass prog_cb_cls = env->FindClass("com/veilframe/app/upscale/inference/NcnnProgressCallback");
    if (prog_cb_cls) {
        g_progress_callback_class = (jclass)env->NewGlobalRef(prog_cb_cls);
        g_on_progress_method = env->GetMethodID(g_progress_callback_class, "onProgress", "(III)V");
    }

    LOGI("NCNN global initialized, GPU count: %d", ncnn::get_gpu_count());
    return 0;
}

JNIEXPORT void JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeGlobalDestroy(JNIEnv* env, jclass clazz) {
    if (g_row_callback_class) {
        env->DeleteGlobalRef(g_row_callback_class);
        g_row_callback_class = nullptr;
    }
    if (g_progress_callback_class) {
        env->DeleteGlobalRef(g_progress_callback_class);
        g_progress_callback_class = nullptr;
    }
    ncnn::destroy_gpu_instance();
    LOGI("NCNN global destroyed");
}

JNIEXPORT jint JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeGetGpuCount(JNIEnv* env, jclass clazz) {
    return ncnn::get_gpu_count();
}

JNIEXPORT jstring JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeGetGpuDeviceName(JNIEnv* env, jclass clazz, jint gpuid) {
    int count = ncnn::get_gpu_count();
    if (gpuid < 0 || gpuid >= count) {
        return nullptr;
    }
    const ncnn::GpuInfo& info = ncnn::get_gpu_info(gpuid);
    return env->NewStringUTF(info.device_name());
}

JNIEXPORT jlong JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeCreateInstance(
    JNIEnv* env, jclass clazz, jint gpuid, jboolean ttaMode, jint numThreads
) {
    RealSR* realsr = new RealSR(gpuid, ttaMode, numThreads);
    return reinterpret_cast<jlong>(realsr);
}

JNIEXPORT void JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeDestroyInstance(
    JNIEnv* env, jclass clazz, jlong handle
) {
    RealSR* realsr = reinterpret_cast<RealSR*>(handle);
    delete realsr;
}

JNIEXPORT jint JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeLoadModel(
    JNIEnv* env, jclass clazz, jlong handle,
    jstring paramPath, jstring binPath,
    jint scale, jint tilesize, jint prepadding
) {
    RealSR* realsr = reinterpret_cast<RealSR*>(handle);
    if (!realsr) return -1;

    const char* param_str = env->GetStringUTFChars(paramPath, nullptr);
    const char* bin_str = env->GetStringUTFChars(binPath, nullptr);

    realsr->scale = scale;
    realsr->tilesize = tilesize;
    realsr->prepadding = prepadding;

    int ret = realsr->load(param_str, bin_str);

    env->ReleaseStringUTFChars(paramPath, param_str);
    env->ReleaseStringUTFChars(binPath, bin_str);

    return ret;
}

JNIEXPORT jint JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeProcessStreaming(
    JNIEnv* env, jclass clazz, jlong handle,
    jobject inputDirectBuffer, jint width, jint height, jint channels,
    jobject rowCallback, jobject progressCallback
) {
    RealSR* realsr = reinterpret_cast<RealSR*>(handle);
    if (!realsr) return -1;

    const unsigned char* pixeldata = static_cast<const unsigned char*>(
        env->GetDirectBufferAddress(inputDirectBuffer)
    );
    if (!pixeldata) {
        LOGE("Failed to get direct buffer address from input DirectByteBuffer");
        return -2;
    }

    CallbackUserData udata;
    udata.env = env;
    udata.row_callback = rowCallback;
    udata.progress_callback = progressCallback;

    int ret = realsr->process_streaming(
        pixeldata, width, height, channels,
        jni_row_callback,
        progressCallback ? jni_progress_callback : nullptr,
        &udata
    );

    return ret;
}

} // extern "C"
