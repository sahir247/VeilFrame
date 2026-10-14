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

## Result template

| Field | Result |
|---|---|
| Commit SHA / APK SHA-256 | |
| Device / Android build / GPU driver | |
| NCNN build / model SHA-256 pair | |
| Corpus cases passed / total | |
| CPU/GPU visual comparison | |
| Cancellation / fallback / lifecycle stress | |
| Peak RSS/PSS and memory pressure observations | |
| Crashes, seams, corruption, or leaks | |
| Final status: PASS / FAIL / INCOMPLETE | |

Attach logs and image diff sheets to the PR. Until both Adreno and Mali results are recorded, the Vulkan hardware validation gate remains incomplete.
