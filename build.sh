#!/usr/bin/env bash
# ==============================================================================
# VeilFrame — Multi-Target POSIX (Linux / macOS) Build & Packaging Script
# ==============================================================================
set -e

# Detect Operating System and CPU Architecture
UNAME_S="$(uname -s)"
UNAME_M="$(uname -m)"

case "${UNAME_S}" in
    Linux*)     PLATFORM="linux" ;;
    Darwin*)    PLATFORM="macos" ;;
    *)          PLATFORM="unknown" ;;
esac

case "${UNAME_M}" in
    x86_64|amd64)   ARCH="x86_64" ;;
    arm64|aarch64)  ARCH="arm64" ;;
    *)              ARCH="${UNAME_M}" ;;
esac

PKG_NAME="VeilFrame-${PLATFORM}-${ARCH}"

# Help message
show_help() {
    echo "VeilFrame Build System"
    echo "Usage: ./build.sh [OPTIONS|COMMAND]"
    echo ""
    echo "Commands:"
    echo "  clean      Clean previous build/dist artifacts"
    echo "  build      Build standalone binary with PyInstaller"
    echo "  test       Run GUI & CLI test suite"
    echo "  package    Create compressed tarball (${PKG_NAME}.tar.gz)"
    echo "  all        Run clean, build, test, and package (default)"
    echo ""
    echo "Options:"
    echo "  --clean    Equivalent to 'clean'"
    echo "  --build    Equivalent to 'build'"
    echo "  --test     Equivalent to 'test'"
    echo "  --package  Equivalent to 'package'"
    echo "  --help     Show this help message"
}

do_clean() {
    echo "==> Cleaning build artifacts..."
    rm -rf build dist release_package "${PKG_NAME}.tar.gz" *.egg-info
    echo "Clean complete."
}

do_test() {
    echo "==> Running test suite..."
    export QT_QPA_PLATFORM="offscreen"
    if command -v uv >/dev/null 2>&1; then
        uv run python -m unittest discover -s tests
    else
        python3 -m unittest discover -s tests
    fi
}

do_build() {
    echo "==> Building standalone binary for ${PLATFORM} (${ARCH})..."
    if command -v uv >/dev/null 2>&1; then
        echo "Using uv toolchain..."
        uv run pip install -r requirements.txt build pyinstaller
        uv run pyinstaller --noconfirm --clean VeilFrame.spec
    else
        echo "Using standard Python3 / pip..."
        python3 -m pip install --upgrade pip
        python3 -m pip install -r requirements.txt build pyinstaller
        python3 -m pip install -e .
        pyinstaller --noconfirm --clean VeilFrame.spec
    fi

    if [ -f "dist/VeilFrame" ]; then
        chmod +x dist/VeilFrame
        echo "==> Standalone binary generated at: dist/VeilFrame"
        # Validate binary architecture
        if command -v file >/dev/null 2>&1; then
            echo "Binary details: $(file dist/VeilFrame)"
        fi
        # Run doctor probe
        echo "==> Running self-test doctor probe..."
        ./dist/VeilFrame doctor --json
    else
        echo "ERROR: Expected output binary dist/VeilFrame was not found!"
        exit 1
    fi
}

do_package() {
    echo "==> Packaging ${PKG_NAME}.tar.gz..."
    if [ ! -f "dist/VeilFrame" ]; then
        echo "Binary not built yet. Running build first..."
        do_build
    fi

    rm -rf release_package
    mkdir -p release_package
    cp dist/VeilFrame release_package/
    cp README.md LICENSE release_package/
    
    tar -czvf "dist/${PKG_NAME}.tar.gz" -C release_package VeilFrame README.md LICENSE
    rm -rf release_package

    echo ""
    echo "========================================================"
    echo "Package successfully generated at: dist/${PKG_NAME}.tar.gz"
    echo "========================================================"
}

# Main CLI dispatch
ACTION="${1:-all}"

case "${ACTION}" in
    clean|--clean)
        do_clean
        ;;
    build|--build)
        do_build
        ;;
    test|--test)
        do_test
        ;;
    package|--package)
        do_package
        ;;
    all)
        do_clean
        do_build
        do_test
        do_package
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        echo "Unknown option: ${ACTION}"
        show_help
        exit 1
        ;;
esac
