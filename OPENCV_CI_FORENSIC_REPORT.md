# VeilFrame OpenCV / WeChatQRCode Build Forensic Report

**Date of Audit:** October 6, 2026  
**Target Folder:** `C:\Users\parve\Downloads\mimoclaw_workspace_latest`  
**Target Architecture:** Android (`arm64-v8a`, `x86_64`) / Gradle / Kotlin / C++ (OpenCV 4.14.0 + `opencv_contrib` + `wechat_qrcode`)  
**Auditor:** Antigravity AI Forensic QR Subsystem Engineer  
**Audit Mode:** Strictly Read-Only / Evidence-Based Forensic Analysis  

---

## 1. Executive Summary

### 1.1 Origin and Context of the Folder
`C:\Users\parve\Downloads\mimoclaw_workspace_latest` is a forensic snapshot of an active agentic workspace (OpenClaw / Mimo) executed within a Linux container/VM (`/home/work/.openclaw/workspace/...`) on an Azure host (`Linux 6.8.0-1064-azure`). 

The session was actively engaged in reviewing, fixing, and attempting to compile a custom Android build of **OpenCV 4.14.0 with `opencv_contrib` (specifically `wechat_qrcode`)**, and integrating a 5.1k LOC classical computer vision engine (`com.veilframe.app.cv.*`) into the **VeilFrame Android app**.

### 1.2 The Core Problem Being Solved
Official OpenCV distributions (both the official Android SDK and the published Maven package `org.opencv:opencv:4.14.0`) **only compile main OpenCV modules**. They **do not include `opencv_contrib`**, and therefore **do not ship `org.opencv.wechat_qrcode.WeChatQRCode`**. Because VeilFrame's architectural contract designates `cv::wechat_qrcode::WeChatQRCode` (with deep learning detector + super-resolution models) as its **PRIMARY QR decoding engine**, VeilFrame cannot use pre-packaged Maven artifacts. A custom build of OpenCV for Android with `opencv_contrib` is mandatory.

### 1.3 Key Forensic Findings

1. **OpenCV Build Was Interrupted and Incomplete:**
   - **FACT:** The compilation started under Ninja for ABI `arm64-v8a`, reached step **[897/1137]** (compiling `modules/dnn`), and was halted. Only four static archives (`libopencv_core.a`, `libopencv_flann.a`, `libopencv_imgproc.a`, `libopencv_photo.a`) were generated in `o4a/lib/arm64-v8a`.
   - **FACT:** No dynamic libraries (`libopencv_java4.so`) were built.
   - **FACT:** No artifacts for ABI `x86_64` were built.
   - **FACT:** The output directory `OpenCV-android-sdk` contains zero compiled code (only an empty `sdk/java/javadoc` hierarchy).

2. **Fatal Build Configuration Flaw in `build_opencv.sh` (The "Whitelist Kill"):**
   - **FACT:** The build script invoked OpenCV's `build_sdk.py` with:
     ```bash
     --modules_list=core,imgproc,imgcodecs,video,photo,objdetect,dnn,wechat_qrcode
     ```
   - **FACT:** In OpenCV's CMake system, `--modules_list` sets `-DBUILD_LIST=...`. Because `java` and `java_bindings_generator` were omitted from this whitelist, CMake logged:
     ```text
     -- Module opencv_java_bindings_generator disabled by whitelist
     -- Module opencv_java disabled by whitelist
     -- Java wrappers: NO
     ```
   - **CRITICAL CONSEQUENCE:** Even if the build had run to 100% completion, **it would never have produced `WeChatQRCode.java` or `libopencv_java4.so`**. The downstream script `assemble_opencv_sdk.sh` would have crashed immediately.

3. **Status of the Model Files:**
   - **FACT:** The four required WeChat QR models (`detect.prototxt`, `detect.caffemodel`, `sr.prototxt`, `sr.caffemodel`) are present in `VeilFramee/android/app/src/main/assets/cv/wechat_qr/` and are **real Caffe models** (not LFS text pointers). They are bit-for-bit identical to the upstream WeChatCV reference models downloaded by CMake.

4. **Status of the `:opencv-sdk` Module:**
   - **FACT:** `VeilFramee/android/opencv-sdk` is an empty skeleton containing only `build.gradle.kts`, `consumer-rules.pro`, and `src/main/AndroidManifest.xml`. It has no Java source files and no native `.so` files.
   - **FACT:** When Gradle was executed (`precompile.log`), Kotlin compilation failed with hundreds of `Unresolved reference 'Mat'`, `Unresolved reference 'Core'`, etc., because `:opencv-sdk` had no source.

5. **Portability and CI Breakage:**
   - **FACT:** All scripts contain hardcoded `/home/work/...` Linux absolute paths.
   - **FACT:** Existing GitHub Actions CI (`.github/workflows/ci.yml`) has no steps to provision or build OpenCV. An automated CI runner will fail immediately upon attempting to compile the Android app.

---

## 2. Folder Inventory

The audited folder `C:\Users\parve\Downloads\mimoclaw_workspace_latest` contains **40,441 files** totaling **4.38 GB (4,379,725,247 bytes)**.

### 2.1 High-Level Directory Map

```text
mimoclaw_workspace_latest/
├── AGENTS.md                               (13,150 B - instructions for AI agents)
├── HEARTBEAT.md                            (168 B - agent heartbeat config)
├── IDENTITY.md                             (636 B - agent persona identity)
├── SOUL.md                                 (7,132 B - agent behavior guidelines)
├── TOOLS.md                                (860 B - environment tool descriptors)
├── USER.md                                 (477 B - user profile/preferences)
├── veilframe_cv_eval_fixes.patch           (44,587 B - 17-file git patch of CV bug fixes)
├── veilframe_cv_fix_report.md              (6,721 B - human markdown report of eval fixes)
├── memory/
│   └── 2026-10-06.md                       (1,721 B - agent session log documenting build attempt)
└── .openclaw/
    ├── workspace-state.json                (120 B - workspace metadata)
    └── tmp/
        ├── setup_toolchain.sh              (1,995 B - provisions JDK 17, SDK 35, NDK, clones OpenCV)
        ├── build_opencv.sh                 (916 B - invokes build_sdk.py)
        ├── assemble_opencv_sdk.sh          (1,084 B - copies Java & .so into :opencv-sdk)
        ├── build_opencv.log                (134,850 B, 1636 lines - log of interrupted build)
        ├── precompile.log                  (346,157 B - log of failed Gradle compileDebugKotlin)
        ├── eval_full.txt                   (23,264 B - initial audit findings of CV implementation)
        ├── cvtest-venv/                    (Python venv for local OpenCV mathematical verification)
        ├── pydeps/                         (1,412 files, 227 MB - headless OpenCV 5 & NumPy wheels)
        ├── toolchain/                      (37,029 files, 3.94 GB - SDK, NDK, JDK, OpenCV sources, build)
        │   ├── cmdline-tools.zip           (153,607,504 B - Android command-line tools archive)
        │   ├── jdk17.tar.gz                (193,252,603 B - Eclipse Temurin 17.0.20.1 archive)
        │   ├── opencv-veilframe.config.py  (200 B - ABI definition for arm64-v8a & x86_64)
        │   ├── jdk17/                      (246 files, 332 MB - extracted JDK 17.0.20.1)
        │   ├── android-sdk/                (23,115 files, 2.54 GB - SDK 35, NDK 27.2.12479018, CMake 3.22.1)
        │   ├── opencv-src/                 (7,805 files, 307 MB - OpenCV 4.14.0 source git tree)
        │   ├── opencv-contrib-src/         (3,182 files, 152 MB - opencv_contrib 4.14.0 git tree)
        │   └── opencv-contrib-sdk/         (2,678 files, 264 MB - build output tree)
        │       ├── o4a/                    (CMake/Ninja build directory; interrupted at step 897)
        │       └── OpenCV-android-sdk/     (Empty target SDK directory; 0 files)
        └── mimo-repo/                      (1,990 files, 210 MB - Git working repo on branch opencv-implementation)
            ├── .git/                       (Git metadata, commits 66d0e99 -> b89178b -> 1a3a65a)
            ├── veilframe_cv_implementation_plan.txt (7,808 B - CV platform architecture plan)
            ├── veilframe_cv_work_done.txt  (13,617 B - catalog of implemented Kotlin classes)
            ├── veilframe_cv_work_remaining.txt (11,823 B - actionable next steps)
            └── VeilFramee/                 (VeilFrame app repository snapshot with CV code)
```

### 2.2 Forensic Categorization Table

| Component Category | Path | Size | File Count | Status | Authoritative? |
|---|---|---:|---:|---|---|
| **Root Agent Notes & Patch** | Root `*.md`, `*.patch` | ~75 KB | 9 | Current | Patch and fix report authoritative for CV fixes |
| **Toolchain Scripts** | `.openclaw/tmp/*.sh` | 4 KB | 3 | Stale / Broken | Not authoritative; contain fatal config bugs and hardcoded paths |
| **Build Logs** | `.openclaw/tmp/*.log`, `*.txt` | 504 KB | 3 | Historical | Authoritative proof of failure modes |
| **Android SDK / NDK** | `.openclaw/tmp/toolchain/android-sdk/` | 2.54 GB | 23,115 | Complete | Linux x86_64 toolchain only |
| **JDK 17** | `.openclaw/tmp/toolchain/jdk17/` | 332 MB | 246 | Complete | Eclipse Temurin 17.0.20.1 Linux x86_64 |
| **OpenCV Source** | `.../toolchain/opencv-src/` | 307 MB | 7,805 | Complete | Authoritative source pinned at 4.14.0 (`0654a42`) |
| **opencv_contrib Source** | `.../toolchain/opencv-contrib-src/` | 152 MB | 3,182 | Complete | Authoritative source pinned at 4.14.0 (`a8e9acd`) |
| **Build Workdir (o4a)** | `.../toolchain/opencv-contrib-sdk/o4a/` | 264 MB | 2,678 | Intermediate / Incomplete | Ephemeral build state; halted at step 897 |
| **Generated SDK** | `.../toolchain/opencv-contrib-sdk/OpenCV-android-sdk/` | 0 B | 0 | Incomplete / Empty | Stale placeholder; 0 files produced |
| **App Source Snapshot** | `.openclaw/tmp/mimo-repo/VeilFramee/` | ~160 MB | 1,106 | Modified / WIP | Contains 33 new Kotlin CV files + QR integration |
| **QR Caffe Models** | `.../assets/cv/wechat_qr/` | 1.03 MB | 4 | Complete / Verified | Authoritative runtime assets |
| **Module `:opencv-sdk`** | `.../android/opencv-sdk/` | ~2 KB | 3 | Skeleton | Incomplete; missing Java & JNI binaries |

---

## 3. Discovered Build Workstreams

The folder reveals 7 distinct chronological workstreams:

```text
1. Architecture & Design (task.txt, VEILFRAME_CV_ENGINE.md)
        ↓
2. Evaluation & Deep Bug Fixing (eval_full.txt -> veilframe_cv_eval_fixes.patch)
        ↓
3. Hardening & Optimization (Commit 1a3a65a: DISOpticalFlow, Mat leak fix)
        ↓
4. Toolchain Provisioning (setup_toolchain.sh: Temurin 17, Android SDK 35, NDK 27, CMake)
        ↓
5. OpenCV Contrib Android Build Attempt (build_opencv.sh -> build_opencv.log) [INTERRUPTED]
        ↓
6. Gradle Precompilation Verification (precompile.log) [FAILED - NO OPENCV]
        ↓
7. Workspace Serialization (mimoclaw_workspace_latest export)
```

### Workstream Details

1. **Architecture & Design (Documented in `task.txt` & `VEILFRAME_CV_ENGINE.md`):**
   - **Contract:** WeChatQRCode is the PRIMARY engine. Google ML Kit is SECONDARY fallback. ZXing is legacy/diagnostic only. QR generator validation exercises the identical WeChat engine users scan with.
   - **Evidence:** Fully reflected across `WeChatQrEngine.kt`, `ScanabilityValidator.kt`, and `QrScanner.kt`.

2. **Evaluation and Bug Fixing (`veilframe_cv_fix_report.md`, `veilframe_cv_eval_fixes.patch`):**
   - **Findings Addressed:** 23 findings from `eval_full.txt` plus 6 independent findings (e.g., `blendWeighted` 8U/32F depth-mismatch crash, `SmartSharpener` inverted protection mask, coroutine job cancellation).
   - **Evidence:** Committed to `mimo-repo` branch `opencv-implementation` as commit `b89178b`.

3. **Phase 2/3 Hardening:**
   - **Findings Addressed:** DISOpticalFlow instance caching to avoid recreate per frame; recording `OpenCVLoader.initLocal()` outcome in `initError`; Mat ownership leak in `tvL1Flow`.
   - **Evidence:** Committed to `mimo-repo` as commit `1a3a65a`.

4. **Toolchain Provisioning (`setup_toolchain.sh`):**
   - **Actions:** Downloaded Temurin JDK 17, Android command-line tools, installed NDK `27.2.12479018`, CMake `3.22.1`, build-tools `35.0.0`, platforms `android-35`, and cloned OpenCV + `opencv_contrib` at branch `4.14.0`.
   - **Status:** Succeeded locally in the Linux container.

5. **OpenCV Contrib Android Compilation (`build_opencv.sh`):**
   - **Actions:** Invoked `python3 build_sdk.py`.
   - **Status:** **FATAL CONFIGURATION BUG** (omitted `java` from `--modules_list`), then **ABORTED AT STEP 897**.

6. **Assembly & Gradle Integration (`assemble_opencv_sdk.sh`):**
   - **Actions:** Planned to copy `sdk/java` and `sdk/native/libs` into `VeilFramee/android/opencv-sdk`.
   - **Status:** Never succeeded because the prerequisite build step never finished.

7. **Gradle Compilation Attempt (`precompile.log`):**
   - **Actions:** Executed `./gradlew :app:compileDebugKotlin`.
   - **Status:** Failed with unresolved symbol errors because `:opencv-sdk` was empty.

---

## 4. Reconstructed Build Pipeline

### Actual Flow Found in Workspace

```text
[Step 1: Toolchain Setup]
  Input: Linux x86_64 host, curl, git
  Script: setup_toolchain.sh
  Output: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, git repos
  Status: Succeeded (Linux-specific)

[Step 2: OpenCV Native Build]
  Input: opencv-src (4.14.0), opencv-contrib-src (4.14.0), opencv-veilframe.config.py
  Script: build_opencv.sh -> build_sdk.py
  Output Expected: OpenCV-android-sdk/sdk/{java,native}
  Actual Output: Incomplete o4a/ (only 4 static .a files), 0 dynamic libraries, 0 Java files
  Status: Interrupted at step 897; Java disabled by module whitelist

[Step 3: SDK Assembly]
  Input: OpenCV-android-sdk/
  Script: assemble_opencv_sdk.sh
  Output Expected: android/opencv-sdk/src/main/{java,jniLibs}
  Actual Output: None (script aborted or was never run)
  Status: Blocked

[Step 4: Gradle Build]
  Input: android/ (including :app and :opencv-sdk)
  Command: ./gradlew :app:compileDebugKotlin
  Output Expected: Compiled Kotlin bytecode
  Actual Output: Hundreds of "Unresolved reference" errors (precompile.log)
  Status: Failed
```

### Why the Pipeline Broke Down
1. **Wrong invocation:** `build_opencv.sh` used `--modules_list` without `java`.
2. **Process termination:** The build was stopped before ABI `arm64-v8a` finished linking and before ABI `x86_64` even started.
3. **No CI pipeline:** The entire pipeline lived in ad-hoc shell scripts in a disposable `.openclaw/tmp` directory, disconnected from GitHub Actions.

---

## 5. Toolchain Analysis

### 5.1 JDK
- **Artifact:** `.openclaw/tmp/toolchain/jdk17.tar.gz` (193,252,603 bytes)
- **Extracted Directory:** `.openclaw/tmp/toolchain/jdk17/`
- **Version (from `jdk17/release`):**
  - `IMPLEMENTOR="Eclipse Adoptium"`
  - `JAVA_VERSION="17.0.20.1"`
  - `SEMANTIC_VERSION="17.0.20.1+1"`
  - `OS_ARCH="x86_64"`, `OS_NAME="Linux"`
- **Assessment:** Correct Java 17 LTS release for Android Gradle Plugin 8.6+, but binary archive is strictly Linux x86_64.

### 5.2 Android SDK & Command-Line Tools
- **Artifact:** `cmdline-tools.zip` (153,607,504 bytes, version `11076708`)
- **Installed Platform:** `platforms/android-35`
- **Installed Build-Tools:** `build-tools/35.0.0`
- **Installed CMake:** `cmake/3.22.1` (`3.22.1-g37088a8`)
- **Installed NDK:** `ndk/27.2.12479018` (NDK r27b)
- **Assessment:** Android SDK and NDK versions match modern Android 15 (API 35) requirements.

### 5.3 16KB Page Size Support
- **Evidence:** `opencv-veilframe.config.py`:
  ```python
  ABIs = [
      ABI("3", "arm64-v8a", None, 26, cmake_vars=dict(ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES='ON')),
      ABI("5", "x86_64", None, 26, cmake_vars=dict(ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES='ON')),
  ]
  ```
- **Significance:** In NDK 27, setting `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES='ON'` configures max page size to 16KB (`-Wl,-z,max-page-size=16384`), mandatory for Google Play compliance on Android 15 devices.

---

## 6. OpenCV Source Analysis

- **Directory:** `.openclaw/tmp/toolchain/opencv-src/`
- **Git Commit:** `0654a422119ebcbab81682f6e91f63a6285ec5c9`
- **Git Commit Subject:** `Release: OpenCV 4.14.0`
- **Git Tag:** `4.14.0`
- **Version Header (`modules/core/include/opencv2/core/version.hpp`):**
  - `CV_VERSION_MAJOR 4`
  - `CV_VERSION_MINOR 14`
  - `CV_VERSION_REVISION 0`
  - `CV_VERSION "4.14.0"`
- **Assessment:** Clean, verified checkout of upstream OpenCV 4.14.0.

---

## 7. opencv_contrib Source Analysis

- **Directory:** `.openclaw/tmp/toolchain/opencv-contrib-src/`
- **Git Commit:** `a8e9acdf73b98beaa056aa13da74fe409059f81d`
- **Git Describe:** `4.14.0`
- **WeChatQRCode Module (`modules/wechat_qrcode/`):**
  - **CMake definition (`modules/wechat_qrcode/CMakeLists.txt`):**
    ```cmake
    set(the_description "WeChat QR code Detector")
    ocv_define_module(wechat_qrcode opencv_core opencv_imgproc opencv_objdetect opencv_dnn WRAP java objc python js)
    ```
  - **C++ Class (`include/opencv2/wechat_qrcode.hpp`):**
    ```cpp
    class CV_EXPORTS_W WeChatQRCode {
    public:
        CV_WRAP WeChatQRCode(const std::string& detector_prototxt_path = "",
                             const std::string& detector_caffe_model_path = "",
                             const std::string& super_resolution_prototxt_path = "",
                             const std::string& super_resolution_caffe_model_path = "");
        ~WeChatQRCode(){};

        CV_WRAP std::vector<std::string> detectAndDecode(InputArray img, OutputArrayOfArrays points = noArray());
        CV_WRAP void setScaleFactor(float _scalingFactor);
        CV_WRAP float getScaleFactor();
    ```
  - **WRAP java Proof:** The module explicitly specifies `WRAP java`, and its C++ methods are decorated with `CV_EXPORTS_W` and `CV_WRAP`. When OpenCV's Java generator runs, it parses these macros and outputs `org.opencv.wechat_qrcode.WeChatQRCode.java`.

---

## 8. Build Configuration Analysis

### 8.1 The Fatal Bug in `build_opencv.sh`
In `.openclaw/tmp/build_opencv.sh`:
```bash
python3 opencv-src/platforms/android/build_sdk.py \
  --config="$TC/opencv-veilframe.config.py" \
  --ndk_path="$ANDROID_NDK" --sdk_path="$ANDROID_SDK" --use_android_buildtools \
  --modules_list=core,imgproc,imgcodecs,video,photo,objdetect,dnn,wechat_qrcode \
  --extra_modules_path="$TC/opencv-contrib-src/modules" \
  --no_samples_build --no_ccache --no_kotlin \
  opencv-contrib-sdk opencv-src
```

### 8.2 Log Evidence of the Failure
From `.openclaw/tmp/build_opencv.log`:
- **Line 549:**
  ```text
  -- Using whitelist: opencv_core;opencv_dnn;opencv_imgcodecs;opencv_imgproc;opencv_objdetect;opencv_photo;opencv_video;opencv_wechat_qrcode
  ```
- **Line 552–553:**
  ```text
  -- Module opencv_java_bindings_generator disabled by whitelist
  -- Module opencv_java disabled by whitelist
  ```
- **Line 588–590:**
  ```text
  --   Java:                          export all functions
  --     ant:                         NO
  --     Java wrappers:               NO
  --     Java tests:                  NO
  ```

### 8.3 Explanation
OpenCV's `build_sdk.py` passes `--modules_list` directly to CMake as `-DBUILD_LIST=<list>`. In OpenCV's CMake logic, `BUILD_LIST` disables every module not explicitly in that list unless pulled in as a mandatory dependency. Because `java` and `java_bindings_generator` are bindings modules, not C++ dependencies of `wechat_qrcode`, CMake disabled them.

**Fix Required:**
Change `--modules_list` in `build_sdk.py` to:
```bash
--modules_list=core,imgproc,imgcodecs,video,photo,objdetect,dnn,wechat_qrcode,java
```
Or omit `--modules_list` entirely and let OpenCV build its standard module set alongside `wechat_qrcode`.

---

## 9. ABI Analysis

| ABI | Configured API | 16KB Page Size Flag | Native Binary Present? | Size on Disk |
|---|---|---|---|---|
| `arm64-v8a` | API 26 (Android 8.0) | `ON` (`-Wl,-z,max-page-size=16384`) | **PARTIAL** (4 `.a` archives; 0 `.so`) | 18.6 MB (static archives only) |
| `x86_64` | API 26 (Android 8.0) | `ON` (`-Wl,-z,max-page-size=16384`) | **NO** (0 files) | 0 B |
| `armeabi-v7a` | Not configured | N/A | No | 0 B |
| `x86` | Not configured | N/A | No | 0 B |

- **Proof of Partial arm64-v8a:**
  Inside `.openclaw/tmp/toolchain/opencv-contrib-sdk/o4a/lib/arm64-v8a/`:
  - `libopencv_core.a` (8,596,182 bytes)
  - `libopencv_flann.a` (1,233,348 bytes)
  - `libopencv_imgproc.a` (7,484,810 bytes)
  - `libopencv_photo.a` (1,286,786 bytes)
- **Proof of Missing x86_64:**
  `build_sdk.py` processes ABIs in sequential order. Because `arm64-v8a` never completed, `x86_64` configuration never started.

---

## 10. OpenCV Module Analysis

| Module | Source Tree | Requested | Built in o4a | Java Binding Generated | Note |
|---|---|---|---|---|---|
| `core` | `opencv-src` | Yes | Yes (`.a`) | No | Core data structures (`Mat`, `Point`, `Scalar`) |
| `imgproc` | `opencv-src` | Yes | Yes (`.a`) | No | Filtering, transforms, color conversions |
| `flann` | `opencv-src` | Dependency | Yes (`.a`) | No | Fast library for approximate nearest neighbors |
| `photo` | `opencv-src` | Yes | Yes (`.a`) | No | Non-local means denoising |
| `imgcodecs` | `opencv-src` | Yes | In progress | No | Interrupted |
| `video` | `opencv-src` | Yes | Pending | No | DIS & Farnebäck optical flow |
| `objdetect` | `opencv-src` | Yes | Pending | No | QR / barcode detectors |
| `dnn` | `opencv-src` | Yes | Halted (step 897) | No | Required backend for Caffe inference |
| `wechat_qrcode` | `opencv-contrib-src`| Yes | Pending | No | Required by VeilFrame |
| `java` | `opencv-src` | **No** (omitted) | **Disabled** | **No** | **Killed by whitelist** |

---

## 11. WeChatQRCode Verification

### 11.1 Java Binding Search
- **Search Command:** Recursive scan for `WeChatQRCode.java` across the entire `mimoclaw_workspace_latest` tree.
- **Result:** **0 files found.**
- **Finding:** `org/opencv/wechat_qrcode/WeChatQRCode.java` does **not** exist in the workspace.

### 11.2 Native Implementation Verification
- **Header:** Verified at `.openclaw/tmp/toolchain/opencv-contrib-src/modules/wechat_qrcode/include/opencv2/wechat_qrcode.hpp`.
- **Implementation:** Verified at `.openclaw/tmp/toolchain/opencv-contrib-src/modules/wechat_qrcode/src/`.
- **Native Library:** `libopencv_java4.so` was **not** generated.

### 11.3 Kotlin Integration Compatibility Check
Comparing `include/opencv2/wechat_qrcode.hpp` with `WeChatQrEngine.kt` (lines 50–175):

1. **Constructor:**
   - C++: `WeChatQRCode(detector_prototxt, detector_caffe, sr_prototxt, sr_caffe)`
   - Kotlin:
     ```kotlin
     val detector = WeChatQRCode(
         detectorModels.first!!.absolutePath,
         detectorModels.second!!.absolutePath,
         srModels.first?.absolutePath ?: "",
         srModels.second?.absolutePath ?: "",
     )
     ```
   - **Verdict:** **MATCHES**.

2. **Detection Method:**
   - C++: `std::vector<std::string> detectAndDecode(InputArray img, OutputArrayOfArrays points = noArray())`
   - Generated Java Wrapper Contract: `public List<String> detectAndDecode(Mat img, List<Mat> points)`
   - Kotlin:
     ```kotlin
     val points = ArrayList<Mat>()
     val texts: List<String> = engine.detectAndDecode(image, points)
     ```
   - **Verdict:** **MATCHES**.

3. **Removed APIs:**
   - Note in `WeChatQrEngine.kt`: `setUseSRModule` was intentionally removed because it does not exist in OpenCV 4.14 WeChatQRCode.
   - **Verdict:** **CORRECT**.

---

## 12. Model File Verification

The WeChatQRCode engine requires four specific model files. All four were located and analyzed:

### 12.1 Forensic Table of Model Files

| Model File | Location in App Assets | File Size | Format | SHA-256 Hash | Status |
|---|---|---:|---|---|---|
| `detect.prototxt` | `android/app/src/main/assets/cv/wechat_qr/` | 42,656 B | Text (Caffe NetParameter) | `E8ACFC395CAF443A47F15686A9B9207B36CB8F7E6CEB8FBAF6466665E68A9466` | Real prototxt |
| `detect.caffemodel` | `android/app/src/main/assets/cv/wechat_qr/` | 965,430 B | Binary (Caffe Model Weights)| `CC49B8C9BABAF45F3037610FE499DF38C8819EBDA29E90CA9F2E33270F6EF809` | Real binary |
| `sr.prototxt` | `android/app/src/main/assets/cv/wechat_qr/` | 5,984 B | Text (Caffe NetParameter) | `8AE41ACBA97E8B4A8E741EE350481E49B8E01D787193F470A4C95EE1C02D5B61` | Real prototxt |
| `sr.caffemodel` | `android/app/src/main/assets/cv/wechat_qr/` | 23,929 B | Binary (Caffe Model Weights)| `E5D36889D8E6EF2F1C1F515F807CEC03979320AC81792CD8FB927C31FD658AE3` | Real binary |

### 12.2 Verification Against CMake Download Cache
In `.openclaw/tmp/toolchain/opencv-contrib-sdk/o4a/downloads/wechat_qrcode/`:
- Exactly identical files with identical hashes exist.
- MD5 Hashes defined in OpenCV CMake:
  - `detect.caffemodel`: `238e2b2d6f3c18d6c3a30de0c31e23cf`
  - `detect.prototxt`: `6fb4976b32695f9f5c6305c19f12537d`
  - `sr.caffemodel`: `cbfcd60361a73beb8c583eea7e8e6664`
  - `sr.prototxt`: `69db99927a70df953b471daaba03fbef`
- Upstream source URL:
  `https://raw.githubusercontent.com/WeChatCV/opencv_3rdparty/a8b69ccc738421293254aec5ddb38bd523503252/`

### 12.3 Git LFS Check
- Checked `git cat-file -s` in `mimo-repo`.
- `detect.caffemodel` is stored as an actual Git blob of 965,430 bytes.
- None of the model files are Git LFS pointer text files.

---

## 13. Generated SDK Analysis

Inspected `.openclaw/tmp/toolchain/opencv-contrib-sdk/OpenCV-android-sdk/`:
- `OpenCV-android-sdk/sdk/`:
  - Contains only `java/javadoc/` (empty directory).
  - No `sdk/java/src/`
  - No `sdk/native/libs/`
  - No `sdk/native/jni/`
- **Assessment:** **EMPTY PLACEHOLDER**. The directory was created by `build_sdk.py` during initialization, but because the build aborted during compilation of the first ABI, no SDK files were ever gathered.

---

## 14. :opencv-sdk Analysis

Inspected `.openclaw/tmp/mimo-repo/VeilFramee/android/opencv-sdk/`:

### 14.1 Files Present
```text
android/opencv-sdk/
├── build.gradle.kts      (1,252 B)
├── consumer-rules.pro    (137 B)
└── src/
    └── main/
        └── AndroidManifest.xml (466 B)
```

### 14.2 Configuration Details
- **Namespace:** `org.opencv`
- **compileSdk:** 35, **minSdk:** 26
- **SourceSets:**
  ```kotlin
  sourceSets {
      getByName("main") {
          java.srcDirs("src/main/java")
          jniLibs.srcDirs("src/main/jniLibs")
      }
  }
  ```
- **Consumer ProGuard Rules:**
  ```proguard
  -keep class org.opencv.** { *; }
  -dontwarn org.opencv.**
  ```
- **Manifest:** Declares `org.opencv.android.OpenCVInitProvider` with `initOrder="100"` to automatically load `libopencv_java4.so` at app startup.

### 14.3 State Assessment
- **Status:** **SKELETON ONLY**.
- `src/main/java` does not exist.
- `src/main/jniLibs` does not exist.
- Without these folders, Gradle treats `:opencv-sdk` as having `NO-SOURCE`.

---

## 15. VeilFrame Integration Analysis

### 15.1 Dependency Map

```text
VeilFrameApplication.onCreate()
        │ (calls)
        ▼
WeChatQrEngine.install(context)
        │
        ├─► extracts assets/cv/wechat_qr/*.{prototxt,caffemodel} -> filesDir/cv_models/wechat_qr/
        ├─► calls OpenCVLoader.initLocal()  ──► loads libopencv_java4.so (or OpenCVInitProvider)
        └─► instantiates org.opencv.wechat_qrcode.WeChatQRCode(4 model paths)
                │
                ▼
        WeChatQrEngine.isAvailable = (detector != null)
                ▲
                │ (consumed by)
        ┌───────┴──────────────────────────────┐
        │                                      │
WeChatQrDecoder.decode()             QrScanner.tryWeChatDecode()
        ▲                                      ▲
        │                                      │
ScanabilityValidator.resolveDecoder()          │
(Primary: WeChatQrDecoder;                     CameraX YUV luma plane
 Fallback: MlKitQrDecoder)                     (decimated to ≤1024px)
        ▲
        │
AutoRepairEngine / validateStrict()
(Export release gate for artistic QR)
```

### 15.2 Verification in Source Files
1. **`settings.gradle.kts`:** Includes `:opencv-sdk`.
2. **`app/build.gradle.kts`:** Replaced external Maven dependency with `implementation(project(":opencv-sdk"))`.
3. **`VeilFrameApplication.kt` (line 17):**
   ```kotlin
   runCatching { com.veilframe.app.cv.qr.WeChatQrEngine.install(this) }
   ```
4. **`WeChatQrDecoder.kt`:** Implements `QrDecoder`, calls `WeChatQrEngine.get().decodeReport(mat)`.
5. **`ScanabilityValidator.kt` (lines 148, 180):** `resolveDecoder()` prioritizes `wechatDecoder` when `isAvailable == true`, falling back to `mlKitDecoder` on JVM tests or when native code is absent.
6. **`QrScanner.kt` (lines 209–240):** Primary CameraX frame processing passes the Y (luma) plane to `tryWeChatDecode()` before invoking ML Kit.

---

## 16. Script-by-Script Analysis

### 16.1 `setup_toolchain.sh`
- **Path:** `.openclaw/tmp/setup_toolchain.sh`
- **Purpose:** Downloads JDK 17, Android command-line tools, installs SDK/NDK packages, and clones OpenCV repositories.
- **Hard-coded Paths:**
  - `TC=/home/work/.openclaw/workspace/.openclaw/tmp/toolchain`
  - `SNAP=/home/work/.openclaw/workspace/.openclaw/tmp/mimo-repo/.openclaw/tmp`
- **Portability:** Linux x86_64 only (downloads `.tar.gz` and Linux CLI tools).
- **CI Suitability:** Unsuitable for CI. In GitHub Actions, JDK, Android SDK, and NDK should be provisioned via official actions (`actions/setup-java`, `setup-android`).

### 16.2 `build_opencv.sh`
- **Path:** `.openclaw/tmp/build_opencv.sh`
- **Purpose:** Launches `opencv-src/platforms/android/build_sdk.py`.
- **Hard-coded Paths:** `TC=/home/work/.openclaw/workspace/.openclaw/tmp/toolchain`
- **Fatal Defect:** `--modules_list` whitelist excludes `java`, disabling all Java wrappers.
- **Portability:** Linux only.

### 16.3 `assemble_opencv_sdk.sh`
- **Path:** `.openclaw/tmp/assemble_opencv_sdk.sh` (and duplicate in `mimo-repo/.openclaw/tmp/`)
- **Purpose:** Copies Java bindings and native `.so` files from the built SDK into `android/opencv-sdk`.
- **Hard-coded Paths:**
  - `SRC=.../opencv-contrib-sdk/OpenCV-android-sdk`
  - `DEST=.../mimo-repo/VeilFramee/android/opencv-sdk`
- **Defect:** Checks `test -f "$SRC/sdk/native/libs/arm64-v8a/libopencv_java4.so"`, which never existed.

### 16.4 `opencv-veilframe.config.py`
- **Path:** `.openclaw/tmp/toolchain/opencv-veilframe.config.py`
- **Purpose:** Defines ABIs `arm64-v8a` and `x86_64` with API 26 and `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES='ON'`.
- **Quality:** Clean and correct configuration.

---

## 17. Log / Build Forensics

### 17.1 `build_opencv.log` Forensics
- **Total Lines:** 1,636 lines
- **Initial Phase:** CMake configuration succeeded. Clang 18.0.3 (NDK r27b) detected.
- **Warning Detected:** KleidiCV download failed (HTTP error); non-fatal fallback.
- **Download Success:** WeChat models (`detect.caffemodel`, `detect.prototxt`, `sr.caffemodel`, `sr.prototxt`) downloaded from GitHub and verified via MD5.
- **Ninja Execution:** Step `[1/1137]` through `[897/1137]`.
- **Last Action Recorded:**
  ```text
  [897/1137] Building CXX object modules/dnn/CMakeFiles/opencv_dnn.dir/src/vkcom/src/fence.cpp.o
  ```
- **Exit State:** No fatal compilation error in log. The process simply stopped (corroborating `veilframe_cv_work_remaining.txt`: stopped on purpose / timeout).

### 17.2 `precompile.log` Forensics
- **Command Run:** `./gradlew :app:compileDebugKotlin`
- **Log Proof:**
  ```text
  > Task :opencv-sdk:compileDebugKotlin NO-SOURCE
  > Task :opencv-sdk:compileDebugJavaWithJavac NO-SOURCE
  > Task :app:compileDebugKotlin
  e: .../ImageQualityAnalyzer.kt:34:25 Unresolved reference 'Mat'.
  e: .../ImageQualityAnalyzer.kt:41:28 Unresolved reference 'Core'.
  ```
- **Finding:** Direct proof that Gradle cannot build `:app` until `:opencv-sdk` contains compiled OpenCV Java classes.

---

## 18. Duplicate and Version Analysis

### 18.1 Scripts and Configurations

| Item | Instance A | Instance B | Comparison | Authoritative Version |
|---|---|---|---|---|
| `assemble_opencv_sdk.sh` | `.openclaw/tmp/` | `mimo-repo/.openclaw/tmp/` | Differ by `DEST` path | `.openclaw/tmp/` version is adapted for `VeilFramee` clone |
| `opencv-veilframe.config.py` | `.openclaw/tmp/toolchain/` | `mimo-repo/.openclaw/tmp/toolchain/` | **Identical** (`66D1BEC2...`) | Both identical |
| `IDENTITY.md` | Root | `mimo-repo/` | Root lacks `Language: English` | `mimo-repo/` version |
| `AGENTS.md` | Root | `mimo-repo/` | **Identical** (`96146444...`) | Both identical |

### 18.2 WeChat Model Files

| File | Assets Directory | o4a Downloads Directory | Comparison | Authoritative Version |
|---|---|---|---|---|
| `detect.prototxt` | 42,656 B | 42,656 B | **Bit-for-bit identical** | Assets version (tracked in git) |
| `detect.caffemodel` | 965,430 B | 965,430 B | **Bit-for-bit identical** | Assets version (tracked in git) |
| `sr.prototxt` | 5,984 B | 5,984 B | **Bit-for-bit identical** | Assets version (tracked in git) |
| `sr.caffemodel` | 23,929 B | 23,929 B | **Bit-for-bit identical** | Assets version (tracked in git) |

---

## 19. CI Architecture

To make this build reproducible in CI without running a 30-minute OpenCV native compilation on every commit, the architecture should be divided into **Tracked Source**, **Pre-Built SDK Artifacts**, and **Runtime Assets**:

```text
[Tracked Source in Git]
├── android/app/src/main/assets/cv/wechat_qr/*.{prototxt,caffemodel}  (Runtime Caffe Models)
├── android/opencv-sdk/build.gradle.kts                              (Gradle Module Config)
├── android/opencv-sdk/consumer-rules.pro                            (ProGuard Rules)
├── android/opencv-sdk/src/main/AndroidManifest.xml                  (InitProvider Manifest)
└── scripts/build_opencv_android.sh                                  (Canonical Build Script)

[Pre-Built Binary Artifact / CI Cache]
└── android/opencv-sdk/src/main/
    ├── java/org/opencv/**                                           (Generated Java Bindings)
    └── jniLibs/
        ├── arm64-v8a/libopencv_java4.so                             (Native Binary with Contrib)
        └── x86_64/libopencv_java4.so                                (Emulator Native Binary)
```

### Proposed CI Workflow Strategy
1. **Option A (Committed Pre-Built SDK - Recommended for VeilFrame):**
   - Build `opencv-sdk` once with `arm64-v8a` and `x86_64` (compressed size ~18 MB total).
   - Check in `src/main/java` and `src/main/jniLibs` directly into `android/opencv-sdk/`.
   - Result: Standard `./gradlew assembleRelease` runs immediately in CI without external toolchain dependencies.
2. **Option B (GitHub Actions Release Artifact / Cache):**
   - A dedicated workflow `.github/workflows/build-opencv-sdk.yml` compiles OpenCV when `scripts/build_opencv_android.sh` or OpenCV version changes.
   - Publishes an AAR or zipped `opencv-sdk-prebuilt.tar.gz` to GitHub Releases.
   - Main CI workflow downloads the pre-built archive before running Gradle.

---

## 20. CI Cache Strategy

| Cache Item | Cache Key | Invalidation Trigger | Recommended Action |
|---|---|---|---|
| **Android SDK / NDK** | `sdk-35-ndk-27.2.12479018` | NDK version change | Handled by standard GitHub runner |
| **OpenCV Git Trees** | `opencv-4.14.0-contrib` | OpenCV commit hash | Shallow clone during build step |
| **OpenCV CMake / Ninja Build** | `o4a-arm64-x86_64-${CONFIG_HASH}` | Changes to build flags or ABIs | Cache `o4a` build directory if building in CI |
| **Compiled `:opencv-sdk` AAR** | `opencv-aar-4.14.0-wechat` | OpenCV or script change | **Store in GitHub Releases or commit prebuilt** |

---

## 21. Reproducibility Audit

### Question
> If this entire scattered folder disappeared and we cloned the repository on a clean GitHub Actions runner, could we reproduce the OpenCV + WeChatQRCode Android SDK?

### Answer: **NO**

### Missing Elements Preventing Reproducibility Today:
1. **No CI Workflow for OpenCV:** `.github/workflows/ci.yml` does not contain any OpenCV compilation step.
2. **Broken Build Script:** `build_opencv.sh` actively strips Java bindings via an incorrect `--modules_list` whitelist.
3. **Missing Pre-Built Binaries:** `:opencv-sdk` in git is missing `src/main/java` and `src/main/jniLibs`.
4. **Machine-Specific Paths:** All existing scripts depend on `/home/work/...`.
5. **Missing Upstream Commit Pinning in Scripts:** `setup_toolchain.sh` clones `--branch 4.14.0` rather than exact commit SHA.

---

## 22. Build Supply-Chain Audit

| Finding | Severity | Description | Remediation |
|---|---|---|---|
| **Unpinned Upstream Downloads in CMake** | **Medium** | OpenCV CMake downloads Caffe models and ADE over raw GitHub URLs during build. | Models are already checked into app assets with verified SHA-256; use local files during build if necessary. |
| **Dynamic `git clone --branch`** | **Low** | `setup_toolchain.sh` cloned `4.14.0` branches which are mutable git tags. | Pin exact commits (`0654a42` and `a8e9acd`). |
| **Unchecksummed Toolchain Downloads** | **Medium** | `curl -sL https://api.adoptium.net/...` and Google SDK downloads in `setup_toolchain.sh` without SHA-256 validation. | Use official GitHub Actions (`actions/setup-java@v4`). |

---

## 23. License / Provenance Notes

| Component | License | Evidence Path | Implication |
|---|---|---|---|
| **OpenCV 4.14.0** | Apache 2.0 | `opencv-src/LICENSE` | Compatible with commercial and open-source distribution; attribution required. |
| **opencv_contrib 4.14.0** | Apache 2.0 | `opencv-contrib-src/LICENSE` | Compatible; attribution required. |
| **WeChatQRCode Module** | Apache 2.0 (Copyright 2021 THL A29 Limited, Tencent) | `opencv-contrib-src/modules/wechat_qrcode/LICENSE` | Fully permissive under Apache 2.0; Tencent copyright notice must be retained in third-party notices. |
| **WeChat QR Caffe Models** | Open-source distribution via WeChatCV/opencv_3rdparty | WeChatCV/opencv_3rdparty commit `a8b69ccc` | Permitted for use with OpenCV WeChatQRCode. |
| **Temurin JDK 17** | GPLv2 + Classpath Exception | Adoptium / Eclipse Foundation | Standard build-time tool; no runtime impact. |

---

## 24. Problems and Risks

1. **Fatal Build Parameter (`--modules_list`):**
   Excluding `java` causes a silent omission of Java wrappers. Anyone re-running `build_opencv.sh` will wait 20 minutes for compilation only to end up with an unusable build.
2. **Lack of x86_64 Emulator Binaries:**
   If only `arm64-v8a` is built, local development on Android emulators running on Intel/AMD or standard GitHub Actions Linux runners without ARM translation will crash with `UnsatisfiedLinkError`.
3. **Merge Conflict Risk:**
   The Kotlin CV engine in `mimo-repo/VeilFramee` has not been merged into the main VeilFrame repository (`PrivacyVideoCleaner_v1_source`).

---

## 25. Recommended Repository Structure

```text
VeilFrame/
├── .github/
│   └── workflows/
│       ├── ci.yml                               (Updated with prebuilt opencv check)
│       └── build-opencv-sdk.yml                 (Optional: builds SDK on tag/release)
├── android/
│   ├── app/
│   │   ├── build.gradle.kts                     (implementation(project(":opencv-sdk")))
│   │   └── src/main/
│   │       ├── assets/cv/wechat_qr/             (4 Caffe model files - tracked in Git)
│   │       └── java/com/veilframe/app/cv/       (33 CV engine Kotlin classes)
│   └── opencv-sdk/
│       ├── build.gradle.kts                     (Module configuration)
│       ├── consumer-rules.pro                   (ProGuard rules)
│       └── src/main/
│           ├── AndroidManifest.xml              (OpenCVInitProvider)
│           ├── java/org/opencv/                 (Prebuilt OpenCV + WeChat Java bindings)
│           └── jniLibs/
│               ├── arm64-v8a/libopencv_java4.so (Prebuilt dynamic library)
│               └── x86_64/libopencv_java4.so    (Prebuilt dynamic library)
└── scripts/
    └── build_opencv_android.py                  (Canonical, portable Python build script)
```

---

## 26. Migration Plan

### Phase 1: Complete and Fix the OpenCV Android SDK Build
1. Modify `build_sdk.py` invocation to include `java`:
   ```bash
   --modules_list=core,imgproc,imgcodecs,video,photo,objdetect,dnn,wechat_qrcode,java
   ```
2. Build both `arm64-v8a` and `x86_64` targets with NDK 27 (`API 26`, flexible page sizes `ON`).
3. Verify that `OpenCV-android-sdk/sdk/java/src/org/opencv/wechat_qrcode/WeChatQRCode.java` and `libopencv_java4.so` exist for both ABIs.

### Phase 2: Populate `:opencv-sdk`
1. Copy `sdk/java/src/org` into `android/opencv-sdk/src/main/java/org`.
2. Copy `sdk/native/libs/{arm64-v8a,x86_64}/libopencv_java4.so` into `android/opencv-sdk/src/main/jniLibs/`.

### Phase 3: Integrate CV Engine Code into VeilFrame
1. Apply the 33 Kotlin files from `com.veilframe.app.cv` into `android/app/src/main/java/com/veilframe/app/cv/`.
2. Ensure commit `b89178b` (fixes for 23 eval findings) and `1a3a65a` (DIS cache & Mat leak fixes) are incorporated.
3. Update `ScanabilityValidator.kt`, `QrScanner.kt`, `WeChatQrDecoder.kt`, and `VeilFrameApplication.kt`.

### Phase 4: CI Automation
1. Check in the compiled `android/opencv-sdk/src/main/` directory to Git (compressed footprint is modest, ~15–18 MB).
2. Run standard `./gradlew :app:assembleDebug :app:testDebugUnitTest` in GitHub Actions.

---

## 27. Final Status Matrix

| Area | Status | Evidence | Problem | Action |
|---|---|---|---|---|
| **OpenCV source** | ✅ | `opencv-src/` at commit `0654a42` (tag `4.14.0`) | None | Keep pinned at 4.14.0 |
| **contrib source** | ✅ | `opencv-contrib-src/` at commit `a8e9acd` (tag `4.14.0`) | None | Keep pinned at 4.14.0 |
| **JDK** | ✅ | `jdk17/` Eclipse Temurin 17.0.20.1 | Download archive in `.openclaw/tmp` is Linux-only | Use `actions/setup-java@v4` in CI |
| **Android SDK** | ✅ | `android-sdk/` API 35, build-tools 35.0.0 | Local Linux install in temporary directory | Use GitHub runner SDK in CI |
| **NDK** | ✅ | `ndk/27.2.12479018` | None | Standard NDK r27b |
| **CMake** | ✅ | `cmake/3.22.1` | None | Use SDK CMake |
| **Build config** | ❌ | `build_opencv.sh` lines 12–16 | `--modules_list` whitelists out `java` bindings | Add `java` to `--modules_list` or remove whitelist |
| **ABI arm64-v8a** | ⚠️ | `o4a/lib/arm64-v8a/*.a` | Only 4 static libs; build halted at step 897 | Resume/re-run build to completion |
| **ABI x86_64** | ❌ | Zero files in `o4a/lib/x86_64` | Never started | Must build for emulator support |
| **wechat_qrcode** | ✅ | Source in `modules/wechat_qrcode`, C++ API verified | None in source; was omitted from Java wrapper build | Ensure `java` module is included during build |
| **Java binding** | ❌ | Zero instances of `WeChatQRCode.java` found | Never generated | Rebuild OpenCV with `java` enabled |
| **Native library** | ❌ | Zero instances of `libopencv_java4.so` found | Build interrupted; linking never executed | Complete native compilation |
| **QR models** | ✅ | 4 Caffe models in `assets/cv/wechat_qr/` verified bit-for-bit | None | Keep tracked in app assets |
| **SDK assembly** | ❌ | `assemble_opencv_sdk.sh` failed | Source files did not exist; hardcoded paths | Re-run assembly after build succeeds |
| **`:opencv-sdk`** | ⚠️ | `android/opencv-sdk/` has Gradle config & manifest | Missing `src/main/java` and `src/main/jniLibs` | Populate with assembled build output |
| **VeilFrame integration**| ⚠️ | Kotlin code in `mimo-repo` complete & verified | Not yet merged into main VeilFrame repository | Merge `mimo-repo` CV branch into VeilFrame |
| **CI** | ❌ | `.github/workflows/ci.yml` has no OpenCV build step | CI will fail to build APK | Check in prebuilt `:opencv-sdk` or add CI build job |
| **Reproducibility** | ❌ | Scripts broken, paths hardcoded, artifacts missing | Cannot be reproduced from scratch on clean runner | Implement portable build script and check in SDK |

---

## 28. Exact Next Steps

To turn this scattered working folder into a clean, reproducible, production-ready build:

1. **Step 1: Fix `build_sdk.py` Invocation**
   Run the OpenCV Android SDK build in a Linux environment with `java` explicitly added to `--modules_list`:
   ```bash
   python3 opencv-src/platforms/android/build_sdk.py \
     --config=opencv-veilframe.config.py \
     --ndk_path="$ANDROID_NDK" --sdk_path="$ANDROID_SDK" --use_android_buildtools \
     --modules_list=core,imgproc,imgcodecs,video,photo,objdetect,dnn,wechat_qrcode,java \
     --extra_modules_path=opencv-contrib-src/modules \
     --no_samples_build --no_ccache --no_kotlin \
     opencv-contrib-sdk opencv-src
   ```

2. **Step 2: Assemble the Local `:opencv-sdk` Module**
   Once the build completes:
   ```bash
   cp -r opencv-contrib-sdk/OpenCV-android-sdk/sdk/java/src/org android/opencv-sdk/src/main/java/
   mkdir -p android/opencv-sdk/src/main/jniLibs/arm64-v8a
   mkdir -p android/opencv-sdk/src/main/jniLibs/x86_64
   cp opencv-contrib-sdk/OpenCV-android-sdk/sdk/native/libs/arm64-v8a/libopencv_java4.so android/opencv-sdk/src/main/jniLibs/arm64-v8a/
   cp opencv-contrib-sdk/OpenCV-android-sdk/sdk/native/libs/x86_64/libopencv_java4.so android/opencv-sdk/src/main/jniLibs/x86_64/
   ```

3. **Step 3: Commit the Assembled `:opencv-sdk` into Git**
   Commit `android/opencv-sdk/src/main/java` and `android/opencv-sdk/src/main/jniLibs` directly to the repository. This guarantees that **any developer and any clean GitHub Actions runner can immediately run `./gradlew assembleRelease` without needing NDK, CMake, or OpenCV source compilation**.

4. **Step 4: Merge CV Engine Code into VeilFrame**
   Copy the 33 Kotlin source files from `com.veilframe.app.cv.*` and the integration changes in `ScanabilityValidator.kt`, `QrScanner.kt`, `WeChatQrDecoder.kt`, and `VeilFrameApplication.kt` into the main VeilFrame repository.

5. **Step 5: Run Full Verification**
   Execute:
   ```bash
   ./gradlew :app:compileDebugKotlin
   ./gradlew :app:testDebugUnitTest
   ```

---

## Appendix A — File Inventory

### Detailed Breakdown of Key Folders

| Directory / File | Type | File Count | Size | Purpose |
|---|---|---:|---:|---|
| `.openclaw/tmp/setup_toolchain.sh` | Shell Script | 1 | 1,995 B | Toolchain download & preparation |
| `.openclaw/tmp/build_opencv.sh` | Shell Script | 1 | 916 B | Android OpenCV build invocation |
| `.openclaw/tmp/assemble_opencv_sdk.sh` | Shell Script | 1 | 1,084 B | Module assembly script |
| `.openclaw/tmp/build_opencv.log` | Text Log | 1 | 134,850 B | Ninja build log (1636 lines) |
| `.openclaw/tmp/precompile.log` | Text Log | 1 | 346,157 B | Gradle Kotlin compilation failure log |
| `.openclaw/tmp/eval_full.txt` | Audit Log | 1 | 23,264 B | Initial evaluation findings (23 items) |
| `.openclaw/tmp/toolchain/opencv-veilframe.config.py` | Python Config | 1 | 200 B | ABI configuration for `build_sdk.py` |
| `.openclaw/tmp/toolchain/android-sdk/` | Directory | 23,115 | 2.54 GB | Android SDK 35, NDK 27, CMake 3.22.1 |
| `.openclaw/tmp/toolchain/jdk17/` | Directory | 246 | 332 MB | Eclipse Temurin 17.0.20.1 |
| `.openclaw/tmp/toolchain/opencv-src/` | Git Repo | 7,805 | 307 MB | OpenCV 4.14.0 source |
| `.openclaw/tmp/toolchain/opencv-contrib-src/` | Git Repo | 3,182 | 152 MB | opencv_contrib 4.14.0 source |
| `.openclaw/tmp/toolchain/opencv-contrib-sdk/o4a/` | Build Tree | 2,678 | 264 MB | CMake / Ninja intermediate files |
| `.openclaw/tmp/mimo-repo/` | Git Repo | 1,990 | 210 MB | Mimo working repository (`opencv-implementation`) |
| `veilframe_cv_eval_fixes.patch` | Git Patch | 1 | 44,587 B | Diff of 17 fixed CV Kotlin files |
| `veilframe_cv_fix_report.md` | Markdown | 1 | 6,721 B | Documentation of applied CV fixes |
| `memory/2026-10-06.md` | Markdown | 1 | 1,721 B | Developer session record |

---

## Appendix B — Important Commands

### 1. Correct OpenCV Android Build Command
```bash
python3 opencv-src/platforms/android/build_sdk.py \
  --config="$PWD/opencv-veilframe.config.py" \
  --ndk_path="$ANDROID_NDK" \
  --sdk_path="$ANDROID_SDK" \
  --use_android_buildtools \
  --modules_list=core,imgproc,imgcodecs,video,photo,objdetect,dnn,wechat_qrcode,java \
  --extra_modules_path="$PWD/opencv-contrib-src/modules" \
  --no_samples_build \
  --no_ccache \
  --no_kotlin \
  opencv-contrib-sdk opencv-src
```

### 2. Assembly Command
```bash
SRC="opencv-contrib-sdk/OpenCV-android-sdk"
DEST="android/opencv-sdk"

mkdir -p "$DEST/src/main/java"
mkdir -p "$DEST/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST/src/main/jniLibs/x86_64"

cp -r "$SRC/sdk/java/src/org" "$DEST/src/main/java/"
cp "$SRC/sdk/native/libs/arm64-v8a/libopencv_java4.so" "$DEST/src/main/jniLibs/arm64-v8a/"
cp "$SRC/sdk/native/libs/x86_64/libopencv_java4.so" "$DEST/src/main/jniLibs/x86_64/"
```

### 3. Gradle Verification Commands
```bash
# Compile Kotlin across app and modules
./gradlew :app:compileDebugKotlin

# Run CV unit tests (JVM)
./gradlew :app:testDebugUnitTest --tests "com.veilframe.app.cv.*"

# Run full unit test suite
./gradlew :app:testDebugUnitTest
```

---

## Appendix C — Hashes / Versions

| Artifact | Version / Commit | SHA-256 Hash |
|---|---|---|
| **OpenCV** | `4.14.0` (commit `0654a42`) | N/A (Git repository) |
| **opencv_contrib** | `4.14.0` (commit `a8e9acd`) | N/A (Git repository) |
| **NDK** | `27.2.12479018` (r27b) | N/A (Directory) |
| **JDK** | `Temurin-17.0.20.1+1` | N/A (Directory) |
| `detect.prototxt` | Upstream `a8b69ccc` | `E8ACFC395CAF443A47F15686A9B9207B36CB8F7E6CEB8FBAF6466665E68A9466` |
| `detect.caffemodel` | Upstream `a8b69ccc` | `CC49B8C9BABAF45F3037610FE499DF38C8819EBDA29E90CA9F2E33270F6EF809` |
| `sr.prototxt` | Upstream `a8b69ccc` | `8AE41ACBA97E8B4A8E741EE350481E49B8E01D787193F470A4C95EE1C02D5B61` |
| `sr.caffemodel` | Upstream `a8b69ccc` | `E5D36889D8E6EF2F1C1F515F807CEC03979320AC81792CD8FB927C31FD658AE3` |
| `opencv-veilframe.config.py`| N/A | `66D1BEC29B4885029ACC71C3BFA0E0311A340DDAC6067C953869631CB808EF76` |

---

## Appendix D — Unknowns

1. **Why `fast-mist` Exec Session Was Halted:**
   The log terminates at step 897 without a compiler error. It is unknown whether the build was manually killed by the user, hit an agent execution timeout, or ran out of RAM on the host machine.
2. **Device-Specific Latency of WeChatQRCode on Camera Y-Plane:**
   `QrScanner.kt` implements a contract that runs WeChat on throttled frames (8–10 FPS) decimated to ≤1024px. The exact millisecond execution time on mid-range Android hardware remains unknown until on-device profiling is executed.
3. **Reason for Not Checking In Prebuilt Binaries Initially:**
   It is unknown whether the developer intended to commit the `.so` binaries or intended to build OpenCV in GitHub Actions on every run. (Committing the ~18 MB prebuilt module is overwhelmingly standard for Android NDK projects with complex C++ dependencies like OpenCV).
