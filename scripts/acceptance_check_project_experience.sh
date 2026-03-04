#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

UNIT_TESTS="MultiChannelRetrievalEngineTests,IntentResolverTests,ModelRoutingTests,ChatQueueLimiterTests,ThreadPoolConfigTests,IngestionPipelineTests,RagTraceTests,RAGChatServiceImplTests,StreamChatEventHandlerTests,StreamCancelTests,ConversationMemoryTests,MCPRegistryAndServiceTests"

echo "[acceptance] step 1/2: run core unit tests"
./mvnw -pl bootstrap -Dtest="${UNIT_TESTS}" test

echo "[acceptance] step 2/2: run live SSE smoke test when backend is online"
if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  BASE_URL="${BASE_URL:-http://localhost:8080/api/ragent}" \
  LOGIN_USERNAME="${LOGIN_USERNAME:-admin}" \
  LOGIN_PASSWORD="${LOGIN_PASSWORD:-admin}" \
  QUESTION="${QUESTION:-请简要介绍这个系统的主要能力}" \
  ./scripts/chat_sse_smoke.sh
else
  echo "[acceptance] skip SSE smoke: backend port 8080 is not listening"
fi

echo "[acceptance] done"
