# 亮点1：多通道检索引擎

> 策略模式 + CompletableFuture 并行 + 后处理器链（去重→精排），实现意图定向与全局兜底互补的多路召回架构。

## 1. 问题背景

### 为什么需要？

单一检索策略无法同时满足精确性和召回率：

- **意图明确时**：应该只在对应知识库中检索（如"Spring IOC" → 只搜 Java 集合），减少噪音
- **意图模糊时**：需要全局兜底检索所有知识库，保证不遗漏
- **多意图时**：需要并行检索多个知识库，最后去重合并
- **检索后**：多路结果可能重复，需要去重；相关性排序可能不准，需要 Rerank 精排

### 不做会怎样？

- 全局检索：噪音多，无关文档占据 Top-K 位置
- 单一通道：意图模糊时召回率低
- 无后处理：重复文档浪费 LLM 上下文窗口

## 2. 设计方案

### 架构图

```
SearchContext (question + intents + topK)
    │
    ▼
MultiChannelRetrievalEngine.retrieveKnowledgeChannels()
    │
    ├─── 阶段1：executeSearchChannels() ─── 并行 ───┐
    │                                                 │
    │  ┌─ IntentDirectedSearchChannel ─┐              │
    │  │  priority=1 (最高)            │              │
    │  │  isEnabled: 有 KB 意图?       │   并行       │
    │  │  └→ IntentParallelRetriever   │   执行       │
    │  │     (每个意图→指定 Collection) │              │
    │  └───────────────────────────────┘              │
    │                                                 │
    │  ┌─ VectorGlobalSearchChannel ───┐              │
    │  │  priority=10 (兜底)           │              │
    │  │  isEnabled: 无意图/低置信度?  │              │
    │  │  └→ CollectionParallelRetriever│              │
    │  │     (遍历所有 Collection)      │              │
    │  └───────────────────────────────┘              │
    │                                                 │
    ├─── 阶段2：executePostProcessors() ─── 顺序 ───┐
    │                                                 │
    │  ┌─ DeduplicationPostProcessor ──┐  order=1    │
    │  │  LinkedHashMap 保序去重       │              │
    │  │  通道优先级: INTENT > GLOBAL   │              │
    │  └───────────────────────────────┘              │
    │                                                 │
    │  ┌─ RerankPostProcessor ─────────┐  order=10   │
    │  │  调用 RerankService 精排      │              │
    │  │  输出最终 Top-K               │              │
    │  └───────────────────────────────┘              │
    │                                                 │
    ▼
List<RetrievedChunk> (去重 + 精排后的最终结果)
```

### 两个通道的互补逻辑

| 条件 | IntentDirected | VectorGlobal |
|------|:-:|:-:|
| 有 KB 意图 + 高置信度 | 启用 | 禁用 |
| 有 KB 意图 + 低置信度 | 启用 | 启用（双保险） |
| 无意图 | 禁用 | 启用（兜底） |

### 设计模式

- **策略模式**：`SearchChannel` 接口 + 多实现
- **模板方法**：`AbstractParallelRetriever` 定义并行检索骨架
- **责任链**：后处理器按 order 排序依次执行
- **开闭原则**：新增通道只需实现 `SearchChannel`，新增后处理器只需实现 `SearchResultPostProcessor`

## 3. 核心代码解析

### 3.1 SearchChannel 接口

```java
// 文件：bootstrap/.../rag/core/retrieve/channel/SearchChannel.java

public interface SearchChannel {
    String getName();                        // 通道名称
    int getPriority();                       // 优先级（越小越高）
    boolean isEnabled(SearchContext context); // 是否启用（动态决策）
    SearchChannelResult search(SearchContext context);  // 执行检索
    SearchChannelType getType();             // 通道类型枚举
}
```

### 3.2 并行执行通道 — executeSearchChannels

```java
// 文件：bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java:83-162

private List<SearchChannelResult> executeSearchChannels(SearchContext context) {
    // 过滤启用的通道
    List<SearchChannel> enabledChannels = channels.stream()
            .filter(ch -> ch.isEnabled(context))
            .sorted(Comparator.comparingInt(SearchChannel::getPriority))
            .toList();

    if (enabledChannels.isEmpty()) return List.of();

    // CompletableFuture 并行执行
    List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
            .map(channel -> CompletableFuture.supplyAsync(
                    () -> {
                        long start = System.currentTimeMillis();
                        SearchChannelResult result = channel.search(context);
                        result.setLatencyMs(System.currentTimeMillis() - start);
                        return result;
                    },
                    ragRetrievalExecutor     // ← TTL 包装线程池
            ).exceptionally(ex -> {
                log.warn("通道 {} 检索失败", channel.getName(), ex);
                return SearchChannelResult.empty(channel.getType());  // 降级为空
            }))
            .toList();

    return futures.stream()
            .map(CompletableFuture::join)
            .filter(r -> !r.getChunks().isEmpty())
            .toList();
}
```

### 3.3 去重后处理器 — LinkedHashMap 保序

```java
// 文件：bootstrap/.../rag/core/retrieve/postprocessor/DeduplicationPostProcessor.java:58-88

@Override
public List<RetrievedChunk> process(List<SearchChannelResult> channelResults, SearchContext ctx) {
    // 按通道优先级排序：INTENT_DIRECTED > KEYWORD_ES > VECTOR_GLOBAL
    channelResults.sort(Comparator.comparingInt(r -> channelPriority(r.getChannelType())));

    // LinkedHashMap 保持插入顺序 + 自动去重
    LinkedHashMap<String, RetrievedChunk> deduped = new LinkedHashMap<>();
    for (SearchChannelResult result : channelResults) {
        for (RetrievedChunk chunk : result.getChunks()) {
            deduped.merge(chunk.getId(), chunk,
                    (existing, incoming) -> {
                        // 保留分数更高的
                        return existing.getScore() >= incoming.getScore() ? existing : incoming;
                    });
        }
    }
    return new ArrayList<>(deduped.values());
}

// 通道优先级定义
private int channelPriority(SearchChannelType type) {
    return switch (type) {
        case INTENT_DIRECTED -> 0;    // 最高优先级
        case KEYWORD_ES      -> 1;
        case VECTOR_GLOBAL   -> 2;    // 最低优先级
        default              -> 99;
    };
}
```

### 3.4 模板方法 — AbstractParallelRetriever

```java
// 文件：bootstrap/.../rag/core/retrieve/channel/AbstractParallelRetriever.java:61-99

protected List<RetrievedChunk> parallelRetrieve(List<T> targets, SearchContext context) {
    List<CompletableFuture<List<RetrievedChunk>>> futures = targets.stream()
            .map(target -> CompletableFuture.supplyAsync(
                    () -> createRetrievalTask(target, context),  // 子类实现
                    ragInnerRetrievalExecutor
            ).exceptionally(ex -> {
                log.warn("{} 检索 [{}] 失败", getStatisticsName(),
                        getTargetIdentifier(target), ex);
                return List.of();                    // 单目标失败不影响整体
            }))
            .toList();

    List<RetrievedChunk> allChunks = new ArrayList<>();
    int successCount = 0, failCount = 0;
    for (CompletableFuture<List<RetrievedChunk>> f : futures) {
        List<RetrievedChunk> chunks = f.join();
        if (!chunks.isEmpty()) {
            allChunks.addAll(chunks);
            successCount++;
        } else {
            failCount++;
        }
    }
    log.info("{}: 成功={}, 失败={}, 总召回={}",
            getStatisticsName(), successCount, failCount, allChunks.size());
    return allChunks;
}
```

### 3.5 意图定向通道的启用逻辑

```java
// 文件：bootstrap/.../rag/core/retrieve/channel/IntentDirectedSearchChannel.java:65-79

@Override
public boolean isEnabled(SearchContext context) {
    if (!channelProperties.getIntentDirected().isEnabled()) return false;

    // 存在有效 KB 意图才启用
    List<SubQuestionIntent> intents = context.getIntents();
    if (CollUtil.isEmpty(intents)) return false;

    double minScore = channelProperties.getIntentDirected().getMinIntentScore();
    return intents.stream()
            .flatMap(si -> si.nodeScores().stream())
            .anyMatch(ns -> ns.getNode().isKB() && ns.getScore() >= minScore);
}
```

## 4. 设计意图

### 为什么选通道架构而非 if-else 分支？

| 方案 | 优点 | 缺点 |
|------|------|------|
| if-else 分支 | 简单直接 | 扩展难，新增检索方式要改主流程 |
| **通道接口**（本方案） | 开闭原则，新增通道零修改主引擎 | 需要设计接口 |

通道接口让检索引擎完全不知道具体有哪些通道——它只调用 `isEnabled()` 和 `search()`。新增 ES 关键词通道只需实现 `SearchChannel` 接口并注册为 Spring Bean。

### 为什么 DeduplicationPostProcessor 使用 LinkedHashMap？

- `HashMap` 不保序，合并后文档顺序随机
- `LinkedHashMap` 保持插入顺序（先插入的通道优先级更高的文档排在前面）
- `merge()` 方法天然支持去重 + 分数取最高

### 为什么通道失败用 exceptionally 降级而非抛异常？

任何一个通道的失败不应该阻断整个检索。`exceptionally()` 返回空结果，让其他通道的结果仍然可用。这是典型的"部分降级"策略。

## 5. 面试话术

### 30 秒版

> 我设计了一个多通道检索引擎。核心是 `SearchChannel` 接口——每个通道有优先级、启用条件和检索逻辑。当前有两个通道：意图定向通道（意图明确时精准检索指定集合）和全局兜底通道（意图模糊时搜所有集合），两者互补启用。通道内部用 `AbstractParallelRetriever` 模板方法实现多集合并行检索。检索完成后经过后处理器链——先 LinkedHashMap 保序去重（高优先级通道的结果优先保留），再调用 Rerank 模型精排。整个架构对扩展开放，新增通道或后处理器只需实现接口。

### 追问应对

**Q：两个通道同时启用时结果怎么合并？**
> 首先两个通道并行执行，各自返回 `SearchChannelResult`（包含 chunks 列表和 confidence）。然后 `DeduplicationPostProcessor` 按通道优先级排序（意图定向 > 全局），用 LinkedHashMap 去重——同一个 chunk 如果两个通道都检索到，保留分数更高的。最后 `RerankPostProcessor` 调用 Rerank 模型对去重后的结果统一精排，输出最终 Top-K。

**Q：如果所有通道都失败了怎么办？**
> 每个通道的 `CompletableFuture` 都有 `exceptionally()` 兜底，单通道失败返回空结果而不抛异常。如果所有通道都返回空，最终 `retrieveKnowledgeChannels()` 返回空列表。上层 `RAGChatServiceImpl` 检测到无检索结果后，会直接用用户问题调用 LLM，相当于退化为纯对话模式——不会报错，只是回答质量降低。

**Q：后处理器链的顺序为什么是先去重后精排？**
> 顺序很重要。如果先精排后去重，Rerank 模型会处理大量重复文档，浪费算力和 token。先去重可以减少精排的输入规模（通常减少 30-50%），提高效率。去重的 order=1，精排的 order=10，通过 Spring 注入时按 order 排序保证执行顺序。

## 6. 关联亮点

- [亮点2：意图识别](./02-intent-recognition.md) — 意图结果驱动通道启用决策
- [亮点5：专用线程池](./05-thread-pool-ttl.md) — `ragRetrievalThreadPoolExecutor` + `ragInnerRetrievalThreadPoolExecutor`
- [亮点3：模型路由](./03-model-routing-circuit-breaker.md) — Rerank 调用走 RoutingRerankService
- [亮点7：全链路追踪](./07-rag-trace-aop.md) — 通道执行标注 `@RagTraceNode`
