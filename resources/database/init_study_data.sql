-- ============================================================
-- 学习中心 & 面试题库 初始数据
-- ============================================================

SET NAMES utf8mb4;

-- -------------------- 学习模块 --------------------
INSERT INTO `t_study_module` (`id`, `name`, `description`, `icon`, `sort_order`, `enabled`, `create_time`, `update_time`) VALUES
(1001, 'RAgent 系统架构', 'Monorepo 结构、分层设计、技术选型', 'Layers', 1, 1, NOW(), NOW()),
(1002, 'RAG 检索流程', '意图识别、多通道检索、Rerank、Prompt 编排', 'Search', 2, 1, NOW(), NOW()),
(1003, 'Ingestion Pipeline', '流水线节点体系、分块策略、文档解析', 'Upload', 3, 1, NOW(), NOW()),
(1004, '模型路由与调用', '多提供商抽象、路由策略、熔断恢复、流式首包探测', 'Cpu', 4, 1, NOW(), NOW()),
(1005, '前端架构', 'AdminLayout、Zustand 状态管理、SSE 流式处理', 'Monitor', 5, 1, NOW(), NOW());

-- -------------------- 学习章节 --------------------
-- 模块1: RAgent 系统架构
INSERT INTO `t_study_chapter` (`id`, `module_id`, `title`, `summary`, `sort_order`, `create_time`, `update_time`) VALUES
(2001, 1001, 'Monorepo 模块划分', '了解 bootstrap / framework / infra-ai / mcp-server 四大模块的职责与依赖关系', 1, NOW(), NOW()),
(2002, 1001, '核心业务包结构', '深入 bootstrap 模块中的 rag / ingestion / knowledge / user 等业务包', 2, NOW(), NOW()),
(2003, 1001, '技术选型与基础设施', 'Spring Boot 3.5、MyBatis-Plus、SA-Token、Milvus、RustFS 等技术选型理由', 3, NOW(), NOW());

-- 模块2: RAG 检索流程
INSERT INTO `t_study_chapter` (`id`, `module_id`, `title`, `summary`, `sort_order`, `create_time`, `update_time`) VALUES
(2004, 1002, 'RAG v3 主编排流程', '从 SSE 请求入口到最终响应的完整链路', 1, NOW(), NOW()),
(2005, 1002, '多通道检索引擎', 'RetrievalEngine、MultiChannelRetrievalEngine、SearchChannel 体系', 2, NOW(), NOW()),
(2006, 1002, 'Prompt 编排与记忆', 'RAGPromptService、会话记忆、Query Rewrite', 3, NOW(), NOW());

-- 模块3: Ingestion Pipeline
INSERT INTO `t_study_chapter` (`id`, `module_id`, `title`, `summary`, `sort_order`, `create_time`, `update_time`) VALUES
(2007, 1003, '流水线节点体系', 'Fetcher → Parser → Enhancer → Chunker → Enricher → Indexer 六大节点', 1, NOW(), NOW()),
(2008, 1003, '分块策略详解', 'FixedSize / Paragraph / Sentence / StructureAware 四种策略对比', 2, NOW(), NOW());

-- 模块4: 模型路由与调用
INSERT INTO `t_study_chapter` (`id`, `module_id`, `title`, `summary`, `sort_order`, `create_time`, `update_time`) VALUES
(2009, 1004, '模型路由核心机制', 'RoutingLLMService 按优先级选择候选模型、失败自动切换', 1, NOW(), NOW()),
(2010, 1004, '流式首包探测与熔断', 'FirstPacketAwaiter、ModelHealthStore、熔断恢复策略', 2, NOW(), NOW());

-- 模块5: 前端架构
INSERT INTO `t_study_chapter` (`id`, `module_id`, `title`, `summary`, `sort_order`, `create_time`, `update_time`) VALUES
(2011, 1005, '管理后台布局体系', 'AdminLayout 侧边栏、面包屑、路由守卫设计', 1, NOW(), NOW()),
(2012, 1005, '状态管理与流式通信', 'Zustand stores、useStreamResponse Hook、SSE 事件协议', 2, NOW(), NOW());

-- -------------------- 学习文档 --------------------
-- 模块1 章节1: Monorepo 模块划分
INSERT INTO `t_study_document` (`id`, `chapter_id`, `module_id`, `title`, `content`, `create_time`, `update_time`) VALUES
(3001, 2001, 1001, 'RAgent Monorepo 模块划分详解', '# RAgent Monorepo 模块划分

## 整体架构

RAgent 采用 Maven 多模块（Monorepo）结构，分为 4 个核心模块：

```
frontend (React)  -->  bootstrap (Spring Boot 入口 + 业务实现)
                           ├── framework   (通用基础)
                           ├── infra-ai    (AI 基础设施)
                           └── mcp-server  (MCP 扩展位)
```

## 模块职责

### bootstrap — 业务入口
- Spring Boot 启动类 `RagentApplication` 所在模块
- 包含所有业务实现：RAG 对话、知识库管理、Ingestion 引擎、用户认证
- 依赖 framework 和 infra-ai

### framework — 通用基础
- `Result<T>` 统一返回值 + `GlobalExceptionHandler` 全局异常处理
- `UserContext`（ThreadLocal）请求级用户上下文
- `@IdempotentSubmit` / `@IdempotentConsume` 幂等控制
- `RagTraceContext` / `RagTraceNode` 链路追踪数据结构
- `SnowflakeIdInitializer` 分布式 ID 生成

### infra-ai — AI 路由核心
- `RoutingLLMService` / `RoutingEmbeddingService` / `RoutingRerankService`
- 多提供商 ChatClient 实现：Ollama / BaiLian / SiliconFlow
- `FirstPacketAwaiter` 流式首包探测
- `ModelSelector` + `ModelHealthStore` 模型健康管理

### mcp-server — MCP 扩展位
- 当前为空模块，预留给 MCP 工具扩展

## 依赖方向
模块依赖严格单向：`bootstrap → framework`、`bootstrap → infra-ai`，framework 和 infra-ai 之间无依赖。', NOW(), NOW());

-- 模块1 章节2: 核心业务包结构
INSERT INTO `t_study_document` (`id`, `chapter_id`, `module_id`, `title`, `content`, `create_time`, `update_time`) VALUES
(3002, 2002, 1001, 'bootstrap 核心业务包结构', '# bootstrap 核心业务包结构

## 包路径总览

Base package: `com.nageoffer.ai.ragent`

| 包路径 | 职责 |
|--------|------|
| `rag.controller` | RAG 对话入口，SSE 流式 |
| `rag.service.impl` | RAG v3 主编排（RAGChatServiceImpl） |
| `rag.core.retrieve` | 检索内核：RetrievalEngine + MultiChannelRetrievalEngine |
| `rag.core.retrieve.channel.strategy` | 检索通道：VectorGlobalSearchChannel、IntentDirectedSearchChannel |
| `rag.core.retrieve.postprocessor.impl` | 后处理器：DeduplicationPostProcessor、RerankPostProcessor |
| `rag.core.prompt` | Prompt 编排（RAGPromptService） |
| `rag.core.mcp` | MCP 工具调用链 |
| `rag.core.rewrite` | Query Rewrite + 多问句拆分 |
| `rag.core.intent` | 意图识别 |
| `rag.core.memory` | 会话记忆与摘要 |
| `ingestion.engine` | Ingestion Pipeline 执行引擎 |
| `ingestion.node` | Pipeline 节点实现 |
| `knowledge` | 知识库 CRUD + 向量集合管理 |
| `user` | 用户认证（SA-Token） |

## 设计原则
- 每个业务域独立成包，包内按 controller / service / dao 分层
- DAO 层使用 MyBatis-Plus，实体类后缀 `DO`，Mapper 接口后缀 `Mapper`
- 请求对象后缀 `Request`，响应 VO 后缀 `VO`', NOW(), NOW());

-- 模块1 章节3: 技术选型
INSERT INTO `t_study_document` (`id`, `chapter_id`, `module_id`, `title`, `content`, `create_time`, `update_time`) VALUES
(3003, 2003, 1001, '技术选型与基础设施', '# 技术选型与基础设施

## 后端技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5 | 应用框架 |
| Java | 17 | 编程语言 |
| MyBatis-Plus | — | ORM 框架，简化 CRUD |
| SA-Token | — | 轻量认证框架 |
| Lombok | — | 减少样板代码 |
| Spotless | — | 代码格式化（含 License Header） |

## 基础设施

| 组件 | 用途 |
|------|------|
| MySQL 8+ | 业务数据存储 |
| Redis | SA-Token Session + 缓存 |
| Milvus 2.6 | 向量数据库 |
| RustFS | S3 兼容文件存储 |
| etcd | Milvus 元数据存储 |

## 为什么选择这些技术？

### Milvus 而非 Pinecone/Weaviate
- 开源自托管，数据不出域
- 支持多种索引类型（IVF_FLAT、HNSW 等）
- 与 Java 生态有良好的 SDK 支持

### SA-Token 而非 Spring Security
- 配置简洁，API 友好
- 内置 Token 管理、角色/权限校验
- 与 Redis 集成开箱即用', NOW(), NOW());

-- 模块2 章节1: RAG v3 主编排流程
INSERT INTO `t_study_document` (`id`, `chapter_id`, `module_id`, `title`, `content`, `create_time`, `update_time`) VALUES
(3004, 2004, 1002, 'RAG v3 主编排流程', '# RAG v3 主编排流程

## 完整数据流

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

## SSE 事件协议

事件类型按顺序推送：`meta` → `message`(delta) → `finish`/`cancel`/`reject` → `done`

- **meta**: 包含 taskId、conversationId 等元信息
- **message**: 增量文本，`type` 为 `response` 或 `think`（深度思考）
- **finish**: 正常完成
- **done**: 流结束标记

## 核心编排类
`RAGChatServiceImpl` 是 v3 版本的主编排器，负责协调以上所有环节。', NOW(), NOW());

-- 模块2 章节2: 多通道检索引擎
INSERT INTO `t_study_document` (`id`, `chapter_id`, `module_id`, `title`, `content`, `create_time`, `update_time`) VALUES
(3005, 2005, 1002, '多通道检索引擎', '# 多通道检索引擎

## 架构设计

检索引擎采用「通道 + 后处理器」的管道模式：

```
用户 Query
  → MultiChannelRetrievalEngine
      ├── VectorGlobalSearchChannel    (全局向量检索)
      ├── IntentDirectedSearchChannel  (意图定向检索)
      └── ... (可扩展更多通道)
  → PostProcessor Chain
      ├── DeduplicationPostProcessor   (去重)
      └── RerankPostProcessor          (重排序)
  → 最终检索结果
```

## 核心接口

### SearchChannel
每个检索通道实现 `SearchChannel` 接口，定义如何从知识库中检索相关文档片段。

### SearchResultPostProcessor
后处理器链对多通道结果进行统一处理：去重、重排序、过滤等。

## 扩展方式
新增检索通道：实现 `SearchChannel` 接口，注册为 Spring Bean 即可自动发现。', NOW(), NOW());

-- 模块2 章节3: Prompt 编排与记忆
INSERT INTO `t_study_document` (`id`, `chapter_id`, `module_id`, `title`, `content`, `create_time`, `update_time`) VALUES
(3006, 2006, 1002, 'Prompt 编排与记忆', '# Prompt 编排与记忆

## RAGPromptService

负责将检索到的知识、会话历史、用户问题组装成完整的 Prompt：

```
[System Message]  — 系统角色设定
[Context Message] — 检索到的知识库片段
[History Messages] — 会话记忆（最近 N 轮）
[User Message]    — 当前用户问题
```

## 会话记忆机制

### 短期记忆
直接加载最近 N 条 `t_message` 记录作为历史上下文。

### 长期记忆（摘要）
当对话轮次超过阈值时，通过 LLM 生成会话摘要存储到 `t_conversation_summary`，后续轮次用摘要替代原始消息。

## Query Rewrite
对用户原始问题进行改写，解决指代消解和语义补全问题。同时支持多问句拆分，将复合问题拆解为独立子查询。', NOW(), NOW());

-- 模块3 章节1: 流水线节点体系
INSERT INTO `t_study_document` (`id`, `chapter_id`, `module_id`, `title`, `content`, `create_time`, `update_time`) VALUES
(3007, 2007, 1003, '流水线节点体系', '# Ingestion Pipeline 流水线节点体系

## 六大节点

```
Fetcher → Parser → Enhancer → Chunker → Enricher → Indexer
```

| 节点 | 职责 | 示例实现 |
|------|------|---------|
| Fetcher | 获取原始文档 | LocalFile / HttpUrl / S3 / Feishu |
| Parser | 解析文档为文本 | Tika（PDF/DOC/DOCX）/ Markdown |
| Enhancer | 文本增强 | 清洗、格式化 |
| Chunker | 文本分块 | FixedSize / Paragraph / Sentence / StructureAware |
| Enricher | 分块富化 | 添加元数据、生成摘要 |
| Indexer | 写入向量库 | Milvus Collection 写入 + Embedding |

## 流水线配置
流水线通过 `t_ingestion_pipeline` 和 `t_ingestion_pipeline_node` 两张表持久化，支持自定义节点组合和参数配置。

## 执行记录
每次执行生成 `t_ingestion_task` 记录，每个节点的执行详情记录在 `t_ingestion_task_node` 中，便于排查问题。', NOW(), NOW());

-- 模块3 章节2: 分块策略
INSERT INTO `t_study_document` (`id`, `chapter_id`, `module_id`, `title`, `content`, `create_time`, `update_time`) VALUES
(3008, 2008, 1003, '分块策略详解', '# 分块策略详解

## 四种策略对比

| 策略 | 适用场景 | 特点 |
|------|---------|------|
| FixedSize | 通用文档 | 按固定字符数切分，简单高效 |
| Paragraph | 段落清晰的文档 | 按段落边界切分，保持语义完整性 |
| Sentence | 精细检索场景 | 按句子边界切分，粒度最细 |
| StructureAware | Markdown/HTML | 识别标题层级，按结构切分 |

## 实现继承体系

所有分块策略继承 `AbstractEmbeddingChunker`，需要实现 `doChunk()` 方法。

## 如何选择？

- **FAQ 类知识库**: Paragraph 策略，一问一答对应一个 chunk
- **技术文档**: StructureAware 策略，按标题层级保持结构
- **长篇文章**: FixedSize + overlap，确保上下文不丢失
- **法规条款**: Sentence 策略，精确到条款级别', NOW(), NOW());

-- 模块4 章节1: 模型路由核心机制
INSERT INTO `t_study_document` (`id`, `chapter_id`, `module_id`, `title`, `content`, `create_time`, `update_time`) VALUES
(3009, 2009, 1004, '模型路由核心机制', '# 模型路由核心机制

## RoutingLLMService

核心职责：按优先级选择候选模型，失败时自动切换到下一个。

```java
// 伪代码
for (ModelCandidate candidate : sortedByPriority(candidates)) {
    if (healthStore.isHealthy(candidate)) {
        try {
            return chatClient.chat(candidate, messages);
        } catch (Exception e) {
            healthStore.markUnhealthy(candidate);
            continue; // 切换到下一个候选
        }
    }
}
throw new NoAvailableModelException();
```

## 模型选择器 ModelSelector

根据以下因素选择最佳模型：
1. **优先级权重** — 配置文件中设定
2. **健康状态** — 排除熔断中的模型
3. **负载均衡** — 同优先级模型间轮询

## 多提供商抽象

通过 `ChatClient` 接口统一抽象：
- `OllamaChatClient` — 本地部署模型
- `BaiLianChatClient` — 阿里云百炼
- `SiliconFlowChatClient` — SiliconFlow 平台', NOW(), NOW());

-- 模块4 章节2: 流式首包探测与熔断
INSERT INTO `t_study_document` (`id`, `chapter_id`, `module_id`, `title`, `content`, `create_time`, `update_time`) VALUES
(3010, 2010, 1004, '流式首包探测与熔断', '# 流式首包探测与熔断

## FirstPacketAwaiter

**问题**：流式输出时，如果模型服务异常，可能已经向前端推送了部分内容，此时切换模型会导致"半截输出"。

**解决方案**：在正式推送前，先等待第一个有效数据包到达，确认模型可用后再开始向前端推流。

## ModelHealthStore

维护每个模型的健康状态：

- **HEALTHY** — 正常可用
- **UNHEALTHY** — 连续失败达到阈值，进入熔断
- **HALF_OPEN** — 熔断超时后，允许试探性请求

## 熔断恢复策略

配置项：
- `ai.selection.failure-threshold` — 连续失败次数阈值（默认 3）
- `ai.selection.open-duration-ms` — 熔断持续时间（默认 60000ms）

当模型连续失败达到阈值时自动熔断，超过持续时间后进入半开状态，如果下次请求成功则恢复为健康。', NOW(), NOW());

-- 模块5 章节1: 管理后台布局体系
INSERT INTO `t_study_document` (`id`, `chapter_id`, `module_id`, `title`, `content`, `create_time`, `update_time`) VALUES
(3011, 2011, 1005, '管理后台布局体系', '# 管理后台布局体系

## AdminLayout 结构

```
┌─────────────────────────────────────────┐
│ Sidebar │         Topbar              │
│         │─────────────────────────────│
│  Logo   │ Breadcrumbs                 │
│  Menu   │                             │
│  Groups │       Content (Outlet)      │
│         │                             │
│ Collapse│                             │
└─────────────────────────────────────────┘
```

## 路由守卫

- `RequireAuth` — 验证用户是否已登录
- `RequireAdmin` — 验证用户是否为管理员角色
- `RedirectIfAuth` — 已登录用户重定向到聊天页面

## 菜单分组

菜单通过 `menuGroups` 数组配置，支持：
- 简单菜单项（直接跳转）
- 子菜单组（可折叠展开）
- 面包屑映射（`breadcrumbMap`）

## UI 组件库
基于 Radix UI 原语 + Tailwind CSS 封装在 `components/ui/` 目录。', NOW(), NOW());

-- 模块5 章节2: 状态管理与流式通信
INSERT INTO `t_study_document` (`id`, `chapter_id`, `module_id`, `title`, `content`, `create_time`, `update_time`) VALUES
(3012, 2012, 1005, '状态管理与流式通信', '# 状态管理与流式通信

## Zustand Store 体系

| Store | 职责 |
|-------|------|
| `authStore` | 用户认证状态、登录/登出 |
| `chatStore` | 会话列表、消息列表、当前会话 |
| `themeStore` | 主题配置 |

### authStore 特点
- 使用 localStorage 持久化 token
- 登录时自动设置 Axios Authorization header
- 登出时同步清理 chatStore

## useStreamResponse Hook

封装 SSE 流式响应处理：

```typescript
const { startStream, stopStream } = useStreamResponse({
  onMeta: (payload) => { /* 处理元信息 */ },
  onMessage: (payload) => { /* 处理增量文本 */ },
  onThinking: (payload) => { /* 处理深度思考 */ },
  onFinish: (payload) => { /* 处理完成 */ },
  onDone: () => { /* 流结束 */ },
});
```

## SSE 事件协议

前后端约定的事件类型：
1. `meta` — 返回 taskId、conversationId
2. `message` — 增量文本（type: response/think）
3. `finish` / `cancel` / `reject` — 终结事件
4. `done` — 流结束

## Axios 配置
- 请求拦截器自动添加 Authorization header
- 响应拦截器统一处理错误和登录过期', NOW(), NOW());

-- ==================== 面试题库 ====================

-- -------------------- 面试题分类 --------------------
INSERT INTO `t_interview_category` (`id`, `name`, `description`, `icon`, `sort_order`, `create_time`, `update_time`) VALUES
(4001, '系统架构设计', 'Monorepo 结构、模块划分、分层设计', 'Layers', 1, NOW(), NOW()),
(4002, 'RAG 检索流程', '检索链路、多通道策略、后处理器', 'Search', 2, NOW(), NOW()),
(4003, '向量数据库与 Embedding', 'Milvus 使用、向量索引、嵌入模型', 'Database', 3, NOW(), NOW()),
(4004, '模型路由与调用', '多模型路由、熔断恢复、流式输出', 'Cpu', 4, NOW(), NOW()),
(4005, 'Ingestion Pipeline', '流水线设计、分块策略、文档解析', 'Upload', 5, NOW(), NOW()),
(4006, '前端工程', 'React 架构、状态管理、SSE 处理', 'Monitor', 6, NOW(), NOW());

-- -------------------- 面试题目 --------------------
-- 分类1: 系统架构设计
INSERT INTO `t_interview_question` (`id`, `category_id`, `question`, `answer`, `difficulty`, `tags`, `create_time`, `update_time`) VALUES
(5001, 4001,
'请描述 RAgent 的 Monorepo 模块划分及各模块的核心职责。',
'## 参考答案

RAgent 采用 Maven 多模块结构，共 4 个核心模块：

### 1. bootstrap（业务入口）
- Spring Boot 启动类所在模块
- 包含所有业务实现：RAG 对话、知识库管理、Ingestion 引擎、用户认证
- 依赖 framework 和 infra-ai

### 2. framework（通用基础）
- `Result<T>` 统一返回值 + 全局异常处理
- `UserContext` 请求级用户上下文（ThreadLocal）
- 幂等控制（`@IdempotentSubmit`）
- 链路追踪数据结构
- 分布式 ID 生成（Snowflake）

### 3. infra-ai（AI 路由核心）
- 模型路由服务：LLM / Embedding / Rerank
- 多提供商 ChatClient 抽象
- 流式首包探测
- 模型健康状态管理

### 4. mcp-server（MCP 扩展位）
- 预留给 MCP 工具扩展，当前为空

**关键设计原则**：模块依赖严格单向，framework 和 infra-ai 之间无依赖。',
1, '架构,模块化,Monorepo', NOW(), NOW()),

(5002, 4001,
'RAgent 中的统一返回值 `Result<T>` 和全局异常处理是如何设计的？为什么要这样设计？',
'## 参考答案

### Result<T> 统一返回值
```java
public class Result<T> {
    private String code;    // "0" 表示成功
    private String message; // 提示信息
    private T data;         // 业务数据
}
```

通过 `Results.success(data)` 和 `Results.fail(errorCode)` 工厂方法创建。

### GlobalExceptionHandler
使用 `@RestControllerAdvice` 全局捕获异常：
- `ClientException` → 4xx 客户端错误
- `ServiceException` → 5xx 服务端错误
- `MethodArgumentNotValidException` → 参数校验失败
- `Exception` → 兜底处理

### 设计原因
1. **前端统一处理**：前端只需判断 `code === "0"` 即可区分成功/失败
2. **错误码体系**：通过 `BaseErrorCode` 枚举管理，避免硬编码
3. **日志集中**：异常处理器中统一记录日志，业务代码无需 try-catch
4. **安全性**：避免将内部异常栈暴露给前端',
2, '架构,异常处理,统一返回值', NOW(), NOW()),

(5003, 4001,
'如果要在 RAgent 中新增一个业务模块（如"学习中心"），需要哪些步骤？涉及哪些文件？',
'## 参考答案

### 完整步骤

#### 1. 数据库
- 在 `schema_table.sql` 中添加业务表
- 表名前缀 `t_`，必须包含 `id`/`create_time`/`update_time`/`deleted` 字段

#### 2. 后端代码（在 bootstrap 模块中）
按照 `com.nageoffer.ai.ragent.{模块名}` 创建包结构：
- `dao/entity/` — DO 实体类（`@TableName`、`@TableId(ASSIGN_ID)`）
- `dao/mapper/` — Mapper 接口（继承 `BaseMapper<DO>`）
- `service/` — Service 接口
- `service/impl/` — ServiceImpl 实现类（`@Service` + `@RequiredArgsConstructor`）
- `controller/` — Controller（`@RestController`）
- `controller/request/` — 请求对象
- `controller/vo/` — 视图对象

#### 3. 修改启动类
在 `RagentApplication.java` 的 `@MapperScan` 中添加新 Mapper 包路径。

#### 4. 前端
- `services/` 中新增 API 封装
- `pages/admin/` 中新增页面组件
- 修改 `router.tsx` 添加路由
- 修改 `AdminLayout.tsx` 添加侧边栏菜单

#### 5. 代码规范
- 运行 `mvnw compile` 自动添加 License Header
- 遵循命名约定：DO/Mapper/Service/Request/VO 后缀',
3, '架构,扩展性,开发流程', NOW(), NOW());

-- 分类2: RAG 检索流程
INSERT INTO `t_interview_question` (`id`, `category_id`, `question`, `answer`, `difficulty`, `tags`, `create_time`, `update_time`) VALUES
(5004, 4002,
'请描述 RAgent 中 RAG v3 对话的完整数据流。',
'## 参考答案

完整链路（从 SSE 请求到响应）：

```
GET /rag/v3/chat (SSE)
  → 1. 会话初始化：生成/复用 conversationId + taskId
  → 2. 记忆加载：从 t_message 加载历史消息
  → 3. Query Rewrite：指代消解 + 语义补全
  → 4. 多问句拆分：将复合问题拆解为独立子查询
  → 5. 意图识别：匹配意图树节点
  → 6. 歧义引导：判断是否需要用户澄清
  → 7. 检索引擎（并行）：
       ├── KB: MultiChannelRetrievalEngine
       │   ├── VectorGlobalSearchChannel
       │   ├── IntentDirectedSearchChannel
       │   └── → 后处理器链（去重 + Rerank）
       └── MCP: 参数抽取 → 工具执行 → 结果聚合
  → 8. Prompt 编排：组装 system/context/history/user
  → 9. 模型路由：选择模型 + 流式首包探测
  → 10. SSE 推送：meta → message(delta) → finish → done
  → 11. 异步任务：消息落库 / 标题生成 / trace 记录
```

关键设计要点：
- 检索通道并行执行，提高响应速度
- 流式首包探测避免半截输出
- 异步落库不阻塞用户响应',
2, 'RAG,检索,数据流', NOW(), NOW()),

(5005, 4002,
'RAgent 的多通道检索引擎是如何设计的？如何新增一个检索通道？',
'## 参考答案

### 多通道检索架构

采用「通道 + 后处理器」管道模式：

```
MultiChannelRetrievalEngine
  ├── Channel 1: VectorGlobalSearchChannel（全局向量检索）
  ├── Channel 2: IntentDirectedSearchChannel（意图定向检索）
  └── Channel N: 可扩展
  → PostProcessor Chain
      ├── DeduplicationPostProcessor（去重）
      └── RerankPostProcessor（Rerank 重排序）
```

### 核心接口
- `SearchChannel`：定义检索通道，每个通道实现独立的检索逻辑
- `SearchResultPostProcessor`：后处理器接口，对合并后的结果进行二次处理

### 新增检索通道步骤
1. 实现 `SearchChannel` 接口
2. 注册为 Spring Bean（`@Component`）
3. 框架自动发现并纳入多通道并行检索

### 设计优势
- **开闭原则**：新增通道不需要修改现有代码
- **并行执行**：多通道同时检索，合并结果
- **后处理解耦**：去重、排序等逻辑与检索逻辑分离',
2, 'RAG,检索通道,设计模式', NOW(), NOW());

-- 分类3: 向量数据库与 Embedding
INSERT INTO `t_interview_question` (`id`, `category_id`, `question`, `answer`, `difficulty`, `tags`, `create_time`, `update_time`) VALUES
(5006, 4003,
'RAgent 中 Milvus 向量数据库是如何使用的？知识库与 Collection 的关系是什么？',
'## 参考答案

### Milvus 在 RAgent 中的角色
Milvus 作为向量数据库，存储文档分块的向量表示，用于语义相似度检索。

### 知识库与 Collection 的关系
- 每个知识库（`t_knowledge_base`）对应一个 Milvus Collection
- `collection_name` 字段记录关联的 Collection 名称
- Collection 中的每条记录对应一个文档分块（chunk）

### 数据写入流程
```
文档 → Parser（解析） → Chunker（分块） → Embedding（向量化） → Milvus（写入）
```

### 检索流程
```
用户 Query → Embedding → Milvus TopK 检索 → 返回相似分块
```

### 关键配置
- 默认连接地址：`localhost:19530`
- 索引类型：支持 IVF_FLAT、HNSW 等
- 向量维度：由 Embedding 模型决定',
1, 'Milvus,向量数据库,Embedding', NOW(), NOW()),

(5007, 4003,
'在 RAG 系统中，Embedding 模型的选择和向量维度对检索效果有什么影响？RAgent 是如何管理多个 Embedding 模型的？',
'## 参考答案

### Embedding 模型的影响

#### 向量维度
- 维度越高，表达能力越强，但存储和计算成本也越高
- 常见维度：384（轻量）、768（中等）、1024+（高精度）

#### 模型选择因素
1. **语言支持**：中文场景需要选择对中文优化的模型
2. **领域适配**：通用模型 vs 领域微调模型
3. **性能与成本**：推理速度、API 调用费用

### RAgent 的 Embedding 管理

#### RoutingEmbeddingService
与 LLM 路由类似，Embedding 也采用路由模式：
- 支持多个 Embedding 提供商
- 按优先级选择，失败自动切换
- 每个知识库绑定一个 `embedding_model` 标识

#### 注意事项
- 同一个知识库必须使用相同的 Embedding 模型（维度一致性）
- 更换模型需要重新向量化所有分块',
3, 'Embedding,向量,模型选择', NOW(), NOW());

-- 分类4: 模型路由与调用
INSERT INTO `t_interview_question` (`id`, `category_id`, `question`, `answer`, `difficulty`, `tags`, `create_time`, `update_time`) VALUES
(5008, 4004,
'RAgent 的模型路由机制是如何实现的？它解决了什么问题？',
'## 参考答案

### 解决的问题
在生产环境中，依赖单一模型提供商存在风险：
- 服务不可用导致全站故障
- 不同场景需要不同模型（成本/质量权衡）

### 路由机制实现

#### RoutingLLMService
核心路由逻辑：
1. 获取所有候选模型，按优先级排序
2. 跳过健康状态为 UNHEALTHY 的模型
3. 尝试调用最高优先级的健康模型
4. 失败则标记为不健康，切换到下一个候选
5. 所有候选都失败则抛出异常

#### ModelSelector
负责模型选择策略：
- 优先级权重（配置文件设定）
- 健康状态过滤
- 同优先级轮询

#### ModelHealthStore
模型健康状态管理：
- HEALTHY → UNHEALTHY（连续失败达阈值）
- UNHEALTHY → HALF_OPEN（熔断超时后试探）
- HALF_OPEN → HEALTHY（试探成功）',
1, '模型路由,容错,熔断', NOW(), NOW()),

(5009, 4004,
'什么是流式首包探测（FirstPacketAwaiter）？它解决了什么问题？请描述其工作原理。',
'## 参考答案

### 问题背景
在流式输出场景中，存在"半截输出"问题：
1. 开始向前端推送模型 A 的流式响应
2. 推送了几个 token 后模型 A 异常
3. 切换到模型 B 重新生成
4. 前端收到的内容变成了"模型 A 的前半段 + 模型 B 的完整内容"

### FirstPacketAwaiter 解决方案
在正式向前端推流之前，先等待模型返回第一个有效数据包：

```
用户请求 → 选择模型 A → 开始流式调用
                      → 等待第一个有效 token（缓冲区）
                      → 收到第一个 token ✓
                      → 确认模型可用，开始正式推流
```

如果等待超时或收到错误：
```
用户请求 → 选择模型 A → 开始流式调用
                      → 等待第一个有效 token（缓冲区）
                      → 超时/错误 ✗
                      → 标记模型 A 不健康
                      → 切换模型 B，重新开始
                      → 收到第一个 token ✓
                      → 开始正式推流
```

### 关键优势
- 零污染：前端永远不会收到失败模型的部分输出
- 用户无感知：切换过程对用户透明（仅增加少量延迟）',
3, '流式输出,首包探测,容错', NOW(), NOW());

-- 分类5: Ingestion Pipeline
INSERT INTO `t_interview_question` (`id`, `category_id`, `question`, `answer`, `difficulty`, `tags`, `create_time`, `update_time`) VALUES
(5010, 4005,
'RAgent 的 Ingestion Pipeline 由哪些节点组成？请描述每个节点的职责。',
'## 参考答案

### 六大节点及其职责

| 节点 | 职责 | 说明 |
|------|------|------|
| **Fetcher** | 获取原始文档 | 支持多种数据源：LocalFile、HttpUrl、S3、Feishu |
| **Parser** | 解析文档为文本 | Tika（PDF/DOC/DOCX）、Markdown 解析器 |
| **Enhancer** | 文本增强 | 清洗无效字符、格式标准化 |
| **Chunker** | 文本分块 | 四种策略：FixedSize、Paragraph、Sentence、StructureAware |
| **Enricher** | 分块富化 | 添加元数据（标题、来源）、生成摘要 |
| **Indexer** | 写入向量库 | 调用 Embedding 模型向量化，写入 Milvus |

### 流水线配置持久化
- `t_ingestion_pipeline`：流水线定义
- `t_ingestion_pipeline_node`：节点配置（JSON 参数）
- `t_ingestion_task`：执行记录
- `t_ingestion_task_node`：节点级执行详情

### 扩展方式
实现 `IngestionNode` 接口，注册为 Spring Bean 即可自动纳入流水线。',
1, 'Ingestion,流水线,节点', NOW(), NOW()),

(5011, 4005,
'RAgent 支持哪些分块策略？在不同场景下应该如何选择？',
'## 参考答案

### 四种分块策略

#### 1. FixedSize（固定大小）
- 按固定字符数切分
- 支持 overlap（重叠）
- 适用场景：通用文档、无明确结构的文本

#### 2. Paragraph（段落）
- 按段落边界（换行符）切分
- 保持段落语义完整性
- 适用场景：FAQ、新闻文章

#### 3. Sentence（句子）
- 按句子边界切分
- 粒度最细
- 适用场景：法规条款、精确检索场景

#### 4. StructureAware（结构感知）
- 识别 Markdown/HTML 标题层级
- 按文档结构切分
- 适用场景：技术文档、产品手册

### 选择策略决策树
```
文档有清晰的标题结构？
  ├── 是 → StructureAware
  └── 否 → 文档是问答/段落型？
              ├── 是 → Paragraph
              └── 否 → 需要精确到句子级别？
                          ├── 是 → Sentence
                          └── 否 → FixedSize + overlap
```',
2, '分块策略,Chunking,检索优化', NOW(), NOW()),

(5012, 4005,
'如何在 RAgent 中新增一个 Fetcher（如飞书文档抓取）？请描述接口设计和实现步骤。',
'## 参考答案

### 接口设计
实现 `DocumentFetcher` 接口：
```java
public interface DocumentFetcher {
    // 判断是否支持该来源类型
    boolean supports(String sourceType);

    // 执行抓取，返回原始文档内容
    FetchResult fetch(FetchContext context);
}
```

### 实现步骤
1. 创建 `FeishuDocumentFetcher` 类
2. 实现 `supports()` 方法，返回是否支持 "feishu" 类型
3. 实现 `fetch()` 方法：
   - 解析飞书文档 URL/ID
   - 调用飞书开放平台 API 获取文档内容
   - 处理认证（App ID + App Secret）
   - 返回文档原始内容
4. 添加 `@Component` 注解，注册为 Spring Bean
5. 框架自动发现，在创建 Ingestion 任务时可选择 "feishu" 来源类型

### 关键考虑
- 错误处理：网络超时、认证失败、文档不存在
- 速率限制：飞书 API 有调用频率限制
- 格式转换：飞书文档格式 → 标准文本/Markdown',
3, 'Ingestion,Fetcher,扩展', NOW(), NOW());

-- 分类6: 前端工程
INSERT INTO `t_interview_question` (`id`, `category_id`, `question`, `answer`, `difficulty`, `tags`, `create_time`, `update_time`) VALUES
(5013, 4006,
'RAgent 前端的状态管理方案是什么？为什么选择 Zustand 而非 Redux？',
'## 参考答案

### 状态管理方案：Zustand

RAgent 使用 Zustand 管理全局状态，包含三个 Store：
- `authStore` — 用户认证、Token 管理
- `chatStore` — 会话列表、消息记录
- `themeStore` — 主题配置

### 为什么选择 Zustand

#### 1. 简洁性
Zustand 的 API 极其简单：
```typescript
const useStore = create((set) => ({
  count: 0,
  increment: () => set((state) => ({ count: state.count + 1 })),
}));
```
相比 Redux 不需要 Action Type、Action Creator、Reducer 等样板代码。

#### 2. 性能
- 默认按引用精确订阅，避免不必要的重渲染
- 不需要 Provider 包裹
- 支持 selector 精确选择状态片段

#### 3. 与 React 深度集成
- 直接作为 Hook 使用
- 支持 TypeScript 类型推导
- 内置中间件（persist、devtools）

#### 4. 适合项目规模
RAgent 前端状态较为简单（认证 + 聊天），Zustand 的轻量级方案更合适。',
1, '前端,状态管理,Zustand', NOW(), NOW()),

(5014, 4006,
'RAgent 如何处理 SSE 流式响应？前端的 useStreamResponse Hook 是如何工作的？',
'## 参考答案

### SSE 流式响应处理

#### 后端
使用 `SseEmitter` 推送事件流：
- 通过 `SseEmitterSender` 封装发送逻辑
- 事件类型：meta → message → finish/cancel/reject → done

#### 前端 useStreamResponse Hook

核心工作流程：
1. **建立连接**：使用 `fetch` API 发起 SSE 请求
2. **流式读取**：通过 `ReadableStream` 逐块读取响应
3. **事件解析**：解析 SSE 格式（`event:` + `data:`）
4. **回调分发**：根据事件类型调用对应的回调函数

```typescript
const { startStream, stopStream } = useStreamResponse({
  onMeta: (payload) => { /* 初始化 UI */ },
  onMessage: (payload) => { /* 追加增量文本 */ },
  onThinking: (payload) => { /* 显示思考过程 */ },
  onFinish: (payload) => { /* 完成处理 */ },
  onError: (error) => { /* 错误处理 */ },
});
```

#### 关键设计
- **增量更新**：`onMessage` 接收 delta 文本，前端累加显示
- **深度思考**：`think` 类型的 message 可独立展示思考过程
- **错误恢复**：连接断开时自动通知上层组件
- **取消支持**：用户可随时调用 `stopStream()` 中断',
2, '前端,SSE,流式处理', NOW(), NOW()),

(5015, 4006,
'RAgent 前端的路由守卫是如何设计的？如何确保管理后台只有管理员可以访问？',
'## 参考答案

### 路由守卫体系

RAgent 定义了三个路由守卫组件：

#### 1. RequireAuth
```typescript
function RequireAuth({ children }) {
  const isAuthenticated = useAuthStore(state => state.isAuthenticated);
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  return children;
}
```
- 保护需要登录的页面（如 /chat）
- 未登录用户重定向到登录页

#### 2. RequireAdmin
```typescript
function RequireAdmin({ children }) {
  const user = useAuthStore(state => state.user);
  const isAuthenticated = useAuthStore(state => state.isAuthenticated);
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  if (user?.role !== "admin") return <Navigate to="/chat" replace />;
  return children;
}
```
- 保护管理后台（/admin/*）
- 未登录 → 登录页
- 非管理员 → 聊天页

#### 3. RedirectIfAuth
- 已登录用户访问登录页时重定向到聊天页
- 避免重复登录

### 使用方式
在 `router.tsx` 中用守卫组件包裹路由元素：
```tsx
{
  path: "/admin",
  element: <RequireAdmin><AdminLayout /></RequireAdmin>,
  children: [...]
}
```

### 安全注意
前端路由守卫只是 UX 层面的保护，后端 API 必须通过 SA-Token 做独立的权限校验。',
1, '前端,路由守卫,权限', NOW(), NOW());
