#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BROWSERMCP_SERVER_COMMAND="${BROWSERMCP_SERVER_COMMAND:-npx -y @browsermcp/mcp}"
BROWSERMCP_PROBE_URL="${BROWSERMCP_PROBE_URL:-https://news.ycombinator.com/}"
BROWSERMCP_PROBE_TIMEOUT_MS="${BROWSERMCP_PROBE_TIMEOUT_MS:-30000}"
BROWSERMCP_EXTENSION_URL="${BROWSERMCP_EXTENSION_URL:-https://chromewebstore.google.com/detail/browser-mcp-automate-your/bjfgambnhccakkhmkepdoekmckoijdlc}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
PROBE_JSON="${TMP_DIR}/browsermcp-probe.json"

echo "[internet-connected] probe BrowserMCP connection ..."
if ! node "${ROOT_DIR}/scripts/mcp-bridge/call.mjs" \
  --serverCommand "${BROWSERMCP_SERVER_COMMAND}" \
  --tool browser_navigate \
  --args "{\"url\":\"${BROWSERMCP_PROBE_URL}\"}" \
  --timeoutMs "${BROWSERMCP_PROBE_TIMEOUT_MS}" >"${PROBE_JSON}" 2>/dev/null; then
  echo "[internet-connected] FAIL: bridge call failed"
  exit 1
fi

probe_success="false"
probe_is_error="false"
probe_error_code=""
probe_error_message=""
probe_text=""

if command -v jq >/dev/null 2>&1; then
  probe_success="$(jq -r '.success // false' "${PROBE_JSON}")"
  probe_is_error="$(jq -r '.result.isError // false' "${PROBE_JSON}")"
  probe_error_code="$(jq -r '.errorCode // empty' "${PROBE_JSON}")"
  probe_error_message="$(jq -r '.errorMessage // empty' "${PROBE_JSON}")"
  probe_text="$(jq -r '[.result.content[]?.text // empty] | join(" ")' "${PROBE_JSON}")"
else
  if grep -q '"success":true' "${PROBE_JSON}"; then
    probe_success="true"
  fi
  if grep -q '"isError":true' "${PROBE_JSON}"; then
    probe_is_error="true"
  fi
  probe_error_code="$(sed -n 's/.*"errorCode":"\([^"]*\)".*/\1/p' "${PROBE_JSON}" | head -n 1)"
  probe_error_message="$(sed -n 's/.*"errorMessage":"\([^"]*\)".*/\1/p' "${PROBE_JSON}" | head -n 1)"
  probe_text="$(sed -n 's/.*"text":"\([^"]*\)".*/\1/p' "${PROBE_JSON}" | head -n 1)"
fi

if [ "${probe_success}" != "true" ]; then
  echo "[internet-connected] FAIL: BrowserMCP probe failed"
  if [ -n "${probe_error_code}" ] || [ -n "${probe_error_message}" ]; then
    echo "[internet-connected] errorCode=${probe_error_code}, errorMessage=${probe_error_message}"
  else
    cat "${PROBE_JSON}"
  fi
  echo "[internet-connected] 请打开 BrowserMCP 扩展并点击 Connect 后重试"
  echo "[internet-connected] 扩展安装地址: ${BROWSERMCP_EXTENSION_URL}"
  exit 1
fi

if [ "${probe_is_error}" = "true" ] || [[ "${probe_text}" == *"No connection to browser extension"* ]]; then
  echo "[internet-connected] FAIL: BrowserMCP extension is not connected"
  echo "[internet-connected] detail: ${probe_text}"
  echo "[internet-connected] 请打开 BrowserMCP 扩展并点击 Connect 后重试"
  echo "[internet-connected] 扩展安装地址: ${BROWSERMCP_EXTENSION_URL}"
  exit 1
fi

echo "[internet-connected] running strict matrix ..."
INTERNET_REQUIRE_CONNECTED=true "${ROOT_DIR}/scripts/prompt_regression_matrix.sh"

echo "[internet-connected] running trace smoke (expect MCP>=1) ..."
QUESTION="帮我联网搜索一下今天 AI 领域的 3 条新闻" \
EXPECT_RETRIEVE_MIN=0 \
EXPECT_MCP_MIN=1 \
EXPECT_RETRIEVE_CHANNEL_MIN=0 \
"${ROOT_DIR}/scripts/trace_fullchain_smoke.sh"

echo "[internet-connected] running strict UI regression ..."
UI_REQUIRE_CONNECTED_INTERNET=true "${ROOT_DIR}/scripts/ui_prompt_regression.sh"

echo "[internet-connected] PASS"
