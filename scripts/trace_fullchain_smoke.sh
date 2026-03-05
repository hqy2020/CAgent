#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/ragent}"
LOGIN_USERNAME="${LOGIN_USERNAME:-${USERNAME:-admin}}"
LOGIN_PASSWORD="${LOGIN_PASSWORD:-${PASSWORD:-admin}}"
QUESTION="${QUESTION:-HashMap 的底层原理是什么？}"
DEEP_THINKING="${DEEP_THINKING:-false}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-120}"
EXPECT_REWRITE_MIN="${EXPECT_REWRITE_MIN:-1}"
EXPECT_INTENT_MIN="${EXPECT_INTENT_MIN:-1}"
EXPECT_RETRIEVE_MIN="${EXPECT_RETRIEVE_MIN:-1}"
EXPECT_MCP_MIN="${EXPECT_MCP_MIN:-0}"
EXPECT_MCP_MAX="${EXPECT_MCP_MAX:-}"
EXPECT_RETRIEVE_CHANNEL_MIN="${EXPECT_RETRIEVE_CHANNEL_MIN:-0}"
EXPECT_RETRIEVE_CHANNEL_MAX="${EXPECT_RETRIEVE_CHANNEL_MAX:-}"
EXPECT_GENERATE_MIN="${EXPECT_GENERATE_MIN:-1}"
CURL_COMMON_ARGS=(--noproxy "*" -sS --max-time "${TIMEOUT_SECONDS}")

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

LOGIN_BODY="$TMP_DIR/login.json"
SSE_BODY="$TMP_DIR/sse.txt"
RUNS_BODY="$TMP_DIR/runs.json"
NODES_BODY="$TMP_DIR/nodes.json"

assert_min() {
  local name="$1"
  local actual="$2"
  local expected="$3"
  if [ "${actual}" -lt "${expected}" ]; then
    echo "[trace-smoke] FAIL: ${name}=${actual} < expected_min=${expected}"
    return 1
  fi
  return 0
}

assert_max() {
  local name="$1"
  local actual="$2"
  local expected="$3"
  if [ -z "${expected}" ]; then
    return 0
  fi
  if [ "${actual}" -gt "${expected}" ]; then
    echo "[trace-smoke] FAIL: ${name}=${actual} > expected_max=${expected}"
    return 1
  fi
  return 0
}

echo "[trace-smoke] login: ${BASE_URL}/auth/login"
curl "${CURL_COMMON_ARGS[@]}" \
  -X POST "${BASE_URL}/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${LOGIN_USERNAME}\",\"password\":\"${LOGIN_PASSWORD}\"}" >"${LOGIN_BODY}"

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
  echo "[trace-smoke] login failed: cannot extract token"
  cat "${LOGIN_BODY}"
  exit 1
fi

echo "[trace-smoke] request SSE: ${BASE_URL}/rag/v3/chat"
curl "${CURL_COMMON_ARGS[@]}" -N -G "${BASE_URL}/rag/v3/chat" \
  --data-urlencode "question=${QUESTION}" \
  --data-urlencode "deepThinking=${DEEP_THINKING}" \
  -H "Accept: text/event-stream" \
  -H "Authorization: ${TOKEN}" >"${SSE_BODY}"

META_JSON="$(awk '
  $0=="event:meta" {meta=1; next}
  meta && /^data:/ {
    sub(/^data:[[:space:]]*/, "", $0);
    print;
    exit
  }
' "${SSE_BODY}")"

if [ -z "${META_JSON}" ]; then
  echo "[trace-smoke] no meta event in SSE output"
  cat "${SSE_BODY}"
  exit 1
fi

CONVERSATION_ID=""
TASK_ID=""
if command -v jq >/dev/null 2>&1; then
  CONVERSATION_ID="$(printf '%s' "${META_JSON}" | jq -r '.conversationId // empty')"
  TASK_ID="$(printf '%s' "${META_JSON}" | jq -r '.taskId // empty')"
else
  CONVERSATION_ID="$(printf '%s' "${META_JSON}" | sed -n 's/.*"conversationId":"\([^"]*\)".*/\1/p' | head -n 1)"
  TASK_ID="$(printf '%s' "${META_JSON}" | sed -n 's/.*"taskId":"\([^"]*\)".*/\1/p' | head -n 1)"
fi

if [ -z "${TASK_ID}" ] && [ -z "${CONVERSATION_ID}" ]; then
  echo "[trace-smoke] cannot extract taskId/conversationId from meta event"
  echo "${META_JSON}"
  exit 1
fi

RUN_QUERY="${BASE_URL}/rag/traces/runs?current=1&size=1"
if [ -n "${TASK_ID}" ]; then
  RUN_QUERY="${RUN_QUERY}&taskId=${TASK_ID}"
elif [ -n "${CONVERSATION_ID}" ]; then
  RUN_QUERY="${RUN_QUERY}&conversationId=${CONVERSATION_ID}"
fi

echo "[trace-smoke] query trace run: ${RUN_QUERY}"
curl "${CURL_COMMON_ARGS[@]}" \
  -H "Authorization: ${TOKEN}" \
  "${RUN_QUERY}" >"${RUNS_BODY}"

TRACE_ID=""
if command -v jq >/dev/null 2>&1; then
  TRACE_ID="$(jq -r '.data.records[0].traceId // empty' "${RUNS_BODY}")"
else
  TRACE_ID="$(sed -n 's/.*"traceId":"\([^"]*\)".*/\1/p' "${RUNS_BODY}" | head -n 1)"
fi

if [ -z "${TRACE_ID}" ]; then
  echo "[trace-smoke] no trace run found"
  cat "${RUNS_BODY}"
  exit 1
fi

echo "[trace-smoke] query trace nodes: ${BASE_URL}/rag/traces/runs/${TRACE_ID}/nodes"
curl "${CURL_COMMON_ARGS[@]}" \
  -H "Authorization: ${TOKEN}" \
  "${BASE_URL}/rag/traces/runs/${TRACE_ID}/nodes" >"${NODES_BODY}"

NODE_TEXT="$(cat "${NODES_BODY}")"

rewrite_count="$(printf '%s' "${NODE_TEXT}" | grep -c '"nodeType":"REWRITE"' || true)"
intent_count="$(printf '%s' "${NODE_TEXT}" | grep -c '"nodeType":"INTENT"' || true)"
retrieve_count="$(printf '%s' "${NODE_TEXT}" | grep -c '"nodeType":"RETRIEVE"' || true)"
mcp_count="$(printf '%s' "${NODE_TEXT}" | grep -c '"nodeType":"MCP"' || true)"
retrieve_channel_count="$(printf '%s' "${NODE_TEXT}" | grep -c '"nodeType":"RETRIEVE_CHANNEL"' || true)"
generate_count="$(printf '%s' "${NODE_TEXT}" | grep -Ec '"nodeType":"LLM_ROUTING"|"nodeType":"LLM_PROVIDER"' || true)"

echo "[trace-smoke] traceId=${TRACE_ID}"
echo "[trace-smoke] stage counts: rewrite=${rewrite_count}, intent=${intent_count}, retrieve=${retrieve_count}, mcp=${mcp_count}, retrieve_channel=${retrieve_channel_count}, generate=${generate_count}"

if ! assert_min "rewrite" "${rewrite_count}" "${EXPECT_REWRITE_MIN}" ||
   ! assert_min "intent" "${intent_count}" "${EXPECT_INTENT_MIN}" ||
   ! assert_min "retrieve" "${retrieve_count}" "${EXPECT_RETRIEVE_MIN}" ||
   ! assert_min "mcp" "${mcp_count}" "${EXPECT_MCP_MIN}" ||
   ! assert_max "mcp" "${mcp_count}" "${EXPECT_MCP_MAX}" ||
   ! assert_min "retrieve_channel" "${retrieve_channel_count}" "${EXPECT_RETRIEVE_CHANNEL_MIN}" ||
   ! assert_max "retrieve_channel" "${retrieve_channel_count}" "${EXPECT_RETRIEVE_CHANNEL_MAX}" ||
   ! assert_min "generate" "${generate_count}" "${EXPECT_GENERATE_MIN}"; then
  echo "[trace-smoke] FAIL: trace stage assertions not met"
  cat "${NODES_BODY}"
  exit 1
fi

echo "[trace-smoke] PASS"
