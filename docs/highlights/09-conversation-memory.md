# 亮点9：多层记忆管理

> 并行加载摘要+历史 + 异步 LLM 摘要压缩 + 分布式锁防并发 + 滑动窗口保鲜，实现长对话的上下文记忆不丢失。

## 1. 问题背景

### 为什么需要？

长对话场景下的核心矛盾：

- **上下文窗口有限**：LLM 的上下文窗口有长度限制，不能把所有历史消息全部塞进去
- **历史不能丢**：用户 10 轮前提到的关键信息（如"我问的是 Java 8"），后续回答仍需参考
- **延迟敏感**：摘要生成需要调用 LLM，不能阻塞当前对话响应

### 不做会怎样？

- 简单截断：丢失早期上下文，回答质量急剧下降
- 全量传入：超出上下文窗口限制，或消耗大量 token
- 同步摘要：每次对话多等 2-3 秒

## 2. 设计方案

### 架构图

```
┌───── DefaultConversationMemoryService.load() ─────┐
│                                                     │
│  CompletableFuture 并行:                            │
│  ┌──────────────────┐  ┌─────────────────────────┐ │
│  │ 加载最新摘要     │  │ 加载最近 N 轮历史       │ │
│  │ (SummaryService) │  │ (MemoryStore)            │ │
│  │                  │  │                          │ │
│  │ t_conversation   │  │ t_message                │ │
│  │ _summary         │  │ ORDER BY id DESC         │ │
│  │                  │  │ LIMIT keepTurns × 2      │ │
│  └────────┬─────────┘  └────────────┬────────────┘ │
│           │                         │               │
│           └──── attachSummary() ────┘               │
│                     │                               │
│                     ▼                               │
│           [摘要(system)] + [历史消息(user/assistant)]│
└─────────────────────────────────────────────────────┘

┌───── append() → compressIfNeeded() ─────┐
│                                          │
│  if (ASSISTANT 消息 && 摘要已启用) {     │
│      异步提交到 memorySummaryExecutor    │
│                                          │
│      ┌──────────────────────────────┐    │
│      │ 1. 获取 Redis 分布式锁       │    │
│      │ 2. 统计用户消息数            │    │
│      │    ≥ summaryStartTurns?      │    │
│      │ 3. 确定压缩区间:             │    │
│      │    [lastSummaryMsgId,        │    │
│      │     最近keepTurns的边界)     │    │
│      │ 4. 加载区间内消息            │    │
│      │ 5. LLM 生成新摘要            │    │
│      │    (合并旧摘要 + 新消息)     │    │
│      │ 6. 存入 t_conversation_summary│    │
│      └──────────────────────────────┘    │
│  }                                       │
└──────────────────────────────────────────┘
```

### 关键参数

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `historyKeepTurns` | 8 | 保留原文的最近轮数 |
| `summaryEnabled` | false | 是否启用摘要压缩 |
| `summaryStartTurns` | 9 | 消息数达到此值才触发压缩 |
| `summaryMaxChars` | 200 | 摘要最大字符数 |

### 设计模式

- **分层架构**：Service → Store → Mapper 三层分离
- **异步解耦**：摘要生成与主流程异步
- **悲观锁**：Redis 分布式锁防并发压缩

## 3. 核心代码解析

### 3.1 并行加载 — 摘要 + 历史同时取

```java
// 文件：bootstrap/.../rag/core/memory/DefaultConversationMemoryService.java:52-69

@Override
public List<ChatMessage> load(String conversationId, String userId) {
    if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
        return List.of();
    }

    // 两个 IO 操作并行执行
    CompletableFuture<ChatMessage> summaryFuture = CompletableFuture.supplyAsync(
            () -> loadSummaryWithFallback(conversationId, userId)
    );
    CompletableFuture<List<ChatMessage>> historyFuture = CompletableFuture.supplyAsync(
            () -> loadHistoryWithFallback(conversationId, userId)
    );

    // 等待两者完成后合并
    return CompletableFuture.allOf(summaryFuture, historyFuture)
            .thenApply(v -> attachSummary(summaryFuture.join(), historyFuture.join()))
            .join();
}
```

### 3.2 摘要附加 — 作为 system 消息前置

```java
// 文件：bootstrap/.../rag/core/memory/DefaultConversationMemoryService.java:111-123

private List<ChatMessage> attachSummary(ChatMessage summary, List<ChatMessage> history) {
    if (summary == null || StrUtil.isBlank(summary.getContent())) {
        return history;
    }
    // 摘要装饰：添加 "[对话摘要]" 前缀
    ChatMessage decorated = summaryService.decorateIfNeeded(summary);
    List<ChatMessage> result = new ArrayList<>();
    result.add(decorated);           // 摘要放在最前面
    result.addAll(history);          // 历史消息紧随其后
    return result;
}
```

### 3.3 异步压缩触发 — compressIfNeeded

```java
// 文件：bootstrap/.../rag/core/memory/MySQLConversationMemorySummaryService.java:72-85

@Override
public void compressIfNeeded(String conversationId, String userId, ChatMessage message) {
    if (!memoryProperties.getSummaryEnabled()) return;        // 开关检查
    if (!"assistant".equalsIgnoreCase(message.getRole())) return;  // 只在回复后触发

    // 异步执行，不阻塞当前请求
    CompletableFuture.runAsync(
            () -> doCompressIfNeeded(conversationId, userId),
            memorySummaryExecutor                             // ← 专用线程池
    );
}
```

### 3.4 压缩核心逻辑 — 分布式锁 + 区间加载 + LLM 摘要

```java
// 文件：bootstrap/.../rag/core/memory/MySQLConversationMemorySummaryService.java:106-176

private void doCompressIfNeeded(String conversationId, String userId) {
    // 1. 分布式锁，防止同一会话并发压缩
    RLock lock = redissonClient.getLock("ragent:summary:lock:" + conversationId);
    if (!lock.tryLock(0, 60, TimeUnit.SECONDS)) return;

    try {
        // 2. 检查用户消息总数是否达到阈值
        long userMsgCount = groupService.countUserMessages(conversationId, userId);
        if (userMsgCount < memoryProperties.getSummaryStartTurns()) return;

        // 3. 确定压缩区间
        ConversationSummaryDO latestSummary = groupService.findLatestSummary(conversationId, userId);
        Long afterId = latestSummary != null ? latestSummary.getLastMessageId() : null;

        // 获取最近 keepTurns 轮用户消息，确定 cutoffId
        List<ConversationMessageDO> recentUserMsgs = groupService
                .listLatestUserOnlyMessages(conversationId, userId, keepTurns);
        if (recentUserMsgs.isEmpty()) return;
        Long cutoffId = recentUserMsgs.get(recentUserMsgs.size() - 1).getId();

        // 4. 加载 [afterId, cutoffId) 区间的所有消息
        List<ConversationMessageDO> toCompress = groupService
                .listMessagesBetweenIds(conversationId, userId, afterId, cutoffId);
        if (toCompress.isEmpty()) return;

        // 5. 调用 LLM 生成摘要
        String existingSummary = latestSummary != null ? latestSummary.getContent() : null;
        String newSummary = generateSummary(toCompress, existingSummary);

        // 6. 存入数据库
        Long lastMsgId = toCompress.get(toCompress.size() - 1).getId();
        summaryMapper.insert(ConversationSummaryDO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .content(newSummary)
                .lastMessageId(lastMsgId)
                .build());
    } finally {
        lock.unlock();
    }
}
```

### 3.5 LLM 摘要 Prompt — 合并去重

```java
// 文件：bootstrap/.../rag/core/memory/MySQLConversationMemorySummaryService.java:187-227

private String generateSummary(List<ConversationMessageDO> messages, String existingSummary) {
    List<ChatMessage> summaryMessages = new ArrayList<>();
    summaryMessages.add(ChatMessage.system(summaryPrompt));

    // 历史摘要作为参考（而非事实来源）
    if (StrUtil.isNotBlank(existingSummary)) {
        summaryMessages.add(ChatMessage.assistant(
                "历史摘要（仅用于合并去重，不得作为事实新增来源；"
                + "若与本轮对话冲突，以本轮对话为准）：\n"
                + existingSummary.trim()
        ));
    }

    // 待压缩的消息
    summaryMessages.addAll(messages.stream()
            .map(m -> new ChatMessage(m.getRole(), m.getContent()))
            .toList());

    // 摘要生成指令
    summaryMessages.add(ChatMessage.user(
            "合并以上对话与历史摘要，去重后输出更新摘要。"
            + "要求：严格≤" + summaryMaxChars + "字符；仅一行。"
    ));

    return llmService.chat(ChatRequest.builder()
            .messages(summaryMessages)
            .temperature(0.3D)
            .build());
}
```

### 3.6 配置校验 — summaryStartTurns > historyKeepTurns

```java
// 文件：bootstrap/.../rag/config/validation/MemoryConfigValidator.java

public boolean isValid(MemoryProperties config, ConstraintValidatorContext context) {
    if (Boolean.TRUE.equals(config.getSummaryEnabled())) {
        if (config.getSummaryStartTurns() <= config.getHistoryKeepTurns()) {
            // summaryStartTurns 必须大于 historyKeepTurns
            // 否则刚加载的原文历史就被压缩了，没有意义
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "summaryStartTurns 必须大于 historyKeepTurns")
                    .addConstraintViolation();
            return false;
        }
    }
    return true;
}
```

## 4. 设计意图

### 为什么摘要 + 原文并存而非只保留摘要？

| 方案 | 优点 | 缺点 |
|------|------|------|
| 只保留最近 N 轮原文 | 简单 | 早期上下文完全丢失 |
| 只保留摘要 | 节省 token | 丢失细节和措辞 |
| **摘要 + 原文**（本方案） | 早期概要 + 近期细节 | 实现稍复杂 |

最终传给 LLM 的消息结构是：`[摘要(system)] + [最近8轮原文(user/assistant)] + [当前问题(user)]`。摘要保留了早期对话的要点，原文保留了最近对话的完整细节。

### 为什么摘要生成用分布式锁？

同一会话可能有多个 ASSISTANT 消息快速到达（如快速连续提问），每个都触发 `compressIfNeeded()`。不加锁会导致：
- 多个线程同时查询同一区间的消息
- 生成多条重复摘要
- 浪费 LLM 调用

Redis 分布式锁保证同一会话同时只有一个压缩任务。

### 为什么 summaryStartTurns 必须大于 historyKeepTurns？

如果 `summaryStartTurns = 5` 而 `historyKeepTurns = 8`，意味着第 5 轮就开始压缩，但 load 时加载最近 8 轮原文。被压缩的消息仍然在原文窗口内，压缩毫无意义。自定义校验注解 `@ValidMemoryConfig` 在启动时就拦截了这种无效配置。

## 5. 面试话术

### 30 秒版

> 我设计了一套分层记忆管理系统。加载时，摘要和历史消息通过 CompletableFuture 并行加载，合并后传给 LLM——摘要覆盖早期对话要点，原文保留最近 8 轮细节。写入时，每条 ASSISTANT 回复后异步判断是否需要压缩。压缩逻辑是：先获取 Redis 分布式锁，再统计用户消息数是否达到阈值，然后加载"上次摘要结束位置"到"当前保留窗口边界"之间的消息，调用 LLM 生成新摘要并与旧摘要合并去重。整个压缩过程在专用线程池中异步执行，不阻塞当前对话。

### 追问应对

**Q：摘要生成失败了怎么办？**
> 整个压缩流程在 try-catch 中，失败只打 warn 日志，不影响对话。下一次 ASSISTANT 消息到达时会重新触发压缩。由于使用分布式锁，不会出现重复压缩。即使摘要暂时缺失，load() 的 `loadSummaryWithFallback()` 返回 null，直接跳过摘要、只返回原文历史，保证基本可用。

**Q：为什么不用向量检索做记忆（Memory RAG）？**
> 向量检索适合海量非结构化知识库，但对话记忆有时序性——第 3 轮的上下文和第 8 轮密切相关。LLM 摘要能理解时序关系并提炼要点（"用户在讨论 Java 8 的 Stream API"），向量检索只能找语义相似的片段，可能丢失因果链。当然，如果对话特别长（100+ 轮），可以考虑两者结合。

**Q：并行加载的容错怎么做的？**
> `loadSummaryWithFallback()` 和 `loadHistoryWithFallback()` 各自包裹 try-catch。摘要加载失败→返回 null（跳过摘要），历史加载失败→返回空列表。两者独立容错，不会因为一个失败导致另一个也无法返回。

## 6. 关联亮点

- [亮点5：专用线程池](./05-thread-pool-ttl.md) — 使用 `memorySummaryThreadPoolExecutor` 异步压缩
- [亮点3：模型路由](./03-model-routing-circuit-breaker.md) — 摘要生成调用 RoutingLLMService
- [亮点7：全链路追踪](./07-rag-trace-aop.md) — 记忆加载标注 `@RagTraceNode`
