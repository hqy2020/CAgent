# 亮点7：全链路追踪 AOP

> 基于 `@RagTraceRoot` / `@RagTraceNode` 双注解 + ArrayDeque 栈 + TransmittableThreadLocal，实现 RAG 请求的全链路树形追踪，零侵入业务代码。

## 1. 问题背景

### 为什么需要？

一次 RAG 对话请求涉及 10+ 个异步步骤：查询改写、意图识别、多通道检索、Rerank、MCP 工具调用、LLM 流式生成等。出现问题时：

- **定位困难**：不知道是哪个环节慢或出错
- **无法关联**：异步线程中的日志和主线程日志无法串联
- **缺乏量化**：每个环节的耗时没有结构化记录

### 不做会怎样？

- 排查问题靠 grep 日志，效率极低
- 无法发现性能瓶颈（如某个通道检索超时但被其他通道掩盖）
- 生产环境缺乏可观测性

## 2. 设计方案

### 架构图

```
@RagTraceRoot（入口方法）
│
├─ 生成 traceId (Snowflake)
├─ 记录 RagTraceRunDO (t_rag_trace_run)
├─ RagTraceContext.setTraceId()
│
├─ @RagTraceNode("query-rewrite")     ← depth=0
│   ├─ pushNode(nodeId-1)
│   ├─ 记录 RagTraceNodeDO
│   └─ popNode()
│
├─ @RagTraceNode("intent-resolve")    ← depth=0
│   ├─ pushNode(nodeId-2)
│   ├─ @RagTraceNode("intent-classify") ← depth=1（子线程，TTL 透传）
│   │   ├─ pushNode(nodeId-3)
│   │   ├─ parentNodeId = nodeId-2
│   │   └─ popNode()
│   └─ popNode()
│
├─ @RagTraceNode("retrieval")         ← depth=0
│   └─ ...
│
└─ finally: RagTraceContext.clear()
```

### 数据模型

两张表形成 Run → Node 的一对多树形结构：

| 表名 | 关键字段 | 作用 |
|------|---------|------|
| `t_rag_trace_run` | traceId, traceName, conversationId, taskId, status, durationMs | 一次完整请求 |
| `t_rag_trace_node` | traceId, nodeId, **parentNodeId**, **depth**, nodeType, durationMs | 请求内的节点 |

### 设计模式

- **AOP 切面**：零侵入，只需加注解
- **栈结构**：ArrayDeque 实现 push/pop，天然支持嵌套
- **模板方法**：aroundRoot/aroundNode 共享 try-proceed-catch-finally 骨架

## 3. 核心代码解析

### 3.1 两个注解定义

```java
// 文件：framework/.../trace/RagTraceRoot.java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RagTraceRoot {
    String name() default "";
    String conversationIdArg() default "conversationId";  // 自动从方法参数取值
    String taskIdArg() default "taskId";
}

// 文件：framework/.../trace/RagTraceNode.java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RagTraceNode {
    String name() default "";
    String type() default "METHOD";   // REWRITE / INTENT / RETRIEVE / LLM_PROVIDER 等
}
```

### 3.2 切面核心 — aroundRoot

```java
// 文件：bootstrap/.../rag/aop/RagTraceAspect.java:60-115

@Around("@annotation(traceRoot)")
public Object aroundRoot(ProceedingJoinPoint joinPoint, RagTraceRoot traceRoot) throws Throwable {
    if (!traceProperties.isEnabled()) {
        return joinPoint.proceed();         // 开关关闭，直接放行
    }

    String existingTraceId = RagTraceContext.getTraceId();
    if (StrUtil.isNotBlank(existingTraceId)) {
        return joinPoint.proceed();         // 避免重复创建 root
    }

    String traceId = IdUtil.getSnowflakeNextIdStr();    // ← Snowflake ID
    String conversationId = resolveStringArg(signature, args, traceRoot.conversationIdArg());

    // 记录 Run 开始
    traceRecordService.startRun(RagTraceRunDO.builder()
            .traceId(traceId)
            .traceName(traceName)
            .entryMethod(method.getDeclaringClass().getName() + "#" + method.getName())
            .conversationId(conversationId)
            .userId(UserContext.getUserId())
            .status("RUNNING")
            .startTime(new Date())
            .build());

    RagTraceContext.setTraceId(traceId);    // ← 设置到 TTL，子线程可见
    try {
        Object result = joinPoint.proceed();
        traceRecordService.finishRun(traceId, "SUCCESS", null, new Date(), durationMs);
        return result;
    } catch (Throwable ex) {
        traceRecordService.finishRun(traceId, "ERROR", truncateError(ex), new Date(), durationMs);
        throw ex;
    } finally {
        RagTraceContext.clear();             // ← 必须清理，防止线程复用污染
    }
}
```

### 3.3 切面核心 — aroundNode

```java
// 文件：bootstrap/.../rag/aop/RagTraceAspect.java:117-173

@Around("@annotation(traceNode)")
public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode traceNode) throws Throwable {
    if (!traceProperties.isEnabled()) return joinPoint.proceed();

    String traceId = RagTraceContext.getTraceId();
    if (StrUtil.isBlank(traceId)) return joinPoint.proceed();   // 不在链路中，跳过

    String nodeId = IdUtil.getSnowflakeNextIdStr();
    String parentNodeId = RagTraceContext.currentNodeId();       // ← 栈顶 = 父节点
    int depth = RagTraceContext.depth();                         // ← 栈深度 = 嵌套层级

    traceRecordService.startNode(RagTraceNodeDO.builder()
            .traceId(traceId)
            .nodeId(nodeId)
            .parentNodeId(parentNodeId)    // ← 关键：记录父子关系
            .depth(depth)
            .nodeType(traceNode.type())
            .nodeName(traceNode.name())
            .status("RUNNING")
            .build());

    RagTraceContext.pushNode(nodeId);       // ← 入栈，成为新的栈顶
    try {
        Object result = joinPoint.proceed();
        traceRecordService.finishNode(traceId, nodeId, "SUCCESS", null, new Date(), durationMs);
        return result;
    } catch (Throwable ex) {
        traceRecordService.finishNode(traceId, nodeId, "ERROR", truncateError(ex), new Date(), durationMs);
        throw ex;
    } finally {
        RagTraceContext.popNode();          // ← 出栈，恢复父节点
    }
}
```

### 3.4 栈结构 — push/pop 机制

```java
// 文件：framework/.../trace/RagTraceContext.java:64-82

public static void pushNode(String nodeId) {
    Deque<String> stack = NODE_STACK.get();
    if (stack == null) {
        stack = new ArrayDeque<>();     // 懒初始化
        NODE_STACK.set(stack);
    }
    stack.push(nodeId);
}

public static void popNode() {
    Deque<String> stack = NODE_STACK.get();
    if (stack == null || stack.isEmpty()) return;  // 空栈安全
    stack.pop();
    if (stack.isEmpty()) NODE_STACK.remove();      // 清理空栈，避免内存泄漏
}
```

### 3.5 参数自动解析

```java
// 文件：bootstrap/.../rag/aop/RagTraceAspect.java:175-194

private String resolveStringArg(MethodSignature signature, Object[] args, String argName) {
    if (StrUtil.isBlank(argName) || args == null) return null;
    String[] parameterNames = signature.getParameterNames();
    for (int i = 0; i < parameterNames.length; i++) {
        if (argName.equals(parameterNames[i])) {
            return args[i] == null ? null : String.valueOf(args[i]);
        }
    }
    return null;
}
```

## 4. 设计意图

### 为什么选 AOP 注解而非手动埋点？

| 方案 | 优点 | 缺点 |
|------|------|------|
| 手动 try/finally 埋点 | 最灵活 | 侵入业务代码，容易遗漏 |
| **AOP 注解**（本方案） | 零侵入，一行注解搞定 | 切面优先级需注意 |
| Agent/SDK 自动插桩 | 完全无侵入 | 粒度过粗，无法标注语义 |

### 为什么用 ArrayDeque 栈而非递增计数器？

栈结构不仅记录深度，还能获取 **当前父节点 ID**。计数器只知道深度，无法建立父子关系。栈天然支持嵌套和回退，`push(nodeId)` / `pop()` 对称操作保证数据一致性。

### 为什么 aroundRoot 要检查 existingTraceId？

防止嵌套入口方法（如 A 方法调用 B 方法，两者都标注了 `@RagTraceRoot`）重复创建链路。只有第一个进入的方法创建 root，内部方法自动退化为普通调用。

### 为什么 finally 中必须 clear()？

线程池中的线程是复用的。如果不清理，下一个请求可能继承上一个请求的 traceId，导致链路混乱。`clear()` 调用 `remove()` 彻底清除 TTL 变量。

## 5. 面试话术

### 30 秒版

> 我设计了一套基于 AOP 的全链路追踪系统。通过 `@RagTraceRoot` 标记请求入口，`@RagTraceNode` 标记关键步骤。切面自动用 Snowflake 生成 traceId，通过 ArrayDeque 栈维护父子关系——pushNode 入栈、popNode 出栈——实现任意嵌套深度的树形追踪。traceId 存储在 TransmittableThreadLocal 中，配合 TtlExecutors 包装的线程池，在异步线程中也能正确传播。最终追踪数据落库到两张表：Run 记录整体请求，Node 记录每个步骤的耗时和状态。

### 追问应对

**Q：如何保证追踪不影响业务性能？**
> 首先，追踪有全局开关 `traceProperties.isEnabled()`，关闭后切面直接 `proceed()`。开启时，主要开销是两次数据库插入（startRun/startNode + finishRun/finishNode），使用 MyBatis-Plus 的简单 insert/update，耗时微秒级。相比 RAG 流程中的 LLM 调用（秒级），追踪开销可忽略。如果需要进一步优化，可以把记录改为异步写入。

**Q：跨线程的 parentNodeId 是怎么维持正确的？**
> 关键是 TransmittableThreadLocal + ArrayDeque 栈。当主线程执行到 `@RagTraceNode("retrieval")` 时 pushNode(A)，此时栈内为 [A]。然后通过 TTL 包装的线程池提交子任务，子线程自动继承栈 [A]。子线程中执行 `@RagTraceNode("vector-search")` 时，`currentNodeId()` 返回 A 作为 parentNodeId，然后 pushNode(B)，栈变为 [B, A]。子线程的 pop 不影响主线程的栈（TTL 提供了快照隔离）。

**Q：和 SkyWalking/Zipkin 等 APM 工具有什么区别？**
> APM 工具面向通用的 HTTP/RPC 调用链追踪，粒度是方法级。我们的追踪面向 RAG 业务语义——节点类型标注为 REWRITE/INTENT/RETRIEVE/LLM_PROVIDER 等，可以在管理后台直接看到"哪个检索通道耗时最长"、"意图识别准确率如何"。两者可以互补：APM 做基础设施层监控，自建追踪做业务层可观测。

## 6. 关联亮点

- [亮点5：专用线程池 + TtlExecutors](./05-thread-pool-ttl.md) — TTL 包装是追踪能跨线程的基础
- [亮点1：多路检索引擎](./01-multi-channel-retrieval.md) — 检索通道标注 `@RagTraceNode`
- [亮点3：模型路由与三态熔断](./03-model-routing-circuit-breaker.md) — LLM 调用标注 `@RagTraceNode`
- [亮点2：意图识别与问题重写](./02-intent-recognition.md) — 意图解析和改写都有追踪节点
