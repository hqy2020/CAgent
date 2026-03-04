#!/usr/bin/env bash
# ragent-common.sh - Ragent container health check utilities
#
# Usage:
#   source scripts/lib/ragent-common.sh
#   wait_containers ragent-mysql ragent-redis ragent-milvus
#
# Or run directly:
#   ./scripts/lib/ragent-common.sh wait_containers ragent-mysql ragent-redis

set -euo pipefail

# ── Colors ────────────────────────────────────────
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log_info()  { printf "${GREEN}[INFO]${NC}  %s\n" "$*"; }
log_warn()  { printf "${YELLOW}[WARN]${NC}  %s\n" "$*"; }
log_error() { printf "${RED}[ERROR]${NC} %s\n" "$*"; }
log_step()  { printf "${CYAN}[STEP]${NC}  %s\n" "$*"; }

# ── wait_containers ───────────────────────────────
# Polls `docker inspect` for container health status.
# Args: container names (variadic)
# Env:  HEALTH_TIMEOUT (default 180s), HEALTH_INTERVAL (default 10s)
wait_containers() {
  local timeout="${HEALTH_TIMEOUT:-180}"
  local interval="${HEALTH_INTERVAL:-10}"
  local containers=("$@")

  if [ ${#containers[@]} -eq 0 ]; then
    log_warn "No containers specified for health check"
    return 0
  fi

  log_step "Waiting for ${#containers[@]} containers to become healthy (timeout: ${timeout}s)..."

  local elapsed=0
  while [ "$elapsed" -lt "$timeout" ]; do
    local all_healthy=true
    local status_line=""

    for c in "${containers[@]}"; do
      local state
      state=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$c" 2>/dev/null || echo "not-found")

      case "$state" in
        healthy)
          status_line+="  ${GREEN}${c}: healthy${NC}"
          ;;
        no-healthcheck)
          # Container without healthcheck is considered ready if running
          local running
          running=$(docker inspect --format='{{.State.Running}}' "$c" 2>/dev/null || echo "false")
          if [ "$running" = "true" ]; then
            status_line+="  ${GREEN}${c}: running${NC}"
          else
            status_line+="  ${YELLOW}${c}: not-running${NC}"
            all_healthy=false
          fi
          ;;
        not-found)
          status_line+="  ${RED}${c}: not-found${NC}"
          all_healthy=false
          ;;
        *)
          status_line+="  ${YELLOW}${c}: ${state}${NC}"
          all_healthy=false
          ;;
      esac
    done

    if $all_healthy; then
      printf "\n"
      log_info "All containers are healthy!"
      return 0
    fi

    printf "\r  [%3ds/${timeout}s]${status_line}" "$elapsed"
    sleep "$interval"
    elapsed=$((elapsed + interval))
  done

  printf "\n"
  log_error "Timed out after ${timeout}s waiting for containers"
  # Print final status for debugging
  for c in "${containers[@]}"; do
    local state
    state=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$c" 2>/dev/null || echo "not-found")
    log_error "  $c: $state"
  done
  return 1
}

# ── wait_port ─────────────────────────────────────
# Waits for a TCP port to start listening.
# Args: port [timeout_seconds]
wait_port() {
  local port="$1"
  local timeout="${2:-120}"
  local elapsed=0

  while [ "$elapsed" -lt "$timeout" ]; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done

  log_error "Port $port did not become available within ${timeout}s"
  return 1
}

# ── Direct invocation support ─────────────────────
# Allows: ./ragent-common.sh wait_containers ragent-mysql ragent-redis
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  if [ $# -lt 1 ]; then
    echo "Usage: $0 <function_name> [args...]"
    echo "Available functions: wait_containers, wait_port"
    exit 1
  fi
  func="$1"
  shift
  "$func" "$@"
fi
