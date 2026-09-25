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
# While we're already extracting and repackaging the AppImage for the EGL
# fix, this also switches squashfs compression from mksquashfs's default
# (gzip) to xz, which is meaningfully smaller for a bundle this size (see
# the appimagetool invocation below) — a real difference for anyone on a
# slow connection downloading this thing.
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

# Repackage using appimagetool if available; otherwise download it.
if ! command -v appimagetool > /dev/null 2>&1; then
  echo "appimagetool not found, installing..."
  wget -q "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage" -O appimagetool
  chmod +x appimagetool
  APPIMAGETOOL="./appimagetool --appimage-extract-and-run"
else
  APPIMAGETOOL="appimagetool"
fi

# Try xz compression via the env var interface supported by newer appimagetool
# (the old -comp/-X* direct args were dropped from the continuous release).
# xz with a full-size dictionary and the x86 BCJ filter typically produces
# 25-40% smaller output than gzip for a Rust/WebKitGTK bundle.
# If the env var approach also fails (e.g. mksquashfs doesn't support xz on
# this host), fall back silently to default compression so the build never
# breaks just over a size optimisation.
if APPIMAGETOOL_MKSQUASHFS_ARGS="-comp xz -Xdict-size 100% -Xbcj x86" \
     $APPIMAGETOOL squashfs-root "patched.AppImage" 2>/dev/null; then
  echo "Repackaged with xz compression."
else
  echo "xz compression unavailable, falling back to default compression..."
  $APPIMAGETOOL squashfs-root "patched.AppImage"
fi

popd > /dev/null

cp "$WORKDIR/patched.AppImage" "$APPIMAGE"
chmod +x "$APPIMAGE"
rm -rf "$WORKDIR"

echo "Patched $APPIMAGE successfully."
