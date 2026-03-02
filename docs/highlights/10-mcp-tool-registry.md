# 亮点10：MCP 工具动态注册与调用

> Spring Bean 自动发现 + ConcurrentHashMap 注册表 + CompletableFuture 批量并行 + LLM 参数提取，实现可扩展的工具调用框架。

## 1. 问题背景

### 为什么需要？

RAG 系统不仅需要检索知识库，还需要调用实时数据源（销售数据、库存查询、天气 API 等）：

- **需求多变**：业务方随时可能要求接入新的数据源
- **调用复杂**：每个工具有不同的参数定义，需要从用户问题中自动提取
- **性能要求**：多个工具可能需要并行调用

### 不做会怎样？

- 新增工具需要修改核心 RAG 流程代码
- 工具调用逻辑散落在各处，难以管理
- 无法复用参数提取、错误处理等通用逻辑

## 2. 设计方案

### 架构图

```
@PostConstruct 启动时
    │
    ▼
DefaultMCPToolRegistry.init()
    │  List<MCPToolExecutor> 自动注入
    │  遍历 → ConcurrentHashMap.put(toolId, executor)
    ▼
MCPToolRegistry {
    "sales_query" → SalesMCPExecutor
    "weather"     → WeatherMCPExecutor
    ...
}

─── 运行时调用流程 ───

意图识别结果: [NodeScore(kind=MCP, mcpToolId="sales_query")]
    │
    ▼
MCPParameterExtractor.extractParameters()
    │  LLM 从用户问题中提取参数
    │  "上个月华东区销售额" → { region: "华东", period: "上月" }
    ▼
MCPServiceOrchestrator.execute(MCPRequest)
    │
    ├─ 1. registry.getExecutor(toolId)    ← 查找执行器
    ├─ 2. 参数校验 + 权限检查
    ├─ 3. executor.execute(request)        ← 执行
    └─ 4. 返回 MCPResponse

─── 批量并行 ───

MCPServiceOrchestrator.executeBatch(requests)
    │
    ▼
requests.stream()
    .map(r → CompletableFuture.supplyAsync(() → execute(r), mcpBatchExecutor))
    .toList()
    │
    ▼
futures.stream().map(CompletableFuture::join).toList()
```

### 设计模式

- **注册表模式**：`MCPToolRegistry` 管理所有工具
- **策略模式**：`MCPToolExecutor` 接口，每个工具一个实现
- **自动发现**：Spring `@PostConstruct` 批量注册
- **函数式调用**：`buildToolsDescription()` 生成 LLM 可读的工具列表

## 3. 核心代码解析

### 3.1 MCPToolExecutor 接口

```java
// 文件：bootstrap/.../rag/core/mcp/MCPToolExecutor.java

public interface MCPToolExecutor {
    /** 返回工具定义（名称、描述、参数列表） */
    MCPTool getToolDefinition();

    /** 执行工具调用 */
    MCPResponse execute(MCPRequest request);

    /** 快捷方法：获取工具 ID */
    default String getToolId() {
        return getToolDefinition().getToolId();
    }

    /** 判断是否支持该请求 */
    default boolean supports(MCPRequest request) {
        return getToolId().equals(request.getToolId());
    }
}
```

### 3.2 工具注册表 — @PostConstruct 自动发现

```java
// 文件：bootstrap/.../rag/core/mcp/DefaultMCPToolRegistry.java

@Service
public class DefaultMCPToolRegistry implements MCPToolRegistry {

    private final ConcurrentHashMap<String, MCPToolExecutor> executors = new ConcurrentHashMap<>();
    private final List<MCPToolExecutor> discoveredExecutors;  // Spring 自动注入

    public DefaultMCPToolRegistry(List<MCPToolExecutor> discoveredExecutors) {
        this.discoveredExecutors = discoveredExecutors;
    }

    @PostConstruct
    public void init() {
        for (MCPToolExecutor executor : discoveredExecutors) {
            String toolId = executor.getToolId();
            MCPToolExecutor existing = executors.put(toolId, executor);
            if (existing != null) {
                log.warn("工具 [{}] 被覆盖注册: {} → {}",
                        toolId, existing.getClass().getSimpleName(),
                        executor.getClass().getSimpleName());
            } else {
                log.info("注册 MCP 工具: [{}] - {}",
                        toolId, executor.getToolDefinition().getName());
            }
        }
        log.info("MCP 工具注册完成，共 {} 个工具", executors.size());
    }

    @Override
    public void register(MCPToolExecutor executor) {
        executors.put(executor.getToolId(), executor);   // 支持运行时动态注册
    }

    @Override
    public void unregister(String toolId) {
        executors.remove(toolId);
    }

    @Override
    public MCPToolExecutor getExecutor(String toolId) {
        return executors.get(toolId);
    }
}
```

### 3.3 编排器 — 单工具执行

```java
// 文件：bootstrap/.../rag/core/mcp/MCPServiceOrchestrator.java

@Override
public MCPResponse execute(MCPRequest request) {
    // 1. 参数校验
    if (request == null || StrUtil.isBlank(request.getToolId())) {
        return MCPResponse.error(null, "INVALID_PARAM", "请求参数无效");
    }

    // 2. 查找执行器
    MCPToolExecutor executor = toolRegistry.getExecutor(request.getToolId());
    if (executor == null) {
        return MCPResponse.error(request.getToolId(), "TOOL_NOT_FOUND",
                "工具不存在: " + request.getToolId());
    }

    // 3. 权限检查
    MCPTool tool = executor.getToolDefinition();
    if (tool.isRequireUserId() && StrUtil.isBlank(request.getUserId())) {
        return MCPResponse.error(request.getToolId(), "USER_REQUIRED",
                "该工具需要用户身份");
    }

    // 4. 执行 + 计时
    long start = System.currentTimeMillis();
    try {
        MCPResponse response = executor.execute(request);
        response.setCostMs(System.currentTimeMillis() - start);
        return response;
    } catch (Exception e) {
        log.error("MCP 工具 [{}] 执行失败", request.getToolId(), e);
        return MCPResponse.error(request.getToolId(), "EXECUTION_ERROR", e.getMessage());
    }
}
```

### 3.4 批量并行执行

```java
// 文件：bootstrap/.../rag/core/mcp/MCPServiceOrchestrator.java

@Override
public List<MCPResponse> executeBatch(List<MCPRequest> requests) {
    if (CollUtil.isEmpty(requests)) return List.of();

    List<CompletableFuture<MCPResponse>> futures = requests.stream()
            .map(request -> CompletableFuture.supplyAsync(
                    () -> execute(request),
                    mcpBatchThreadPoolExecutor    // ← TTL 包装线程池
            ))
            .toList();

    return futures.stream()
            .map(CompletableFuture::join)         // 等待所有完成
            .toList();
}
```

### 3.5 为 LLM 构建工具描述

```java
// 文件：bootstrap/.../rag/core/mcp/MCPServiceOrchestrator.java

@Override
public String buildToolsDescription() {
    List<MCPTool> tools = listAvailableTools();
    if (tools.isEmpty()) return "";

    StringBuilder sb = new StringBuilder();
    sb.append("【可用工具列表】\n\n");

    for (MCPTool tool : tools) {
        sb.append("工具名称: ").append(tool.getName()).append("\n");
        sb.append("工具ID: ").append(tool.getToolId()).append("\n");
        sb.append("功能描述: ").append(tool.getDescription()).append("\n");

        if (tool.getExamples() != null && !tool.getExamples().isEmpty()) {
            sb.append("示例问题: ")
              .append(String.join(" / ", tool.getExamples()))
              .append("\n");
        }

        if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
            sb.append("参数:\n");
            tool.getParameters().forEach((name, def) -> {
                sb.append("  - ").append(name);
                if (def.isRequired()) sb.append(" (必填)");
                sb.append(": ").append(def.getDescription()).append("\n");
            });
        }
        sb.append("\n");
    }
    return sb.toString();
}
```

### 3.6 MCPTool 工具定义

```java
// 文件：bootstrap/.../rag/core/mcp/MCPTool.java

@Data
@Builder
public class MCPTool {
    private String toolId;                               // 唯一 ID
    private String name;                                  // 展示名称
    private String description;                           // 功能描述（给 LLM）
    private List<String> examples;                        // 示例问题
    private Map<String, ParameterDef> parameters;         // 参数定义
    private boolean requireUserId;                        // 是否需要用户身份

    @Data
    @Builder
    public static class ParameterDef {
        private String description;
        private String type;               // string / number / boolean / array / object
        private boolean required;
        private String defaultValue;
        private List<String> enumValues;   // 枚举约束
    }
}
```

### 3.7 响应合并 — mergeResponsesToText

```java
// 文件：bootstrap/.../rag/core/mcp/MCPServiceOrchestrator.java

@Override
public String mergeResponsesToText(List<MCPResponse> responses) {
    StringBuilder sb = new StringBuilder();
    int failCount = 0;

    for (MCPResponse resp : responses) {
        if (resp.isSuccess() && StrUtil.isNotBlank(resp.getTextResult())) {
            sb.append(resp.getTextResult()).append("\n\n");
        } else {
            failCount++;
        }
    }

    if (failCount > 0) {
        sb.append("（").append(failCount).append(" 个工具调用失败）\n");
    }
    return sb.toString().trim();
}
```

## 4. 设计意图

### 为什么选注册表而非直接 if-else 路由？

| 方案 | 优点 | 缺点 |
|------|------|------|
| if-else 路由 | 简单 | 每新增工具改主逻辑 |
| **注册表 + 自动发现** | 开闭原则，新工具只写一个类 | 需要注册表管理 |
| 反射扫描 | 最灵活 | 安全性差，调试难 |

新增一个 MCP 工具只需要：
1. 实现 `MCPToolExecutor` 接口
2. 标注 `@Component`
3. 完成——`@PostConstruct` 自动注册

### 为什么 MCPResponse 有 textResult 字段？

工具返回的结构化数据（JSON）不能直接塞给 LLM。`textResult` 是工具自己格式化的"人类可读"文本，直接拼接到 Prompt 中。这样每个工具可以控制自己的输出格式，不需要统一的数据→文本转换器。

### 为什么参数提取用 LLM 而非规则？

用户说"上个月华东区的销售额"，规则引擎很难准确提取 `region=华东` 和 `period=上月`。LLM 理解自然语言语义，提取准确率远高于正则匹配。`MCPParameterExtractor` 把工具的参数定义喂给 LLM，让它输出结构化 JSON。

## 5. 面试话术

### 30 秒版

> 我设计了一套 MCP 工具动态注册与调用框架。核心是 `MCPToolExecutor` 接口——每个工具实现 `getToolDefinition()`（描述参数和功能）和 `execute()`（执行逻辑）。启动时 `@PostConstruct` 自动发现所有实现类，注册到 ConcurrentHashMap。调用时，编排器先查注册表找到执行器，校验参数和权限，然后执行并计时。批量调用通过 CompletableFuture 并行执行。特别设计了 `buildToolsDescription()` 方法，把所有工具的名称、参数、示例拼成文本，喂给 LLM 做意图识别和参数提取。

### 追问应对

**Q：如何保证工具执行不会拖慢主流程？**
> 两个机制。第一，批量执行使用 `mcpBatchThreadPoolExecutor`（TTL 包装），与检索线程池隔离，不抢资源。第二，`execute()` 有 try-catch 和计时，单个工具失败返回 error 响应，不影响其他工具和主流程。`mergeResponsesToText()` 会标注"N 个工具调用失败"，LLM 仍能基于成功的工具结果生成回答。

**Q：运行时动态注册的场景是什么？**
> 除了启动时自动发现，`register()` 和 `unregister()` 支持运行时动态增删工具。场景包括：管理后台新增/禁用工具、A/B 测试新工具、根据租户配置加载不同工具集。ConcurrentHashMap 保证线程安全。

**Q：和 OpenAI Function Calling 有什么区别？**
> Function Calling 是 LLM 原生能力——模型决定调用哪个函数。我们的 MCP 系统是"意图驱动"的——意图识别阶段已经确定了要调用哪个工具（kind=MCP），参数由专门的 `MCPParameterExtractor` 提取。这样做的好处是：意图识别可以结合知识库元数据（如工具关联的 examples），比纯靠 LLM 的 Function Calling 更可控。

## 6. 关联亮点

- [亮点2：意图识别](./02-intent-recognition.md) — kind=MCP 的意图触发工具调用
- [亮点5：专用线程池](./05-thread-pool-ttl.md) — 使用 `mcpBatchThreadPoolExecutor`
- [亮点3：模型路由](./03-model-routing-circuit-breaker.md) — 参数提取调用 RoutingLLMService
- [亮点1：多路检索](./01-multi-channel-retrieval.md) — MCP 结果与 KB 结果合并
