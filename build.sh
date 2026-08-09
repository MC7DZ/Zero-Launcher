#!/usr/bin/env bash
#
# build.sh - Build script for Zero Launcher (Tauri app)
#
# Usage:
#   ./build.sh --deps      Install system dependencies (Rust, Node, WebKitGTK, etc.)
#   ./build.sh --release   Build the app in release mode (produces AppImage + deb)
#   ./build.sh --debug     Build the app in debug mode (faster, unoptimized)
#   ./build.sh --all       Run --deps then --release
#
# This script targets Debian/Ubuntu-based systems (apt). It installs a
# fixed, known-good WebKitGTK version by relying on Ubuntu 24.04's default
# package set, which avoids a WebKitGTK EGL/blank-screen regression present
# in some intermediate WebKitGTK releases used by other distros/versions.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# NO_STRIP avoids a linuxdeploy bug where its bundled `strip` binary fails
# on newer ELF sections (.relr.dyn) produced by current toolchains.
export NO_STRIP=1

log() {
    printf '\n\033[1;34m==>\033[0m %s\n' "$1"
}

err() {
    printf '\033[1;31mERROR:\033[0m %s\n' "$1" >&2
    exit 1
}

install_deps() {
    log "Updating package lists"
    sudo apt-get update

    log "Installing system dependencies"
    sudo apt-get install -y \
        libwebkit2gtk-4.1-dev \
        libgtk-3-dev \
        libayatana-appindicator3-dev \
        librsvg2-dev \
        patchelf \
        build-essential \
        curl \
        wget \
        file \
        fuse \
        libfuse2 \
        desktop-file-utils \
        ca-certificates

    if ! command -v node >/dev/null 2>&1; then
        log "Installing Node.js 20"
        curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
        sudo apt-get install -y nodejs
    else
        log "Node.js already installed: $(node --version)"
    fi

    if ! command -v cargo >/dev/null 2>&1; then
        log "Installing Rust toolchain"
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
        # shellcheck disable=SC1090
        source "$HOME/.cargo/env"
    else
        log "Rust already installed: $(rustc --version)"
    fi

    log "Installing frontend dependencies"
    npm install

    log "Dependencies installed successfully"
}

build_app() {
    local mode="$1"

    if [ ! -d node_modules ]; then
        log "node_modules missing, running npm install first"
        npm install
    fi

    if [ "$mode" = "release" ]; then
        log "Building Zero Launcher (release)"
        npm run tauri build
    elif [ "$mode" = "debug" ]; then
        log "Building Zero Launcher (debug)"
        npm run tauri build -- --debug
    else
        err "Unknown build mode: $mode"
    fi

    log "Build finished. Output files:"
    find src-tauri/target/*/bundle -type f \( -iname "*.AppImage" -o -iname "*.deb" \) 2>/dev/null || true
}

usage() {
    cat <<EOF
Usage: $0 [OPTION]

  --deps       Install system dependencies (Rust, Node, WebKitGTK, etc.)
  --release    Build the app in release mode
  --debug      Build the app in debug mode
  --all        Run --deps then --release
  -h, --help   Show this help message
EOF
}

if [ $# -eq 0 ]; then
    usage
    exit 1
fi

case "$1" in
    --deps)
        install_deps
        ;;
    --release)
        build_app "release"
        ;;
    --debug)
        build_app "debug"
        ;;
    --all)
        install_deps
        build_app "release"
        ;;
    -h|--help)
        usage
        ;;
    *)
        err "Unknown option: $1"
        ;;
esac
