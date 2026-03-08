#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v node >/dev/null 2>&1; then
  echo "[api-regression] FAIL: node is required"
  exit 1
fi

cd "$ROOT_DIR"
node "$ROOT_DIR/scripts/benchmarks/api_regression.mjs"
