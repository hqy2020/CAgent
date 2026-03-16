# ============================================================
# Ragent - macOS 一键启动 Makefile
# ============================================================
#
# 快速开始:
#   make start     # 一键启动全栈 (Docker → 基础设施 → 后端 → 前端 → 浏览器)
#   make stop      # 停止全部
#   make status    # 查看运行状态
#
# 单独操作:
#   make infra     # 仅启动 Docker 容器
#   make backend   # 仅启动后端
#   make frontend  # 仅启动前端
#   make restart   # 重启前后端 (保留基础设施)
#   make logs      # 查看日志
#   make clean     # 停止全部 + 清理日志 + docker compose down
#
# ============================================================

SHELL := /bin/bash

# ── 路径解析 (不硬编码) ──────────────────────────────
ROOT_DIR    := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
SCRIPTS_DIR := $(ROOT_DIR)scripts
LIB_DIR     := $(SCRIPTS_DIR)/lib
FRONTEND_DIR := $(ROOT_DIR)frontend

# ── Compose ──────────────────────────────────────────
COMPOSE_FILE := $(ROOT_DIR)resources/docker/ragent-infra.compose.yaml
COMPOSE_CMD  := docker compose -f $(COMPOSE_FILE)

# ── 日志 ─────────────────────────────────────────────
BACKEND_LOG  := /tmp/ragent-backend.log
FRONTEND_LOG := /tmp/ragent-frontend.log

# ── 端口 ─────────────────────────────────────────────
BACKEND_PORT  := 8080
FRONTEND_PORT := 5173
CHAT_URL      := http://localhost:$(FRONTEND_PORT)/chat

# ── Maven ────────────────────────────────────────────
MAVEN_CMD := ./mvnw compile -pl bootstrap -am -q && ./mvnw -pl bootstrap spring-boot:run -Dspring-boot.run.profiles=local

# ── 需要等待健康检查的关键容器 ────────────────────────
HEALTH_CONTAINERS := ragent-mysql ragent-redis ragent-milvus ragent-rocketmq-broker

# ── 加载 .env (API Keys 等) ──────────────────────────
-include $(ROOT_DIR).env
export

# ── 颜色 ─────────────────────────────────────────────
GREEN  := \033[0;32m
YELLOW := \033[1;33m
RED    := \033[0;31m
CYAN   := \033[0;36m
BOLD   := \033[1m
NC     := \033[0m

.PHONY: start stop status logs restart infra backend frontend clean app help

# ── 默认目标 ─────────────────────────────────────────
.DEFAULT_GOAL := help

# ============================================================
# make start - 一键启动全栈
# ============================================================
start: infra backend frontend
	@printf "\n$(GREEN)$(BOLD)========================================$(NC)\n"
	@printf "$(GREEN)$(BOLD)  Ragent 全栈启动完成!$(NC)\n"
	@printf "$(GREEN)$(BOLD)========================================$(NC)\n"
	@printf "  前端:  $(CYAN)$(CHAT_URL)$(NC)\n"
	@printf "  后端:  $(CYAN)http://localhost:$(BACKEND_PORT)$(NC)\n"
	@printf "  Attu:  $(CYAN)http://localhost:8000$(NC)\n"
	@printf "  RocketMQ Dashboard: $(CYAN)http://localhost:8082$(NC)\n"
	@printf "\n"
	@open "$(CHAT_URL)" 2>/dev/null || true

# ============================================================
# make stop - 停止前后端 + 基础设施容器
# ============================================================
stop:
	@printf "$(YELLOW)[STOP]$(NC) Stopping Ragent services...\n"
	@# 停止后端 (Spring Boot on 8080)
	@if lsof -nP -iTCP:$(BACKEND_PORT) -sTCP:LISTEN -t >/dev/null 2>&1; then \
		kill $$(lsof -nP -iTCP:$(BACKEND_PORT) -sTCP:LISTEN -t) 2>/dev/null || true; \
		printf "$(GREEN)[STOP]$(NC) Backend stopped\n"; \
	else \
		printf "$(CYAN)[STOP]$(NC) Backend not running\n"; \
	fi
	@# 停止前端 (Vite on 5173)
	@if lsof -nP -iTCP:$(FRONTEND_PORT) -sTCP:LISTEN -t >/dev/null 2>&1; then \
		kill $$(lsof -nP -iTCP:$(FRONTEND_PORT) -sTCP:LISTEN -t) 2>/dev/null || true; \
		printf "$(GREEN)[STOP]$(NC) Frontend stopped\n"; \
	else \
		printf "$(CYAN)[STOP]$(NC) Frontend not running\n"; \
	fi
	@# 停止 Docker 容器
	@if docker info >/dev/null 2>&1 && [ -f "$(COMPOSE_FILE)" ]; then \
		$(COMPOSE_CMD) stop 2>/dev/null || true; \
		printf "$(GREEN)[STOP]$(NC) Docker containers stopped\n"; \
	fi
	@printf "$(GREEN)[DONE]$(NC) All services stopped\n"

# ============================================================
# make status - 查看所有服务状态
# ============================================================
status:
	@printf "$(BOLD)── Application Services ──$(NC)\n"
	@if lsof -nP -iTCP:$(BACKEND_PORT) -sTCP:LISTEN >/dev/null 2>&1; then \
		printf "  $(GREEN)●$(NC) Backend   :$(BACKEND_PORT)  $(GREEN)running$(NC)\n"; \
	else \
		printf "  $(RED)●$(NC) Backend   :$(BACKEND_PORT)  $(RED)stopped$(NC)\n"; \
	fi
	@if lsof -nP -iTCP:$(FRONTEND_PORT) -sTCP:LISTEN >/dev/null 2>&1; then \
		printf "  $(GREEN)●$(NC) Frontend  :$(FRONTEND_PORT)  $(GREEN)running$(NC)\n"; \
	else \
		printf "  $(RED)●$(NC) Frontend  :$(FRONTEND_PORT)  $(RED)stopped$(NC)\n"; \
	fi
	@printf "\n$(BOLD)── Docker Containers ──$(NC)\n"
	@if docker info >/dev/null 2>&1; then \
		docker ps -a --filter "label=com.docker.compose.project=ragent-infra" \
			--format "{{.Names}}\t{{.State}}\t{{.Status}}" 2>/dev/null \
			| while IFS=$$'\t' read -r name state status; do \
				if [ "$$state" = "running" ]; then \
					printf "  \033[0;32m●\033[0m %-35s %s\n" "$$name" "$$status"; \
				else \
					printf "  \033[0;31m●\033[0m %-35s %s\n" "$$name" "$$status"; \
				fi; \
			done || printf "  $(YELLOW)No ragent containers found$(NC)\n"; \
	else \
		printf "  $(RED)Docker is not running$(NC)\n"; \
	fi

# ============================================================
# make logs - tail 后端 + 前端日志
# ============================================================
logs:
	@printf "$(CYAN)[LOGS]$(NC) Tailing backend & frontend logs (Ctrl+C to exit)\n"
	@printf "$(CYAN)[LOGS]$(NC) Backend:  $(BACKEND_LOG)\n"
	@printf "$(CYAN)[LOGS]$(NC) Frontend: $(FRONTEND_LOG)\n\n"
	@tail -f $(BACKEND_LOG) $(FRONTEND_LOG) 2>/dev/null || \
		printf "$(YELLOW)No log files found. Start the services first: make start$(NC)\n"

# ============================================================
# make restart - 重启前后端 (保留基础设施)
# ============================================================
restart:
	@printf "$(YELLOW)[RESTART]$(NC) Restarting application services...\n"
	@# 停止后端
	@if lsof -nP -iTCP:$(BACKEND_PORT) -sTCP:LISTEN -t >/dev/null 2>&1; then \
		kill $$(lsof -nP -iTCP:$(BACKEND_PORT) -sTCP:LISTEN -t) 2>/dev/null || true; \
		sleep 2; \
	fi
	@# 停止前端
	@if lsof -nP -iTCP:$(FRONTEND_PORT) -sTCP:LISTEN -t >/dev/null 2>&1; then \
		kill $$(lsof -nP -iTCP:$(FRONTEND_PORT) -sTCP:LISTEN -t) 2>/dev/null || true; \
		sleep 1; \
	fi
	@$(MAKE) --no-print-directory backend frontend
	@printf "$(GREEN)[DONE]$(NC) Application restarted\n"

# ============================================================
# make infra - 启动 Docker Desktop + 全栈容器
# ============================================================
infra:
	@printf "$(CYAN)[INFRA]$(NC) Checking Docker...\n"
	@# 确保 Docker Desktop 运行
	@if ! docker info >/dev/null 2>&1; then \
		printf "$(YELLOW)[INFRA]$(NC) Starting Docker Desktop...\n"; \
		open -a Docker; \
		for i in $$(seq 1 30); do \
			if docker info >/dev/null 2>&1; then break; fi; \
			sleep 2; \
		done; \
		if ! docker info >/dev/null 2>&1; then \
			printf "$(RED)[ERROR]$(NC) Docker failed to start within 60s\n"; \
			exit 1; \
		fi; \
		printf "$(GREEN)[INFRA]$(NC) Docker Desktop is ready\n"; \
	else \
		printf "$(GREEN)[INFRA]$(NC) Docker already running\n"; \
	fi
	@# 启动容器
	@printf "$(CYAN)[INFRA]$(NC) Starting containers via docker compose...\n"
	@$(COMPOSE_CMD) up -d
	@# 等待健康检查
	@source $(LIB_DIR)/ragent-common.sh && wait_containers $(HEALTH_CONTAINERS)

# ============================================================
# make backend - 启动 Spring Boot 后端
# ============================================================
backend:
	@if lsof -nP -iTCP:$(BACKEND_PORT) -sTCP:LISTEN >/dev/null 2>&1; then \
		printf "$(GREEN)[BACKEND]$(NC) Already running on port $(BACKEND_PORT)\n"; \
	else \
		printf "$(CYAN)[BACKEND]$(NC) Starting Spring Boot...\n"; \
		cd $(ROOT_DIR) && nohup env \
			-u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
			-u all_proxy -u ALL_PROXY \
			NO_PROXY=localhost,127.0.0.1,::1 \
			bash -lc "$(MAVEN_CMD)" > $(BACKEND_LOG) 2>&1 & \
		printf "$(CYAN)[BACKEND]$(NC) Waiting for port $(BACKEND_PORT)...\n"; \
		source $(LIB_DIR)/ragent-common.sh && wait_port $(BACKEND_PORT) 120; \
		printf "$(GREEN)[BACKEND]$(NC) Started on port $(BACKEND_PORT)\n"; \
	fi

# ============================================================
# make frontend - 启动 Vite 前端
# ============================================================
frontend:
	@if lsof -nP -iTCP:$(FRONTEND_PORT) -sTCP:LISTEN >/dev/null 2>&1; then \
		printf "$(GREEN)[FRONTEND]$(NC) Already running on port $(FRONTEND_PORT)\n"; \
	else \
		printf "$(CYAN)[FRONTEND]$(NC) Starting Vite dev server...\n"; \
		if [ ! -d "$(FRONTEND_DIR)/node_modules" ]; then \
			printf "$(CYAN)[FRONTEND]$(NC) Installing npm dependencies...\n"; \
			cd $(FRONTEND_DIR) && npm install > /tmp/ragent-frontend-install.log 2>&1; \
		fi; \
		cd $(FRONTEND_DIR) && nohup env VITE_API_TARGET="$${VITE_API_TARGET:-http://127.0.0.1:$(BACKEND_PORT)}" npm run dev -- --host 0.0.0.0 --port $(FRONTEND_PORT) > $(FRONTEND_LOG) 2>&1 & \
		printf "$(CYAN)[FRONTEND]$(NC) Waiting for port $(FRONTEND_PORT)...\n"; \
		source $(LIB_DIR)/ragent-common.sh && wait_port $(FRONTEND_PORT) 60; \
		printf "$(GREEN)[FRONTEND]$(NC) Started on port $(FRONTEND_PORT)\n"; \
	fi

# ============================================================
# make clean - 停止全部 + 清日志 + docker compose down
# ============================================================
clean:
	@printf "$(RED)[CLEAN]$(NC) Stopping all services and cleaning up...\n"
	@# 停止应用进程
	@if lsof -nP -iTCP:$(BACKEND_PORT) -sTCP:LISTEN -t >/dev/null 2>&1; then \
		kill $$(lsof -nP -iTCP:$(BACKEND_PORT) -sTCP:LISTEN -t) 2>/dev/null || true; \
	fi
	@if lsof -nP -iTCP:$(FRONTEND_PORT) -sTCP:LISTEN -t >/dev/null 2>&1; then \
		kill $$(lsof -nP -iTCP:$(FRONTEND_PORT) -sTCP:LISTEN -t) 2>/dev/null || true; \
	fi
	@# Docker compose down (保留 volumes)
	@if docker info >/dev/null 2>&1 && [ -f "$(COMPOSE_FILE)" ]; then \
		$(COMPOSE_CMD) down 2>/dev/null || true; \
	fi
	@# 清理日志
	@rm -f $(BACKEND_LOG) $(FRONTEND_LOG) /tmp/ragent-frontend-install.log
	@printf "$(GREEN)[CLEAN]$(NC) All cleaned up\n"

# ============================================================
# make app - 生成 Ragent.app 桌面启动器
# ============================================================
app:
	@$(SCRIPTS_DIR)/create-macos-app.sh

# ============================================================
# make help - 显示帮助
# ============================================================
help:
	@printf "$(BOLD)Ragent - macOS 一键启动$(NC)\n\n"
	@printf "$(BOLD)Usage:$(NC)\n"
	@printf "  make $(GREEN)start$(NC)      一键启动全栈 (Docker → 容器 → 后端 → 前端 → 浏览器)\n"
	@printf "  make $(GREEN)stop$(NC)       停止所有服务\n"
	@printf "  make $(GREEN)status$(NC)     查看运行状态\n"
	@printf "  make $(GREEN)logs$(NC)       查看实时日志\n"
	@printf "  make $(GREEN)restart$(NC)    重启前后端 (保留基础设施)\n"
	@printf "  make $(GREEN)infra$(NC)      仅启动 Docker 容器\n"
	@printf "  make $(GREEN)backend$(NC)    仅启动后端\n"
	@printf "  make $(GREEN)frontend$(NC)   仅启动前端\n"
	@printf "  make $(RED)clean$(NC)      停止全部 + 清理日志 + docker compose down\n"
	@printf "  make $(CYAN)app$(NC)        生成 Ragent.app 桌面启动器图标\n"
	@printf "\n$(BOLD)Ports:$(NC)\n"
	@printf "  Frontend (Vite):     $(CYAN)http://localhost:$(FRONTEND_PORT)/chat$(NC)\n"
	@printf "  Backend (Spring):    $(CYAN)http://localhost:$(BACKEND_PORT)$(NC)\n"
	@printf "  Attu (Milvus UI):    $(CYAN)http://localhost:8000$(NC)\n"
	@printf "  RocketMQ Dashboard:  $(CYAN)http://localhost:8082$(NC)\n"
	@printf "\n$(BOLD)Setup:$(NC)\n"
	@printf "  cp .env.example .env && vim .env  # 填入 API Keys\n"
	@printf "  make start                        # 一键启动\n"
