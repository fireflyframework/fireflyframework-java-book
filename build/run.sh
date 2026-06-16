#!/usr/bin/env bash
# macOS build wrapper: ensures Homebrew's cairo/pango are loadable (the DYLD
# shim), then runs the build inside the isolated venv at build/.venv. Pass-through
# args go to build.py (e.g. --config book.es.yaml). For Linux/CI use build-book.sh
# (no DYLD shim needed there).
set -euo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BREW_PREFIX="$(brew --prefix 2>/dev/null || echo /opt/homebrew)"
export DYLD_FALLBACK_LIBRARY_PATH="${BREW_PREFIX}/lib:/usr/local/lib:${DYLD_FALLBACK_LIBRARY_PATH:-}"
exec "${REPO_DIR}/build/.venv/bin/python" "${REPO_DIR}/build/build.py" "$@"
