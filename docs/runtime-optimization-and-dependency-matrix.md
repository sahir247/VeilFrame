# Runtime optimization and dependency compatibility review

**Review date:** 2026-11-16  
**Baseline:** PR #43, branch `upgrade/android-toolchain-dependencies-2026-11`

This review is deliberately compatibility-led. A newer version is selected only when the complete toolchain and its runtime dependencies can be used together without relying on an untested preview.

## Selected Android build matrix

| Layer | Selected version | Reason |
|---|---:|---|
| Android Gradle Plugin | 9.3.3 | Latest stable patch in the AGP 9.3 line; KGP 2.4.21's published matrix lists 9.3.1 as its ceiling, so patch compatibility is verified by CI |
| Gradle wrapper | 9.5.0 | AGP 9.3.x minimum supported Gradle |
| Kotlin Gradle Plugin | 2.4.21 | Latest stable KGP in the selected compatibility range |
| JDK / Java target | 17 | POI 6.0.0 requires Java 17; retains app bytecode target |
| Compile SDK | 36 | Stable Android 16 SDK; API 37 preview was unnecessary and its CI package install was unreliable |
| Target SDK | 36 | Meets the current Google Play target API requirement; verify Android 16 behavior |
| minSdk | 26 | Retained: the selected ML Kit, AndroidX and LiteRT artifacts do not require API 30/32 |
| Android NDK | 27.2.12479018 (r27c) | Aligns app native compilation, custom OpenCV SDK build and maintained FFmpegKit 8.1.9 |
| CMake | 3.22.1 | Kept with the existing custom OpenCV build script; no CMake 4.x migration is bundled here |

KGP 2.4.21's published compatibility matrix lists AGP 9.3.1 as its maximum fully supported AGP version and Gradle 9.7 as its maximum Gradle version. AGP 9.3.3 is a patch release in the same 9.3 line and includes D8/R8 fixes; CI is verifying this patch-level pairing. AGP 9.5.1 is the newer stable AGP line, but it is outside KGP 2.4.21's published full-compatibility range. KGP 2.5 was not selected because the available release is not stable.

AGP 9's built-in Kotlin and new Android DSL defaults are not yet a clean fit for the external KGP 2.4.21 plugin. The project therefore sets `android.builtInKotlin=false` and `android.newDsl=false` as a compatibility bridge. Revisit these flags when the external KGP supports the new DSL or when the project deliberately migrates to AGP's built-in Kotlin compiler.

## Direct dependency inventory

Versions below reflect the selected stable dependency set as of the review date.

| Area | Dependency | Selected version | Compatibility / decision |
|---|---|---:|---|
| AndroidX | Core KTX | 1.19.1 | Current stable |
| AndroidX | AppCompat | 1.8.0 | Current stable |
| AndroidX | ConstraintLayout | 2.2.2 | Current stable |
| AndroidX | DynamicAnimation | 1.1.0 | Current stable |
| AndroidX | Lifecycle runtime / ViewModel | 2.12.0 | Keep the two artifacts aligned |
| AndroidX | Fragment KTX | 1.9.1 | Current stable |
| AndroidX | DocumentFile | 1.1.0 | Current stable |
| AndroidX | ViewPager2 | 1.1.0 | Latest stable line |
| AndroidX | WebKit | 1.18.0 | Current stable |
| AndroidX | ExifInterface | 1.4.2 | Current stable |
| AndroidX | Media3 ExoPlayer / UI | 1.11.1 | Keep modules aligned |
| AndroidX | CameraX | 1.6.2 | Keep CameraX artifacts aligned |
| Kotlin | Coroutines Android / Test | 1.11.0 | Keep runtime and test artifacts aligned |
| ML Kit | Bundled barcode scanning | 17.3.0 | Current bundled model; does not require a Play-services model download |
| ML Kit | Bundled text recognition | 16.0.1 | Retained; current bundled artifact |
| LiteRT | Core | 2.3.2 | Current CompiledModel runtime |
| LiteRT | GPU accelerator | 2.3.2 | Must match core exactly |
| FFmpegKit maintained | Full GPL | 8.1.9 | Uses NDK r27c; app NDK aligned to r27.2 |
| Apache POI | POI / OOXML | 6.0.0 | Current stable; Java 17 baseline |
| Apache POI | Full OOXML schemas | 6.0.0 | Retained for broad OOXML schema coverage and to avoid the prior lite-schema D8 class issue |
| Apache Commons | Compress | 1.28.0 | Matches POI 6.0.0 |
| Apache Commons | IO | 2.22.0 | Matches POI 6.0.0 |
| Bouncy Castle | bcprov / bcpkix / bcutil | 1.86 | All modules aligned; avoids the earlier mixed-version runtime linkage failure |
| ZXing | Core | 3.5.4 | Latest published stable |
| PDFBox Android | Tom Roush Android fork | 2.0.27.0 | Kept; Apache PDFBox 3.x is not a drop-in replacement for the Android fork |
| Smart Exception | smart-exception-java | 0.2.1 | Retained current artifact |
| JSON | org.json | 20260814 | Latest published stable at review date |
| jose4j | jose4j | 0.9.7 | Latest published stable at review date |
| Netty (build classpath constraint) | Netty modules | 4.2.20.Final | Keep modules on one version |
| Protocol Buffers (build classpath constraint) | protobuf-java | 4.36.2 | Latest stable runtime selected for the build classpath; CI must validate plugin compatibility |
| JDOM (build classpath constraint) | jdom2 | 2.0.6.1 | Latest published release |
| Tests | JUnit 4 | 4.13.2 | Current JUnit 4 line |
| Tests | Robolectric | 4.18 | Current stable |
| Tests | AndroidX Test JUnit | 1.3.0 | Current stable |
| Tests | Espresso Core | 3.7.0 | Current stable |

### Deliberately retained major-version boundaries

- **OpenCV 4.14.0:** OpenCV 5.1.0 is newer upstream, but the app consumes a custom Android SDK with WeChatQRCode/contrib integration and JNI code. A major OpenCV upgrade needs a separate API, ABI, native build and output-parity migration; it is not safe to slip into a dependency-only change.
- **PDFBox Android 2.0.27.0:** this is an Android-specific fork. Apache PDFBox 3.0.8 is not a direct replacement for the fork's package/API surface.
- **CMake 3.22.1:** kept with the existing OpenCV build recipe; moving to CMake 4.x is a separate native build compatibility change.
- **protobuf-java 4.36.2:** this is a buildscript classpath constraint, not an Android runtime dependency. CI must confirm the Gradle/AGP plugin graph accepts it; if a plugin requires a specific older runtime, prefer its compatible version rather than forcing a classpath conflict.

## Runtime optimization changes

### LiteRT inference

Both FairScan document segmentation and ColorOS Lift & Shift matting now use LiteRT 2.3.2 `CompiledModel`, rather than the legacy TFLite `Interpreter` / `GpuDelegate` API.

- The runtime is bundled locally. No Play-services inference dependency is introduced, preserving offline model availability.
- Each model owns a single-thread executor. Creation, warm-up, inference, tensor-buffer access and close remain on that thread to avoid GPU delegate/thread-affinity errors.
- Input and output TensorBuffers are created once and reused.
- One warm-up inference runs during model initialization, so first-use shader compilation is not charged to the first user frame.
- GPU is selected only when the bundled environment reports it available; failed GPU setup falls back to CPU.
- CPU fallback uses a bounded thread count and XNNPACK `xnnPackFlags=0x23` to use the transient indirection-buffer option.
- Environment compiler cache is enabled with Application context to reduce repeated accelerator compilation after process restarts.
- The FairScan suspend API now dispatches to its model executor instead of performing synchronous model work on the caller thread. Model initialization is also scheduled off-main.

### PDF thumbnail memory and responsiveness

- Process-wide bitmap cache capped at 32 MiB (rather than scaling to one-eighth of an arbitrarily large `largeHeap`).
- Thumbnail width clamped to 64–512 px and height bounded to 1024 px, preserving aspect ratio.
- At most two PDF thumbnail renders run concurrently.
- Cache is trimmed/cleared on Android memory-pressure callbacks.
- Loader stores Application context, not an Activity context.
- Coroutine cancellation is rethrown; an interrupted screen/task is not converted into an ordinary null thumbnail.
- An allocation failure clears the thumbnail cache and returns no thumbnail instead of retaining more bitmap memory.
- PDF flatten before/after previews now use a dynamic DPI capped at 1280 px on the longest edge instead of fixed 150 DPI, bounding preview bitmap memory for large-format pages.
- Flattened PDF bytes are read from the temporary output file on the IO worker before returning to Main; preview document/stream resources are closed with `use`, and abandoned preview bitmaps are recycled on cancellation.

### Native MatPool memory pressure

The app now trims idle pooled OpenCV Mats when Android reports that the UI is hidden or the process is under low memory. The pool's existing trim operation releases idle objects only; active leases are not forcibly released.

## What is not claimed

These changes are expected to improve reliability and reduce allocation churn, but they are **not yet proven to improve user-perceived speed**. In particular, GPU acceleration can vary by device and model operator support.

Before release, compare old and new implementations on the same physical devices:

1. Segmentation/matting output parity on identical input images, separately for CPU and GPU, using an explicit MAE/IoU tolerance.
2. Cold model initialization and warm p50/p95 inference latency, including tensor readback.
3. GPU-capable device and CPU-only device fallback behavior.
4. Peak PSS/native heap during repeated matting and long-document thumbnail scrolling.
5. Cancellation when leaving PDF tools and behavior after Android memory trim.
6. Android 16 targetSdk 36 edge-to-edge, system bars and navigation behavior.

CI compilation and tests are necessary but do not replace this device-level validation.


## Research sources

Version and compatibility checks were made against release documentation and artifact metadata available on 2026-11-16:

- [Kotlin Gradle Plugin compatibility matrix](https://kotlinlang.org/docs/gradle-configure-project.html) — supported Gradle and AGP ranges for KGP 2.4.21.
- [Android Gradle Plugin 9.3 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes) — AGP 9.3.3 D8/R8 fixes, Gradle 9.5 minimum, Build Tools 36.0.0 and JDK 17.
- [Android Gradle Plugin current releases](https://developer.android.com/reference/tools/gradle-api) — current AGP 9.5.1 and past 9.3.3 release line.
- [AndroidX stable release channel](https://developer.android.com/jetpack/androidx/versions/stable-channel) — Core, AppCompat, Fragment, CameraX, Media3 and WebKit release versions.
- [LiteRT for Android](https://developers.google.com/edge/litert/android) — CompiledModel as the modern CPU/GPU/NPU API; LiteRT 2.3.2 is latest and supports API 23.
- [LiteRT CompiledModel API reference](https://developers.google.com/edge/api/litert/kotlin/com/google/ai/edge/litert/CompiledModel) — model, tensor-buffer and accelerator APIs.
- [ML Kit release notes](https://developers.google.com/ml-kit/release-notes) and [bundled barcode scanning setup](https://developers.google.com/ml-kit/vision/barcode-scanning/android) — bundled model versions and API requirements.
- [FFmpegKit maintained releases](https://github.com/ffmpegkit-maintained/ffmpeg/releases) and [Maven Central artifact versions](https://central.sonatype.com/artifact/dev.ffmpegkit-maintained/ffmpeg-kit-full-gpl/versions) — 8.1.9 Android release built with NDK r27c.
- [Apache POI change history](https://poi.apache.org/changes.html) — POI 6.0.0 Java 17 baseline and aligned Commons/Bouncy Castle/XMLBeans dependency upgrades.
- [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878) — API 36 requirement for new apps and updates from 2026-08-31.
- [Maven Central org.json versions](https://central.sonatype.com/artifact/org.json/json/versions), [Netty BOM versions](https://central.sonatype.com/artifact/io.netty/netty-bom/versions), [Protobuf Java versions](https://central.sonatype.com/artifact/com.google.protobuf/protobuf-java/versions), [ZXing Core](https://central.sonatype.com/artifact/com.google.zxing/core/versions), and [jose4j](https://central.sonatype.com/artifact/org.bitbucket.b_c/jose4j) — selected artifact releases.
- [OpenCV upstream releases](https://github.com/opencv/opencv/releases) — major-version boundary considered before retaining the app's custom 4.14.0 SDK.
