# Ragent

Ragent 是一个面向个人知识库场景的 RAG 应用，提供流式对话、知识库管理、文档入库流水线、热点监控和 MCP 工具接入能力。

这份 README 的目标只有一件事：让第一次拿到仓库的人，能尽快把项目跑起来，并知道从哪里开始二次开发。

![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)
![Java](https://img.shields.io/badge/Java-17-ff7f2a.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.x-6db33f.svg)
![React](https://img.shields.io/badge/React-18-61dafb.svg)
![Milvus](https://img.shields.io/badge/Milvus-2.6.x-00b3ff.svg)

## 功能概览

- RAG 对话：SSE 流式输出、Query Rewrite、多问句拆分、意图识别、多通道检索
- 知识库：知识库管理、文档上传、分块、向量化、Milvus 检索
- 入库流水线：解析、增强、富集、索引，支持异步执行
- 热点监控：热点聚合、定时扫描、WebSocket 推送
- MCP 集成：可接 Browser MCP、Obsidian MCP 等外部工具
- 会话能力：会话记忆、摘要、链路追踪

## 技术栈

- 后端：Java 17、Spring Boot 3.5、MyBatis-Plus、SA-Token、RocketMQ
- 前端：React 18、Vite 5、TypeScript、Tailwind
- 基础设施：MySQL、Redis、Milvus、RustFS、RocketMQ、Neo4j
- 模型接入：MiniMax、SiliconFlow、百炼、Ollama

## 仓库结构

```text
.
├── bootstrap/                 # Spring Boot 应用入口和主要业务实现
├── framework/                 # 通用基础设施封装
├── infra-ai/                  # Chat / Embedding / Rerank 模型路由
├── frontend/                  # React 前端
├── resources/
│   ├── database/              # 建表和初始化数据
│   └── docker/                # 基础设施 compose
├── scripts/                   # 启动、测试、回归脚本
├── docs/                      # 架构、说明和测试报告
└── Makefile                   # macOS 本地一键启动入口
```

## 启动前准备

最低要求：

- Docker Desktop
- JDK 17
- Node.js 18+
- npm 9+

推荐额外准备：

- `cp .env.example .env`
- 至少填写 `MINIMAX_API_KEY` 和 `SILICONFLOW_API_KEY`

为什么是这两个：

- 当前默认聊天模型是 `minimax-m2.5`
- 当前默认 embedding / rerank 模型来自 SiliconFlow
- 不填这两个 Key，服务可以启动，但问答和向量化能力不会完整可用

如果你要改成纯本地模型，需要同步调整 [bootstrap/src/main/resources/application.yaml](/Users/openingcloud/IdeaProjects/ragent/bootstrap/src/main/resources/application.yaml) 里的默认模型和启用项。

## 方式一：macOS 一键启动

这是仓库里最快的本地启动方式。

### 1. 配置环境变量

```bash
cp .env.example .env
```

编辑 `.env`，至少补齐：

```env
MINIMAX_API_KEY=your-minimax-api-key
SILICONFLOW_API_KEY=your-siliconflow-api-key
```

可选项：

- `OBSIDIAN_API_KEY`：启用 Obsidian MCP 时需要
- `VIDEO_TRANSCRIPT_*`：启用视频转录时需要

### 2. 启动项目

```bash
make start
```

这个命令会做几件事：

- 启动 Docker 基础设施
- 启动后端 `Spring Boot`
- 启动前端 `Vite`
- 自动打开浏览器

### 3. 访问系统

- 前端：[http://localhost:5173](http://localhost:5173)
- 后端：[http://localhost:8080/api/ragent](http://localhost:8080/api/ragent)
- Milvus 管理界面 Attu：[http://localhost:8000](http://localhost:8000)
- RocketMQ Dashboard：[http://localhost:8082](http://localhost:8082)

默认账号：

- 用户名：`admin`
- 密码：`admin`

数据库会在容器首次启动时自动执行 [resources/database/schema_table.sql](/Users/openingcloud/IdeaProjects/ragent/resources/database/schema_table.sql) 和 [resources/database/init_data.sql](/Users/openingcloud/IdeaProjects/ragent/resources/database/init_data.sql)。

### 4. 常用命令

```bash
make status
make logs
make stop
make restart
make clean
```

说明：

- `make start` / `make restart` 偏向本地开发
- `make clean` 会停止服务并清理日志，但不会删除 Docker volume

## 方式二：跨平台手动启动

如果你不是 macOS，或者不想使用 `make start`，按下面步骤执行。

### 1. 启动基础设施

```bash
docker compose -f resources/docker/ragent-infra.compose.yaml up -d
```

这会启动：

- MySQL 8
- Redis 7
- Milvus 2.6
- RustFS
- RocketMQ 5
- Neo4j

### 2. 配置环境变量

```bash
cp .env.example .env
```

然后在当前 shell 导出：

```bash
set -a
source .env
set +a
```

### 3. 启动后端

```bash
./mvnw -pl bootstrap spring-boot:run -Dspring-boot.run.profiles=local
```

后端默认地址：

- `http://localhost:8080/api/ragent`

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端开发环境默认通过 Vite 代理 `/api` 到 `http://localhost:8080`，本地开发通常不需要额外配置。

如果前端和后端不在同一地址，可以在启动前设置：

```bash
VITE_API_TARGET=http://your-backend-host:8080 npm run dev
```

## 首次验证

项目启动后，建议按这个顺序检查：

1. 打开 [http://localhost:5173/login](http://localhost:5173/login)，用 `admin / admin` 登录
2. 进入聊天页，确认可以创建会话和发送消息
3. 进入工作区 `/workspace/knowledge`，创建一个知识库
4. 上传一份 Markdown 或 PDF 文档
5. 等待入库完成后回到聊天页，确认可以命中文档内容

如果第 2 步失败，通常是模型 Key 没配好。

如果第 4 步失败，通常先看：

- 后端日志：`/tmp/ragent-backend.log`
- Docker 容器状态：`docker ps`

## 端口说明

| 组件 | 端口 |
| --- | --- |
| 前端 Vite | `5173` |
| 后端 Spring Boot | `8080` |
| MySQL | `3306` |
| Redis | `6379` |
| Milvus | `19530` |
| RustFS | `9000` |
| RustFS Console | `9001` |
| Attu | `8000` |
| RocketMQ NameServer | `9876` |
| RocketMQ Broker | `10911` / `10909` |
| RocketMQ Dashboard | `8082` |
| Neo4j Browser | `7474` |
| Neo4j Bolt | `7687` |

## 关键配置

主要配置文件是 [bootstrap/src/main/resources/application.yaml](/Users/openingcloud/IdeaProjects/ragent/bootstrap/src/main/resources/application.yaml)。

重点看这些配置块：

- `spring.datasource`：MySQL
- `spring.data.redis`：Redis
- `milvus`：Milvus 连接
- `rustfs`：对象存储
- `rocketmq`：异步入库消息队列
- `ai.providers`：模型服务商地址和 API Key
- `ai.chat / ai.embedding / ai.rerank`：默认模型和候选列表
- `rag.search`：检索策略
- `rag.memory`：会话记忆
- `rag.external-mcp`：外部 MCP 桥接

当前默认模型配置是：

- Chat：`minimax-m2.5`
- Embedding：`qwen-emb-8b`
- Rerank：`sf-rerank`

## 生产部署建议

仓库里提供了基础设施 compose，但没有把前后端打成完整生产镜像。生产环境通常按下面方式部署。

### 1. 构建后端

```bash
./mvnw -pl bootstrap -am clean package -DskipTests
```

### 2. 构建前端

```bash
cd frontend
npm install
VITE_API_BASE_URL=/api/ragent npm run build
```

### 3. 反向代理

建议用 Nginx：

- 静态文件指向 `frontend/dist`
- `/api/ragent/` 代理到 Spring Boot
- `/api/ragent/ws/hotspots` 开启 WebSocket 代理

如果不走同域部署，就把 `VITE_API_BASE_URL` 在构建时改成后端完整地址。

## 二次开发入口

第一次接手代码，建议按这个顺序看：

1. [frontend/src/router.tsx](/Users/openingcloud/IdeaProjects/ragent/frontend/src/router.tsx)
2. [bootstrap/src/main/resources/application.yaml](/Users/openingcloud/IdeaProjects/ragent/bootstrap/src/main/resources/application.yaml)
3. [bootstrap/src/main/java/com/openingcloud/ai/ragent/rag/service/impl/RAGChatServiceImpl.java](/Users/openingcloud/IdeaProjects/ragent/bootstrap/src/main/java/com/openingcloud/ai/ragent/rag/service/impl/RAGChatServiceImpl.java)
4. [bootstrap/src/main/java/com/openingcloud/ai/ragent/rag/core/retrieve/RetrievalEngine.java](/Users/openingcloud/IdeaProjects/ragent/bootstrap/src/main/java/com/openingcloud/ai/ragent/rag/core/retrieve/RetrievalEngine.java)
5. [bootstrap/src/main/java/com/openingcloud/ai/ragent/ingestion/](/Users/openingcloud/IdeaProjects/ragent/bootstrap/src/main/java/com/openingcloud/ai/ragent/ingestion)
6. [infra-ai/src/main/java/com/openingcloud/ai/ragent/infra/](/Users/openingcloud/IdeaProjects/ragent/infra-ai/src/main/java/com/openingcloud/ai/ragent/infra)

模块分工：

- `bootstrap/rag`：问答主链路、意图、检索、记忆、MCP、Trace
- `bootstrap/knowledge`：知识库和文档管理
- `bootstrap/ingestion`：入库流水线和任务编排
- `bootstrap/admin`：热点监控相关能力
- `infra-ai`：模型选择、熔断、降级、provider 适配
- `frontend/src/pages/workspace`：工作区页面

## 测试

后端测试：

```bash
./mvnw test
```

前端测试：

```bash
cd frontend
npm test
```

仓库里还有一批脚本化回归测试，位于 [scripts/](/Users/openingcloud/IdeaProjects/ragent/scripts)：

- `chat_quality_test.sh`
- `prompt_regression_matrix.sh`
- `trace_fullchain_smoke.sh`
- `ingestion_pipeline_user_test.sh`
- `mcp_kb_integration_test.sh`

## 常见问题

### 1. 项目能启动，但聊天报错

先检查：

- `.env` 里的 `MINIMAX_API_KEY`
- `.env` 里的 `SILICONFLOW_API_KEY`
- [bootstrap/src/main/resources/application.yaml](/Users/openingcloud/IdeaProjects/ragent/bootstrap/src/main/resources/application.yaml) 里的默认模型是否和你的 provider 对得上

### 2. 数据库没有初始化

通常是因为 MySQL volume 已存在，`docker-entrypoint-initdb.d` 只会在首次初始化时执行。

处理方式（会清空 MySQL 等容器数据）：

```bash
docker compose -f resources/docker/ragent-infra.compose.yaml down -v
docker compose -f resources/docker/ragent-infra.compose.yaml up -d
```

### 3. 前端能打开，但接口 404

本地开发确认两件事：

- 后端是否启动在 `8080`
- 前端是否通过 `npm run dev` 启动，而不是直接打开静态文件

### 4. MCP / Obsidian 不可用

这部分是可选能力。首次部署可以先不接。

如果要启用，需要额外确认：

- 本机已安装 `npx`
- 使用 Obsidian MCP 时本机可用 `uvx`
- `OBSIDIAN_API_KEY`、`OBSIDIAN_HOST`、`OBSIDIAN_PORT` 配置正确

## 参考文档

- [docs/multi-channel-retrieval.md](/Users/openingcloud/IdeaProjects/ragent/docs/multi-channel-retrieval.md)
- [docs/highlights/01-multi-channel-retrieval.md](/Users/openingcloud/IdeaProjects/ragent/docs/highlights/01-multi-channel-retrieval.md)
- [docs/highlights/03-model-routing-circuit-breaker.md](/Users/openingcloud/IdeaProjects/ragent/docs/highlights/03-model-routing-circuit-breaker.md)
- [docs/highlights/06-ingestion-pipeline.md](/Users/openingcloud/IdeaProjects/ragent/docs/highlights/06-ingestion-pipeline.md)
- [docs/highlights/10-mcp-tool-registry.md](/Users/openingcloud/IdeaProjects/ragent/docs/highlights/10-mcp-tool-registry.md)
