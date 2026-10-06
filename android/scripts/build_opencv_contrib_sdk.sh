#!/usr/bin/env bash
# ==============================================================================
# VeilFrame Android OpenCV 4.14.0 + opencv_contrib (WeChatQRCode) Build Script
#
# Reproducibly builds the custom OpenCV Android SDK with wechat_qrcode and Java
# bindings for ARM64 and x86_64, placing output directly into :opencv-sdk.
#
# Prerequisites:
#   - Linux x86_64 (or WSL2)
#   - JDK 17
#   - Android SDK (build-tools 35, platforms android-35)
#   - Android NDK (r25+ or r27 recommended)
#   - CMake >= 3.22, Ninja, Python 3, Git
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OPENCV_SDK_DIR="${PROJECT_ROOT}/android/opencv-sdk"

OPENCV_VERSION="4.14.0"
WORK_DIR="${SCRIPT_DIR}/.opencv_build"

echo "========================================================================"
echo "VeilFrame Custom OpenCV + WeChatQRCode Android SDK Builder"
echo "OpenCV Version: ${OPENCV_VERSION}"
echo "Target SDK Dir: ${OPENCV_SDK_DIR}"
echo "========================================================================"

mkdir -p "${WORK_DIR}"
cd "${WORK_DIR}"

# 1. Verify Android Toolchain
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_NDK_ROOT:-}" ]; then
    export ANDROID_NDK_HOME="${ANDROID_NDK_ROOT}"
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
    # Auto-detect latest installed NDK under Android SDK
    if [ -d "${ANDROID_HOME}/ndk" ]; then
        LATEST_NDK=$(ls -d "${ANDROID_HOME}/ndk/"* 2>/dev/null | sort -V | tail -n 1)
        if [ -n "${LATEST_NDK}" ]; then
            export ANDROID_NDK_HOME="${LATEST_NDK}"
        fi
    fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
    echo "ERROR: ANDROID_NDK_HOME is not set or does not exist."
    echo "Please set ANDROID_NDK_HOME=/path/to/android-ndk"
    exit 1
fi

echo "Using Android NDK: ${ANDROID_NDK_HOME}"

# 2. Clone or update OpenCV and opencv_contrib pinned to 4.14.0
if [ ! -d "opencv" ]; then
    echo "==> Cloning opencv ${OPENCV_VERSION}..."
    git clone --depth 1 --branch "${OPENCV_VERSION}" https://github.com/opencv/opencv.git opencv
else
    echo "==> opencv source directory exists"
fi

if [ ! -d "opencv_contrib" ]; then
    echo "==> Cloning opencv_contrib ${OPENCV_VERSION}..."
    git clone --depth 1 --branch "${OPENCV_VERSION}" https://github.com/opencv/opencv_contrib.git opencv_contrib
else
    echo "==> opencv_contrib source directory exists"
fi

# 3. Create build configuration
# FIX: In OpenCV's CMake system, --modules_list sets -DBUILD_LIST.
# If 'java' is omitted, CMake disables java and java_bindings_generator by whitelist!
# Modules MUST explicitly include: core, imgproc, imgcodecs, video, photo, objdetect, dnn, wechat_qrcode, java
BUILD_MODULES="core,imgproc,imgcodecs,video,photo,objdetect,dnn,wechat_qrcode,java"

BUILD_OUT="${WORK_DIR}/output"
rm -rf "${BUILD_OUT}"
mkdir -p "${BUILD_OUT}"

cat << 'EOF' > "${WORK_DIR}/opencv-veilframe.config.py"
ABIs = [
    ABI("3", "arm64-v8a", "aarch64-linux-android"),
    ABI("4", "x86_64", "x86_64-linux-android"),
]
EOF

echo "==> Invoking OpenCV build_sdk.py with verified module whitelist..."
python3 "${WORK_DIR}/opencv/platforms/android/build_sdk.py" \
    --ndk_path="${ANDROID_NDK_HOME}" \
    --sdk_path="${ANDROID_HOME:-/opt/android-sdk}" \
    --opencv_dir="${WORK_DIR}/opencv" \
    --extra_modules_path="${WORK_DIR}/opencv_contrib/modules" \
    --modules_list="${BUILD_MODULES}" \
    --config="${WORK_DIR}/opencv-veilframe.config.py" \
    --no_samples_build \
    --build_doc=OFF \
    "${BUILD_OUT}"

SDK_STAGE="${BUILD_OUT}/OpenCV-android-sdk/sdk"

if [ ! -d "${SDK_STAGE}/native/libs" ] || [ ! -d "${SDK_STAGE}/java/src" ]; then
    echo "ERROR: OpenCV build failed to produce expected sdk/native/libs or sdk/java/src structure."
    exit 1
fi

echo "==> Staging compiled native libraries and Java bindings into :opencv-sdk..."
mkdir -p "${OPENCV_SDK_DIR}/src/main/jniLibs/arm64-v8a"
mkdir -p "${OPENCV_SDK_DIR}/src/main/jniLibs/x86_64"
mkdir -p "${OPENCV_SDK_DIR}/src/main/java"

# Copy native libraries (libopencv_java4.so)
if [ -f "${SDK_STAGE}/native/libs/arm64-v8a/libopencv_java4.so" ]; then
    cp "${SDK_STAGE}/native/libs/arm64-v8a/libopencv_java4.so" "${OPENCV_SDK_DIR}/src/main/jniLibs/arm64-v8a/"
    echo "✓ Copied arm64-v8a/libopencv_java4.so"
fi
if [ -f "${SDK_STAGE}/native/libs/x86_64/libopencv_java4.so" ]; then
    cp "${SDK_STAGE}/native/libs/x86_64/libopencv_java4.so" "${OPENCV_SDK_DIR}/src/main/jniLibs/x86_64/"
    echo "✓ Copied x86_64/libopencv_java4.so"
fi

# Copy Java bindings (including org.opencv.wechat_qrcode.WeChatQRCode)
cp -R "${SDK_STAGE}/java/src/org" "${OPENCV_SDK_DIR}/src/main/java/"
echo "✓ Copied org.opencv Java sources"

# Verify WeChatQRCode binding exists
if [ -f "${OPENCV_SDK_DIR}/src/main/java/org/opencv/wechat_qrcode/WeChatQRCode.java" ]; then
    echo "✓ Verified WeChatQRCode.java binding is present in :opencv-sdk"
else
    echo "WARNING: WeChatQRCode.java was not found in generated Java bindings!"
fi

echo "========================================================================"
echo "Successfully built and integrated custom OpenCV 4.14.0 + WeChatQRCode SDK"
echo "========================================================================"
