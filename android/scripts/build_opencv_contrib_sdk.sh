#!/usr/bin/env bash
# ==============================================================================
# VeilFrame Android OpenCV 4.14.0 + opencv_contrib (WeChatQRCode) Build Script
# Native OpenCV 4.14.0 SDK build trigger (ARM64 + x86_64) - 2026-10-07T21:23:00Z
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
# Exact upstream release commit SHAs for OpenCV 4.14.0 and opencv_contrib 4.14.0
OPENCV_COMMIT_SHA="0654a42e19215ef25b1d367d822f3c630447e7c7"
OPENCV_CONTRIB_COMMIT_SHA="a8e9acd62cabd30419dba83007f2ac0d07de5e2c"

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
EXPECTED_NDK_VERSION="27.2.12479018"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/ndk/${EXPECTED_NDK_VERSION}" ]; then
        export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/${EXPECTED_NDK_VERSION}"
    elif [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "${ANDROID_NDK_ROOT}" ]; then
        export ANDROID_NDK_HOME="${ANDROID_NDK_ROOT}"
    fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
    echo "FATAL: Required NDK ${EXPECTED_NDK_VERSION} is not found."
    echo "Please install Android NDK ${EXPECTED_NDK_VERSION} or set ANDROID_NDK_HOME to it."
    exit 1
fi

if [ ! -f "${ANDROID_NDK_HOME}/source.properties" ]; then
    echo "FATAL: NDK source.properties is missing in ${ANDROID_NDK_HOME}"
    exit 1
fi
ACTUAL_NDK_VERSION="$(sed -n 's/^Pkg.Revision *= *//p' "${ANDROID_NDK_HOME}/source.properties" | head -n 1 | tr -d '[:space:]')"
if [ -z "${ACTUAL_NDK_VERSION}" ]; then
    echo "FATAL: Failed to parse Pkg.Revision from ${ANDROID_NDK_HOME}/source.properties"
    exit 1
fi

if [ "${ACTUAL_NDK_VERSION}" != "${EXPECTED_NDK_VERSION}" ]; then
    echo "FATAL: Toolchain mismatch: expected exact NDK ${EXPECTED_NDK_VERSION}, got ${ACTUAL_NDK_VERSION} (${ANDROID_NDK_HOME})"
    exit 1
fi

echo "✓ Verified Android NDK: ${ANDROID_NDK_HOME} (${ACTUAL_NDK_VERSION})"

# 2. Checkout pinned repositories with exact commit validation
checkout_pinned_repo() {
    local target_dir="$1"
    local repo_url="$2"
    local expected_sha="$3"

    if [ ! -d "${target_dir}/.git" ]; then
        echo "==> Cloning ${target_dir} (${OPENCV_VERSION})..."
        git clone --depth 1 --branch "${OPENCV_VERSION}" "${repo_url}" "${target_dir}"
    fi

    echo "==> Validating revision for ${target_dir}..."
    (
        cd "${target_dir}"
        CURRENT_SHA=$(git rev-parse HEAD 2>/dev/null || echo "none")
        if [ "${CURRENT_SHA}" != "${expected_sha}" ]; then
            echo "Checking out pinned commit ${expected_sha}..."
            git fetch --depth 1 origin "${expected_sha}" 2>/dev/null || git fetch --depth 1 origin "refs/tags/${OPENCV_VERSION}:refs/tags/${OPENCV_VERSION}" || git fetch origin
            git checkout -f "${expected_sha}"
            CURRENT_SHA=$(git rev-parse HEAD)
            if [ "${CURRENT_SHA}" != "${expected_sha}" ]; then
                echo "ERROR: Failed to pin ${target_dir} to ${expected_sha} (got ${CURRENT_SHA})"
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
# Modules MUST explicitly include: core, imgproc, imgcodecs, video, videoio, photo, objdetect, dnn, wechat_qrcode, java
BUILD_MODULES="core,imgproc,imgcodecs,video,videoio,photo,objdetect,dnn,wechat_qrcode,java"

BUILD_OUT="${WORK_DIR}/output"
rm -rf "${BUILD_OUT}"
mkdir -p "${BUILD_OUT}"

cat << 'EOF' > "${WORK_DIR}/opencv-veilframe.config.py"
ABIs = [
    ABI("3", "arm64-v8a", None, "26", cmake_vars=dict(ANDROID_STL="c++_static", ANDROID_TOOLCHAIN="clang")),
    ABI("4", "x86_64", None, "26", cmake_vars=dict(ANDROID_STL="c++_static", ANDROID_TOOLCHAIN="clang")),
]
EOF

echo "==> Invoking OpenCV build_sdk.py with verified module whitelist..."
python3 "${WORK_DIR}/opencv/platforms/android/build_sdk.py" \
    --ndk_path="${ANDROID_NDK_HOME}" \
    --sdk_path="${ANDROID_HOME:-/opt/android-sdk}" \
    --extra_modules_path="${WORK_DIR}/opencv_contrib/modules" \
    --modules_list="${BUILD_MODULES}" \
    --config="${WORK_DIR}/opencv-veilframe.config.py" \
    --no_samples_build \
    "${BUILD_OUT}" \
    "${WORK_DIR}/opencv"

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

validate_elf_arch() {
    local so_path="$1"
    local expected_arch="$2"
    local readelf_bin=""
    if command -v readelf >/dev/null 2>&1; then
        readelf_bin="readelf"
    else
        local ndk_llvm_readelf
        ndk_llvm_readelf=$(find "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt" -name "llvm-readelf" 2>/dev/null | head -n 1 || true)
        if [ -n "${ndk_llvm_readelf}" ] && [ -x "${ndk_llvm_readelf}" ]; then
            readelf_bin="${ndk_llvm_readelf}"
        fi
    fi

    if [ -z "${readelf_bin}" ]; then
        echo "FATAL: readelf or llvm-readelf is required for fail-closed ELF architecture verification."
        exit 1
    fi

    local header
    header=$("${readelf_bin}" -h "${so_path}" 2>/dev/null || true)
    if ! echo "${header}" | grep -q "${expected_arch}"; then
        echo "FATAL: ELF machine architecture mismatch for ${so_path}!"
        echo "Expected: ${expected_arch}"
        echo "${header}"
        exit 1
    fi
    echo "✓ Validated ELF architecture (${expected_arch}) for $(basename "${so_path}")"
}

validate_elf_arch "${SDK_STAGE}/native/libs/arm64-v8a/libopencv_java4.so" "AArch64"
validate_elf_arch "${SDK_STAGE}/native/libs/x86_64/libopencv_java4.so" "Advanced Micro Devices X86-64"

if [ ! -s "${SDK_STAGE}/java/src/org/opencv/core/Mat.java" ]; then
    echo "FATAL: Core Java bindings (org/opencv/core/Mat.java) missing or empty!"
    exit 1
fi
if [ ! -s "${SDK_STAGE}/java/src/org/opencv/wechat_qrcode/WeChatQRCode.java" ]; then
    echo "FATAL: WeChatQRCode Java binding (org/opencv/wechat_qrcode/WeChatQRCode.java) missing or empty!"
    exit 1
fi
if [ ! -s "${SDK_STAGE}/java/src/org/opencv/videoio/Videoio.java" ]; then
    echo "FATAL: VideoIO Java binding (org/opencv/videoio/Videoio.java) missing or empty!"
    exit 1
fi

echo "✓ All required native libraries (valid ELF ABIs) and Java sources verified in build stage."

# 5. Staging into :opencv-sdk with completion marker
echo "==> Staging compiled native libraries, Java bindings, and resources into :opencv-sdk..."
# Remove marker first so partial staging cannot be interpreted as complete
rm -f "${OPENCV_SDK_DIR}/src/main/.opencv-sdk-complete"

mkdir -p "${OPENCV_SDK_DIR}/src/main/jniLibs/arm64-v8a"
mkdir -p "${OPENCV_SDK_DIR}/src/main/jniLibs/x86_64"
mkdir -p "${OPENCV_SDK_DIR}/src/main/java"
mkdir -p "${OPENCV_SDK_DIR}/src/main/res"

cp "${SDK_STAGE}/native/libs/arm64-v8a/libopencv_java4.so" "${OPENCV_SDK_DIR}/src/main/jniLibs/arm64-v8a/"
cp "${SDK_STAGE}/native/libs/x86_64/libopencv_java4.so" "${OPENCV_SDK_DIR}/src/main/jniLibs/x86_64/"
cp -R "${SDK_STAGE}/java/src/org" "${OPENCV_SDK_DIR}/src/main/java/"
if [ -d "${SDK_STAGE}/java/res" ]; then
    cp -R "${SDK_STAGE}/java/res/"* "${OPENCV_SDK_DIR}/src/main/res/"
fi

# Ensure CameraBridgeViewBase styled attributes XML is always present and valid
mkdir -p "${OPENCV_SDK_DIR}/src/main/res/values"
if [ ! -s "${OPENCV_SDK_DIR}/src/main/res/values/attrs.xml" ] || ! grep -q '<declare-styleable name="CameraBridgeViewBase">' "${OPENCV_SDK_DIR}/src/main/res/values/attrs.xml"; then
    cat << 'EOF' > "${OPENCV_SDK_DIR}/src/main/res/values/attrs.xml"
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <declare-styleable name="CameraBridgeViewBase">
        <attr name="show_fps" format="boolean" />
        <attr name="camera_id" format="integer">
            <enum name="any" value="-1" />
            <enum name="back" value="99" />
            <enum name="front" value="98" />
        </attr>
    </declare-styleable>
</resources>
EOF
fi

if [ ! -s "${OPENCV_SDK_DIR}/src/main/res/values/attrs.xml" ]; then
    echo "FATAL: attrs.xml missing or empty in :opencv-sdk!"
    exit 1
fi
if ! grep -q '<declare-styleable name="CameraBridgeViewBase">' "${OPENCV_SDK_DIR}/src/main/res/values/attrs.xml"; then
    echo "FATAL: attrs.xml does not declare CameraBridgeViewBase!"
    exit 1
fi

ARM64_SO="${OPENCV_SDK_DIR}/src/main/jniLibs/arm64-v8a/libopencv_java4.so"
X86_64_SO="${OPENCV_SDK_DIR}/src/main/jniLibs/x86_64/libopencv_java4.so"
ARM64_SHA256="$(sha256sum "${ARM64_SO}" | awk '{print $1}')"
X86_64_SHA256="$(sha256sum "${X86_64_SO}" | awk '{print $1}')"

# Write completion marker with exact provenance metadata
cat << EOF > "${OPENCV_SDK_DIR}/src/main/.opencv-sdk-complete"
OpenCV=${OPENCV_VERSION}
OpenCV_SHA=${OPENCV_COMMIT_SHA}
Contrib_SHA=${OPENCV_CONTRIB_COMMIT_SHA}
NDK=${EXPECTED_NDK_VERSION}
STL=c++_static
ABIs=arm64-v8a,x86_64
ARM64_SHA256=${ARM64_SHA256}
X86_64_SHA256=${X86_64_SHA256}
GeneratedAt=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
Status=VERIFIED_COMPLETE
EOF

echo "✓ Wrote completion marker: ${OPENCV_SDK_DIR}/src/main/.opencv-sdk-complete"
echo "========================================================================"
echo "Successfully built, verified, and staged custom OpenCV 4.14.0 SDK"
echo "========================================================================"
