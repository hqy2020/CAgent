#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/Users/openingcloud/IdeaProjects/ragent"

# Load API keys from .env if present
ENV_FILE="$ROOT_DIR/.env"
if [ -f "$ENV_FILE" ]; then
  set -a
  source "$ENV_FILE"
  set +a
fi
FRONTEND_DIR="$ROOT_DIR/frontend"
CHAT_URL="http://localhost:5173/chat"
BACKEND_LOG="/tmp/ragent-backend.log"
FRONTEND_LOG="/tmp/ragent-frontend.log"
MAVEN_CMD="./mvnw -pl bootstrap spring-boot:run -Dspring-boot.run.profiles=local"

ensure_docker_service() {
  if ! command -v docker >/dev/null 2>&1; then
    return
  fi

  if ! docker info >/dev/null 2>&1; then
    open -a Docker || true
    sleep 5
  fi

  if docker info >/dev/null 2>&1; then
    docker compose -f "$ROOT_DIR/resources/docker/milvus/milvus-mini.compose.yaml" up -d >/dev/null 2>&1 || true
    for container in ragent-redis onecoupon-mysql onecoupon-redis; do
      if docker ps -a --format '{{.Names}}' | rg -x "$container" >/dev/null 2>&1; then
        docker start "$container" >/dev/null 2>&1 || true
      fi
    done
  fi
}

start_backend_if_needed() {
  if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
    return
  fi

  cd "$ROOT_DIR"
  nohup env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u all_proxy -u ALL_PROXY \
    NO_PROXY=localhost,127.0.0.1,::1 \
    bash -lc "$MAVEN_CMD" >"$BACKEND_LOG" 2>&1 &
}

start_frontend_if_needed() {
  if lsof -nP -iTCP:5173 -sTCP:LISTEN >/dev/null 2>&1; then
    return
  fi

  cd "$FRONTEND_DIR"
  if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
    npm install >/tmp/ragent-frontend-install.log 2>&1
  fi
  nohup npm run dev -- --host 127.0.0.1 --port 5173 >"$FRONTEND_LOG" 2>&1 &
}

wait_port_ready() {
  local port="$1"
  local attempts=60
  while [ "$attempts" -gt 0 ]; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      return
    fi
    sleep 1
    attempts=$((attempts - 1))
  done
}

ensure_docker_service
start_backend_if_needed
start_frontend_if_needed
wait_port_ready 8080
wait_port_ready 5173
open "$CHAT_URL"
