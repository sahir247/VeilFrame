#pragma once

#include <mutex>
#include <android/log.h>
#include "net.h"
#include "gpu.h"

namespace veilframe {

inline std::mutex& get_gpu_lifecycle_mutex() {
    static std::mutex s_gpu_mutex;
    return s_gpu_mutex;
}

inline int& get_gpu_lifecycle_ref_count() {
    static int s_gpu_ref_count = 0;
    return s_gpu_ref_count;
}

/**
 * Thread-safe acquisition of shared NCNN GPU (Vulkan) instance.
 * Calls ncnn::create_gpu_instance() when ref count transitions from 0 to 1.
 * Returns true if GPU instance is active, false otherwise.
 */
inline bool acquire_gpu_instance() {
    std::lock_guard<std::mutex> lock(get_gpu_lifecycle_mutex());
    int& ref_count = get_gpu_lifecycle_ref_count();
    if (ref_count == 0) {
        try {
            ncnn::create_gpu_instance();
            ref_count = 1;
            __android_log_print(ANDROID_LOG_INFO, "VeilFrameGPU", "Vulkan GPU instance created (ref_count = 1)");
            return true;
        } catch (const std::exception& e) {
            __android_log_print(ANDROID_LOG_WARN, "VeilFrameGPU", "ncnn::create_gpu_instance failed: %s; falling back to CPU", e.what());
            return false;
        } catch (...) {
            __android_log_print(ANDROID_LOG_WARN, "VeilFrameGPU", "ncnn::create_gpu_instance failed with unknown exception; falling back to CPU");
            return false;
        }
    } else {
        ref_count++;
        __android_log_print(ANDROID_LOG_INFO, "VeilFrameGPU", "Vulkan GPU instance retained (ref_count = %d)", ref_count);
        return true;
    }
}

/**
 * Thread-safe release of shared NCNN GPU (Vulkan) instance.
 * Calls ncnn::destroy_gpu_instance() only when ref count drops to 0.
 */
inline void release_gpu_instance() {
    std::lock_guard<std::mutex> lock(get_gpu_lifecycle_mutex());
    int& ref_count = get_gpu_lifecycle_ref_count();
    if (ref_count > 0) {
        ref_count--;
        __android_log_print(ANDROID_LOG_INFO, "VeilFrameGPU", "Vulkan GPU instance released (ref_count = %d)", ref_count);
        if (ref_count == 0) {
            try {
                ncnn::destroy_gpu_instance();
                __android_log_print(ANDROID_LOG_INFO, "VeilFrameGPU", "Vulkan GPU instance destroyed (ref_count = 0)");
            } catch (const std::exception& e) {
                __android_log_print(ANDROID_LOG_WARN, "VeilFrameGPU", "ncnn::destroy_gpu_instance error: %s", e.what());
            } catch (...) {
                __android_log_print(ANDROID_LOG_WARN, "VeilFrameGPU", "ncnn::destroy_gpu_instance error: unknown exception");
            }
        }
    }
}

} // namespace veilframe
