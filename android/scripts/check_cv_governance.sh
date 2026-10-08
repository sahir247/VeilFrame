#!/usr/bin/env bash
# ============================================================================
# CV Governance Ratchet (CV_RELIABILITY_UPGRADE_PLAN, Phase B/C)
#
# Enforces the hard-won invariants of v2.3.0:
#   1. Every production consumer of com.veilframe.app.cv.* must reference
#      CvRuntime (native-availability gate / governed engine) — except the
#      explicitly exempted frame/persistence/render paths below.
#   2. Heavy CV work (BitmapBridge/Mat pipelines) must not run on raw
#      Dispatchers.IO without engine.submit governance.
#
# Runs in CI (tier4-matrix-oracle job) on every push/PR. Fails the build on
# regressions; new exemptions must be added here deliberately, in review.
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."
SRC="app/src/main/java/com/veilframe/app"

# Deliberate exemptions (documented in CV_RELIABILITY_UPGRADE_PLAN §3):
EXEMPT=(
  "$SRC/VeilFrameApplication.kt"              # bootstrap call site itself
  "$SRC/qr/scanner/QrScanner.kt"              # frame-token pipeline + CvRuntime native gate
  "$SRC/qr/decoder/WeChatQrDecoder.kt"        # decode wrapper over WeChatQrEngine
  "$SRC/document/DocumentSession.kt"          # persistence only (DocumentMode enum)
  "$SRC/ui/views/DocumentQuadOverlayView.kt"  # rendering only (Point geometry)
)

status=0
while IFS= read -r f; do
  skip=0
  for e in "${EXEMPT[@]}"; do
    if [[ "$f" == "$e" ]]; then skip=1; break; fi
  done
  [[ $skip -eq 1 ]] && continue

  if ! grep -q "CvRuntime" "$f"; then
    echo "CV GOVERNANCE VIOLATION: $f references com.veilframe.app.cv.* but never CvRuntime (native gate / engine)."
    status=1
  fi

  if grep -q "BitmapBridge.toMat" "$f" && ! grep -q "engine.submit" "$f"; then
    echo "CV GOVERNANCE VIOLATION: $f runs BitmapBridge/Mat work without CvEngine.submit governance."
    status=1
  fi
done < <(grep -rl "com\.veilframe\.app\.cv\." "$SRC" --include='*.kt' \
  | grep -v "^$SRC/cv/" \
  | grep -v "^$SRC/upscale/" \
  | sort)
# NOTE: upscale/** is exempt from CvEngine governance BY DESIGN (ADR 0006 §2.7,
# fix plan §6): it is an ONNX stack, not OpenCV — it borrows cv.core utilities
# (MemInfoMemoryProbe, CvTelemetry) and is ratcheted separately below
# (typed-failure taxonomy) plus the ThermalGovernor wiring check.

# Upscaler streaming pipeline is engine-adjacent by design (ONNX, not OpenCV),
# but must keep its typed-failure taxonomy:
if ! grep -q "UpscaleMemoryException" "$SRC/upscale/inference/UpscaleInferenceEngine.kt" 2>/dev/null; then
  echo "CV GOVERNANCE VIOLATION: upscaler engine lost its typed OOM failure path."
  status=1
fi
if ! grep -q "ThermalGovernor" "$SRC/upscale/ui/ImageUpscalerController.kt" 2>/dev/null; then
  echo "CV GOVERNANCE VIOLATION: upscaler lost its ThermalGovernor wiring."
  status=1
fi

if [[ $status -eq 0 ]]; then
  echo "CV governance ratchet: PASS"
fi
exit $status
