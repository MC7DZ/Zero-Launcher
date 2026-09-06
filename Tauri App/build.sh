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
# This script targets Debian/Ubuntu-based systems (apt) and is built/tested
# against Ubuntu 20.04 (glibc/webkit baseline chosen for AppImage
# compatibility with older distros — AppImages built here will also run
# fine on newer distros, since glibc is backward-compatible). Ubuntu 20.04
# ships libwebkit2gtk-4.0 rather than the 4.1 package used on 22.04+, so
# this script installs whichever of the two is available and builds
# against it accordingly (see WEBKIT_PKG below).

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
        libgtk-3-dev \
        librsvg2-dev \
        patchelf \
        build-essential \
        curl \
        wget \
        file \
        fuse \
        libfuse2 \
        desktop-file-utils \
        ca-certificates \
        pkg-config

    # libayatana-appindicator3-dev isn't in Ubuntu 20.04's default repos
    # (it landed in 22.04); fall back to the older libappindicator3-dev,
    # which provides the same pkg-config name Tauri looks for.
    if apt-cache show libayatana-appindicator3-dev >/dev/null 2>&1; then
        sudo apt-get install -y libayatana-appindicator3-dev
    else
        log "libayatana-appindicator3-dev not found, falling back to libappindicator3-dev (Ubuntu 20.04)"
        sudo apt-get install -y libappindicator3-dev
    fi

    # Tauri 2 (via wry) links against webkit2gtk-4.1, which only exists on
    # Ubuntu 22.04+. Ubuntu 20.04 ships webkit2gtk-4.0 instead — same
    # underlying WebKitGTK API, just an older soname/pkg-config name — so
    # on 20.04 we install the 4.0 dev package and add pkg-config alias
    # files so the 4.1 lookup resolves to it at build time.
    if apt-cache show libwebkit2gtk-4.1-dev >/dev/null 2>&1; then
        log "Installing libwebkit2gtk-4.1-dev"
        sudo apt-get install -y libwebkit2gtk-4.1-dev
    else
        log "libwebkit2gtk-4.1-dev not found, installing libwebkit2gtk-4.0-dev (Ubuntu 20.04) with a 4.1 pkg-config alias"
        sudo apt-get install -y libwebkit2gtk-4.0-dev

        PC_DIR="/usr/lib/x86_64-linux-gnu/pkgconfig"
        for pair in "webkit2gtk-4.0:webkit2gtk-4.1" "javascriptcoregtk-4.0:javascriptcoregtk-4.1"; do
            src="${pair%%:*}"
            dst="${pair##*:}"
            if [ -f "$PC_DIR/$src.pc" ] && [ ! -f "$PC_DIR/$dst.pc" ]; then
                sudo sed "s/${src}/${dst}/g" "$PC_DIR/$src.pc" | sudo tee "$PC_DIR/$dst.pc" >/dev/null
                log "Created pkg-config alias: $dst.pc -> $src.pc"
            fi
        done
    fi

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
        if [ -f "src-tauri/scripts/patch-appimage-egl.sh" ]; then
            log "Patching AppImage to avoid bundled-EGL host mismatch (EGL_BAD_PARAMETER fix)"
            chmod +x src-tauri/scripts/patch-appimage-egl.sh
            ./src-tauri/scripts/patch-appimage-egl.sh
        fi
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