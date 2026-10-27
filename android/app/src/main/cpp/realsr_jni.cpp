// VeilFrame NCNN Upscaler JNI Bridge
// Connects Kotlin NcnnNative / NcnnUpscaleEngine to native RealSR NCNN C++ engine

#include <jni.h>
#include <string>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>
#include <cstring>
#include <atomic>
#include <android/log.h>
#include <android/bitmap.h>
#include <cmath>
#include "realsr.h"

#define TAG "VeilFrame.NcnnJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static jclass g_row_callback_class = nullptr;
static jmethodID g_on_row_method = nullptr;

static jclass g_progress_callback_class = nullptr;
static jmethodID g_on_progress_method = nullptr;

static jclass g_strip_callback_class = nullptr;
static jmethodID g_on_strip_method = nullptr;

static jclass g_buffer_class = nullptr;
static jmethodID g_buffer_clear_method = nullptr;

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

static bool valid_strip_output_dimensions(jint width, jint height, int scale) {
    if (scale != 2 && scale != 3 && scale != 4) return false;
    const uint64_t out_width = static_cast<uint64_t>(width) * static_cast<uint64_t>(scale);
    const uint64_t out_height = static_cast<uint64_t>(height) * static_cast<uint64_t>(scale);
    if (out_width > 32768ULL || out_height > 32768ULL) return false;
    // Strip streaming memory scales with out_width * tile_height rather than full frame.
    // Allow up to 32768x32768 output dimensions (standard 12MP/48MP/108MP phone photos upscaled 4x).
    return (out_width * out_height) <= 1073741824ULL;
}

struct CallbackUserData {
    JNIEnv* env = nullptr;
    jobject strip_callback = nullptr;
    jobject row_callback = nullptr;
    jobject progress_callback = nullptr;
    std::atomic<bool>* cancel_flag = nullptr;
    int w = 0;
    int channels = 0;
    bool has_exception = false;
    bool cancelled = false;
    bool callback_failed = false;
    // One reusable row buffer and one DirectByteBuffer object per inference call.
    std::vector<unsigned char> row_buffer;
    jobject row_direct_buffer = nullptr;
    // Reusable strip direct buffer caching
    unsigned char* last_strip_data = nullptr;
    uint64_t last_strip_capacity = 0;
    jobject strip_direct_buffer = nullptr;

    ~CallbackUserData() {
        if (env) {
            if (row_direct_buffer) {
                env->DeleteLocalRef(row_direct_buffer);
                row_direct_buffer = nullptr;
            }
            if (strip_direct_buffer) {
                env->DeleteLocalRef(strip_direct_buffer);
                strip_direct_buffer = nullptr;
            }
        }
    }
};

static bool prepare_reusable_row_buffer(
    JNIEnv* env,
    CallbackUserData* cb,
    jint input_width,
    jint channels,
    int scale
) {
    if (!env || !cb || input_width <= 0 || scale <= 0 ||
        (channels != 3 && channels != 4)) {
        return false;
    }
    const uint64_t output_width = static_cast<uint64_t>(input_width) *
        static_cast<uint64_t>(scale);
    const uint64_t capacity64 = output_width * static_cast<uint64_t>(channels);
    if (capacity64 == 0 ||
        capacity64 > static_cast<uint64_t>(std::numeric_limits<jint>::max()) ||
        capacity64 > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
        return false;
    }
    cb->row_buffer.resize(static_cast<size_t>(capacity64));
    cb->row_direct_buffer = env->NewDirectByteBuffer(
        cb->row_buffer.data(), static_cast<jlong>(capacity64));
    return cb->row_direct_buffer != nullptr;
}

static int jni_strip_callback(unsigned char* strip_data, int y0, int height, void* user_data) {
    CallbackUserData* cb = static_cast<CallbackUserData*>(user_data);
    if (!cb) {
        return 0;
    }
    if (cb->cancel_flag && cb->cancel_flag->load()) {
        cb->cancelled = true;
        return -1;
    }
    if (!cb->env || !cb->strip_callback) {
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

    jobject direct_buf = nullptr;
    if (cb->strip_direct_buffer && cb->last_strip_data == strip_data && cb->last_strip_capacity == capacity64) {
        direct_buf = cb->strip_direct_buffer;
    } else {
        if (cb->strip_direct_buffer) {
            cb->env->DeleteLocalRef(cb->strip_direct_buffer);
            cb->strip_direct_buffer = nullptr;
        }
        direct_buf = cb->env->NewDirectByteBuffer(strip_data, static_cast<jlong>(capacity64));
        if (!direct_buf) {
            cb->has_exception = true;
            LOGE("Failed to allocate DirectByteBuffer for input strip y0=%d h=%d", y0, height);
            return -1;
        }
        cb->strip_direct_buffer = direct_buf;
        cb->last_strip_data = strip_data;
        cb->last_strip_capacity = capacity64;
    }

    jobject cleared_buffer = cb->env->CallObjectMethod(direct_buf, g_buffer_clear_method);
    if (cb->env->ExceptionCheck()) {
        cb->has_exception = true;
        return -1;
    }
    if (cleared_buffer) cb->env->DeleteLocalRef(cleared_buffer);

    jint ret = cb->env->CallIntMethod(cb->strip_callback, g_on_strip_method, direct_buf, y0, height);

    if (cb->env->ExceptionCheck()) {
        cb->has_exception = true;
        return -1; // Abort without clearing exception
    }

    if (ret == 1 || (cb->cancel_flag && cb->cancel_flag->load())) {
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
    if (!cb) {
        return 0;
    }
    if (cb->cancel_flag && cb->cancel_flag->load()) {
        cb->cancelled = true;
        return -1;
    }
    if (!cb->env || !cb->row_callback) {
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
    if (!cb->row_direct_buffer || cb->row_buffer.size() < capacity64) {
        cb->has_exception = true;
        LOGE("Reusable DirectByteBuffer is not initialized for row %d", row_index);
        return -1;
    }
    // Reuse one direct buffer object across all rows. Kotlin consumes it
    // synchronously before this storage is overwritten for the next row.
    std::memcpy(cb->row_buffer.data(), row_data, static_cast<size_t>(capacity64));
    jobject cleared_buffer = cb->env->CallObjectMethod(
        cb->row_direct_buffer, g_buffer_clear_method
    );
    if (cb->env->ExceptionCheck()) {
        cb->has_exception = true;
        return -1;
    }
    if (cleared_buffer) cb->env->DeleteLocalRef(cleared_buffer);

    jint ret = cb->env->CallIntMethod(
        cb->row_callback, g_on_row_method, cb->row_direct_buffer, row_index, row_width, channels
    );

    if (cb->env->ExceptionCheck()) {
        cb->has_exception = true;
        return -1; // Abort on Java exception WITHOUT clearing it
    }

    if (ret == 1 || (cb->cancel_flag && cb->cancel_flag->load())) {
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
    if (!cb) {
        return 0;
    }
    if (cb->cancel_flag && cb->cancel_flag->load()) {
        cb->cancelled = true;
        return -1;
    }
    if (!cb->env || !cb->progress_callback) {
        return 0;
    }

    jint ret = cb->env->CallIntMethod(cb->progress_callback, g_on_progress_method, current_tile, total_tiles, percent);
    if (cb->env->ExceptionCheck()) {
        cb->has_exception = true;
        return -1; // Abort on Java exception without clearing it
    }
    if (ret == 1 || (cb->cancel_flag && cb->cancel_flag->load())) {
        cb->cancelled = true;
        return -1;
    }
    if (ret != 0) {
        cb->callback_failed = true;
        return -1;
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
    std::atomic<bool> cancelled{false};
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
        LOGE("initialize_callback_method: FindClass failed for %s", class_name);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }

    *global_class = static_cast<jclass>(env->NewGlobalRef(local_class));
    if (!*global_class) {
        LOGE("initialize_callback_method: NewGlobalRef failed for %s", class_name);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(local_class);
        return false;
    }

    *method_id = env->GetMethodID(local_class, method_name, signature);
    env->DeleteLocalRef(local_class);
    if (!*method_id) {
        LOGE("initialize_callback_method: GetMethodID failed for %s.%s %s", class_name, method_name, signature);
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

    if (g_buffer_class) env->DeleteGlobalRef(g_buffer_class);
    g_buffer_class = nullptr;
    g_buffer_clear_method = nullptr;
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
        try {
            ncnn::create_gpu_instance();
            gpu_instance_created = true;
        } catch (const std::exception& eg) {
            LOGW("ncnn::create_gpu_instance failed: %s; falling back to CPU", eg.what());
        } catch (...) {
            LOGW("ncnn::create_gpu_instance failed with unknown exception; falling back to CPU");
        }

        if (!initialize_callback_method(
                env,
                "com/veilframe/app/upscale/inference/NcnnRowCallback",
                "onRow", "(Ljava/nio/ByteBuffer;III)I",
                &g_row_callback_class, &g_on_row_method)) {
            LOGE("nativeGlobalInit: failed to initialize NcnnRowCallback.onRow");
            error_code = -1;
        } else if (!initialize_callback_method(
                env,
                "com/veilframe/app/upscale/inference/NcnnProgressCallback",
                "onProgress", "(III)I",
                &g_progress_callback_class, &g_on_progress_method)) {
            LOGE("nativeGlobalInit: failed to initialize NcnnProgressCallback.onProgress");
            error_code = -2;
        } else if (!initialize_callback_method(
                env,
                "com/veilframe/app/upscale/inference/NcnnStripCallback",
                "onFetchStrip", "(Ljava/nio/ByteBuffer;II)I",
                &g_strip_callback_class, &g_on_strip_method)) {
            LOGE("nativeGlobalInit: failed to initialize NcnnStripCallback.onFetchStrip");
            error_code = -3;
        } else if (!initialize_callback_method(
                env,
                "java/nio/Buffer",
                "clear", "()Ljava/nio/Buffer;",
                &g_buffer_class, &g_buffer_clear_method)) {
            // Covariant fallback: on Android ART bytecode, ByteBuffer overrides clear() returning ByteBuffer
            if (!initialize_callback_method(
                    env,
                    "java/nio/ByteBuffer",
                    "clear", "()Ljava/nio/ByteBuffer;",
                    &g_buffer_class, &g_buffer_clear_method)) {
                LOGE("nativeGlobalInit: failed to initialize Buffer.clear");
                error_code = -4;
            }
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
            LOGW("Draining %zu orphaned instance handle(s) during NCNN global teardown", g_instance_registry.size());
            g_instance_registry.clear();
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

JNIEXPORT void JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeCancel(
    JNIEnv* env, jclass clazz, jlong handle
) {
    std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
    if (g_runtime_state != RuntimeState::READY) return;
    auto it = g_instance_registry.find(handle);
    if (it != g_instance_registry.end() && it->second) {
        it->second->cancelled.store(true);
        LOGI("nativeCancel: flagged handle %lld for immediate cancellation", (long long)handle);
    }
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

    lease.instance->cancelled.store(false);
    CallbackUserData udata{};
    udata.env = env;
    udata.row_callback = rowCallback;
    udata.progress_callback = progressCallback;
    udata.cancel_flag = &lease.instance->cancelled;
    udata.w = width;
    udata.channels = channels;
    udata.has_exception = false;
    udata.cancelled = false;
    udata.callback_failed = false;

    try {
        if (!prepare_reusable_row_buffer(
                env, &udata, width, channels, lease.instance->engine->scale)) {
            return env->ExceptionCheck() ? -12 : -11;
        }
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
        !valid_strip_output_dimensions(width, height, lease.instance->engine->scale) ||
        !stripCallback || !rowCallback || !g_on_strip_method || !g_on_row_method) {
        LOGE("nativeProcessStripStreaming: invalid dimensions or callbacks");
        return -3;
    }

    lease.instance->cancelled.store(false);
    CallbackUserData udata{};
    udata.env = env;
    udata.strip_callback = stripCallback;
    udata.row_callback = rowCallback;
    udata.progress_callback = progressCallback;
    udata.cancel_flag = &lease.instance->cancelled;
    udata.w = width;
    udata.channels = channels;
    udata.has_exception = false;
    udata.cancelled = false;
    udata.callback_failed = false;

    try {
        if (!prepare_reusable_row_buffer(
                env, &udata, width, channels, lease.instance->engine->scale)) {
            return env->ExceptionCheck() ? -12 : -11;
        }
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

static inline float lanczos4_kernel(float x) {
    if (x == 0.0f) return 1.0f;
    float ax = std::fabs(x);
    if (ax >= 4.0f) return 0.0f;
    const float PI = 3.14159265358979323846f;
    float px = PI * x;
    return (std::sin(px) * std::sin(px * 0.25f)) / (px * px * 0.25f);
}

JNIEXPORT jboolean JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeAdaptiveDetailEnhance(
    JNIEnv* env, jclass clazz,
    jobject bitmap,
    jfloat strength
) {
    if (strength <= 0.01f || !bitmap) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    const int w = info.width;
    const int h = info.height;
    if (w < 4 || h < 4) return JNI_FALSE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;

    unsigned char* rgba = reinterpret_cast<unsigned char*>(pixels);
    const int stride = info.stride;

    // 1. Compute luminance plane Y
    std::vector<float> Y(static_cast<size_t>(w) * h);
    #pragma omp parallel for
    for (int y = 0; y < h; y++) {
        const unsigned char* row = rgba + y * stride;
        float* y_row = Y.data() + y * w;
        for (int x = 0; x < w; x++) {
            float r = row[x * 4 + 0];
            float g = row[x * 4 + 1];
            float b = row[x * 4 + 2];
            y_row[x] = 0.299f * r + 0.587f * g + 0.114f * b;
        }
    }

    // 2. Compute 3x3 separable Gaussian blur and Sobel texture gradient
    std::vector<float> blur_h(static_cast<size_t>(w) * h);
    #pragma omp parallel for
    for (int y = 0; y < h; y++) {
        const float* y_row = Y.data() + y * w;
        float* b_row = blur_h.data() + y * w;
        for (int x = 0; x < w; x++) {
            int xm1 = (x > 0) ? x - 1 : 0;
            int xp1 = (x < w - 1) ? x + 1 : w - 1;
            b_row[x] = 0.25f * y_row[xm1] + 0.5f * y_row[x] + 0.25f * y_row[xp1];
        }
    }

    #pragma omp parallel for
    for (int y = 0; y < h; y++) {
        int ym1 = (y > 0) ? y - 1 : 0;
        int yp1 = (y < h - 1) ? y + 1 : h - 1;
        const float* b_m1 = blur_h.data() + ym1 * w;
        const float* b_0  = blur_h.data() + y * w;
        const float* b_p1 = blur_h.data() + yp1 * w;

        const float* y_m1 = Y.data() + ym1 * w;
        const float* y_0  = Y.data() + y * w;
        const float* y_p1 = Y.data() + yp1 * w;

        unsigned char* row = rgba + y * stride;

        for (int x = 0; x < w; x++) {
            int xm1 = (x > 0) ? x - 1 : 0;
            int xp1 = (x < w - 1) ? x + 1 : w - 1;

            float y_val = y_0[x];
            float blur_val = 0.25f * b_m1[x] + 0.5f * b_0[x] + 0.25f * b_p1[x];
            float high_freq = y_val - blur_val;

            // 3x3 Sobel gradient magnitude
            float gx = (y_m1[xp1] + 2.0f * y_0[xp1] + y_p1[xp1]) - (y_m1[xm1] + 2.0f * y_0[xm1] + y_p1[xm1]);
            float gy = (y_p1[xm1] + 2.0f * y_p1[x] + y_p1[xp1]) - (y_m1[xm1] + 2.0f * y_m1[x] + y_m1[xp1]);
            float grad_mag = (std::fabs(gx) + std::fabs(gy)) * 0.125f;

            // Texture weight: suppress in flat regions (noise immunity), apply in textured regions
            float weight = std::min(1.0f, grad_mag * (1.0f / 16.0f));

            float enhanced_y = y_val + strength * weight * high_freq;
            if (enhanced_y < 0.0f) enhanced_y = 0.0f;
            if (enhanced_y > 255.0f) enhanced_y = 255.0f;

            float gain = (enhanced_y + 1e-3f) / (y_val + 1e-3f);

            float r = row[x * 4 + 0] * gain;
            float g = row[x * 4 + 1] * gain;
            float b = row[x * 4 + 2] * gain;

            row[x * 4 + 0] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, r + 0.5f)));
            row[x * 4 + 1] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, g + 0.5f)));
            row[x * 4 + 2] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, b + 0.5f)));
            // Alpha row[x * 4 + 3] remains unchanged
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

JNIEXPORT jobject JNICALL
Java_com_veilframe_app_upscale_inference_NcnnNative_nativeScaleLanczos4(
    JNIEnv* env, jclass clazz,
    jobject srcBitmap,
    jint targetW,
    jint targetH
) {
    if (!srcBitmap || targetW <= 0 || targetH <= 0) return nullptr;

    AndroidBitmapInfo src_info;
    if (AndroidBitmap_getInfo(env, srcBitmap, &src_info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    if (src_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return nullptr;

    const int src_w = src_info.width;
    const int src_h = src_info.height;

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (!bitmapClass) return nullptr;
    jmethodID createBitmapMethod = env->GetStaticMethodID(
        bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );
    if (!createBitmapMethod) return nullptr;
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argbField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argbConfig = env->GetStaticObjectField(configClass, argbField);

    jobject dstBitmap = env->CallStaticObjectMethod(
        bitmapClass, createBitmapMethod,
        targetW, targetH, argbConfig
    );
    if (!dstBitmap) return nullptr;

    void* src_pixels = nullptr;
    void* dst_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &src_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    if (AndroidBitmap_lockPixels(env, dstBitmap, &dst_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return nullptr;
    }

    const unsigned char* src_rgba = reinterpret_cast<const unsigned char*>(src_pixels);
    unsigned char* dst_rgba = reinterpret_cast<unsigned char*>(dst_pixels);
    const int src_stride = src_info.stride;

    struct FilterWeights {
        int indices[8];
        float weights[8];
    };

    std::vector<FilterWeights> h_weights(targetW);
    const float scale_x = static_cast<float>(src_w) / targetW;
    for (int x = 0; x < targetW; x++) {
        float src_pos = (x + 0.5f) * scale_x - 0.5f;
        int base_idx = static_cast<int>(std::floor(src_pos));
        float sum = 0.0f;
        for (int k = -3; k <= 4; k++) {
            int tap = k + 3;
            int idx = std::min(src_w - 1, std::max(0, base_idx + k));
            h_weights[x].indices[tap] = idx;
            float w = lanczos4_kernel(src_pos - (base_idx + k));
            h_weights[x].weights[tap] = w;
            sum += w;
        }
        float inv_sum = (sum != 0.0f) ? (1.0f / sum) : 1.0f;
        for (int tap = 0; tap < 8; tap++) {
            h_weights[x].weights[tap] *= inv_sum;
        }
    }

    std::vector<FilterWeights> v_weights(targetH);
    const float scale_y = static_cast<float>(src_h) / targetH;
    for (int y = 0; y < targetH; y++) {
        float src_pos = (y + 0.5f) * scale_y - 0.5f;
        int base_idx = static_cast<int>(std::floor(src_pos));
        float sum = 0.0f;
        for (int k = -3; k <= 4; k++) {
            int tap = k + 3;
            int idx = std::min(src_h - 1, std::max(0, base_idx + k));
            v_weights[y].indices[tap] = idx;
            float w = lanczos4_kernel(src_pos - (base_idx + k));
            v_weights[y].weights[tap] = w;
            sum += w;
        }
        float inv_sum = (sum != 0.0f) ? (1.0f / sum) : 1.0f;
        for (int tap = 0; tap < 8; tap++) {
            v_weights[y].weights[tap] *= inv_sum;
        }
    }

    std::vector<float> temp_buf(static_cast<size_t>(targetW) * src_h * 4);
    #pragma omp parallel for
    for (int y = 0; y < src_h; y++) {
        const unsigned char* in_row = src_rgba + y * src_stride;
        float* out_row = temp_buf.data() + static_cast<size_t>(y) * targetW * 4;

        for (int x = 0; x < targetW; x++) {
            const FilterWeights& fw = h_weights[x];
            float r = 0.0f, g = 0.0f, b = 0.0f, a = 0.0f;
            for (int tap = 0; tap < 8; tap++) {
                int src_x = fw.indices[tap];
                float w = fw.weights[tap];
                r += in_row[src_x * 4 + 0] * w;
                g += in_row[src_x * 4 + 1] * w;
                b += in_row[src_x * 4 + 2] * w;
                a += in_row[src_x * 4 + 3] * w;
            }
            out_row[x * 4 + 0] = r;
            out_row[x * 4 + 1] = g;
            out_row[x * 4 + 2] = b;
            out_row[x * 4 + 3] = a;
        }
    }

    #pragma omp parallel for
    for (int y = 0; y < targetH; y++) {
        const FilterWeights& fw = v_weights[y];
        unsigned char* out_row = dst_rgba + y * targetW * 4;

        for (int x = 0; x < targetW; x++) {
            float r = 0.0f, g = 0.0f, b = 0.0f, a = 0.0f;
            for (int tap = 0; tap < 8; tap++) {
                int src_y = fw.indices[tap];
                float w = fw.weights[tap];
                const float* in_pixel = temp_buf.data() + (static_cast<size_t>(src_y) * targetW + x) * 4;
                r += in_pixel[0] * w;
                g += in_pixel[1] * w;
                b += in_pixel[2] * w;
                a += in_pixel[3] * w;
            }
            out_row[x * 4 + 0] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, r + 0.5f)));
            out_row[x * 4 + 1] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, g + 0.5f)));
            out_row[x * 4 + 2] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, b + 0.5f)));
            out_row[x * 4 + 3] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, a + 0.5f)));
        }
    }

    AndroidBitmap_unlockPixels(env, dstBitmap);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    return dstBitmap;
}

} // extern "C"
