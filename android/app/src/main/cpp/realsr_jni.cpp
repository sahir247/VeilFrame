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

static jclass g_strip_callback_class = nullptr;
static jmethodID g_on_strip_method = nullptr;

struct CallbackUserData {
    JNIEnv* env;
    jobject strip_callback;
    jobject row_callback;
    jobject progress_callback;
    int w;
    int channels;
    bool has_exception;
};

static int jni_strip_callback(unsigned char* strip_data, int y0, int height, void* user_data) {
    CallbackUserData* cb = static_cast<CallbackUserData*>(user_data);
    if (!cb || !cb->env || !cb->strip_callback) {
        return 0;
    }

    jlong capacity = (jlong)height * cb->w * cb->channels;
    jobject direct_buf = cb->env->NewDirectByteBuffer(strip_data, capacity);
    if (!direct_buf) {
        LOGE("Failed to allocate DirectByteBuffer for input strip y0=%d h=%d", y0, height);
        return -1;
    }

    jint ret = cb->env->CallIntMethod(cb->strip_callback, g_on_strip_method, direct_buf, y0, height);
    cb->env->DeleteLocalRef(direct_buf);

    if (cb->env->ExceptionCheck()) {
        cb->has_exception = true;
        return -1; // Abort without clearing exception
    }

    return ret;
}

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
        cb->has_exception = true;
        return -1; // Abort on Java exception WITHOUT clearing it
    }

    return ret;
}

static int jni_progress_callback(int current_tile, int total_tiles, int percent, void* user_data) {
    CallbackUserData* cb = static_cast<CallbackUserData*>(user_data);
    if (!cb || !cb->env || !cb->progress_callback) {
        return 0;
    }

    cb->env->CallVoidMethod(cb->progress_callback, g_on_progress_method, current_tile, total_tiles, percent);
    if (cb->env->ExceptionCheck()) {
        cb->has_exception = true;
        return -1; // Abort on Java exception without clearing it
    }
    return 0;
}

#include <atomic>

static std::atomic<int> g_active_instances{0};
static std::atomic<bool> g_is_initialized{false};
static ncnn::Mutex g_lifecycle_lock;

static void release_cached_global_refs(JNIEnv* env) {
    if (g_row_callback_class) {
        env->DeleteGlobalRef(g_row_callback_class);
        g_row_callback_class = nullptr;
    }
    g_on_row_method = nullptr;

    if (g_progress_callback_class) {
        env->DeleteGlobalRef(g_progress_callback_class);
        g_progress_callback_class = nullptr;
    }
    g_on_progress_method = nullptr;

    if (g_strip_callback_class) {
        env->DeleteGlobalRef(g_strip_callback_class);
        g_strip_callback_class = nullptr;
    }
    g_on_strip_method = nullptr;

    if (g_active_instances.load() == 0) {
        ncnn::destroy_gpu_instance();
    }
    g_is_initialized.store(false);
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeGlobalInit(JNIEnv* env, jclass clazz) {
    ncnn::MutexLockGuard guard(g_lifecycle_lock);
    if (g_is_initialized.load() && g_row_callback_class && g_progress_callback_class && g_strip_callback_class) {
        return 0; // Already initialized, idempotent
    }

    ncnn::create_gpu_instance();

    jclass row_cb_cls = env->FindClass("com/veilframe/app/upscale/inference/NcnnRowCallback");
    if (!row_cb_cls) {
        LOGE("Failed to find NcnnRowCallback class");
        release_cached_global_refs(env);
        return -1;
    }
    g_row_callback_class = (jclass)env->NewGlobalRef(row_cb_cls);
    if (!g_row_callback_class) {
        LOGE("Failed to create global ref for NcnnRowCallback");
        release_cached_global_refs(env);
        return -2;
    }
    g_on_row_method = env->GetMethodID(row_cb_cls, "onRow", "(Ljava/nio/ByteBuffer;III)I");
    if (!g_on_row_method) {
        LOGE("Failed to find onRow method");
        release_cached_global_refs(env);
        return -3;
    }

    jclass prog_cb_cls = env->FindClass("com/veilframe/app/upscale/inference/NcnnProgressCallback");
    if (!prog_cb_cls) {
        LOGE("Failed to find NcnnProgressCallback class");
        release_cached_global_refs(env);
        return -4;
    }
    g_progress_callback_class = (jclass)env->NewGlobalRef(prog_cb_cls);
    if (!g_progress_callback_class) {
        LOGE("Failed to create global ref for NcnnProgressCallback");
        release_cached_global_refs(env);
        return -5;
    }
    g_on_progress_method = env->GetMethodID(prog_cb_cls, "onProgress", "(III)V");
    if (!g_on_progress_method) {
        LOGE("Failed to find onProgress method");
        release_cached_global_refs(env);
        return -6;
    }

    jclass strip_cb_cls = env->FindClass("com/veilframe/app/upscale/inference/NcnnStripCallback");
    if (!strip_cb_cls) {
        LOGE("Failed to find NcnnStripCallback class");
        release_cached_global_refs(env);
        return -7;
    }
    g_strip_callback_class = (jclass)env->NewGlobalRef(strip_cb_cls);
    if (!g_strip_callback_class) {
        LOGE("Failed to create global ref for NcnnStripCallback");
        release_cached_global_refs(env);
        return -8;
    }
    g_on_strip_method = env->GetMethodID(strip_cb_cls, "onFetchStrip", "(Ljava/nio/ByteBuffer;II)I");
    if (!g_on_strip_method) {
        LOGE("Failed to find onFetchStrip method");
        release_cached_global_refs(env);
        return -9;
    }

    g_is_initialized.store(true);
    LOGI("NCNN global initialized, GPU count: %d", ncnn::get_gpu_count());
    return 0;
}

JNIEXPORT void JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeGlobalDestroy(JNIEnv* env, jclass clazz) {
    ncnn::MutexLockGuard guard(g_lifecycle_lock);
    if (g_active_instances.load() > 0) {
        LOGE("Cannot destroy NCNN global instance while %d instances are still active", g_active_instances.load());
        return;
    }
    release_cached_global_refs(env);
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
    g_active_instances.fetch_add(1);
    RealSR* realsr = new RealSR(gpuid, ttaMode, numThreads);
    return reinterpret_cast<jlong>(realsr);
}

JNIEXPORT void JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeDestroyInstance(
    JNIEnv* env, jclass clazz, jlong handle
) {
    RealSR* realsr = reinterpret_cast<RealSR*>(handle);
    delete realsr;
    g_active_instances.fetch_sub(1);
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
    udata.strip_callback = nullptr;
    udata.row_callback = rowCallback;
    udata.progress_callback = progressCallback;
    udata.w = width;
    udata.channels = channels;
    udata.has_exception = false;

    int ret = realsr->process_streaming(
        pixeldata, width, height, channels,
        jni_row_callback,
        progressCallback ? jni_progress_callback : nullptr,
        &udata
    );

    if (udata.has_exception || env->ExceptionCheck()) {
        return -1;
    }

    return ret;
}

JNIEXPORT jint JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeProcessStripStreaming(
    JNIEnv* env, jclass clazz, jlong handle,
    jint width, jint height, jint channels,
    jobject stripCallback, jobject rowCallback, jobject progressCallback
) {
    RealSR* realsr = reinterpret_cast<RealSR*>(handle);
    if (!realsr) return -1;

    CallbackUserData udata;
    udata.env = env;
    udata.strip_callback = stripCallback;
    udata.row_callback = rowCallback;
    udata.progress_callback = progressCallback;
    udata.w = width;
    udata.channels = channels;
    udata.has_exception = false;

    int ret = realsr->process_strip_streaming(
        width, height, channels,
        jni_strip_callback,
        jni_row_callback,
        progressCallback ? jni_progress_callback : nullptr,
        &udata
    );

    if (udata.has_exception || env->ExceptionCheck()) {
        return -1;
    }

    return ret;
}

} // extern "C"
