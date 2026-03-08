#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UI_REGRESSION_DIR="$ROOT_DIR/scripts/ui-regression"
OUTPUT_DIR="${UI_OUTPUT_DIR:-$ROOT_DIR/output/playwright}"

UI_BASE_URL="${UI_BASE_URL:-http://127.0.0.1:5173}"
LOGIN_USERNAME="${LOGIN_USERNAME:-${USERNAME:-admin}}"
LOGIN_PASSWORD="${LOGIN_PASSWORD:-${PASSWORD:-admin}}"
PLAYWRIGHT_HEADLESS="${PLAYWRIGHT_HEADLESS:-true}"
UI_STEP_TIMEOUT_MS="${UI_STEP_TIMEOUT_MS:-120000}"

echo "[ui-regression] base url: ${UI_BASE_URL}"
echo "[ui-regression] output dir: ${OUTPUT_DIR}"

if ! command -v node >/dev/null 2>&1; then
  echo "[ui-regression] FAIL: node is required"
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "[ui-regression] FAIL: npm is required"
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

probe_ui() {
  local target="${UI_BASE_URL%/}/login"
  local status
  status="$(env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u all_proxy -u ALL_PROXY \
    NO_PROXY=localhost,127.0.0.1,::1 \
    curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' "$target" 2>/dev/null || true)"
  status="${status:-000}"
  if [ "$status" = "000" ]; then
    printf 'login page request error'
    return 0
  fi
  if [ "$status" -ge 500 ] 2>/dev/null; then
    printf 'login page unavailable (%s)' "$status"
    return 0
  fi
  return 1
}

UI_BLOCK_REASON=""
if UI_BLOCK_REASON="$(probe_ui)"; then
  echo "[ui-regression] blocked before browser launch: ${UI_BLOCK_REASON}"
else
  if [ ! -d "$UI_REGRESSION_DIR/node_modules/playwright" ]; then
    echo "[ui-regression] installing playwright dependencies..."
    (cd "$UI_REGRESSION_DIR" && npm install >/tmp/ragent-ui-regression-install.log 2>&1)
  fi

  if [ "${UI_AUTO_INSTALL_BROWSERS:-true}" = "true" ]; then
    echo "[ui-regression] ensuring playwright chromium is installed..."
    (cd "$UI_REGRESSION_DIR" && npx playwright install chromium >/tmp/ragent-ui-regression-browser-install.log 2>&1)
  fi
fi

cd "$ROOT_DIR"
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u all_proxy -u ALL_PROXY \
  NO_PROXY=localhost,127.0.0.1,::1 \
  UI_BASE_URL="$UI_BASE_URL" \
  LOGIN_USERNAME="$LOGIN_USERNAME" \
  LOGIN_PASSWORD="$LOGIN_PASSWORD" \
  UI_OUTPUT_DIR="$OUTPUT_DIR" \
  PLAYWRIGHT_HEADLESS="$PLAYWRIGHT_HEADLESS" \
  UI_STEP_TIMEOUT_MS="$UI_STEP_TIMEOUT_MS" \
  UI_BLOCK_REASON="$UI_BLOCK_REASON" \
  node "$UI_REGRESSION_DIR/run.mjs"
