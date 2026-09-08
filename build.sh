#!/usr/bin/env bash
#
# Root convenience build wrapper for Zero Launcher
# Delegates to Tauri App/build.sh
#

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$ROOT_DIR/Tauri App/build.sh" "$@"
