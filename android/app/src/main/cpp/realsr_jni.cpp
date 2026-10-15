// VeilFrame NCNN Upscaler JNI Bridge
// Connects Kotlin NcnnNative / NcnnUpscaleEngine to native RealSR NCNN C++ engine

#include <jni.h>
#include <string>
#include <cstddef>
#include <cstdint>
#include <limits>
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

static bool valid_image_dimensions(jint width, jint height, jint channels) {
    if (width <= 0 || height <= 0 || width > 16384 || height > 16384 ||
        (channels != 3 && channels != 4)) {
        return false;
    }
    const uint64_t pixels = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    // Keep hostile or corrupt JNI inputs away from native allocation paths.
    if (pixels > 100000000ULL) return false;
    return pixels <= std::numeric_limits<size_t>::max() / static_cast<uint64_t>(channels);
}

static uint64_t expected_image_bytes(jint width, jint height, jint channels) {
    return static_cast<uint64_t>(width) * static_cast<uint64_t>(height) *
           static_cast<uint64_t>(channels);
}

static bool valid_output_dimensions(jint width, jint height, int scale) {
    if (scale != 2 && scale != 3 && scale != 4) return false;
    const uint64_t out_width = static_cast<uint64_t>(width) * static_cast<uint64_t>(scale);
    const uint64_t out_height = static_cast<uint64_t>(height) * static_cast<uint64_t>(scale);
    if (out_width > 32768ULL || out_height > 32768ULL) return false;
    return out_width * out_height <= 100000000ULL;
}

struct CallbackUserData {
    JNIEnv* env;
    jobject strip_callback;
    jobject row_callback;
    jobject progress_callback;
    int w;
    int channels;
    bool has_exception;
    bool cancelled;
    bool callback_failed;
};

static int jni_strip_callback(unsigned char* strip_data, int y0, int height, void* user_data) {
    CallbackUserData* cb = static_cast<CallbackUserData*>(user_data);
    if (!cb || !cb->env || !cb->strip_callback) {
        return 0;
    }

    if (height <= 0 || cb->w <= 0 || (cb->channels != 3 && cb->channels != 4)) {
        cb->has_exception = true;
        return -1;
    }
    const uint64_t capacity64 = static_cast<uint64_t>(height) *
        static_cast<uint64_t>(cb->w) * static_cast<uint64_t>(cb->channels);
    if (capacity64 == 0 || capacity64 > static_cast<uint64_t>(std::numeric_limits<jint>::max())) {
        cb->has_exception = true;
        LOGE("Rejected out-of-range strip buffer size");
        return -1;
    }
    jobject direct_buf = cb->env->NewDirectByteBuffer(strip_data, static_cast<jlong>(capacity64));
    if (!direct_buf) {
        cb->has_exception = true;
        LOGE("Failed to allocate DirectByteBuffer for input strip y0=%d h=%d", y0, height);
        return -1;
    }

    jint ret = cb->env->CallIntMethod(cb->strip_callback, g_on_strip_method, direct_buf, y0, height);
    cb->env->DeleteLocalRef(direct_buf);

    if (cb->env->ExceptionCheck()) {
        cb->has_exception = true;
        return -1; // Abort without clearing exception
    }

    if (ret == 1) {
        cb->cancelled = true;
        return -1; // Keep callback cancellation out of NCNN's status namespace.
    }
    if (ret != 0) {
        cb->callback_failed = true;
        LOGE("consumer callback failed with status %d", ret);
        return -1; // Do not misclassify consumer I/O failure as GPU failure.
    }
    return 0;
}

static int jni_row_callback(const unsigned char* row_data, int row_index, int row_width, int channels, void* user_data) {
    CallbackUserData* cb = static_cast<CallbackUserData*>(user_data);
    if (!cb || !cb->env || !cb->row_callback) {
        return 0;
    }

    if (row_width <= 0 || (channels != 3 && channels != 4)) {
        cb->has_exception = true;
        return -1;
    }
    const uint64_t capacity64 = static_cast<uint64_t>(row_width) * static_cast<uint64_t>(channels);
    if (capacity64 == 0 || capacity64 > static_cast<uint64_t>(std::numeric_limits<jint>::max())) {
        cb->has_exception = true;
        LOGE("Rejected out-of-range output row buffer size");
        return -1;
    }
    jobject direct_buf = cb->env->NewDirectByteBuffer(
        const_cast<unsigned char*>(row_data), static_cast<jlong>(capacity64));
    if (!direct_buf) {
        cb->has_exception = true;
        LOGE("Failed to allocate DirectByteBuffer for row %d", row_index);
        return -1;
    }

    jint ret = cb->env->CallIntMethod(cb->row_callback, g_on_row_method, direct_buf, row_index, row_width, channels);
    cb->env->DeleteLocalRef(direct_buf);

    if (cb->env->ExceptionCheck()) {
        cb->has_exception = true;
        return -1; // Abort on Java exception WITHOUT clearing it
    }

    if (ret == 1) {
        cb->cancelled = true;
        return -1; // Keep callback cancellation out of NCNN's status namespace.
    }
    if (ret != 0) {
        cb->callback_failed = true;
        LOGE("consumer callback failed with status %d", ret);
        return -1; // Do not misclassify consumer I/O failure as GPU failure.
    }
    return 0;
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

#include <unordered_map>
#include <memory>
#include <atomic>
#include <new>
#include <exception>
#include <mutex>
#include <condition_variable>
#include <utility>
#include <limits>
#include <cstdint>

namespace {

enum class RuntimeState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    STOPPING
};

struct NativeInstance {
    NativeInstance(int gpuid, bool tta_mode, int num_threads)
        : engine(std::make_shared<RealSR>(gpuid, tta_mode, num_threads)) {}

    std::shared_ptr<RealSR> engine;
    std::mutex call_mutex; // RealSR/NCNN instance methods are not concurrently re-entrant.
};

static std::unordered_map<jlong, std::shared_ptr<NativeInstance>> g_instance_registry;
static std::atomic<jlong> g_next_handle{1};
static RuntimeState g_runtime_state = RuntimeState::UNINITIALIZED;
static size_t g_active_operations = 0;
static std::mutex g_lifecycle_mutex;
static std::condition_variable g_lifecycle_cv;

class OperationLease {
public:
    OperationLease() = default;
    explicit OperationLease(bool active) : active_(active) {}
    OperationLease(const OperationLease&) = delete;
    OperationLease& operator=(const OperationLease&) = delete;

    OperationLease(OperationLease&& other) noexcept : active_(other.active_) {
        other.active_ = false;
    }

    OperationLease& operator=(OperationLease&& other) noexcept {
        if (this != &other) {
            release();
            active_ = other.active_;
            other.active_ = false;
        }
        return *this;
    }

    ~OperationLease() { release(); }

private:
    void release() noexcept {
        if (!active_) return;
        {
            std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
            if (g_active_operations > 0) --g_active_operations;
        }
        active_ = false;
        g_lifecycle_cv.notify_all();
    }

    bool active_ = false;
};

struct InstanceLease {
    OperationLease operation;
    std::shared_ptr<NativeInstance> instance;
    explicit operator bool() const { return instance != nullptr; }
};

static InstanceLease acquire_instance(jlong handle) {
    std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
    if (g_runtime_state != RuntimeState::READY) return {};
    auto it = g_instance_registry.find(handle);
    if (it == g_instance_registry.end()) return {};
    ++g_active_operations;
    return InstanceLease{OperationLease(true), it->second};
}

static bool initialize_callback_method(
    JNIEnv* env,
    const char* class_name,
    const char* method_name,
    const char* signature,
    jclass* global_class,
    jmethodID* method_id
) {
    jclass local_class = env->FindClass(class_name);
    if (!local_class) {
        // This API reports initialization errors by status code; do not leave a
        // pending exception that would make the status contract ambiguous.
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }

    *global_class = static_cast<jclass>(env->NewGlobalRef(local_class));
    if (!*global_class) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(local_class);
        return false;
    }

    *method_id = env->GetMethodID(local_class, method_name, signature);
    env->DeleteLocalRef(local_class);
    if (!*method_id) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteGlobalRef(*global_class);
        *global_class = nullptr;
        return false;
    }
    return true;
}

static void release_cached_global_refs(JNIEnv* env) {
    if (g_row_callback_class) env->DeleteGlobalRef(g_row_callback_class);
    g_row_callback_class = nullptr;
    g_on_row_method = nullptr;

    if (g_progress_callback_class) env->DeleteGlobalRef(g_progress_callback_class);
    g_progress_callback_class = nullptr;
    g_on_progress_method = nullptr;

    if (g_strip_callback_class) env->DeleteGlobalRef(g_strip_callback_class);
    g_strip_callback_class = nullptr;
    g_on_strip_method = nullptr;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeGlobalInit(JNIEnv* env, jclass clazz) {
    {
        std::unique_lock<std::mutex> lock(g_lifecycle_mutex);
        g_lifecycle_cv.wait(lock, [] {
            return g_runtime_state != RuntimeState::INITIALIZING &&
                   g_runtime_state != RuntimeState::STOPPING;
        });
        if (g_runtime_state == RuntimeState::READY) return 0;
        g_runtime_state = RuntimeState::INITIALIZING;
    }

    bool gpu_instance_created = false;
    int error_code = 0;
    try {
        ncnn::create_gpu_instance();
        gpu_instance_created = true;

        if (!initialize_callback_method(
                env,
                "com/veilframe/app/upscale/inference/NcnnRowCallback",
                "onRow", "(Ljava/nio/ByteBuffer;III)I",
                &g_row_callback_class, &g_on_row_method)) {
            error_code = -1;
        } else if (!initialize_callback_method(
                env,
                "com/veilframe/app/upscale/inference/NcnnProgressCallback",
                "onProgress", "(III)V",
                &g_progress_callback_class, &g_on_progress_method)) {
            error_code = -2;
        } else if (!initialize_callback_method(
                env,
                "com/veilframe/app/upscale/inference/NcnnStripCallback",
                "onFetchStrip", "(Ljava/nio/ByteBuffer;II)I",
                &g_strip_callback_class, &g_on_strip_method)) {
            error_code = -3;
        }
    } catch (const std::bad_alloc&) {
        LOGE("nativeGlobalInit: out of memory");
        error_code = -4;
    } catch (const std::exception& e) {
        LOGE("nativeGlobalInit exception: %s", e.what());
        error_code = -5;
    } catch (...) {
        LOGE("nativeGlobalInit: unknown exception");
        error_code = -6;
    }

    if (error_code != 0) {
        release_cached_global_refs(env);
        if (gpu_instance_created) ncnn::destroy_gpu_instance();
        std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
        g_runtime_state = RuntimeState::UNINITIALIZED;
        g_lifecycle_cv.notify_all();
        return error_code;
    }

    {
        std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
        g_runtime_state = RuntimeState::READY;
    }
    g_lifecycle_cv.notify_all();
    LOGI("NCNN global initialized");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeGlobalDestroy(JNIEnv* env, jclass clazz) {
    {
        std::unique_lock<std::mutex> lock(g_lifecycle_mutex);
        g_lifecycle_cv.wait(lock, [] {
            return g_runtime_state != RuntimeState::INITIALIZING;
        });
        if (g_runtime_state != RuntimeState::READY) return;
        if (!g_instance_registry.empty()) {
            LOGE("Refusing NCNN global teardown: %zu instance handle(s) remain", g_instance_registry.size());
            return;
        }

        // Prevent new operations first, then wait for every operation lease.
        // This includes calls whose handle was removed by nativeDestroyInstance
        // but whose shared instance pointer is still being used by inference.
        g_runtime_state = RuntimeState::STOPPING;
        g_lifecycle_cv.wait(lock, [] { return g_active_operations == 0; });
    }

    release_cached_global_refs(env);
    ncnn::destroy_gpu_instance();

    {
        std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
        g_runtime_state = RuntimeState::UNINITIALIZED;
    }
    g_lifecycle_cv.notify_all();
    LOGI("NCNN global destroyed");
}

JNIEXPORT jint JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeGetGpuCount(JNIEnv* env, jclass clazz) {
    OperationLease operation;
    {
        std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
        if (g_runtime_state != RuntimeState::READY) return 0;
        ++g_active_operations;
        operation = OperationLease(true);
    }
    return ncnn::get_gpu_count();
}

JNIEXPORT jstring JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeGetGpuDeviceName(JNIEnv* env, jclass clazz, jint gpuid) {
    OperationLease operation;
    {
        std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
        if (g_runtime_state != RuntimeState::READY) return nullptr;
        ++g_active_operations;
        operation = OperationLease(true);
    }

    const int count = ncnn::get_gpu_count();
    if (gpuid < 0 || gpuid >= count) return nullptr;
    const ncnn::GpuInfo& info = ncnn::get_gpu_info(gpuid);
    const char* name = info.device_name();
    return name ? env->NewStringUTF(name) : nullptr;
}

JNIEXPORT jlong JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeCreateInstance(
    JNIEnv* env, jclass clazz, jint gpuid, jboolean ttaMode, jint numThreads
) {
    OperationLease operation;
    {
        std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
        if (g_runtime_state != RuntimeState::READY) {
            LOGE("nativeCreateInstance called while NCNN runtime is not ready");
            return 0;
        }
        ++g_active_operations;
        operation = OperationLease(true);
    }

    std::shared_ptr<NativeInstance> instance;
    try {
        // Construction happens under an operation lease but without the global
        // lifecycle mutex, so teardown can enter STOPPING and wait safely.
        instance = std::make_shared<NativeInstance>(gpuid, ttaMode == JNI_TRUE, numThreads);
    } catch (const std::bad_alloc&) {
        LOGE("nativeCreateInstance: out of memory allocating RealSR");
        return 0;
    } catch (const std::exception& e) {
        LOGE("nativeCreateInstance exception: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("nativeCreateInstance: unknown exception");
        return 0;
    }

    std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
    if (g_runtime_state != RuntimeState::READY) {
        // A teardown started while the constructor was running. Do not publish
        // a handle into a runtime that is stopping.
        return 0;
    }
    jlong handle = g_next_handle.fetch_add(1);
    if (handle <= 0 || handle == std::numeric_limits<jlong>::max()) {
        LOGE("nativeCreateInstance: handle space exhausted");
        return 0;
    }
    try {
        g_instance_registry.emplace(handle, std::move(instance));
    } catch (const std::bad_alloc&) {
        LOGE("nativeCreateInstance: out of memory registering handle");
        return 0;
    }
    return handle;
}

JNIEXPORT void JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeDestroyInstance(
    JNIEnv* env, jclass clazz, jlong handle
) {
    OperationLease operation;
    std::shared_ptr<NativeInstance> instance_to_drop;
    {
        std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
        if (g_runtime_state != RuntimeState::READY) return;
        auto it = g_instance_registry.find(handle);
        if (it == g_instance_registry.end()) return;
        ++g_active_operations;
        operation = OperationLease(true);
        instance_to_drop = std::move(it->second);
        g_instance_registry.erase(it);
    }
    // Drop the registry owner before releasing our lifecycle lease; any
    // in-flight call has its own lease and keeps the engine alive until done.
    instance_to_drop.reset();
}

JNIEXPORT jint JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeLoadModel(
    JNIEnv* env, jclass clazz, jlong handle,
    jstring paramPath, jstring binPath,
    jint scale, jint tilesize, jint prepadding
) {
    InstanceLease lease = acquire_instance(handle);
    if (!lease) {
        LOGE("nativeLoadModel: invalid handle or runtime not ready: %lld", (long long)handle);
        return -1;
    }
    std::lock_guard<std::mutex> instance_guard(lease.instance->call_mutex);

    if (!paramPath || !binPath ||
        (scale != 2 && scale != 3 && scale != 4) ||
        tilesize < 64 || tilesize > 384 ||
        prepadding < 0 || prepadding > 256) {
        return -3;
    }
    const char* param_str = env->GetStringUTFChars(paramPath, nullptr);
    if (!param_str) return -11;
    const char* bin_str = env->GetStringUTFChars(binPath, nullptr);
    if (!bin_str) {
        env->ReleaseStringUTFChars(paramPath, param_str);
        return -11;
    }

    int ret = -1;
    try {
        RealSR& realsr = *lease.instance->engine;
        realsr.scale = scale;
        realsr.tilesize = tilesize;
        realsr.prepadding = prepadding;
        ret = realsr.load(param_str, bin_str);
        // Normalize RealSR::load()'s private return values into stable JNI statuses.
        // -2 (parameter parse) and -4 (graph contract) are deterministic model errors.
        // -3 combines NCNN weight loading with backend initialization on some paths, so
        // verify it on CPU once when GPU is active: corrupt weights fail there too.
        if (ret == -2) {
            LOGE("nativeLoadModel: NCNN rejected model parameters");
            ret = -20;
        } else if (ret == -3) {
            if (realsr.has_gpu()) {
                LOGE("nativeLoadModel: ambiguous Vulkan-backed NCNN load failure; allow one clean CPU verification");
                ret = -2;
            } else {
                LOGE("nativeLoadModel: NCNN rejected model weights on CPU");
                ret = -21;
            }
        } else if (ret == -4) {
            LOGE("nativeLoadModel: model input/output graph names are invalid");
            ret = -22;
        } else if (ret == -5 || ret == -6 || ret == -8) {
            // These codes represent ncnn Vulkan/compute pipeline setup failures in
            // RealSR::load. A new CPU instance can retry without reusing GPU state.
            ret = realsr.has_gpu() ? -2 : -4;
        } else if (ret == -7) {
            // The required NCNN Interp layer is unavailable; retrying cannot repair
            // a missing registered layer and would just obscure the installation fault.
            ret = -23;
        } else if (ret == -9) {
            // TTA's 8-way streaming transform/merge pipeline is not implemented yet.
            ret = -24;
        } else if (ret != 0) {
            LOGE("nativeLoadModel: unexpected RealSR load status %d", ret);
            ret = -23;
        }
    } catch (const std::bad_alloc&) {
        LOGE("nativeLoadModel: bad_alloc");
        ret = -11;
    } catch (const std::exception& e) {
        LOGE("nativeLoadModel exception: %s", e.what());
        ret = -23;
    } catch (...) {
        LOGE("nativeLoadModel: unknown exception");
        ret = -23;
    }

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
    InstanceLease lease = acquire_instance(handle);
    if (!lease) {
        LOGE("nativeProcessStreaming: invalid handle or runtime not ready: %lld", (long long)handle);
        return -3;
    }
    std::lock_guard<std::mutex> instance_guard(lease.instance->call_mutex);

    if (!inputDirectBuffer || !valid_image_dimensions(width, height, channels) ||
        !valid_output_dimensions(width, height, lease.instance->engine->scale)) {
        LOGE("nativeProcessStreaming: invalid direct buffer or dimensions");
        return -3;
    }
    const unsigned char* pixeldata = static_cast<const unsigned char*>(
        env->GetDirectBufferAddress(inputDirectBuffer)
    );
    if (!pixeldata || env->GetDirectBufferCapacity(inputDirectBuffer) < 0 ||
        static_cast<uint64_t>(env->GetDirectBufferCapacity(inputDirectBuffer)) <
            expected_image_bytes(width, height, channels) ||
        !rowCallback || !g_on_row_method) {
        LOGE("nativeProcessStreaming: invalid direct buffer or dimensions");
        return -3;
    }

    CallbackUserData udata{};
    udata.env = env;
    udata.row_callback = rowCallback;
    udata.progress_callback = progressCallback;
    udata.w = width;
    udata.channels = channels;
    udata.has_exception = false;
    udata.cancelled = false;
    udata.callback_failed = false;

    try {
        const int ret = lease.instance->engine->process_streaming(
            pixeldata, width, height, channels,
            jni_row_callback,
            progressCallback ? jni_progress_callback : nullptr,
            &udata
        );
        if (udata.has_exception || env->ExceptionCheck()) return -12; // JNI delivers any pending Java exception to the caller.
        if (udata.cancelled) return -10;
        if (udata.callback_failed) return -13; // Consumer/storage failure; never trigger GPU fallback.
        if (ret == -100 || ret == -101 || ret == -102) return -11; // NCNN failed to allocate an output/device tensor.
        if (ret != 0) return lease.instance->engine->has_gpu() ? -2 : -4;
        return 0;
    } catch (const std::bad_alloc&) {
        LOGE("nativeProcessStreaming: out of memory");
        return -11;
    } catch (const std::exception& e) {
        LOGE("nativeProcessStreaming exception: %s", e.what());
        return -1;
    } catch (...) {
        LOGE("nativeProcessStreaming: unknown exception");
        return -1;
    }
}

JNIEXPORT jint JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeProcessStripStreaming(
    JNIEnv* env, jclass clazz, jlong handle,
    jint width, jint height, jint channels,
    jobject stripCallback, jobject rowCallback, jobject progressCallback
) {
    InstanceLease lease = acquire_instance(handle);
    if (!lease) {
        LOGE("nativeProcessStripStreaming: invalid handle or runtime not ready: %lld", (long long)handle);
        return -3;
    }
    std::lock_guard<std::mutex> instance_guard(lease.instance->call_mutex);

    if (!valid_image_dimensions(width, height, channels) ||
        !valid_output_dimensions(width, height, lease.instance->engine->scale) ||
        !stripCallback || !rowCallback || !g_on_strip_method || !g_on_row_method) {
        LOGE("nativeProcessStripStreaming: invalid dimensions or callbacks");
        return -3;
    }

    CallbackUserData udata{};
    udata.env = env;
    udata.strip_callback = stripCallback;
    udata.row_callback = rowCallback;
    udata.progress_callback = progressCallback;
    udata.w = width;
    udata.channels = channels;
    udata.has_exception = false;
    udata.cancelled = false;
    udata.callback_failed = false;

    try {
        const int ret = lease.instance->engine->process_strip_streaming(
            width, height, channels,
            jni_strip_callback,
            jni_row_callback,
            progressCallback ? jni_progress_callback : nullptr,
            &udata
        );
        if (udata.has_exception || env->ExceptionCheck()) return -12; // JNI delivers any pending Java exception to the caller.
        if (udata.cancelled) return -10;
        if (udata.callback_failed) return -13; // Consumer/storage failure; never trigger GPU fallback.
        if (ret == -100 || ret == -101 || ret == -102) return -11; // NCNN failed to allocate an output/device tensor.
        if (ret != 0) return lease.instance->engine->has_gpu() ? -2 : -4;
        return 0;
    } catch (const std::bad_alloc&) {
        LOGE("nativeProcessStripStreaming: out of memory");
        return -11;
    } catch (const std::exception& e) {
        LOGE("nativeProcessStripStreaming exception: %s", e.what());
        return -1;
    } catch (...) {
        LOGE("nativeProcessStripStreaming: unknown exception");
        return -1;
    }
}

} // extern "C"
