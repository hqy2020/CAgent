# 亮点2：意图识别与问题重写

> 三级意图树（DOMAIN→CATEGORY→TOPIC）+ LLM 分类 + 多问句拆分 + 并行意图解析 + 总量控制，将模糊用户问题转化为精准检索指令。

## 1. 问题背景

### 为什么需要？

用户的提问往往是模糊的、多义的、复合的：

- **模糊**："系统怎么用" → 哪个系统？OA？人事？ERP？
- **复合**："介绍一下 Spring 的 IOC 和 AOP" → 两个独立话题，需要检索不同知识库
- **口语化**："JVM 那个什么垃圾回收怎么调" → 需要改写为精准检索查询

### 不做会怎样？

- 向量检索召回的文档与用户真实意图不匹配
- 复合问题只检索了一部分，回答不完整
- 无法根据意图选择不同的检索通道（KB 还是 MCP 工具）

## 2. 设计方案

### 架构图

```
用户问题："介绍一下 Spring IOC 和 JVM 垃圾回收"
    │
    ▼
QueryTermMappingService.normalize()        ← 术语归一化
    │
    ▼
MultiQuestionRewriteService.rewriteWithSplit()
    │  LLM 调用：改写 + 拆分
    ▼
RewriteResult {
  rewrittenQuestion: "请介绍 Spring IoC 容器和 JVM 垃圾回收机制"
  subQuestions: [
    "Spring IoC 容器的原理和使用",
    "JVM 垃圾回收机制的原理和调优"
  ]
}
    │
    ▼
IntentResolver.resolve()                    ← 并行意图分类
    │  使用 intentClassifyThreadPoolExecutor
    ▼
┌─────────────────────────────────────┐
│ SubQ1: "Spring IoC..."              │
│   → NodeScore(java-spring, 0.92)    │  ← KB 类型
│                                     │
│ SubQ2: "JVM 垃圾回收..."           │
│   → NodeScore(java-jvm, 0.88)      │  ← KB 类型
└─────────────────────────────────────┘
    │
    ▼
capTotalIntents()                          ← 总量控制 ≤ MAX_INTENT_COUNT
    │
    ▼
IntentGroup {
  mcpIntents: [],
  kbIntents: [java-spring, java-jvm]
}
    │
    ▼
→ IntentDirectedSearchChannel（精准检索指定集合）
```

### 意图树结构

```
ROOT
├─ 集团信息化 (DOMAIN)
│   ├─ 人事系统 (CATEGORY)
│   │   ├─ 入职流程 (TOPIC, kind=KB, collection="hr_onboarding")
│   │   └─ 薪酬管理 (TOPIC, kind=KB, collection="hr_salary")
│   └─ OA 系统 (CATEGORY)
│       └─ 审批流程 (TOPIC, kind=KB, collection="oa_approval")
├─ 实时数据 (DOMAIN)
│   └─ 销售查询 (TOPIC, kind=MCP, mcpToolId="sales_query")
└─ 系统交互 (DOMAIN)
    └─ 自我介绍 (TOPIC, kind=SYSTEM)
```

### 设计模式

- **策略模式**：`IntentClassifier` 接口支持串行/并行分类策略
- **模板方法**：意图树从数据库加载 → 构建 → 缓存的统一流程
- **分而治之**：多问句拆分后并行分类，最后合并

## 3. 核心代码解析

### 3.1 意图节点模型 — IntentNode

```java
// 文件：bootstrap/.../rag/core/intent/IntentNode.java

@Data
@Builder
public class IntentNode {
    private String id;                       // 唯一标识
    private String name;                     // 展示名称
    private String description;              // 语义说明（给 LLM 看）
    private IntentLevel level;               // DOMAIN / CATEGORY / TOPIC
    private String parentId;                 // 父节点 ID
    private IntentKind kind;                 // KB / MCP / SYSTEM
    private String collectionName;           // KB → Milvus 集合名
    private String mcpToolId;                // MCP → 工具 ID
    private Integer topK;                    // 节点级检索数量
    private String fullPath;                 // 完整路径（调试用）
    private List<String> examples;           // 示例问题
    private List<IntentNode> children;       // 子节点

    public boolean isLeaf() { return children == null || children.isEmpty(); }
    public boolean isKB()   { return kind == IntentKind.KB; }
    public boolean isMCP()  { return kind == IntentKind.MCP; }
}
```

### 3.2 LLM 意图分类 — DefaultIntentClassifier

```java
// 文件：bootstrap/.../rag/core/intent/DefaultIntentClassifier.java:159-229

@Override
public List<NodeScore> classifyTargets(String question) {
    IntentTreeData data = loadIntentTreeData();      // 缓存优先

    // 构建 Prompt：把所有叶子节点描述喂给 LLM
    String systemPrompt = buildPrompt(data.leafNodes);
    ChatRequest request = ChatRequest.builder()
            .messages(List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(question)
            ))
            .temperature(0.1D)       // ← 低温度，确保稳定性
            .topP(0.3D)
            .thinking(false)
            .build();

    String raw = llmService.chat(request);           // 同步 LLM 调用

    // 解析 JSON 响应：[{"id": "java-spring", "score": 0.92}, ...]
    JsonArray arr = parseResponse(raw);
    List<NodeScore> scores = new ArrayList<>();
    for (JsonElement el : arr) {
        String id = el.getAsJsonObject().get("id").getAsString();
        double score = el.getAsJsonObject().get("score").getAsDouble();
        IntentNode node = data.id2Node.get(id);
        if (node != null) {
            scores.add(new NodeScore(node, score));
        }
    }
    scores.sort(Comparator.comparingDouble(NodeScore::getScore).reversed());
    return scores;
}
```

### 3.3 Prompt 构建 — 喂给 LLM 的意图列表

```java
// 文件：bootstrap/.../rag/core/intent/DefaultIntentClassifier.java:251-283

private String buildPrompt(List<IntentNode> leafNodes) {
    StringBuilder sb = new StringBuilder();
    for (IntentNode node : leafNodes) {
        sb.append("- id=").append(node.getId()).append("\n");
        sb.append("  path=").append(node.getFullPath()).append("\n");
        sb.append("  description=").append(node.getDescription()).append("\n");

        if (node.isMCP()) {
            sb.append("  type=MCP\n");
            sb.append("  toolId=").append(node.getMcpToolId()).append("\n");
        } else if (node.isSystem()) {
            sb.append("  type=SYSTEM\n");
        } else {
            sb.append("  type=KB\n");
        }

        if (node.getExamples() != null && !node.getExamples().isEmpty()) {
            sb.append("  examples=")
              .append(String.join(" / ", node.getExamples()))
              .append("\n");
        }
    }
    // 渲染到模板中（模板包含 JSON 输出格式要求）
    return promptTemplateLoader.render(INTENT_CLASSIFIER_PROMPT_PATH,
            Map.of("intent_list", sb.toString()));
}
```

### 3.4 并行意图解析 — IntentResolver

```java
// 文件：bootstrap/.../rag/core/intent/IntentResolver.java:52-67

@RagTraceNode(name = "intent-resolve", type = "INTENT")
public List<SubQuestionIntent> resolve(RewriteResult rewriteResult) {
    List<String> subQuestions = CollUtil.isNotEmpty(rewriteResult.subQuestions())
            ? rewriteResult.subQuestions()
            : List.of(rewriteResult.rewrittenQuestion());

    // 每个子问题并行分类
    List<CompletableFuture<SubQuestionIntent>> tasks = subQuestions.stream()
            .map(q -> CompletableFuture.supplyAsync(
                    () -> new SubQuestionIntent(q, classifyIntents(q)),
                    intentClassifyExecutor    // ← TTL 包装线程池
            ))
            .toList();

    List<SubQuestionIntent> subIntents = tasks.stream()
            .map(CompletableFuture::join)
            .toList();

    return capTotalIntents(subIntents);       // ← 总量控制
}
```

### 3.5 意图总量控制 — capTotalIntents

```java
// 文件：bootstrap/.../rag/core/intent/IntentResolver.java:120-144

private List<SubQuestionIntent> capTotalIntents(List<SubQuestionIntent> subIntents) {
    int totalIntents = subIntents.stream()
            .mapToInt(si -> si.nodeScores().size())
            .sum();

    if (totalIntents <= MAX_INTENT_COUNT) return subIntents;  // 未超限

    // 超限时的策略：
    // 1. 每个子问题保证至少保留 Top-1 意图
    List<IntentCandidate> guaranteed = selectTopIntentPerSubQuestion(allCandidates, subIntents.size());
    // 2. 剩余配额按分数全局排序填充
    int remaining = MAX_INTENT_COUNT - guaranteed.size();
    List<IntentCandidate> additional = selectAdditionalIntents(allCandidates, guaranteed, remaining);
    // 3. 重建子问题结构
    return rebuildSubIntents(subIntents, guaranteed, additional);
}
```

### 3.6 查询改写 + 拆分 — MultiQuestionRewriteService

```java
// 文件：bootstrap/.../rag/core/rewrite/MultiQuestionRewriteService.java:69-81

@Override
@RagTraceNode(name = "query-rewrite-and-split", type = "REWRITE")
public RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
    if (!ragConfigProperties.getQueryRewriteEnabled()) {
        // 关闭改写时，仅做术语归一化 + 规则拆分
        String normalized = queryTermMappingService.normalize(userQuestion);
        List<String> subs = ruleBasedSplit(normalized);
        return new RewriteResult(normalized, subs);
    }
    // 开启改写时，调用 LLM 同时完成改写和拆分
    String normalizedQuestion = queryTermMappingService.normalize(userQuestion);
    return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, history);
}
```

## 4. 设计意图

### 为什么用树形结构而非平面标签？

| 方案 | 优点 | 缺点 |
|------|------|------|
| 平面标签分类 | 简单 | 标签多了 LLM 准确率下降 |
| **三级树形结构** | LLM 只需分类叶子节点，路径提供语义上下文 | 需要维护树 |

树形结构的 `fullPath`（如 "集团信息化/人事系统/入职流程"）给 LLM 提供了层级语义，大幅提高分类准确率。

### 为什么拆分后再并行分类？

复合问题（"Spring IOC 和 JVM GC"）如果不拆分，LLM 可能只识别到一个意图。拆分后每个子问题独立分类，保证不遗漏。并行执行避免串行延迟叠加。

### 为什么需要 capTotalIntents？

意图数量直接决定检索通道数。如果 3 个子问题各返回 5 个意图 = 15 个并行检索，会导致检索延迟和资源浪费。`MAX_INTENT_COUNT` 限制总量，同时保证每个子问题至少有 1 个意图不被裁剪。

### 为什么改写和拆分合并为一次 LLM 调用？

分开调用需要两次 LLM 往返（~2-4 秒）。合并为一次调用，LLM 同时输出 `rewrite` 和 `sub_questions`，延迟减半。

## 5. 面试话术

### 30 秒版

> 我设计了一套意图识别系统，核心是三级意图树——DOMAIN、CATEGORY、TOPIC。用户问题先经过术语归一化和 LLM 改写拆分，得到子问题列表。然后并行对每个子问题做 LLM 意图分类，把所有叶子节点的 ID、描述、示例喂给 LLM，让它返回 JSON 格式的分数。最后通过 capTotalIntents 控制总意图数量，保证每个子问题至少有一个意图。识别出的意图直接映射到 Milvus 集合名或 MCP 工具 ID，驱动后续的精准检索。

### 追问应对

**Q：LLM 分类的准确率怎么保证？**
> 三个手段：第一，低温度（0.1）+ 低 topP（0.3）保证输出稳定性；第二，Prompt 中包含每个叶子节点的 description 和 examples，提供充足上下文；第三，fullPath 提供层级语义（如 "集团信息化/人事系统/入职流程"），帮助 LLM 区分同名节点。如果 LLM 返回了未知 ID 或解析失败，会优雅降级为空列表，走全局兜底检索。

**Q：意图树从哪来？怎么更新？**
> 意图树存在数据库 `t_intent_node` 表中，通过 `IntentTreeCacheManager` 缓存到 Redis（TTL 7 天）。数据通过管理后台手动配置和维护。更新时只需改数据库 + 清缓存，无需重启服务。

**Q：并行分类会不会消耗太多 LLM token？**
> 会有成本，但可控。每个子问题的分类调用通常只需要 500-1000 tokens（Prompt 中的意图列表是固定的）。通过 `capTotalIntents` 限制子问题数量（改写拆分时 LLM 也不会拆出太多子问题），实际上大多数请求只有 1-2 个子问题。如果成本敏感，可以关闭改写功能走规则拆分。

## 6. 关联亮点

- [亮点1：多路检索引擎](./01-multi-channel-retrieval.md) — 意图识别结果驱动 IntentDirectedSearchChannel
- [亮点5：专用线程池](./05-thread-pool-ttl.md) — 使用 `intentClassifyThreadPoolExecutor` 并行分类
- [亮点3：模型路由](./03-model-routing-circuit-breaker.md) — 分类调用走 RoutingLLMService 自动降级
- [亮点10：MCP 工具](./10-mcp-tool-registry.md) — kind=MCP 的意图触发工具调用
- [亮点7：全链路追踪](./07-rag-trace-aop.md) — `@RagTraceNode("intent-resolve")` 记录耗时
