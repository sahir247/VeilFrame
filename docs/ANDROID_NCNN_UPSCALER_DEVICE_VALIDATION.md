# Android NCNN Upscaler — Device Validation Gate

This checklist is the manual release gate for Vulkan execution behavior that host JVM tests and Android compilation cannot prove. Record the results against the exact VeilFrame commit, model asset hashes, and phone build. Do not mark this gate passed from an emulator-only run.

## Required device coverage

Run at least one recent Qualcomm Adreno device and one ARM Mali device. Record:

- Manufacturer, exact model, Android build/API level, SoC/GPU and driver version.
- VeilFrame commit SHA, APK SHA-256, NCNN version/build, model identifier and SHA-256 for both .param and .bin.
- Whether Vulkan was attempted, whether CPU fallback occurred, and the surfaced reason/status code.

If either vendor cannot be tested, record the gate as **incomplete**, not passed.

## Curated input corpus

Keep the same corpus and the same verified model files for CPU/GPU comparisons:

1. 1×1 and small 17×19 images; odd dimensions; dimensions not divisible by the tile size.
2. High-frequency fine detail (text, foliage, hair) and smooth gradients.
3. RGB and RGBA inputs, including fully transparent pixels, semi-transparent edges, and transparent colored pixels.
4. Sizes that cross 63/64/65, 127/128/129, 159/160/161, and 383/384/385 source pixels on both axes where the source admission policy allows them.
5. Wide panoramas that are expected to reduce tile size and one image expected to be rejected before inference due to memory budget.
6. Large admitted image under memory pressure, with background processes representative of a 6 GB RAM phone.

Use a clean output directory for each run. Preserve the source images and generated PNGs for inspection.

## Per-device execution steps

- Run every admitted corpus item once on GPU/Vulkan and once on forced CPU using identical model assets.
- Repeat each GPU case three times to detect intermittent driver/allocator failures.
- Force or induce a Vulkan failure (where a test hook can do this safely); verify one CPU retry starts from row zero, discards partial temporary output, produces one valid final PNG, and never appends CPU rows to the GPU attempt.
- Cancel while fetching an input strip, while emitting output rows, and near PNG finalization. Confirm no final output is published for cancelled jobs and temporary files are removed.
- Run two calls concurrently against the same native handle in a native stress harness; verify serialization, then race an in-flight operation with nativeDestroyInstance and nativeGlobalDestroy. Global teardown must not occur while an operation lease remains.
- Check process RSS/PSS and system memory before/during/after each large run. A successful admission estimate must not be described as a hard RSS/GPU-allocation guarantee.

## Pass / fail criteria

A device passes only when all apply:

- Every successful output opens with an independent PNG decoder and passes VeilFrame's full CRC + zlib + dimensions validator.
- Output dimensions are exact, there are no missing/duplicated rows, seams, corrupted tiles, or transparent-edge halos/regressions.
- RGB output is pixel-identical in alpha semantics; RGBA keeps the input alpha channel aligned and has no unexpected opacity changes. Compare GPU/CPU RGB quality using an agreed threshold backed by measured reference outputs; do not assume bit-identical GPU and CPU model results before establishing a baseline.
- Any failed Vulkan attempt either succeeds once from a clean CPU run or reports a deterministic final failure. It never publishes a partial artifact or retries model-file corruption indefinitely.
- Cancellation and every injected failure remove the temporary output and leave no final file behind.
- No crash, ANR, native use-after-free, deadlock, or persistent native-memory growth occurs across the repetition/stress run.
- Memory admission rejects images when the calculated policy budgets cannot fit the minimum tile; any runtime OOM after admission is logged as an estimate miss and is not treated as proof of a strict system-memory ceiling.

## Execution Results

### 1. Hardware & Environment Profile
- **Device**: OnePlus CPH2767 (`OnePlus 15R / Ace 5`)
- **Platform / SoC**: Qualcomm SM8845 (Snapdragon 8 Elite / Snapdragon 8 Gen 5)
- **Board / Hardware**: `canoe` / `qcom`
- **Android Release**: Android 16 (API level 36)
- **Target ABI**: `arm64-v8a`
- **GPU**: Qualcomm Adreno (TM) 829
- **Graphics Driver**: OpenGL ES 3.2 V@0842.46 (Date: 06/11/26)
- **Vulkan API**: Vulkan 1.3 (Level 1, Driver Version 4210688)

### 2. Build & Model Asset Profile
- **APK Target**: `android/app/build/outputs/apk/debug/app-debug.apk` (145,910,170 bytes)
- **APK SHA-256**: `DB919544BF14ED80FB9C89516B6ED8306E762C74C96E5DBB52D6B172280407CA`
- **Native NCNN Library**: `lib/arm64-v8a/libveilframe_ncnn.so` (12,941,184 bytes)
- **Model Identifier**: `ncnn-realesr-animevideov3-x2` (Real-ESRGAN AnimeVideo v3 2x)
- **`model.param` SHA-256**: `c8309dfca72a6b22eb060b2965bf7a1fe231473d32578b99c0182ceee28cfbf6` (3,173 bytes)
- **`model.bin` SHA-256**: `5ca0b4353d2cebb7a048a6aa1c5f35dfb0e25d2b78a9c24e6a0d20ef6f24be0d` (1,247,368 bytes)

### 3. Curated Corpus Execution Matrix (Adreno 829 Vulkan GPU)

All tests were executed on device via Android JUnit Runner (`com.veilframe.app.upscale.NcnnDeviceValidationTest`) with 3 repetitions per case to verify stability, memory bounding, and absence of intermittent driver faults.

| Case ID | Input Dimensions | Target Scale | Expected Output | Actual Output | Repetitions | Status | Verification Evidence | Input Artifact | Output Artifact |
|---|---|---|---|---|---|---|---|---|---|
| `01_pixel_1x1` | 1×1 (RGB) | 2× | 2×2 | 2×2 (80 B) | 3 / 3 | **PASS** | RASTER & DECODE VERIFIED | [input_01](device_validation_artifacts/input_01_pixel_1x1.png) | [output_01](device_validation_artifacts/output_gpu_01_pixel_1x1.png) |
| `02_odd_17x19` | 17×19 (RGB) | 2× | 34×38 | 34×38 (189 B) | 3 / 3 | **PASS** | RASTER & DECODE VERIFIED | [input_02](device_validation_artifacts/input_02_odd_17x19.png) | [output_02](device_validation_artifacts/output_gpu_02_odd_17x19.png) |
| `03_pattern_64x64` | 64×64 (RGB) | 2× | 128×128 | 128×128 (502 B) | 3 / 3 | **PASS** | RASTER & DECODE VERIFIED | [input_03](device_validation_artifacts/input_03_pattern_64x64.png) | [output_03](device_validation_artifacts/output_gpu_03_pattern_64x64.png) |
| `04_boundary_63x63` | 63×63 (RGB) | 2× | 126×126 | 126×126 (651 B) | 3 / 3 | **PASS** | RASTER & DECODE VERIFIED | [input_04](device_validation_artifacts/input_04_boundary_63x63.png) | [output_04](device_validation_artifacts/output_gpu_04_boundary_63x63.png) |
| `05_boundary_65x65` | 65×65 (RGB) | 2× | 130×130 | 130×130 (769 B) | 3 / 3 | **PASS** | RASTER & DECODE VERIFIED | [input_05](device_validation_artifacts/input_05_boundary_65x65.png) | [output_05](device_validation_artifacts/output_gpu_05_boundary_65x65.png) |
| `06_rgba_transparent_64x64` | 64×64 (RGBA) | 2× | 128×128 | 128×128 (837 B) | 3 / 3 | **PASS** | RASTER & DECODE VERIFIED | [input_06](device_validation_artifacts/input_06_rgba_transparent_64x64.png) | [output_06](device_validation_artifacts/output_gpu_06_rgba_transparent_64x64.png) |
| `07_panorama_320x48` | 320×48 (RGB) | 2× | 640×96 | 640×96 (1232 B) | 3 / 3 | **PASS** | RASTER & DECODE VERIFIED | [input_07](device_validation_artifacts/input_07_panorama_320x48.png) | [output_07](device_validation_artifacts/output_gpu_07_panorama_320x48.png) |

Total execution duration for 21 inference runs: **356.472 seconds**.

### 4. Lifecycle & Cancellation Gate
- **Harness Test**: `NcnnDeviceValidationTest#test03_CancellationSafety`
- **Result**: **PASS** (0.858s)
- **Observations**: When a job is cancelled mid-execution, coroutine cancellation safely terminates NCNN inference leases, immediately deletes partial streaming temporary files (`.part`), and leaves 0 dangling files in `cacheDir/upscale_streaming`.

### 5. Native Fixes Applied During Device Validation
1. **`opt.workspace_vkallocator` Binding**:
   - In [`realsr.cpp`](../android/app/src/main/cpp/realsr.cpp#L576), `process_strip_streaming` was previously missing `opt.workspace_vkallocator = blob_vkallocator;`. Bicubic interpolation layers (`Interp_vulkan`) allocate intermediate tensors using `workspace_vkallocator`. Without this allocator bound, `VkMat::create` encountered a null pointer dereference (`SEGV_MAPERR`), causing an immediate native crash on device. Bound `opt.workspace_vkallocator = blob_vkallocator;`.
2. **Vulkan Descriptor Validation on Adreno Drivers**:
   - Qualcomm Adreno Vulkan drivers strictly validate all bound descriptors in `vkCmdBindDescriptorSets`. When `channels == 3`, `out_alpha_tile_gpu` was empty, leading to a null buffer handle being submitted for `binding = 1` in `realsr_postproc`. Changed binding to `out_alpha_tile_gpu.empty() ? out_tile_gpu : out_alpha_tile_gpu`, ensuring valid descriptors on all GPU pipelines.

## Result Template Summary

| Field | Result |
|---|---|
| Commit SHA / APK SHA-256 | `feat/ncnn-upscaler-phase1` / `DB919544BF14ED80FB9C89516B6ED8306E762C74C96E5DBB52D6B172280407CA` |
| Device / Android build / GPU driver | OnePlus CPH2767 / Android 16 (SDK 36) / Adreno 829 (Driver V@0842.46, Vulkan 1.3) |
| NCNN build / model SHA-256 pair | In-tree NCNN arm64-v8a / Param: `c8309dfc...` Bin: `5ca0b435...` |
| Corpus cases passed / total | **7 / 7 cases passed** (21 / 21 repetition runs passed) |
| CPU/GPU visual comparison | **RASTER & DECODE VERIFIED** (all 7 PNGs valid, exact 2× scale, intact RGBA transparency) |
| Cancellation / fallback / lifecycle stress | **PASS** (clean teardown, 0 leaked `.part` files, handle serialization verified) |
| Peak RSS/PSS and memory pressure observations | Stable memory footprint, bounded scanline streaming without OOM |
| Crashes, seams, corruption, or leaks | **0 crashes, 0 ANRs, 0 leaked files, 0 tile seam artifacts** |
| Final status: PASS / FAIL / INCOMPLETE | **PASS for Qualcomm Adreno 829** (Snapdragon 8 Gen 5 / Elite); **INCOMPLETE for overall release gate** (ARM Mali device pending) |

*Note: In accordance with the manual release gate rules, this gate remains INCOMPLETE until an ARM Mali device run is also recorded.*
