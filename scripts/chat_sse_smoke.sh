#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/ragent}"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-admin}"
QUESTION="${QUESTION:-请简要介绍这个系统的主要能力}"
DEEP_THINKING="${DEEP_THINKING:-false}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-120}"
CURL_COMMON_ARGS=(--noproxy "*" -sS --max-time "${TIMEOUT_SECONDS}")

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

LOGIN_BODY="$TMP_DIR/login.json"
SSE_BODY="$TMP_DIR/sse.txt"

echo "[smoke] login: ${BASE_URL}/auth/login"
curl "${CURL_COMMON_ARGS[@]}" \
  -X POST "${BASE_URL}/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}" >"${LOGIN_BODY}"

TOKEN=""
if command -v jq >/dev/null 2>&1; then
  TOKEN="$(jq -r '.data.token // .data // empty' "${LOGIN_BODY}")"
else
  TOKEN="$(sed -n 's/.*"token":"\([^"]*\)".*/\1/p' "${LOGIN_BODY}" | head -n 1)"
  if [ -z "${TOKEN}" ]; then
    TOKEN="$(sed -n 's/.*"data":"\([^"]*\)".*/\1/p' "${LOGIN_BODY}" | head -n 1)"
  fi
fi

if [ -z "${TOKEN}" ]; then
  echo "[smoke] login failed: cannot extract token"
  cat "${LOGIN_BODY}"
  exit 1
fi

echo "[smoke] request SSE: ${BASE_URL}/rag/v3/chat"
curl "${CURL_COMMON_ARGS[@]}" -N -G "${BASE_URL}/rag/v3/chat" \
  --data-urlencode "question=${QUESTION}" \
  --data-urlencode "deepThinking=${DEEP_THINKING}" \
  -H "Accept: text/event-stream" \
  -H "Authorization: ${TOKEN}" >"${SSE_BODY}"

meta_count="$(grep -c '^event:meta$' "${SSE_BODY}" || true)"
message_count="$(grep -c '^event:message$' "${SSE_BODY}" || true)"
finish_count="$(grep -c '^event:finish$' "${SSE_BODY}" || true)"
done_count="$(grep -c '^event:done$' "${SSE_BODY}" || true)"

think_count="$(grep -c '"type":"think"' "${SSE_BODY}" || true)"
response_count="$(grep -c '"type":"response"' "${SSE_BODY}" || true)"

echo "[smoke] event counts: meta=${meta_count}, message=${message_count}, finish=${finish_count}, done=${done_count}"
echo "[smoke] message types: think=${think_count}, response=${response_count}"

if [ "${meta_count}" -lt 1 ] || [ "${message_count}" -lt 1 ] || [ "${finish_count}" -lt 1 ] || [ "${done_count}" -lt 1 ]; then
  echo "[smoke] SSE protocol assertion failed"
  echo "----- SSE output -----"
  cat "${SSE_BODY}"
  exit 1
fi

if [ "${DEEP_THINKING}" = "true" ]; then
  if [ "${think_count}" -lt 1 ]; then
    echo "[smoke] WARN: deepThinking=true but no think events received (model may not support reasoning)"
  else
    echo "[smoke] deep thinking verified: ${think_count} think events"
  fi
else
  if [ "${think_count}" -gt 0 ]; then
    echo "[smoke] FAIL: deepThinking=false but ${think_count} think events received"
    exit 1
  fi
fi

echo "[smoke] PASS"
