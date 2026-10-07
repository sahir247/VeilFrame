#!/usr/bin/env bash
set -e
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
chmod +x "${ROOT_DIR}/android/gradlew" 2>/dev/null || true
exec "${ROOT_DIR}/android/gradlew" -p "${ROOT_DIR}/android" "$@"
