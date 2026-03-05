#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/ragent}"
LOGIN_USERNAME="${LOGIN_USERNAME:-admin}"
LOGIN_PASSWORD="${LOGIN_PASSWORD:-admin}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-120}"
CURL_COMMON_ARGS=(--noproxy "*" -sS --max-time "${TIMEOUT_SECONDS}")

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# ==================== Login ====================
echo "[quality] login: ${BASE_URL}/auth/login"
LOGIN_BODY="$TMP_DIR/login.json"
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
  echo "[quality] login failed: cannot extract token"
  cat "${LOGIN_BODY}"
  exit 1
fi
echo "[quality] login OK"

# ==================== Test Questions ====================
QUESTIONS=(
  "Spring Boot 的默认端口号是多少？"
  "Spring Bean 的生命周期是怎样的？"
  "@Component 和 @Bean 有什么区别？"
  "详细讲解 Spring AOP 的实现原理和使用场景"
  "Spring 事务的传播行为有哪些？@Transactional 注解失效的常见原因是什么？"
)

TYPES=(
  "简单事实"
  "流程/步骤"
  "对比/区别"
  "详细解释"
  "多子问题"
)

EXPECT_MIN=(100 400 300 600 500)
EXPECT_MAX=(500 1000 1600 1500 1600)

extract_response_text() {
  local sse_file="$1"
  if command -v jq >/dev/null 2>&1; then
    grep '^data:' "$sse_file" \
      | sed 's/^data://' \
      | jq -Rr 'fromjson? | select(.type == "response") | (.content // .delta // empty)' 2>/dev/null \
      | tr -d '\n' || true
  else
    grep '^data:' "$sse_file" \
      | sed 's/^data://' \
      | sed -n 's/.*"content":"\([^"]*\)".*/\1/p; s/.*"delta":"\([^"]*\)".*/\1/p' \
      | tr -d '\n' || true
  fi
}

count_chars() {
  local text="$1"
  echo -n "$text" | wc -m | tr -d ' '
}

# ==================== Run Tests ====================
echo ""
echo "========================================================"
echo "  RAG 回答质量测试 — 5 题对比"
echo "========================================================"
echo ""

RESULTS=()
PASS_COUNT=0
TOTAL=${#QUESTIONS[@]}

for i in "${!QUESTIONS[@]}"; do
  q="${QUESTIONS[$i]}"
  t="${TYPES[$i]}"
  idx=$((i + 1))

  echo "[${idx}/${TOTAL}] 发送: ${q}"
  SSE_FILE="$TMP_DIR/sse_${idx}.txt"

  curl "${CURL_COMMON_ARGS[@]}" -N -G "${BASE_URL}/rag/v3/chat" \
    --data-urlencode "question=${q}" \
    --data-urlencode "deepThinking=false" \
    -H "Accept: text/event-stream" \
    -H "Authorization: ${TOKEN}" >"${SSE_FILE}" 2>/dev/null || true

  RESPONSE_TEXT="$(extract_response_text "${SSE_FILE}")"
  CHAR_COUNT="$(count_chars "${RESPONSE_TEXT}")"
  MIN="${EXPECT_MIN[$i]}"
  MAX="${EXPECT_MAX[$i]}"

  if [ "${CHAR_COUNT}" -ge "${MIN}" ] && [ "${CHAR_COUNT}" -le "${MAX}" ]; then
    STATUS="PASS"
    PASS_COUNT=$((PASS_COUNT + 1))
  elif [ "${CHAR_COUNT}" -lt "${MIN}" ]; then
    STATUS="SHORT"
  else
    STATUS="LONG"
  fi

  RESULTS+=("${idx}|${t}|${CHAR_COUNT}|${MIN}-${MAX}|${STATUS}")

  PREVIEW="${RESPONSE_TEXT:0:80}"
  echo "  -> ${CHAR_COUNT} 字 [期望 ${MIN}-${MAX}] ${STATUS}"
  echo "  -> 前80字: ${PREVIEW}..."
  echo ""

  # Save full response for review
  echo "${RESPONSE_TEXT}" > "$TMP_DIR/answer_${idx}.txt"

  # Brief pause between requests
  sleep 1
done

# ==================== Summary Table ====================
echo "========================================================"
echo "  结果汇总"
echo "========================================================"
printf "%-4s %-12s %8s %12s %6s\n" "#" "类型" "实际字数" "期望范围" "结果"
echo "--------------------------------------------------------"
for row in "${RESULTS[@]}"; do
  IFS='|' read -r num typ cnt rng sts <<< "$row"
  printf "%-4s %-12s %8s %12s %6s\n" "$num" "$typ" "$cnt" "$rng" "$sts"
done
echo "--------------------------------------------------------"
echo "通过: ${PASS_COUNT}/${TOTAL}"
echo ""

if [ "${PASS_COUNT}" -eq "${TOTAL}" ]; then
  echo "[quality] ALL PASS"
  exit 0
else
  echo "[quality] SOME TESTS OUTSIDE EXPECTED RANGE (review answers above)"
  exit 0
fi
