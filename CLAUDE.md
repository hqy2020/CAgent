# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

RAgent 是一个企业级 RAG（Retrieval-Augmented Generation）智能体平台，采用 Monorepo 结构。后端基于 Spring Boot 3.5 + Java 17，前端基于 React 18 + Vite + TypeScript。核心能力包括：多通道知识库检索、MCP 工具调用、会话记忆、流式 SSE 输出、链路追踪、文档 Ingestion Pipeline。

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
# 启动 Milvus + RustFS + etcd + Attu
cd resources/docker/milvus
docker compose -f milvus-stack-2.6.6.compose.yaml up -d
```

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
| `rag.controller` | RAG 对话入口，SSE 流式 |
| `rag.service.impl` | RAG v3 主编排（RAGChatServiceImpl） |
| `rag.core.retrieve` | 检索内核：RetrievalEngine + MultiChannelRetrievalEngine |
| `rag.core.retrieve.channel.strategy` | 检索通道：VectorGlobalSearchChannel、IntentDirectedSearchChannel |
| `rag.core.retrieve.postprocessor.impl` | 后处理器：DeduplicationPostProcessor、RerankPostProcessor |
| `rag.core.prompt` | Prompt 编排（RAGPromptService） |
| `rag.core.mcp` | MCP 工具调用链（MCPToolExecutor / MCPToolRegistry / MCPParameterExtractor） |
| `rag.core.rewrite` | Query Rewrite + 多问句拆分 |
| `rag.core.intent` | 意图识别 |
| `rag.core.memory` | 会话记忆与摘要 |
| `rag.core.guidance` | 歧义引导 |
| `rag.aop` | RagTraceAspect（链路追踪）、ChatRateLimitAspect（限流） |
| `ingestion.engine` | Ingestion Pipeline 执行引擎 |
| `ingestion.node` | Pipeline 节点：Fetcher → Parser → Enhancer → Chunker → Enricher → Indexer |
| `ingestion.strategy.fetcher` | 抓取策略：LocalFile / HttpUrl / S3 / Feishu |
| `knowledge` | 知识库 CRUD + 向量集合管理 |
| `admin` | 管理后台 Dashboard |
| `user` | 用户认证（SA-Token） |
| `core.chunk.strategy` | 分块策略：FixedSize / Paragraph / Sentence / StructureAware |
| `core.parser` | 文档解析：Tika（PDF/DOC/DOCX）+ Markdown |

### infra-ai 模块 — AI 路由核心

- `RoutingLLMService` / `RoutingEmbeddingService` / `RoutingRerankService`：按优先级选择候选模型，失败自动切换，支持熔断恢复。
- `ChatClient` 实现：OllamaChatClient / BaiLianChatClient / SiliconFlowChatClient。
- `FirstPacketAwaiter`：流式首包探测，避免失败模型的半截输出污染前端。
- `ModelSelector` + `ModelHealthStore`：模型健康状态与动态选择。

### framework 模块 — 通用基础

- `Result<T>` + `BaseErrorCode` + `GlobalExceptionHandler`：统一返回值与异常处理。
- `UserContext`（ThreadLocal）+ `LoginUser`：请求级用户上下文传递。
- `@IdempotentSubmit` / `@IdempotentConsume`：基于 AOP + SpEL 的幂等控制。
- `RagTraceContext` / `RagTraceNode`：RAG 链路追踪数据结构。
- `SseEmitterSender`：SSE 发送封装。
- `SnowflakeIdInitializer`：分布式 ID 生成。

## Key Data Flow (RAG v3 Chat)

```
GET /rag/v3/chat (SSE)
  → 会话初始化 (conversationId / taskId)
  → 记忆加载 (history)
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
| 新增文档解析器 | `DocumentParser` | `core.parser` |
| 新增分块策略 | 继承 `AbstractEmbeddingChunker` | `core.chunk.strategy` |
| 新增抓取策略 | `DocumentFetcher` | `ingestion.strategy.fetcher` |

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
- 认证: SA-Token，token 存 Redis
- 向量库: Milvus 2.6（默认 `localhost:19530`）
- 文件存储: RustFS（S3 兼容，默认 `localhost:9000`）
- AI 模型配置在 `ai.providers.*` 下，支持 Ollama / Bailian / SiliconFlow
- 模型路由容错: `ai.selection.failure-threshold` + `ai.selection.open-duration-ms`

## SSE Event Protocol (v3)

事件类型: `meta` → `message`(delta) → `finish`/`cancel`/`reject` → `done`

`message.type`: `"response"` 或 `"think"`（深度思考）

## Conventions

- DAO 层使用 MyBatis-Plus，实体类后缀 `DO`，Mapper 接口后缀 `Mapper`
- 请求对象后缀 `Request`，响应 VO 后缀 `VO`
- 数据库表名前缀 `t_`（如 `t_conversation`、`t_message`、`t_knowledge_base`）
- MyBatis-Plus 自动填充 `createTime` / `updateTime` 字段（`MyMetaObjectHandler`）
- 分布式 ID 使用 Snowflake 算法（`CustomIdentifierGenerator`）
- 前端 Vite 代理 `/api` → `http://localhost:8080`
