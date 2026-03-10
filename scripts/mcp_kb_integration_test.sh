#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/ragent}"
LOGIN_USERNAME="${LOGIN_USERNAME:-${USERNAME:-admin}}"
LOGIN_PASSWORD="${LOGIN_PASSWORD:-${PASSWORD:-admin}}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-120}"
DEEP_THINKING="${DEEP_THINKING:-false}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TRACE_SCRIPT="${SCRIPT_DIR}/trace_fullchain_smoke.sh"

run_case() {
  local case_name="$1"
  local question="$2"
  local expect_mcp_min="$3"
  local expect_mcp_max="$4"
  local expect_retrieve_channel_min="$5"
  local expect_retrieve_channel_max="$6"

  echo "=================================================="
  echo "[integration] case=${case_name}"
  echo "[integration] question=${question}"
  echo "--------------------------------------------------"

  BASE_URL="${BASE_URL}" \
  LOGIN_USERNAME="${LOGIN_USERNAME}" \
  LOGIN_PASSWORD="${LOGIN_PASSWORD}" \
  QUESTION="${question}" \
  DEEP_THINKING="${DEEP_THINKING}" \
  TIMEOUT_SECONDS="${TIMEOUT_SECONDS}" \
  EXPECT_REWRITE_MIN=1 \
  EXPECT_INTENT_MIN=1 \
  EXPECT_RETRIEVE_MIN=1 \
  EXPECT_MCP_MIN="${expect_mcp_min}" \
  EXPECT_MCP_MAX="${expect_mcp_max}" \
  EXPECT_RETRIEVE_CHANNEL_MIN="${expect_retrieve_channel_min}" \
  EXPECT_RETRIEVE_CHANNEL_MAX="${expect_retrieve_channel_max}" \
  EXPECT_GENERATE_MIN=1 \
  "${TRACE_SCRIPT}"
}

run_case "MCP_ONLY" "帮我在 Obsidian 里搜索 HashMap 相关笔记" "1" "" "0" "0"
run_case "KB_ONLY" "HashMap 的底层原理是什么？" "0" "0" "1" ""
run_case "MIXED" "帮我在 Obsidian 里搜索 HashMap 相关笔记，并说明 HashMap 的底层原理。" "1" "" "1" ""

echo "=================================================="
echo "[integration] PASS: MCP/KB integration matrix passed"
