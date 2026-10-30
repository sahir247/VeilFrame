#pragma once

#include <jni.h>

namespace veilframe {

/**
 * RAII scope guard for JNI UTF strings (GetStringUTFChars / ReleaseStringUTFChars).
 * Ensures native string memory is freed on all early returns and exceptions.
 * Move-only semantics prevent accidental double-release.
 */
class JniUtfStringScope {
public:
    JniUtfStringScope(JNIEnv* env, jstring jstr)
        : m_env(env), m_jstr(jstr), m_chars(nullptr) {
        if (m_env && m_jstr) {
            m_chars = m_env->GetStringUTFChars(m_jstr, nullptr);
        }
    }

    ~JniUtfStringScope() {
        release();
    }

    // Move constructor
    JniUtfStringScope(JniUtfStringScope&& other) noexcept
        : m_env(other.m_env), m_jstr(other.m_jstr), m_chars(other.m_chars) {
        other.m_env = nullptr;
        other.m_jstr = nullptr;
        other.m_chars = nullptr;
    }

    // Move assignment
    JniUtfStringScope& operator=(JniUtfStringScope&& other) noexcept {
        if (this != &other) {
            release();
            m_env = other.m_env;
            m_jstr = other.m_jstr;
            m_chars = other.m_chars;
            other.m_env = nullptr;
            other.m_jstr = nullptr;
            other.m_chars = nullptr;
        }
        return *this;
    }

    // Non-copyable
    JniUtfStringScope(const JniUtfStringScope&) = delete;
    JniUtfStringScope& operator=(const JniUtfStringScope&) = delete;

    const char* get() const { return m_chars; }
    const char* c_str() const { return m_chars; }
    explicit operator bool() const { return m_chars != nullptr; }

    void release() {
        if (m_env && m_jstr && m_chars) {
            m_env->ReleaseStringUTFChars(m_jstr, m_chars);
            m_chars = nullptr;
        }
    }

private:
    JNIEnv* m_env;
    jstring m_jstr;
    const char* m_chars;
};

} // namespace veilframe
