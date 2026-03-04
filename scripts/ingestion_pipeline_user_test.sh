#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/ragent}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-180}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-2}"
SAMPLE_URL="${SAMPLE_URL:-https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-admin}"
CURL_COMMON_ARGS=(--noproxy "*" -sS --max-time "${TIMEOUT_SECONDS}")

if ! command -v jq >/dev/null 2>&1; then
  echo "[ingestion-smoke] jq 未安装，无法执行 JSON 断言"
  exit 1
fi

if ! lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "[ingestion-smoke] 后端未启动（8080 未监听），跳过真实用户测试"
  exit 2
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

AUTH_HEADER=()
if [ -n "${AUTH_TOKEN}" ]; then
  AUTH_HEADER=(-H "Authorization: ${AUTH_TOKEN}")
elif [ -n "${USERNAME}" ] && [ -n "${PASSWORD}" ]; then
  LOGIN_BODY="${TMP_DIR}/login.json"
  curl "${CURL_COMMON_ARGS[@]}" \
    -X POST "${BASE_URL}/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}" >"${LOGIN_BODY}"
  TOKEN="$(jq -r '.data.token // .data // empty' "${LOGIN_BODY}")"
  if [ -n "${TOKEN}" ] && [ "${TOKEN}" != "null" ]; then
    AUTH_HEADER=(-H "Authorization: ${TOKEN}")
  fi
fi

api_curl() {
  if [ "${#AUTH_HEADER[@]}" -gt 0 ]; then
    curl "${CURL_COMMON_ARGS[@]}" "${AUTH_HEADER[@]}" "$@"
  else
    curl "${CURL_COMMON_ARGS[@]}" "$@"
  fi
}

PIPELINE_PAYLOAD="${TMP_DIR}/pipeline.json"
jq --arg name "pdf-ingestion-smoke-$(date +%s)" '
  .name = $name
  | (.nodes[]? | .settings? | objects) |= del(.modelId)
' "/Users/openingcloud/IdeaProjects/ragent/docs/examples/pdf-pipeline-request.json" >"${PIPELINE_PAYLOAD}"

echo "[ingestion-smoke] 创建流水线"
PIPELINE_RESP="${TMP_DIR}/pipeline-resp.json"
api_curl \
  -X POST "${BASE_URL}/ingestion/pipelines" \
  -H "Content-Type: application/json" \
  -d @"${PIPELINE_PAYLOAD}" >"${PIPELINE_RESP}"

PIPELINE_ID="$(jq -r '.data.id // empty' "${PIPELINE_RESP}")"
if [ -z "${PIPELINE_ID}" ]; then
  echo "[ingestion-smoke] 创建流水线失败"
  cat "${PIPELINE_RESP}"
  exit 1
fi
echo "[ingestion-smoke] pipelineId=${PIPELINE_ID}"

TASK_PAYLOAD="${TMP_DIR}/task.json"
cat >"${TASK_PAYLOAD}" <<EOF
{
  "pipelineId": "${PIPELINE_ID}",
  "source": {
    "type": "url",
    "location": "${SAMPLE_URL}",
    "fileName": "dummy.pdf"
  },
  "metadata": {
    "owner": "qa",
    "category": "smoke"
  }
}
EOF

echo "[ingestion-smoke] 创建任务"
TASK_RESP="${TMP_DIR}/task-resp.json"
api_curl \
  -X POST "${BASE_URL}/ingestion/tasks" \
  -H "Content-Type: application/json" \
  -d @"${TASK_PAYLOAD}" >"${TASK_RESP}"

TASK_ID="$(jq -r '.data.taskId // empty' "${TASK_RESP}")"
if [ -z "${TASK_ID}" ]; then
  echo "[ingestion-smoke] 创建任务失败"
  cat "${TASK_RESP}"
  exit 1
fi
echo "[ingestion-smoke] taskId=${TASK_ID}"

START_TS="$(date +%s)"
STATUS=""
TASK_DETAIL="${TMP_DIR}/task-detail.json"
while true; do
  api_curl "${BASE_URL}/ingestion/tasks/${TASK_ID}" >"${TASK_DETAIL}"
  STATUS="$(jq -r '.data.status // empty' "${TASK_DETAIL}" | tr '[:upper:]' '[:lower:]')"
  if [ "${STATUS}" = "completed" ] || [ "${STATUS}" = "failed" ]; then
    break
  fi
  NOW_TS="$(date +%s)"
  if [ $((NOW_TS - START_TS)) -gt "${TIMEOUT_SECONDS}" ]; then
    echo "[ingestion-smoke] 等待任务完成超时"
    exit 1
  fi
  sleep "${POLL_INTERVAL_SECONDS}"
done

echo "[ingestion-smoke] 最终状态: ${STATUS}"
if [ "${STATUS}" != "completed" ]; then
  echo "[ingestion-smoke] 任务失败详情:"
  cat "${TASK_DETAIL}"
  exit 1
fi

echo "[ingestion-smoke] 校验任务 metadata 类型"
META_TYPE="$(jq -r '.data.metadata | type' "${TASK_DETAIL}")"
if [ "${META_TYPE}" != "object" ]; then
  echo "[ingestion-smoke] metadata 不是 object，当前类型: ${META_TYPE}"
  cat "${TASK_DETAIL}"
  exit 1
fi

echo "[ingestion-smoke] 拉取节点执行记录"
NODES_DETAIL="${TMP_DIR}/nodes-detail.json"
api_curl "${BASE_URL}/ingestion/tasks/${TASK_ID}/nodes" >"${NODES_DETAIL}"

NODES_TYPE="$(jq -r '.data | type' "${NODES_DETAIL}")"
if [ "${NODES_TYPE}" != "array" ]; then
  echo "[ingestion-smoke] 节点返回不是 array"
  cat "${NODES_DETAIL}"
  exit 1
fi

INVALID_OUTPUT_COUNT="$(jq '[.data[] | select(.output != null and (.output | type != "object"))] | length' "${NODES_DETAIL}")"
if [ "${INVALID_OUTPUT_COUNT}" != "0" ]; then
  echo "[ingestion-smoke] 存在非 object 的节点 output"
  cat "${NODES_DETAIL}"
  exit 1
fi

echo "[ingestion-smoke] PASS"
