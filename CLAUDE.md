# CLAUDE.md
每次回复叫我启云
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Positioning

- 本仓库的工程基线是 **RAgent Second Brain**，采用 Monorepo：后端 Maven 多模块 + 前端 React。
- 当前技术命名以代码为准：Java 包前缀为 `com.openingcloud.ai.ragent`，产品定位为个人第二大脑与知识中枢。
- 技术栈（以 `pom.xml`/`frontend/package.json` 为准）：Java 17、Spring Boot 3.5.7、MyBatis-Plus、React 18、Vite、TypeScript、Milvus 2.6.6、Redis、MySQL、S3 兼容存储。
- 本文件是执行规范，不是宣传文档。新增内容必须可在仓库中被命令、配置、类名或路径验证。



## RAG Core Flow

RAG v3 对话链路（`GET /rag/v3/chat`）：

1. `RAGChatController#chat` 创建 `SseEmitter`，进入 `RAGChatServiceImpl#streamChat`。
2. `ChatRateLimitAspect` + `ChatQueueLimiter` 执行全局排队与并发控制（Redis + Redisson）。
3. 会话初始化与历史加载：`ConversationService` + `ConversationMemoryService#loadAndAppend`。
4. Query 重写与拆分：`QueryRewriteService#rewriteWithSplit`。
5. 意图识别：`IntentResolver#resolve`，歧义引导：`IntentGuidanceService#detectAmbiguity`。
6. 检索聚合：`RetrievalEngine#retrieve`
   - KB：`MultiChannelRetrievalEngine`（并行 `SearchChannel`）
   - MCP：参数抽取 + `MCPService` 批量执行 + 结果聚合
7. Prompt 组装：`RAGPromptService#buildStructuredMessages`。
8. 模型路由与流式输出：`LLMService` 默认由 `RoutingLLMService` 实现，含首包探测和失败切换。
9. 流式事件回传：`StreamCallbackFactory` / `StreamChatEventHandler`。
10. 结束后持久化消息、会话元数据与 trace 记录。

## Document Ingestion Pipeline

- 引擎入口：`ingestion.engine.IngestionEngine`
- 节点接口：`ingestion.node.IngestionNode`
- 节点类型（`IngestionNodeType`）：`fetcher -> parser -> enhancer -> chunker -> enricher -> indexer`
- 节点实现位置：`bootstrap/src/main/java/com/openingcloud/ai/ragent/ingestion/node/`
- 常见数据源抓取器：`ingestion.strategy.fetcher`（`LocalFileFetcher`、`HttpUrlFetcher`、`S3Fetcher`、`FeishuFetcher`）
- Pipeline/任务编排服务：`ingestion.service.IngestionPipelineService`、`IngestionTaskService`
- 知识库上传后触发链路：`knowledge.service.impl.KnowledgeDocumentServiceImpl` -> `IngestionEngine`

## Production-Grade Features

- **多模型路由与容错**：`RoutingLLMService`、`RoutingEmbeddingService`、`RoutingRerankService`
  - 优先级候选模型选择：`ModelSelector`
  - 三态熔断：`ModelHealthStore`（`CLOSED -> OPEN -> HALF_OPEN`）
  - 首包探测缓冲：`FirstPacketAwaiter` + `ProbeBufferingCallback`
- **多路检索 + 后处理流水线**：
  - 通道接口：`SearchChannel`
  - 后处理接口：`SearchResultPostProcessor`
  - 当前后处理器链（按 order 执行）：`DeduplicationPostProcessor`(1) → `RRFPostProcessor`(5) → `RerankPostProcessor`(10) → `QualityFilterPostProcessor`(20)
- **队列式并发限流**：
  - 注解切面：`@ChatRateLimit` + `ChatRateLimitAspect`
  - 排队器：`ChatQueueLimiter`（ZSET 队列、信号量、Pub/Sub 通知）
- **可观测性**：
  - 根追踪：`@RagTraceRoot`
  - 节点追踪：`@RagTraceNode`
  - AOP 记录：`RagTraceAspect`、`RagTraceRecordService`
- **会话记忆治理**：
  - 滑窗 + 摘要 + TTL：`DefaultConversationMemoryService` 与 `MemoryProperties`
- **认证鉴权**：
  - Sa-Token（`sa-token` 配置 + `user/config`）
  - token header 使用 `Authorization`
- **线程池隔离与上下文透传**：
  - `ThreadPoolExecutorConfig` 定义多个独立 Executor
  - 全量由 `TtlExecutors` 包装，透传 `TransmittableThreadLocal`

## Extension Points

| 扩展目标 | 接口/抽象 | 位置 |
|---|---|---|
| 新增检索通道 | `SearchChannel` | `rag/core/retrieve/channel` |
| 新增检索后处理器 | `SearchResultPostProcessor` | `rag/core/retrieve/postprocessor` |
| 新增 MCP 工具 | `MCPToolExecutor`（Spring Bean 自动发现） | `rag/core/mcp/executor` |
| 新增入库节点 | `IngestionNode` | `ingestion/node` |
| 新增模型供应商 | `ChatClient` / `EmbeddingClient` / `RerankClient` | `infra-ai/src/main/java/.../chat|embedding|rerank` |
| 新增分块策略 | 继承 `AbstractEmbeddingChunker` | `core/chunk/strategy` |

## Design Patterns

项目中落地的 7 种经典设计模式。每条均可通过类名在仓库中 `grep -r "class ClassName"` 验证。

### 策略模式（Strategy）

通过接口抽象可替换算法，运行时由 Spring DI `List<Interface>` 注入所有实现，Routing 层按优先级 + 熔断状态选择。

| 策略接口 | 实现类 | 位置 |
|---|---|---|
| `ChatClient` | `BaiLianChatClient`、`SiliconFlowChatClient`、`MiniMaxChatClient`、`OllamaChatClient` | `infra-ai/.../chat` |
| `EmbeddingClient` | `OllamaEmbeddingClient`、`SiliconFlowEmbeddingClient` | `infra-ai/.../embedding` |
| `RerankClient` | `BaiLianRerankClient`、`SiliconFlowRerankClient`、`NoopRerankClient` | `infra-ai/.../rerank` |
| `SearchChannel` | `VectorGlobalSearchChannel`、`IntentDirectedSearchChannel` | `rag/core/retrieve/channel` |
| `ChunkingStrategy` | `FixedSizeTextChunker`、`SentenceChunker`、`ParagraphChunker`、`StructureAwareTextChunker` | `core/chunk/strategy` |
| `DocumentFetcher` | `LocalFileFetcher`、`HttpUrlFetcher`、`S3Fetcher`、`FeishuFetcher` | `ingestion/strategy/fetcher` |

路由选择器：`ModelSelector`（优先级）+ `ModelHealthStore`（三态熔断）。

### 工厂模式（Factory）

| 工厂类 | 创建产品 | 注册机制 |
|---|---|---|
| `ChunkingStrategyFactory` | `ChunkingStrategy` 实例 | `Map<ChunkingMethod, ChunkingStrategy>` 注册 |
| `StreamCallbackFactory` | `StreamCallback` 实例 | 按场景创建不同回调组合 |

位置：`core/chunk/ChunkingStrategyFactory`、`rag/service/handler/StreamCallbackFactory`。

### 观察者模式（Observer）

- **流式回调**：`StreamCallback` 接口定义 `onToken`/`onComplete`/`onError` 事件钩子，`StreamChatEventHandler` 和 `ProbeBufferingCallback` 作为观察者响应 LLM 输出。
- **分布式 Pub/Sub**：`StreamTaskManager` 通过 Redis `RTopic` 发布停止信号，订阅者收到后中断流式生成。

接口位置：`infra-ai/.../chat/StreamCallback`。
实现位置：`rag/service/handler/StreamChatEventHandler`、`RoutingLLMService$ProbeBufferingCallback`（内部类）。

### 装饰器模式（Decorator）

在不修改原始对象接口的前提下透明增强功能：

- **`ProbeBufferingCallback`**：包装 `StreamCallback`，缓冲首批 token 用于探测模型连通性，探测成功后将缓冲内容回放给被装饰的回调。位于 `RoutingLLMService` 内部类。
- **`TtlExecutors`**：包装 `ThreadPoolExecutor`（项目中 9 个独立线程池全部包装），透明添加 `TransmittableThreadLocal` 上下文透传能力。配置在 `ThreadPoolExecutorConfig`。
- **AOP 切面**：`ChatRateLimitAspect`（透明添加限流）、`RagTraceAspect`（透明添加链路追踪），对业务方法无侵入增强。

### 模板方法模式（Template Method）

在抽象基类中定义算法骨架（final 方法），将可变步骤延迟到子类实现。

| 抽象基类 | 模板方法 | 抽象钩子 | 子类 |
|---|---|---|---|
| `AbstractEmbeddingChunker` | `chunk`（final） | `doChunk` | `FixedSizeTextChunker`、`SentenceChunker`、`ParagraphChunker`、`StructureAwareTextChunker` |
| `AbstractParallelRetriever<T>` | `retrieve`（final） | `createRetrievalTask`、`getTargetIdentifier`、`getStatisticsName` | `CollectionParallelRetriever`、`IntentParallelRetriever` |

位置：`core/chunk/AbstractEmbeddingChunker`、`rag/core/retrieve/channel/AbstractParallelRetriever`。

### 责任链模式（Chain of Responsibility）

请求沿链传递，每个处理器决定处理后继续传递。

- **检索后处理器链**：`SearchResultPostProcessor` 接口声明 `getOrder()` 确定执行顺序，`MultiChannelRetrievalEngine` 按 order 排序后依次执行。当前链路：`DeduplicationPostProcessor`(1) → `RRFPostProcessor`(5) → `RerankPostProcessor`(10) → `QualityFilterPostProcessor`(20)。位于 `rag/core/retrieve/postprocessor`。
- **入库流水线**：`IngestionNode` 各节点通过 `nextNodeId` 字段链式串联，`IngestionEngine` 按链路顺序执行 `fetcher → parser → enhancer → chunker → enricher → indexer`。位于 `ingestion/node`。

### 外观模式（Facade）

对外提供统一入口，内部编排多个子系统协作，屏蔽复杂性。

| 门面类 | 聚合的子系统 |
|---|---|
| `RAGChatServiceImpl` | `ConversationService`、`ConversationMemoryService`、`QueryRewriteService`、`IntentResolver`、`RetrievalEngine`、`RAGPromptService`、`LLMService`、`StreamCallbackFactory`、`ChatQueueLimiter` 等 11 个依赖 |
| `MultiChannelRetrievalEngine` | `SearchChannel`(多通道)、`SearchResultPostProcessor`(后处理器链)、`QueryTermMappingService` |
| `RetrievalEngine` | `MultiChannelRetrievalEngine`(KB 检索)、`MCPService`(MCP 工具调用)、`LLMMCPParameterExtractor`(参数抽取) |

## SSE Protocol

后端入口：`RAGChatController#chat` (`GET /rag/v3/chat`, `text/event-stream`)

标准事件顺序：

1. `meta`
2. `message`（delta，`type` 为 `response` 或 `think`）
3. `finish` / `cancel` / `reject`
4. `done`

补充事件：

- `error`（定义在 `SSEEventType`，异常路径使用）

任务终止接口：

- `POST /rag/v3/stop?taskId=...`

## Code Conventions

- DAO 命名：实体后缀 `DO`，Mapper 后缀 `Mapper`（MyBatis-Plus）。
- API DTO 命名：请求后缀 `Request`，响应后缀 `VO`。
- 表命名：`t_` 前缀（例如 `t_conversation`、`t_message`、`t_knowledge_base`）。
- ID 生成：Snowflake（`CustomIdentifierGenerator`）。
- 统一返回与异常：`Result<T>` + `BaseErrorCode` + `GlobalExceptionHandler`。
- 上下文约定：
  - 用户上下文：`UserContext`
  - Trace 上下文：`RagTraceContext`（TTL 透传）
- 格式化约定：
  - Java：Spotless 在 `compile` 阶段自动执行，license 模板在 `resources/format/copyright.txt`
  - Frontend：`eslint` + `prettier`

## Known Gotchas

- **Milvus 版本必须匹配**：SDK 为 `2.6.6`，compose 镜像也应使用 `milvusdb/milvus:v2.6.6`；混用老版本易导致调用异常。
- **Spotless 会修改代码**：首次 `./mvnw compile` 可能自动补 license header 并重排格式，属于预期行为。
- **Surefire Mockito agent 不要随意改**：`pom.xml` 中 `maven-surefire-plugin` 依赖 `-javaagent:${org.mockito:mockito-core:jar}`。
- **macOS 大小写陷阱**：包路径目录名与 `package` 声明大小写不一致会触发类加载问题。
- **限流参数默认偏保守**：`rag.rate-limit.global.max-concurrent=1`，并发压测前先评估并调参。
