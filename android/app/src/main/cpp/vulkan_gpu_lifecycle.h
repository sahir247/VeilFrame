#pragma once

#include <mutex>
#include <limits>
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
    if (ref_count >= std::numeric_limits<int>::max() - 1) {
        __android_log_print(ANDROID_LOG_ERROR, "VeilFrameGPU", "Vulkan GPU instance ref count overflow prevented");
        return false;
    }
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

/**
 * RAII lease token for shared Vulkan GPU instance.
 * Automatically releases the GPU instance when destructed or reset.
 * Move-only to ensure single ownership semantics.
 */
class GpuInstanceLease {
public:
    GpuInstanceLease() : m_acquired(false) {}

    explicit GpuInstanceLease(bool auto_acquire) : m_acquired(false) {
        if (auto_acquire) {
            m_acquired = acquire_gpu_instance();
        }
    }

    ~GpuInstanceLease() {
        reset();
    }

    // Move constructor
    GpuInstanceLease(GpuInstanceLease&& other) noexcept : m_acquired(other.m_acquired) {
        other.m_acquired = false;
    }

    // Move assignment
    GpuInstanceLease& operator=(GpuInstanceLease&& other) noexcept {
        if (this != &other) {
            reset();
            m_acquired = other.m_acquired;
            other.m_acquired = false;
        }
        return *this;
    }

    // Non-copyable
    GpuInstanceLease(const GpuInstanceLease&) = delete;
    GpuInstanceLease& operator=(const GpuInstanceLease&) = delete;

    bool is_acquired() const { return m_acquired; }
    explicit operator bool() const { return m_acquired; }

    bool acquire() {
        if (!m_acquired) {
            m_acquired = acquire_gpu_instance();
        }
        return m_acquired;
    }

    void reset() {
        if (m_acquired) {
            release_gpu_instance();
            m_acquired = false;
        }
    }

private:
    bool m_acquired;
};

} // namespace veilframe
