# 亮点5：专用线程池 + TtlExecutors 上下文传播

> 9 个场景隔离线程池 + TransmittableThreadLocal 包装，实现异步任务间的链路追踪与用户上下文零丢失传播。

## 1. 问题背景

### 为什么需要？

RAG 系统存在大量并发场景：多通道检索并行、意图分类并行、MCP 工具批量执行、模型流式输出、摘要异步生成等。如果所有异步任务共享同一线程池，会导致：

- **资源竞争**：慢任务（如 LLM 摘要）占满线程，导致快任务（如检索）饿死
- **参数不可控**：不同场景对核心线程数、队列容量、拒绝策略的需求完全不同
- **上下文丢失**：Java 的 `ThreadLocal` 在线程池中无法透传，`traceId`、`userId` 在子线程中丢失，链路追踪断裂

### 不做会怎样？

- 链路追踪 AOP 在子线程中获取不到 `traceId`，无法关联节点
- 并发高峰时所有功能相互影响，系统不可预测
- 难以针对单一场景调优（如限制摘要并发不影响检索吞吐）

## 2. 设计方案

### 架构图

```
┌─────────────────── ThreadPoolExecutorConfig ───────────────────┐
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────┐  │
│  │ ragRetrieval     │  │ ragInnerRetrieval│  │ intentClassify│  │
│  │ Executor         │  │ Executor         │  │ Executor      │  │
│  │ CPU | CPU×2      │  │ CPU×2 | CPU×4    │  │ CPU | CPU×2   │  │
│  │ Synchronous      │  │ Linked(100)      │  │ Synchronous   │  │
│  └────────┬─────────┘  └────────┬─────────┘  └───────┬───────┘  │
│           │                     │                     │          │
│           └─────────┬───────────┴─────────────────────┘          │
│                     ▼                                            │
│           TtlExecutors.getTtlExecutor(executor)                  │
│                     │                                            │
│                     ▼                                            │
│           TransmittableThreadLocal 自动透传                      │
│           ┌─────────────────────────────┐                        │
│           │ TRACE_ID  │ TASK_ID │ STACK │    ← RagTraceContext   │
│           │ USER_ID   │ ...     │       │    ← UserContext       │
│           └─────────────────────────────┘                        │
└─────────────────────────────────────────────────────────────────┘
```

### 核心抽象

- **场景隔离**：每个业务场景独立的 `ThreadPoolExecutor` Bean
- **TTL 包装**：所有 Executor 通过 `TtlExecutors.getTtlExecutor()` 包装
- **上下文载体**：`RagTraceContext` 使用 3 个 `TransmittableThreadLocal` 变量

### 设计模式

- **工厂模式**：Spring `@Bean` 工厂方法创建线程池
- **装饰器模式**：`TtlExecutors` 对原始 Executor 进行透明包装

## 3. 核心代码解析

### 3.1 线程池配置类（9 个 Bean）

```java
// 文件：bootstrap/.../rag/config/ThreadPoolExecutorConfig.java

@Configuration
public class ThreadPoolExecutorConfig {

    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    /** MCP 批处理线程池 */
    @Bean
    public Executor mcpBatchThreadPoolExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CPU_COUNT,              // 核心线程数 = CPU 核心数
                CPU_COUNT << 1,         // 最大线程数 = CPU × 2
                60, TimeUnit.SECONDS,
                new SynchronousQueue<>(),           // 无缓冲，直接交付
                ThreadFactoryBuilder.create()
                        .setNamePrefix("mcp_batch_executor_").build(),
                new ThreadPoolExecutor.CallerRunsPolicy()  // 满载时调用者线程执行
        );
        return TtlExecutors.getTtlExecutor(executor);  // ← TTL 包装
    }

    /** 模型流式输出线程池 */
    @Bean
    public Executor modelStreamExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(2, CPU_COUNT >> 1),
                Math.max(4, CPU_COUNT),
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),     // 有界队列
                ThreadFactoryBuilder.create()
                        .setNamePrefix("model_stream_executor_").build(),
                new ThreadPoolExecutor.AbortPolicy()       // ← 满载时直接拒绝
        );
        return TtlExecutors.getTtlExecutor(executor);
    }
    // ... 其余 7 个 Bean 结构类似
}
```

### 3.2 全部 9 个线程池参数一览

| 线程池名称 | 用途 | 核心数 | 最大数 | 队列 | 拒绝策略 | 设计理由 |
|-----------|------|--------|--------|------|----------|---------|
| `mcpBatchThreadPoolExecutor` | MCP 工具批量执行 | CPU | CPU×2 | Synchronous | CallerRuns | CPU 密集 + 不可丢弃 |
| `ragContextThreadPoolExecutor` | RAG 上下文处理 | 2 | 4 | Synchronous | CallerRuns | 轻量级，固定并发 |
| `ragRetrievalThreadPoolExecutor` | 检索通道级并行 | CPU | CPU×2 | Synchronous | CallerRuns | 向量检索 CPU 密集 |
| `ragInnerRetrievalThreadPoolExecutor` | 通道内部并行 | CPU×2 | CPU×4 | Linked(100) | CallerRuns | 混合密集，允许排队 |
| `intentClassifyThreadPoolExecutor` | 意图分类并行 | CPU | CPU×2 | Synchronous | CallerRuns | LLM 调用 IO+CPU |
| `memorySummaryThreadPoolExecutor` | 摘要异步生成 | 1 | max(2,CPU/2) | Linked(200) | CallerRuns | 非关键路径，低优先级 |
| `modelStreamExecutor` | 模型流式输出 | max(2,CPU/2) | max(4,CPU) | Linked(200) | **Abort** | 必须限制流式并发 |
| `chatEntryExecutor` | SSE 排队执行入口 | max(2,CPU/2) | max(4,CPU) | Linked(200) | **Abort** | 需要背压机制 |
| `knowledgeChunkExecutor` | 文档分块 | max(2,CPU/2) | max(4,CPU) | Linked(200) | CallerRuns | IO 密集型 |

### 3.3 TransmittableThreadLocal 上下文

```java
// 文件：framework/.../trace/RagTraceContext.java

public final class RagTraceContext {
    // 三个 TTL 变量，子线程自动继承
    private static final TransmittableThreadLocal<String> TRACE_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<String> TASK_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<Deque<String>> NODE_STACK = new TransmittableThreadLocal<>();

    public static void pushNode(String nodeId) {
        Deque<String> stack = NODE_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            NODE_STACK.set(stack);
        }
        stack.push(nodeId);                 // 子线程中也能正确 push
    }

    public static void popNode() {
        Deque<String> stack = NODE_STACK.get();
        if (stack == null || stack.isEmpty()) return;
        stack.pop();
        if (stack.isEmpty()) NODE_STACK.remove();  // 清理空栈
    }

    public static void clear() {
        TRACE_ID.remove();
        TASK_ID.remove();
        NODE_STACK.remove();
    }
}
```

### 3.4 使用示例 — 检索并行

```java
// 文件：bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java

@Qualifier("ragRetrievalThreadPoolExecutor")
private final Executor ragRetrievalExecutor;

List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
        .map(channel -> CompletableFuture.supplyAsync(
                () -> channel.search(context),    // 子线程中 traceId 自动透传
                ragRetrievalExecutor               // TTL 包装的线程池
        ))
        .toList();
```

## 4. 设计意图

### 为什么选场景隔离而非共享线程池？

| 方案 | 优点 | 缺点 |
|------|------|------|
| 共享 `@Async` 池 | 配置简单 | 慢任务拖垮快任务，无法针对性调优 |
| **场景隔离池**（本方案） | 互不影响，参数可控 | 需要管理多个 Bean |
| 虚拟线程（Java 21） | 资源开销极低 | Java 17 不可用，且 ThreadLocal 语义不同 |

### 为什么用 AbortPolicy 而非 CallerRunsPolicy？

`modelStreamExecutor` 和 `chatEntryExecutor` 使用 `AbortPolicy`，因为：
- 流式输出不能让调用者线程阻塞（会卡死 SSE 连接）
- 需要显式拒绝来触发上层限流机制（ChatQueueLimiter）
- 其余池用 `CallerRunsPolicy` 保证任务不丢，降级为串行

### 为什么选 TTL 而非手动传递？

| 方案 | 优点 | 缺点 |
|------|------|------|
| 手动参数传递 | 显式可控 | 侵入性强，每个方法多一个参数 |
| InheritableThreadLocal | JDK 原生 | 只在 new Thread 时继承，线程池复用时失效 |
| **TransmittableThreadLocal** | 线程池复用安全 | 需要 TtlExecutors 包装 |

## 5. 面试话术

### 30 秒版

> 我设计了 9 个场景隔离线程池，覆盖检索、意图识别、模型流式、摘要生成等不同业务场景。每个池有独立的核心线程数、队列容量和拒绝策略——比如摘要池核心线程只有 1 个，检索池按 CPU 核心数动态设置。所有 Executor 统一通过 TtlExecutors 包装，保证 TransmittableThreadLocal 中的 traceId 和用户上下文在异步线程中自动透传，让全链路追踪不会因线程切换断裂。

### 追问应对

**Q：为什么不用 `@Async` 默认线程池？**
> `@Async` 默认的 SimpleAsyncTaskExecutor 每次创建新线程，不复用。即使配了 ThreadPoolTaskExecutor 也只有一个共享池，无法隔离。我们的场景对并发粒度要求不同——检索要快响应（SynchronousQueue），摘要允许排队（LinkedBlockingQueue(200)），模型流式需要背压（AbortPolicy），共享池无法满足。

**Q：TransmittableThreadLocal 和 InheritableThreadLocal 的区别？**
> InheritableThreadLocal 只在 `new Thread()` 创建子线程时拷贝父线程的值。但线程池中线程是复用的，第二次 submit 时线程已经存在，不会再触发拷贝。TransmittableThreadLocal 通过 TtlRunnable/TtlCallable 包装，在 submit 时快照、run 时恢复、完成后还原，解决了复用线程的上下文传播问题。

**Q：9 个线程池会不会太多？怎么监控？**
> 每个池都有明确的 `namePrefix`（如 `rag_retrieval_executor_`），通过 JMX 或 Micrometer 可以监控活跃线程数、队列深度、拒绝次数。数量看似多，但每个池的核心线程不多（总计约 CPU×8），实际内存开销有限。关键是按场景隔离后，问题排查和性能调优都更精准。

## 6. 关联亮点

- [亮点7：全链路追踪 AOP](./07-rag-trace-aop.md) — TTL 传播的直接消费者
- [亮点1：多路检索引擎](./01-multi-channel-retrieval.md) — 使用 `ragRetrievalThreadPoolExecutor` 并行检索
- [亮点4：分布式队列式限流](./04-distributed-rate-limiting.md) — 使用 `chatEntryExecutor`
- [亮点9：多层记忆管理](./09-conversation-memory.md) — 使用 `memorySummaryThreadPoolExecutor`
- [亮点10：MCP 工具动态注册](./10-mcp-tool-registry.md) — 使用 `mcpBatchThreadPoolExecutor`
