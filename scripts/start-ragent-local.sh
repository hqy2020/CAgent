#!/usr/bin/env bash
# start-ragent-local.sh - 本地启动 Ragent 全栈
# 推荐使用: make start (项目根目录 Makefile)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=lib/ragent-common.sh
source "$ROOT_DIR/scripts/lib/ragent-common.sh"

# Load API keys from .env if present
ENV_FILE="$ROOT_DIR/.env"
if [ -f "$ENV_FILE" ]; then
  set -a
  source "$ENV_FILE"
  set +a
fi

FRONTEND_DIR="$ROOT_DIR/frontend"
MCP_BRIDGE_DIR="$ROOT_DIR/scripts/mcp-bridge"
CHAT_URL="http://localhost:5173/chat"
BACKEND_LOG="/tmp/ragent-backend.log"
FRONTEND_LOG="/tmp/ragent-frontend.log"
COMPOSE_FILE="$ROOT_DIR/resources/docker/ragent-infra.compose.yaml"
MAVEN_CMD="./mvnw -pl bootstrap spring-boot:run -Dspring-boot.run.profiles=local"

ensure_docker_service() {
  if ! command -v docker >/dev/null 2>&1; then
    log_warn "Docker not found, skipping infrastructure setup"
    return
  fi

  if ! docker info >/dev/null 2>&1; then
    log_step "Starting Docker Desktop..."
    open -a Docker || true
    for i in $(seq 1 30); do
      if docker info >/dev/null 2>&1; then break; fi
      sleep 2
    done
  fi

  if docker info >/dev/null 2>&1; then
    log_step "Starting containers via docker compose..."
    docker compose -f "$COMPOSE_FILE" up -d >/dev/null 2>&1 || true
    wait_containers ragent-mysql ragent-redis ragent-milvus ragent-rocketmq-broker
  fi
}

start_backend_if_needed() {
  if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
    log_info "Backend already running on port 8080"
    return
  fi

  if [ -f "$MCP_BRIDGE_DIR/package.json" ] && [ ! -d "$MCP_BRIDGE_DIR/node_modules/@modelcontextprotocol" ]; then
    log_step "Installing MCP bridge dependencies..."
    (cd "$MCP_BRIDGE_DIR" && npm install >/tmp/ragent-mcp-bridge-install.log 2>&1)
  fi

  log_step "Starting Spring Boot backend..."
  cd "$ROOT_DIR"
  nohup env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u all_proxy -u ALL_PROXY \
    NO_PROXY=localhost,127.0.0.1,::1 \
    bash -lc "$MAVEN_CMD" >"$BACKEND_LOG" 2>&1 &
}

start_frontend_if_needed() {
  if lsof -nP -iTCP:5173 -sTCP:LISTEN >/dev/null 2>&1; then
    log_info "Frontend already running on port 5173"
    return
  fi

  log_step "Starting Vite frontend..."
  cd "$FRONTEND_DIR"
  if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
    log_step "Installing npm dependencies..."
    npm install >/tmp/ragent-frontend-install.log 2>&1
  fi
  nohup npm run dev -- --host 127.0.0.1 --port 5173 >"$FRONTEND_LOG" 2>&1 &
}

ensure_docker_service
start_backend_if_needed
start_frontend_if_needed

log_step "Waiting for backend (port 8080)..."
wait_port 8080 120
log_step "Waiting for frontend (port 5173)..."
wait_port 5173 60

if [ "${RUN_UI_REGRESSION:-false}" = "true" ]; then
  log_step "Running UI prompt regression..."
  "$ROOT_DIR/scripts/ui_prompt_regression.sh"
fi

log_info "Ragent is ready! Opening browser..."
open "$CHAT_URL"
