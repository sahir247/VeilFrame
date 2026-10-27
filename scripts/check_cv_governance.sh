#!/usr/bin/env bash
set -euo pipefail
# Forwarder script so running `bash scripts/check_cv_governance.sh` from repo root works identically to CI
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$DIR/../android/scripts/check_cv_governance.sh" "$@"
