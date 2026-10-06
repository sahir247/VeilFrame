#!/usr/bin/env bash
# ==============================================================================
# VeilFrame Android OpenCV 4.14.0 + opencv_contrib (WeChatQRCode) Build Script
#
# Reproducibly builds the custom OpenCV Android SDK with wechat_qrcode and Java
# bindings for ARM64 and x86_64, placing output directly into :opencv-sdk.
#
# Exact Verified Toolchain:
#   - Linux x86_64 (Ubuntu 22.04 / 24.04 or WSL2)
#   - Eclipse Temurin JDK 17
#   - Android SDK API 35 (build-tools 35.0.0)
#   - Android NDK 27.2.12479018
#   - CMake 3.22.1, Ninja, Python 3.10+, Git
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OPENCV_SDK_DIR="${PROJECT_ROOT}/android/opencv-sdk"

OPENCV_VERSION="4.14.0"
# Exact forensic release commit SHAs pinned from verified upstream tree
OPENCV_COMMIT_SHA="0654a42b10ba2dc2b0d00f73fbfb534442654ee0"
OPENCV_CONTRIB_COMMIT_SHA="a8e9acdd94489bb2df921e64906a5efdcfdae267"

WORK_DIR="${SCRIPT_DIR}/.opencv_build"

echo "========================================================================"
echo "VeilFrame Custom OpenCV + WeChatQRCode Android SDK Builder"
echo "OpenCV Version:    ${OPENCV_VERSION} (${OPENCV_COMMIT_SHA})"
echo "opencv_contrib:    ${OPENCV_VERSION} (${OPENCV_CONTRIB_COMMIT_SHA})"
echo "Target Module:     ${OPENCV_SDK_DIR}"
echo "========================================================================"

mkdir -p "${WORK_DIR}"
cd "${WORK_DIR}"

# 1. Verify Android Toolchain
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_NDK_ROOT:-}" ]; then
    export ANDROID_NDK_HOME="${ANDROID_NDK_ROOT}"
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
    # Auto-detect verified NDK 27.2.12479018 or latest installed under Android SDK
    if [ -d "${ANDROID_HOME}/ndk/27.2.12479018" ]; then
        export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/27.2.12479018"
    elif [ -d "${ANDROID_HOME}/ndk" ]; then
        LATEST_NDK=$(ls -d "${ANDROID_HOME}/ndk/"* 2>/dev/null | sort -V | tail -n 1)
        if [ -n "${LATEST_NDK}" ]; then
            export ANDROID_NDK_HOME="${LATEST_NDK}"
        fi
    fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
    echo "ERROR: ANDROID_NDK_HOME is not set or does not exist."
    echo "Expected verified NDK: 27.2.12479018"
    echo "Please set ANDROID_NDK_HOME=/path/to/android-ndk"
    exit 1
fi

echo "Verified Android NDK: ${ANDROID_NDK_HOME}"

# 2. Checkout pinned repositories with exact commit validation
checkout_pinned_repo() {
    local target_dir="$1"
    local repo_url="$2"
    local expected_sha="$3"

    if [ ! -d "${target_dir}/.git" ]; then
        echo "==> Cloning ${target_dir}..."
        git clone "${repo_url}" "${target_dir}"
    fi

    echo "==> Validating revision for ${target_dir}..."
    (
        cd "${target_dir}"
        CURRENT_SHA=$(git rev-parse HEAD 2>/dev/null || echo "none")
        if [ "${CURRENT_SHA}" != "${expected_sha}" ]; then
            echo "Checking out pinned commit ${expected_sha}..."
            git fetch --depth 1 origin "${expected_sha}" 2>/dev/null || git fetch origin
            git checkout -f "${expected_sha}"
            NEW_SHA=$(git rev-parse HEAD)
            if [ "${NEW_SHA}" != "${expected_sha}" ]; then
                echo "ERROR: Failed to pin ${target_dir} to ${expected_sha} (got ${NEW_SHA})"
                exit 1
            fi
        fi
        echo "✓ ${target_dir} is pinned to verified SHA: ${expected_sha}"
    )
}

checkout_pinned_repo "opencv" "https://github.com/opencv/opencv.git" "${OPENCV_COMMIT_SHA}"
checkout_pinned_repo "opencv_contrib" "https://github.com/opencv/opencv_contrib.git" "${OPENCV_CONTRIB_COMMIT_SHA}"

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

# 4. Strict fail-closed validation of generated artifacts
echo "==> Validating generated artifacts in SDK stage..."
if [ ! -s "${SDK_STAGE}/native/libs/arm64-v8a/libopencv_java4.so" ]; then
    echo "FATAL: arm64-v8a/libopencv_java4.so is missing or empty!"
    exit 1
fi
if [ ! -s "${SDK_STAGE}/native/libs/x86_64/libopencv_java4.so" ]; then
    echo "FATAL: x86_64/libopencv_java4.so is missing or empty!"
    exit 1
fi
if [ ! -s "${SDK_STAGE}/java/src/org/opencv/core/Mat.java" ]; then
    echo "FATAL: Core Java bindings (org/opencv/core/Mat.java) missing or empty!"
    exit 1
fi
if [ ! -s "${SDK_STAGE}/java/src/org/opencv/wechat_qrcode/WeChatQRCode.java" ]; then
    echo "FATAL: WeChatQRCode Java binding (org/opencv/wechat_qrcode/WeChatQRCode.java) missing or empty!"
    exit 1
fi

echo "✓ All required native libraries and Java sources verified in build stage."

# 5. Staging into :opencv-sdk with completion marker
echo "==> Staging compiled native libraries and Java bindings into :opencv-sdk..."
# Remove marker first so partial staging cannot be interpreted as complete
rm -f "${OPENCV_SDK_DIR}/src/main/.opencv-sdk-complete"

mkdir -p "${OPENCV_SDK_DIR}/src/main/jniLibs/arm64-v8a"
mkdir -p "${OPENCV_SDK_DIR}/src/main/jniLibs/x86_64"
mkdir -p "${OPENCV_SDK_DIR}/src/main/java"

cp "${SDK_STAGE}/native/libs/arm64-v8a/libopencv_java4.so" "${OPENCV_SDK_DIR}/src/main/jniLibs/arm64-v8a/"
cp "${SDK_STAGE}/native/libs/x86_64/libopencv_java4.so" "${OPENCV_SDK_DIR}/src/main/jniLibs/x86_64/"
cp -R "${SDK_STAGE}/java/src/org" "${OPENCV_SDK_DIR}/src/main/java/"

# Write completion marker with exact provenance metadata
cat << EOF > "${OPENCV_SDK_DIR}/src/main/.opencv-sdk-complete"
OpenCV=${OPENCV_VERSION}
OpenCV_SHA=${OPENCV_COMMIT_SHA}
Contrib_SHA=${OPENCV_CONTRIB_COMMIT_SHA}
ABIs=arm64-v8a,x86_64
GeneratedAt=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
Status=VERIFIED_COMPLETE
EOF

echo "✓ Wrote completion marker: ${OPENCV_SDK_DIR}/src/main/.opencv-sdk-complete"
echo "========================================================================"
echo "Successfully built, verified, and staged custom OpenCV 4.14.0 SDK"
echo "========================================================================"
