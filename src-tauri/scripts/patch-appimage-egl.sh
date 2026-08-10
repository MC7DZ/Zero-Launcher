#!/bin/bash
# Fixes: "Could not create default EGL display: EGL_BAD_PARAMETER. Aborting..."
#
# Root cause: the AppImage bundles its own libEGL / libwayland / libgbm
# libraries (pulled in by linuxdeploy during bundling). WebKitGTK initializes
# EGL against these bundled libs before any env vars are read, and on
# distros/GPUs where the bundled library versions don't match the host's
# kernel DRM/Mesa driver (very common on AMD Raven/Vega iGPUs, Arch/CachyOS,
# Fedora, etc.), eglGetPlatformDisplay fails with EGL_BAD_PARAMETER.
#
# Fix: delete the bundled EGL/Wayland/GBM libs from the AppImage so the
# dynamic linker falls back to the host system's real Mesa stack, which is
# guaranteed to match the host's actual kernel/GPU driver.
#
# Run this after `tauri build` produces the AppImage.

set -euo pipefail

APPIMAGE_DIR="src-tauri/target/release/bundle/appimage"
APPIMAGE=$(find "$APPIMAGE_DIR" -maxdepth 1 -name "*.AppImage" | head -n1)

if [ -z "$APPIMAGE" ]; then
  echo "No AppImage found in $APPIMAGE_DIR, skipping EGL patch."
  exit 0
fi

echo "Patching $APPIMAGE to drop bundled EGL/Wayland/GBM libs..."

WORKDIR=$(mktemp -d)
cp "$APPIMAGE" "$WORKDIR/app.AppImage"
chmod +x "$WORKDIR/app.AppImage"

pushd "$WORKDIR" > /dev/null
./app.AppImage --appimage-extract > /dev/null

# Remove the specific libs that conflict with host Mesa/EGL/Wayland.
# These are the ones repeatedly identified across Tauri AppImage EGL bug
# reports as the source of the mismatch.
find squashfs-root/usr/lib -maxdepth 1 \( \
  -name "libEGL.so*" -o \
  -name "libGL.so*" -o \
  -name "libgbm.so*" -o \
  -name "libwayland-client.so*" -o \
  -name "libwayland-egl.so*" -o \
  -name "libwayland-server.so*" \
\) -exec rm -fv {} \; || true

# Repackage using appimagetool if available; otherwise fall back to
# AppRun-based direct execution (handled by the wrapper, not needed here
# since we rebuild a proper AppImage below).
if ! command -v appimagetool > /dev/null 2>&1; then
  echo "appimagetool not found, installing..."
  wget -q "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage" -O appimagetool
  chmod +x appimagetool
  APPIMAGETOOL="./appimagetool --appimage-extract-and-run"
else
  APPIMAGETOOL="appimagetool"
fi

$APPIMAGETOOL squashfs-root "patched.AppImage"

popd > /dev/null

cp "$WORKDIR/patched.AppImage" "$APPIMAGE"
chmod +x "$APPIMAGE"
rm -rf "$WORKDIR"

echo "Patched $APPIMAGE successfully."
