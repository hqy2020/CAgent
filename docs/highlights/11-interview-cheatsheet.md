# 面试前速查：CAgent 企业级 RAG 智能体平台

## 一、项目介绍话术

### 30 秒版

> 我做的是一个企业级 RAG 智能体平台，技术栈是 Spring Boot 3.5 + Java 17 + Milvus 向量库 + React 18。核心能力是多通道知识库检索和 MCP 工具调用。我负责了全链路的架构设计：包括多路检索引擎、三态熔断的模型路由、分布式队列式限流、全链路追踪 AOP、流式会话停止机制等。项目最大的亮点是把 RAG 的各个环节都做到了生产级别的可靠性和可扩展性。

### 1 分钟版

> 我做的是 CAgent，一个企业级 RAG 智能体平台。后端 Spring Boot 3.5 + Java 17，前端 React 18，向量数据库用 Milvus。
>
> 整体架构分为几层：首先是意图识别层——用户问题先经过 LLM 改写拆分和三级意图树分类，确定要检索哪些知识库或调用哪些工具。然后是检索层——多通道检索引擎并行执行意图定向检索和全局兜底检索，结果经过去重和 Rerank 精排。模型调用层有三态熔断器和流式首包探测，多模型自动降级，避免脏数据输出。
>
> 基础设施方面：9 个场景隔离线程池 + TransmittableThreadLocal 保证异步上下文传播；AOP 全链路追踪零侵入记录每个环节的耗时；Redis ZSET + Lua 脚本实现分布式公平排队限流；流式会话支持跨节点优雅停止。另外还有文档入库 Pipeline 和 MCP 工具动态注册，都是基于接口 + 自动发现的可扩展架构。

### 3 分钟版

> **（项目概览）** CAgent 是一个企业级 RAG 智能体平台，我从零参与了核心架构的设计和实现。技术栈是 Spring Boot 3.5 + Java 17 后端，React 18 前端，Milvus 2.6 向量库，Redis + MySQL 存储。项目采用 Monorepo 多模块结构：framework 模块提供通用基础（统一返回值、异常处理、链路追踪上下文），infra-ai 模块封装 AI 基础设施（模型路由、熔断、流式客户端），bootstrap 模块是业务实现入口。
>
> **（核心流程）** 一次对话请求的完整流程是：用户问题先经过术语归一化和 LLM 改写拆分（一次 LLM 调用同时完成改写和多问句拆分）。然后通过三级意图树（DOMAIN→CATEGORY→TOPIC）做 LLM 分类，识别出 KB 意图和 MCP 意图。检索阶段，多通道检索引擎并行执行——意图定向通道精准检索指定 Milvus 集合，全局兜底通道搜所有集合，两者互补启用。结果经过 LinkedHashMap 保序去重和 Rerank 模型精排。MCP 工具通过注册表模式动态管理，CompletableFuture 批量并行执行。最后 Prompt 编排服务根据场景（纯 KB / 纯 MCP / 混合）选择模板，组装消息序列发给 LLM 流式输出。
>
> **（关键技术亮点）** 模型调用层我实现了三态熔断器——CLOSED→OPEN→HALF_OPEN，连续失败自动熔断，超时后半开探测。流式场景的难点是避免脏数据，我设计了 ProbeBufferingCallback——首包到达前所有输出缓冲，确认成功后才回放给前端，失败则丢弃切换下一个模型。分布式限流用 Redis ZSET 按时间戳排队（FIFO），Lua 脚本原子检查排名并出队，配合本地 Semaphore 双重保障。9 个场景隔离线程池全部用 TtlExecutors 包装，保证 TransmittableThreadLocal 中的 traceId 和用户上下文在异步线程间正确传播。AOP 追踪用 `@RagTraceRoot`/`@RagTraceNode` 双注解 + ArrayDeque 栈实现树形追踪，零侵入业务代码。
>
> **（扩展性）** 项目所有核心组件都遵循开闭原则：新增检索通道实现 SearchChannel 接口，新增后处理器实现 SearchResultPostProcessor，新增 MCP 工具实现 MCPToolExecutor，新增 Pipeline 节点实现 IngestionNode——都是实现接口 + 标注 @Component 就自动生效。

---

## 二、10 个亮点一句话速查

| # | 亮点 | 一句话总结 | 关键词标签 |
|---|------|-----------|----------|
| 1 | [多通道检索引擎](./01-multi-channel-retrieval.md) | 策略模式 + CompletableFuture 并行 + 后处理器链（去重→精排），实现意图定向与全局兜底互补的多路召回 | `SearchChannel` `并行检索` `去重精排` |
| 2 | [意图识别与问题重写](./02-intent-recognition.md) | 三级意图树 + LLM 分类 + 多问句拆分 + 并行意图解析 + 总量控制 | `IntentNode` `LLM分类` `capTotalIntents` |
| 3 | [模型路由与三态熔断](./03-model-routing-circuit-breaker.md) | 多模型候选 + 三态熔断器 + 流式首包探测 + 缓冲回放，零脏数据输出 | `ModelHealthStore` `FirstPacketAwaiter` `ProbeBufferingCallback` |
| 4 | [分布式队列式限流](./04-distributed-rate-limiting.md) | Redis ZSET 排队 + Lua 原子出队 + Semaphore 许可 + 三路释放 | `ZSET` `Lua` `CAS幂等` |
| 5 | [专用线程池 + TtlExecutors](./05-thread-pool-ttl.md) | 9 个场景隔离线程池 + TransmittableThreadLocal 包装，异步上下文零丢失 | `TtlExecutors` `TransmittableThreadLocal` `场景隔离` |
| 6 | [文档入库 Pipeline](./06-ingestion-pipeline.md) | 节点接口 + 链式引擎 + 条件跳过 + Spring 自动发现，6 级全自动入库 | `IngestionNode` `链式执行` `条件评估` |
| 7 | [全链路追踪 AOP](./07-rag-trace-aop.md) | 双注解 + ArrayDeque 栈 + TTL 传播，零侵入树形追踪 | `@RagTraceRoot` `@RagTraceNode` `push/pop` |
| 8 | [会话停止机制](./08-stream-cancellation.md) | CAS 幂等取消 + Redis Pub/Sub 跨节点 + 竞态安全绑定 | `StreamCancellationHandle` `Pub/Sub` `bindHandle竞态` |
| 9 | [多层记忆管理](./09-conversation-memory.md) | 并行加载摘要+历史 + 异步 LLM 压缩 + 分布式锁 + 滑动窗口 | `CompletableFuture并行` `分布式锁` `LLM摘要` |
| 10 | [MCP 工具动态注册](./10-mcp-tool-registry.md) | Spring Bean 自动发现 + 注册表 + 批量并行 + LLM 参数提取 | `MCPToolExecutor` `@PostConstruct` `buildToolsDescription` |

---

## 三、高频追问预判

### 架构维度

**Q：系统的整体架构是怎样的？**
> Monorepo 三模块：framework（通用基础）、infra-ai（AI 路由/熔断/流式）、bootstrap（业务实现）。框架层不依赖业务，AI 层不依赖具体 RAG 流程，业务层编排全部流程。

**Q：为什么选 Milvus 而不是 Elasticsearch / Pinecone？**
> Milvus 开源免费、支持多种索引（IVF_FLAT/HNSW）、有丰富的 Java SDK、支持 Collection 级隔离（每个知识库独立 Collection）。ES 的向量检索是后加的，不如专用向量库成熟。Pinecone 是 SaaS，不适合企业私有化部署。

**Q：SSE 和 WebSocket 怎么选的？**
> SSE 是单向推送，HTTP 协议兼容性好，不需要握手升级，适合"服务端推流、客户端接收"的对话场景。WebSocket 适合双向通信（如协同编辑），对话场景不需要。SSE 断线重连也更简单（带 Last-Event-ID）。

### 并发维度

**Q：系统能支持多少并发？**
> 并发上限由限流器配置（默认 50 并发）和模型 slot 决定。9 个线程池的总核心线程约 CPU×8，有界队列防止内存溢出。关键瓶颈在 LLM 推理速度而非后端。

**Q：线程池参数怎么定的？**
> CPU 密集型（检索/意图分类）用 CPU 核心数，IO 密集型（摘要/分块）用 CPU/2。SynchronousQueue 用于必须快速响应的场景（满载时 CallerRuns），LinkedBlockingQueue 用于允许排队的场景。AbortPolicy 只在需要背压的场景使用（流式输出/SSE 入口）。

**Q：TransmittableThreadLocal 和 InheritableThreadLocal 的区别？**
> ITL 只在 `new Thread()` 时拷贝值，线程池复用时不触发。TTL 通过 TtlRunnable 包装，在 submit 时快照、run 时恢复、完成后还原，解决线程复用的上下文传播。

### 设计模式维度

**Q：项目中用到了哪些设计模式？**

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **策略模式** | SearchChannel、ChatClient、MCPToolExecutor、ChunkingStrategy | 接口 + 多实现 |
| **模板方法** | AbstractParallelRetriever、IngestionEngine | 定义骨架，子类填充 |
| **责任链** | SearchResultPostProcessor 链 | 按 order 排序依次执行 |
| **注册表** | DefaultMCPToolRegistry、IntentTreeCacheManager | ConcurrentHashMap 管理 |
| **装饰器** | TtlExecutors 包装、ProbeBufferingCallback | 透明增强 |
| **状态机** | ModelHealthStore 三态熔断 | CLOSED→OPEN→HALF_OPEN |
| **工厂方法** | StreamCancellationHandles、ChunkingStrategyFactory | 创建具体实现 |
| **观察者** | Redis Pub/Sub 跨节点通知 | 发布-订阅 |
| **AOP 切面** | RagTraceAspect、ChatRateLimitAspect | 零侵入横切关注点 |

---

## 四、关键数字汇总

| 指标 | 数值 | 来源 |
|------|------|------|
| 线程池数量 | **9** 个 | ThreadPoolExecutorConfig |
| 熔断失败阈值 | **2** 次连续失败 | ai.selection.failure-threshold |
| 熔断恢复时间 | **30** 秒 | ai.selection.open-duration-ms |
| 首包探测超时 | **60** 秒 | FirstPacketAwaiter.await() |
| 最大并发数 | **50** | rag.rate-limit.global.max-concurrent |
| 排队超时 | **20** 秒 | rag.rate-limit.global.max-wait-seconds |
| 轮询间隔 | **200** ms | rag.rate-limit.global.poll-interval-ms |
| 许可自动释放 | **600** 秒 | rag.rate-limit.global.lease-seconds |
| 历史保留轮数 | **8** 轮 | rag.memory.history-keep-turns |
| 摘要触发轮数 | **9** 轮 | rag.memory.summary-start-turns |
| 摘要最大字符 | **200** 字符 | rag.memory.summary-max-chars |
| 意图树层级 | **3** 级 | DOMAIN→CATEGORY→TOPIC |
| 意图分类温度 | **0.1** | DefaultIntentClassifier |
| Pipeline 节点 | **6** 种 | Fetcher→Parser→Enhancer→Chunker→Enricher→Indexer |
| 分块默认大小 | **512** 字符 | ChunkerNode settings |
| 分块默认重叠 | **128** 字符 | ChunkerNode settings |
| 模型提供商 | **4** 个 | Ollama/百炼/硅基流动/MiniMax |
| Guava Cache TTL | **30** 分钟 | StreamTaskManager |
| 意图树缓存 | **7** 天 | IntentTreeCacheManager |
| TTL 变量数 | **3** 个 | TRACE_ID / TASK_ID / NODE_STACK |

---

## 五、项目中的设计模式清单

1. **策略模式** — SearchChannel / ChatClient / MCPToolExecutor / IngestionNode / ChunkingStrategy
2. **模板方法** — AbstractParallelRetriever / IngestionEngine executeChain
3. **责任链** — SearchResultPostProcessor (order 排序执行)
4. **状态机** — ModelHealthStore (CLOSED→OPEN→HALF_OPEN)
5. **装饰器** — TtlExecutors 包装 / ProbeBufferingCallback 代理
6. **注册表/注册中心** — DefaultMCPToolRegistry (ConcurrentHashMap)
7. **工厂方法** — StreamCancellationHandles.fromOkHttp() / ChunkingStrategyFactory
8. **观察者/发布订阅** — Redis Pub/Sub 跨节点取消通知
9. **AOP 切面** — RagTraceAspect / ChatRateLimitAspect
10. **Builder 模式** — ChatRequest.builder() / MCPTool.builder() / 各种 DO/VO

---

## 六、快速定位：文件路径速查

| 组件 | 关键文件 |
|------|---------|
| RAG 主编排 | `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java` |
| 多路检索引擎 | `bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java` |
| 检索通道 | `bootstrap/.../rag/core/retrieve/channel/` |
| 后处理器 | `bootstrap/.../rag/core/retrieve/postprocessor/` |
| 意图识别 | `bootstrap/.../rag/core/intent/` |
| 查询改写 | `bootstrap/.../rag/core/rewrite/` |
| 模型路由 | `infra-ai/.../chat/RoutingLLMService.java` |
| 熔断器 | `infra-ai/.../model/ModelHealthStore.java` |
| 首包探测 | `infra-ai/.../chat/FirstPacketAwaiter.java` |
| 线程池 | `bootstrap/.../rag/config/ThreadPoolExecutorConfig.java` |
| 追踪上下文 | `framework/.../trace/RagTraceContext.java` |
| 追踪切面 | `bootstrap/.../rag/aop/RagTraceAspect.java` |
| 限流器 | `bootstrap/.../rag/aop/ChatQueueLimiter.java` |
| Lua 脚本 | `bootstrap/src/main/resources/lua/queue_claim_atomic.lua` |
| 流式取消 | `bootstrap/.../rag/service/handler/StreamTaskManager.java` |
| 记忆管理 | `bootstrap/.../rag/core/memory/` |
| Pipeline 引擎 | `bootstrap/.../ingestion/engine/IngestionEngine.java` |
| Pipeline 节点 | `bootstrap/.../ingestion/node/` |
| MCP 注册表 | `bootstrap/.../rag/core/mcp/DefaultMCPToolRegistry.java` |
| MCP 编排器 | `bootstrap/.../rag/core/mcp/MCPServiceOrchestrator.java` |
| Prompt 编排 | `bootstrap/.../rag/core/prompt/RAGPromptService.java` |
