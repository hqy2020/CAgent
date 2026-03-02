# 亮点6：文档入库 Pipeline

> 节点接口 + 链式执行引擎 + 条件跳过 + Spring 自动发现 + 6 级处理流水线，实现文档从原始字节到向量索引的全自动入库。

## 1. 问题背景

### 为什么需要？

知识库文档入库是一个多步骤、多策略的复杂流程：

- 文档格式多样（PDF、Word、Excel、Markdown、图片）
- 处理步骤多：获取→解析→增强→分块→富化→索引
- 不同文档可能需要跳过某些步骤（如纯文本不需要 OCR 增强）
- 每个步骤可能失败，需要记录日志用于排查

### 不做会怎样？

- 入库逻辑写在一个巨大的 Service 方法里，几百行 if-else
- 新增处理步骤要修改核心逻辑
- 无法灵活配置"跳过某些步骤"

## 2. 设计方案

### 架构图

```
PipelineDefinition (JSON 配置)
    │
    ▼
IngestionEngine.execute(pipeline, context)
    │
    ├─ 1. validatePipeline()    ← 环路检测 + 节点完整性
    ├─ 2. findStartNode()       ← 找无前驱节点
    └─ 3. executeChain()        ← 链式执行
         │
         ▼
    ┌─── FetcherNode ──┐  获取原始字节（本地/HTTP/S3）
    │ nodeType: fetcher │
    └────────┬──────────┘
             │ nextNodeId
    ┌─── ParserNode ───┐  解析为结构化文本（Tika）
    │ nodeType: parser  │
    └────────┬──────────┘
             │ nextNodeId
    ┌─── EnhancerNode ──┐  LLM 文本增强（上下文/关键词/问题）
    │ nodeType: enhancer │  ← condition: 可配置跳过
    └────────┬───────────┘
             │ nextNodeId
    ┌─── ChunkerNode ───┐  文本分块（可配策略/大小/重叠）
    │ nodeType: chunker  │
    └────────┬───────────┘
             │ nextNodeId
    ┌─── EnricherNode ──┐  块级富化（关键词/摘要/元数据）
    │ nodeType: enricher │  ← condition: 可配置跳过
    └────────┬───────────┘
             │ nextNodeId
    ┌─── IndexerNode ───┐  写入 Milvus 向量库
    │ nodeType: indexer  │
    └────────────────────┘
```

### 设计模式

- **策略模式**：`IngestionNode` 接口，每种节点一个实现
- **链式调用**：通过 `nextNodeId` 配置串联
- **模板方法**：`IngestionEngine` 定义执行骨架，节点实现具体逻辑
- **自动发现**：Spring 注入 `List<IngestionNode>`，构造函数构建 nodeType→Node 映射

## 3. 核心代码解析

### 3.1 IngestionNode 接口

```java
// 文件：bootstrap/.../ingestion/node/IngestionNode.java

public interface IngestionNode {
    /** 节点类型标识（如 "fetcher", "parser", "chunker"） */
    String getNodeType();

    /** 执行节点逻辑 */
    NodeResult execute(IngestionContext context, JsonNode settings);
}
```

### 3.2 执行引擎 — IngestionEngine

```java
// 文件：bootstrap/.../ingestion/engine/IngestionEngine.java

@Service
public class IngestionEngine {

    private final Map<String, IngestionNode> nodeMap;  // nodeType → Node

    // Spring 自动发现所有 IngestionNode 实现
    public IngestionEngine(List<IngestionNode> nodes) {
        this.nodeMap = nodes.stream()
                .collect(Collectors.toMap(
                        IngestionNode::getNodeType,
                        Function.identity()
                ));
    }

    public void execute(PipelineDefinition pipeline, IngestionContext context) {
        List<NodeConfig> nodeConfigs = pipeline.getNodes();
        validatePipeline(nodeConfigs);              // 环路检测
        NodeConfig startNode = findStartNode(nodeConfigs);  // 找起点
        executeChain(startNode, nodeConfigs, context);      // 链式执行
    }
}
```

### 3.3 链式执行 — executeChain

```java
// 文件：bootstrap/.../ingestion/engine/IngestionEngine.java

private void executeChain(NodeConfig startNode,
                          List<NodeConfig> allConfigs,
                          IngestionContext context) {
    Map<String, NodeConfig> configMap = allConfigs.stream()
            .collect(Collectors.toMap(NodeConfig::getNodeId, Function.identity()));

    NodeConfig current = startNode;

    while (current != null) {
        // 条件评估：是否跳过当前节点
        if (current.getCondition() != null) {
            boolean shouldRun = conditionEvaluator.evaluate(
                    current.getCondition(), context);
            if (!shouldRun) {
                log.info("节点 [{}] 条件不满足，跳过", current.getNodeId());
                context.addLog(NodeLog.skipped(current.getNodeId(), current.getNodeType()));
                current = configMap.get(current.getNextNodeId());
                continue;
            }
        }

        // 执行节点
        NodeResult result = executeNode(current, context);

        // 检查是否继续
        if (!result.isShouldContinue()) {
            log.info("节点 [{}] 返回停止信号: {}",
                    current.getNodeId(), result.getMessage());
            break;
        }

        // 移动到下一个节点
        current = configMap.get(current.getNextNodeId());
    }
}
```

### 3.4 单节点执行 — executeNode

```java
// 文件：bootstrap/.../ingestion/engine/IngestionEngine.java

private NodeResult executeNode(NodeConfig config, IngestionContext context) {
    IngestionNode node = nodeMap.get(config.getNodeType());
    if (node == null) {
        return NodeResult.fail("未知节点类型: " + config.getNodeType());
    }

    long startTime = System.currentTimeMillis();
    try {
        NodeResult result = node.execute(context, config.getSettings());
        long duration = System.currentTimeMillis() - startTime;

        context.addLog(NodeLog.builder()
                .nodeId(config.getNodeId())
                .nodeType(config.getNodeType())
                .status(result.isSuccess() ? "SUCCESS" : "FAILED")
                .durationMs(duration)
                .message(result.getMessage())
                .build());

        return result;
    } catch (Exception e) {
        long duration = System.currentTimeMillis() - startTime;
        context.addLog(NodeLog.failed(config.getNodeId(), config.getNodeType(),
                e.getMessage(), duration));
        return NodeResult.fail(e.getMessage());
    }
}
```

### 3.5 六个节点简述

```java
// FetcherNode — 从多源获取字节流
// 文件：bootstrap/.../ingestion/node/FetcherNode.java
// 根据 SourceType 路由到 DocumentFetcher（策略模式）
// 幂等：若 rawBytes 已存在则跳过

// ParserNode — 解析为结构化文本
// 文件：bootstrap/.../ingestion/node/ParserNode.java
// 使用 Tika 统一解析 PDF/Word/Excel/Markdown
// MIME 类型双重检测（Content-Type + 扩展名）

// EnhancerNode — LLM 文本增强
// 文件：bootstrap/.../ingestion/node/EnhancerNode.java
// 支持: CONTEXT_ENHANCE / KEYWORDS / QUESTIONS / METADATA
// 可配置自定义 Prompt 模板

// ChunkerNode — 文本分块
// 文件：bootstrap/.../ingestion/node/ChunkerNode.java
// 配置: chunkSize(512) / overlapSize(128) / separator / strategy
// 通过 ChunkingStrategyFactory 选择分块策略

// EnricherNode — 块级富化
// 文件：bootstrap/.../ingestion/node/EnricherNode.java
// 对每个 Chunk 逐个调用 LLM 提取关键词/摘要/元数据
// 结果合并到 Chunk 的 metadata 字段

// IndexerNode — 写入向量库
// 文件：bootstrap/.../ingestion/node/IndexerNode.java
// 自动创建 Milvus Collection
// 批量插入 doc_id + content + metadata + embedding
```

### 3.6 条件评估器 — ConditionEvaluator

```java
// 文件：bootstrap/.../ingestion/engine/ConditionEvaluator.java

// 支持丰富的条件表达式：
// 布尔值：true/false
// SpEL 表达式：字符串类型
// 复合规则：
//   { "all": [条件1, 条件2] }     ← 与逻辑
//   { "any": [条件1, 条件2] }     ← 或逻辑
//   { "not": 条件 }                ← 非
//   { "field": "mimeType", "op": "in", "value": ["application/pdf", "text/markdown"] }
//
// 支持的操作符: eq, ne, in, contains, regex, gt, gte, lt, lte, exists, not_exists
```

### 3.7 NodeResult — 控制流程走向

```java
// 文件：bootstrap/.../ingestion/domain/result/NodeResult.java

public class NodeResult {
    private boolean success;
    private boolean shouldContinue;       // 关键：控制是否继续
    private String message;

    public static NodeResult ok()             { /* success=true,  continue=true  */ }
    public static NodeResult skip(String r)   { /* success=true,  continue=true  */ }
    public static NodeResult fail(String err) { /* success=false, continue=false */ }
    public static NodeResult terminate(String r) { /* success=true, continue=false */ }
}
```

## 4. 设计意图

### 为什么选链式而非 DAG？

| 方案 | 优点 | 缺点 |
|------|------|------|
| 硬编码顺序 | 最简单 | 无法灵活配置 |
| **链式 (nextNodeId)** | 简单直观，条件跳过支持灵活路由 | 不支持并行分支 |
| DAG 图引擎 | 支持并行分支 | 实现复杂，入库场景不需要 |

文档入库是天然的串行流水线（分块必须在解析之后），链式结构足够。条件跳过已能覆盖"跳过增强"等需求。

### 为什么用 Spring 自动发现而非手动注册？

`List<IngestionNode>` 注入让新增节点只需：
1. 实现 `IngestionNode` 接口
2. 标注 `@Component`
3. 完成——引擎自动发现

零修改引擎代码，完全开闭原则。

### 为什么 NodeResult 有 shouldContinue 字段？

不是所有失败都需要中断。`skip()` 表示"条件不满足但可以继续"，`fail()` 表示"出错需要停止"，`terminate()` 表示"成功完成但不需要后续节点"。这种三态控制比简单的 boolean 更灵活。

## 5. 面试话术

### 30 秒版

> 我设计了一套文档入库 Pipeline 引擎。核心是 `IngestionNode` 接口——每个节点负责一个处理步骤（获取→解析→增强→分块→富化→索引）。引擎通过 Spring `List<IngestionNode>` 自动发现所有节点实现，构造 nodeType→Node 的映射。Pipeline 配置定义了节点链（每个节点有 nextNodeId 指向下一步）。执行时，引擎先校验配置（检测环路），找到起始节点，然后 while 循环链式执行。每个节点可以配置条件表达式——比如"只有 PDF 才做 OCR 增强"。节点返回 NodeResult 控制流程走向：成功继续、跳过继续、或失败停止。

### 追问应对

**Q：条件表达式支持哪些语法？**
> 支持布尔值、SpEL 表达式和结构化规则。结构化规则支持 all（与）、any（或）、not（非）组合，字段级比较支持 eq/ne/in/contains/regex/gt/gte/lt/lte/exists 等操作符。比如可以配置 `{"field": "mimeType", "op": "in", "value": ["application/pdf"]}` 来限制只对 PDF 文件执行增强。

**Q：如果某个节点处理很慢怎么办？**
> 每个节点的执行都有耗时记录（context.addLog），可以通过日志发现瓶颈。针对慢节点可以优化：比如 ChunkerNode 可以用 `knowledgeChunkExecutor` 线程池并行分块，IndexerNode 可以批量插入而非逐条。Pipeline 本身是同步串行的，但节点内部可以并行。

**Q：环路检测怎么实现的？**
> `validatePipeline()` 遍历 nodeConfig 列表，用 Set 记录已访问的 nodeId。沿着 nextNodeId 链走一遍，如果遇到已访问的 nodeId，说明有环路，抛出异常拒绝执行。同时检查每个 nodeType 是否在 nodeMap 中存在，避免配置了不存在的节点类型。

## 6. 关联亮点

- [亮点3：模型路由](./03-model-routing-circuit-breaker.md) — EnhancerNode/EnricherNode 调用 LLM
- [亮点5：专用线程池](./05-thread-pool-ttl.md) — IndexerNode 使用 `knowledgeChunkExecutor`
- [亮点1：多路检索](./01-multi-channel-retrieval.md) — 索引后的数据被检索引擎消费
