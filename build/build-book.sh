#!/usr/bin/env bash
# Linux/CI build entry for *Firefly for Java by Example*. No DYLD shim (on Linux
# the cairo/pango shared libs resolve via the normal loader). Resolves the repo
# root, prefers the project venv at build/.venv, and falls back to system
# python3. Pass-through args go to build.py (e.g. --config book.es.yaml).
set -euo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_PY="${REPO_DIR}/build/.venv/bin/python"
if [ -x "${VENV_PY}" ]; then
  PY="${VENV_PY}"
else
  PY="$(command -v python3)"
fi
exec "${PY}" "${REPO_DIR}/build/build.py" "$@"
