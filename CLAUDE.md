# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CAgent 是一个企业级 RAG（Retrieval-Augmented Generation）智能体平台，采用 Monorepo 结构。后端基于 Spring Boot 3.5 + Java 17，前端基于 React 18 + Vite + TypeScript。核心能力包括：多通道知识库检索、MCP 工具调用、会话记忆、流式 SSE 输出、链路追踪、文档 Ingestion Pipeline。

## Build & Run Commands

### Backend (Maven multi-module)

```bash
# 全量编译（跳过测试）
./mvnw clean package -DskipTests

# 仅编译（触发 Spotless 格式化）
./mvnw compile

# 启动后端（仅 bootstrap 模块）
./mvnw -pl bootstrap spring-boot:run

# 运行所有测试
./mvnw test

# 运行单个测试类
./mvnw -pl bootstrap test -Dtest=SomeTestClass

# 运行单个测试方法
./mvnw -pl bootstrap test -Dtest=SomeTestClass#someMethod
```

### Frontend (React + Vite)

```bash
cd frontend
npm install
npm run dev          # 开发服务器 (localhost:5173)
npm run build        # TypeScript编译 + Vite构建
npm run lint         # ESLint检查
npm run format       # Prettier格式化
```

### Infrastructure

```bash
# 完整环境（etcd + minio + milvus），ARM Mac 推荐
cd resources/docker/milvus
docker compose -f milvus-full.compose.yaml up -d

# 精简环境（etcd + milvus，无独立对象存储）
docker compose -f milvus-mini.compose.yaml up -d
```

容器名与端口：`ragent-etcd` (2379)、`ragent-minio` (19000)、`ragent-milvus` (19530)、`ragent-redis` (16379)

## Code Formatting

- **后端**: Spotless Maven Plugin 在 `compile` 阶段自动执行。每个 Java 文件必须包含 Apache 2.0 License Header（模板在 `resources/format/copyright.txt`）。新增 Java 文件如果缺少 license header，`mvnw compile` 会自动添加。
- **前端**: Prettier + ESLint。
- **Lombok**: 项目全局使用 Lombok，配置在根目录 `lombok.config`。`@EqualsAndHashCode` 默认 `callSuper = skip`。

## Architecture (Module Dependency)

```
frontend (React)  -->  bootstrap (Spring Boot 入口 + 业务实现)
                           ├── framework   (通用基础: Result/Exception/Context/Trace/Idempotent/SnowflakeId)
                           ├── infra-ai    (AI 基础设施: Chat/Embedding/Rerank 客户端 + 模型路由)
                           └── mcp-server  (MCP 扩展位，当前为空)
```

Base package: `com.nageoffer.ai.ragent`

### bootstrap 模块 — 核心业务包

| 包路径 | 职责 |
|--------|------|
| `rag.service.impl` | RAG v3 主编排（RAGChatServiceImpl） |
| `rag.core.retrieve` | 检索内核：MultiChannelRetrievalEngine（多通道并行 → 后处理器链） |
| `rag.core.mcp` | MCP 工具调用链（MCPToolExecutor / MCPToolRegistry） |
| `rag.core.intent` | 意图识别（IntentResolver / IntentClassifier） |
| `rag.core.memory` | 会话记忆与摘要（DefaultConversationMemoryService） |
| `rag.aop` | RagTraceAspect（链路追踪）、ChatQueueLimiter（队列限流） |
| `ingestion.engine` | Ingestion Pipeline 执行引擎（IngestionEngine） |
| `ingestion.node` | Pipeline 节点：Fetcher → Parser → Enhancer → Chunker → Enricher → Indexer |
| `knowledge` | 知识库 CRUD + 向量集合管理 |
| `user` | 用户认证（SA-Token） |

### infra-ai 模块 — AI 路由核心

- `RoutingLLMService` / `RoutingEmbeddingService` / `RoutingRerankService`：按优先级选择候选模型，失败自动切换，支持三态熔断（CLOSED → OPEN → HALF_OPEN）。
- `ChatClient` 实现：OllamaChatClient / BaiLianChatClient / SiliconFlowChatClient / MiniMaxChatClient。
- `FirstPacketAwaiter`：流式首包探测（CountDownLatch + AtomicBoolean），避免失败模型的半截输出污染前端。
- `ModelSelector` + `ModelHealthStore`：模型健康状态与动态选择。

### framework 模块 — 通用基础

- `Result<T>` + `BaseErrorCode` + `GlobalExceptionHandler`：统一返回值与异常处理。
- `UserContext`（ThreadLocal）+ `LoginUser`：请求级用户上下文传递。
- `RagTraceContext`（TransmittableThreadLocal）：RAG 链路追踪，支持 push/pop 嵌套节点。

## Key Data Flow (RAG v3 Chat)

```
GET /rag/v3/chat (SSE)
  → 会话初始化 (conversationId / taskId)
  → 记忆加载 (history + summary 并行)
  → Query Rewrite + 多问句拆分
  → 意图识别 → 歧义引导判定
  → RetrievalEngine
      ├── KB: MultiChannelRetrievalEngine (多通道并行 → 后处理器链)
      └── MCP: 参数抽取 → 工具执行 → 结果聚合
  → RAGPromptService (组装 system/context/history/user)
  → RoutingLLMService (模型选择/流式首包探测/失败切换)
  → SSE 推送 (meta/message/finish/done)
  → 消息落库 / 标题生成 / trace 记录
```

## Extension Points

| 扩展点 | 接口 | 位置 |
|--------|------|------|
| 新增检索通道 | `SearchChannel` | `rag.core.retrieve.channel` |
| 新增后处理器 | `SearchResultPostProcessor` | `rag.core.retrieve.postprocessor` |
| 新增 MCP 工具 | `MCPToolExecutor`（Spring Bean 自动发现） | `rag.core.mcp.executor` |
| 新增 Ingestion 节点 | `IngestionNode` | `ingestion.node` |
| 新增模型提供商 | `ChatClient` / `EmbeddingClient` / `RerankClient` | `infra-ai` 对应包 |
| 新增分块策略 | 继承 `AbstractEmbeddingChunker` | `core.chunk.strategy` |

## Frontend Architecture

- **路由**: React Router 6，认证路由守卫在 `router.tsx`
- **状态管理**: Zustand stores（`authStore` / `chatStore` / `themeStore`）
- **UI 组件**: Radix UI 原语 + Tailwind CSS，封装在 `components/ui/`
- **API 层**: Axios 实例在 `services/api.ts`，各领域 service 文件独立
- **SSE 流式**: `hooks/useStreamResponse.ts` 处理流式对话
- **路径别名**: `@` → `./src`（在 `vite.config.ts` 和 `tsconfig.app.json` 中配置）
- **管理后台**: `/admin/*` 路由，需要 admin 角色

## Key Configuration (application.yaml)

- 服务端口: `8080`，上下文路径: `/api/ragent`
- 数据库: MySQL 8+（`ragent` 库），建表脚本: `resources/database/schema_table.sql`
- 初始数据: `resources/database/init_data.sql`（默认管理员 admin/admin）
- 认证: SA-Token，token 通过 `satoken` header 传递，存 Redis
- 向量库: Milvus 2.6（默认 `localhost:19530`）
- 文件存储: MinIO/RustFS（S3 兼容，默认 `localhost:19000`）
- AI 模型配置在 `ai.providers.*` 下，支持 Ollama / Bailian / SiliconFlow / MiniMax
- 模型路由容错: `ai.selection.failure-threshold` + `ai.selection.open-duration-ms`
- `application-local.yaml` profile：用于本地 Docker 环境（MySQL 13306 / Redis 16379），通过 `--spring.profiles.active=local` 激活

## SSE Event Protocol (v3)

事件类型: `meta` → `message`(delta) → `finish`/`cancel`/`reject` → `done`

`message.type`: `"response"` 或 `"think"`（深度思考）

## Conventions

- DAO 层使用 MyBatis-Plus，实体类后缀 `DO`，Mapper 接口后缀 `Mapper`
- 请求对象后缀 `Request`，响应 VO 后缀 `VO`
- 数据库表名前缀 `t_`（如 `t_conversation`、`t_message`、`t_knowledge_base`）
- MyBatis-Plus 自动填充 `createTime` / `updateTime` 字段（`MyMetaObjectHandler`）
- 分布式 ID 使用 Snowflake 算法（`CustomIdentifierGenerator`）
- 线程池统一配置在 `ThreadPoolExecutorConfig`，所有 Executor Bean 经 `TtlExecutors` 包装以支持 TransmittableThreadLocal 上下文传播
- 前端 Vite 代理 `/api` → `http://localhost:8080`

## Known Issues & Gotchas

- **Milvus 版本**: SDK v2.6.6 使用 `MilvusClientV2`，要求 Milvus Server ≥ v2.4。`milvus-full.compose.yaml` 中需确保镜像版本为 `milvusdb/milvus:v2.6.6`，使用旧版镜像（如 v2.3.0）会导致 gRPC `DEADLINE_EXCEEDED`。
- **macOS 大小写**: macOS APFS 默认 case-insensitive，Java package 目录名大小写不一致时会导致 `NoClassDefFoundError`（JVM 按路径查找但 class 声明不匹配）。新建测试文件时注意目录和 `package` 声明大小写一致。
- **Mockito surefire 配置**: pom.xml 中 surefire plugin 的 `argLine` 使用 `-javaagent:${org.mockito:mockito-core:jar}` 解析 Mockito agent 路径，依赖 `maven-dependency-plugin:properties` goal。不要添加 `@{argLine}` 前缀（除非有 JaCoCo 等插件设置该属性）。
- **Spotless 格式化**: 新建 Java 文件后首次 `./mvnw compile` 会自动添加 license header 并可能重新格式化代码，这是正常行为。
