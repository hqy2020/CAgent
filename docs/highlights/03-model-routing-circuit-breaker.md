# 亮点3：模型路由与三态熔断

> 多模型候选 + 三态熔断器（CLOSED→OPEN→HALF_OPEN）+ 流式首包探测 + 缓冲回放，实现 LLM 调用的自动降级与零脏数据输出。

## 1. 问题背景

### 为什么需要？

企业级 RAG 系统依赖外部 LLM 服务（Ollama、百炼、硅基流动、MiniMax），这些服务：

- **不稳定**：可能因限流、网络波动、模型过载而返回错误或超时
- **流式特殊性**：流式请求一旦开始推送，前端已收到部分内容；如果中途失败，用户看到"半截回答"
- **无法预知**：不知道哪个模型当前可用，需要运行时动态判断

### 不做会怎样？

- 单一模型挂掉，整个系统不可用
- 流式输出中途失败，前端展示残缺内容（脏数据）
- 失败模型被反复调用，浪费时间和 token

## 2. 设计方案

### 架构图

```
ChatRequest
    │
    ▼
ModelSelector.selectChatCandidates()
    │  按优先级排序 + 过滤熔断模型
    ▼
┌─────────── RoutingLLMService.streamChat() ───────────┐
│                                                       │
│  for (ModelTarget target : candidates) {              │
│      ┌───────────────────────────────────┐            │
│      │ 1. healthStore.allowCall(id)      │            │
│      │    ├─ CLOSED → 放行               │            │
│      │    ├─ OPEN → 检查超时 → HALF_OPEN │            │
│      │    └─ HALF_OPEN → CAS 放行一次    │            │
│      ├───────────────────────────────────┤            │
│      │ 2. 创建 FirstPacketAwaiter        │            │
│      │ 3. 包装 ProbeBufferingCallback    │ ← 缓冲区  │
│      │ 4. chatClient.streamChat()        │            │
│      │ 5. awaiter.await(60s)             │            │
│      ├───────────────────────────────────┤            │
│      │ SUCCESS → commit() 回放缓冲       │            │
│      │ ERROR/TIMEOUT → markFailure()     │            │
│      │    → cancel + 尝试下一个模型       │            │
│      └───────────────────────────────────┘            │
│  }                                                    │
└───────────────────────────────────────────────────────┘
```

### 熔断器状态机

```
         失败次数 ≥ threshold
CLOSED ────────────────────────► OPEN
  ▲                                │
  │ 探测成功                       │ openDurationMs 过期
  │                                ▼
  └─────────────── HALF_OPEN ◄─────┘
                       │
                       │ 探测失败
                       ▼
                     OPEN (重新熔断)
```

### 设计模式

- **策略模式**：`ChatClient` 接口，4 种 Provider 实现
- **状态模式**：三态熔断器 CLOSED/OPEN/HALF_OPEN
- **代理模式**：`ProbeBufferingCallback` 代理原始回调

## 3. 核心代码解析

### 3.1 三态熔断器 — ModelHealthStore

```java
// 文件：infra-ai/.../model/ModelHealthStore.java

public class ModelHealthStore {

    // 内部状态类
    private static class ModelHealth {
        int consecutiveFailures;     // 连续失败计数
        long openUntil;              // 熔断恢复时间戳
        boolean halfOpenInFlight;    // 半开状态是否有探测请求
        HealthState state;           // CLOSED / OPEN / HALF_OPEN
    }

    public boolean allowCall(String id) {
        ModelHealth health = store.get(id);
        if (health == null) return true;           // 无记录 = 健康

        switch (health.state) {
            case CLOSED:
                return true;                        // 正常放行
            case OPEN:
                if (System.currentTimeMillis() >= health.openUntil) {
                    health.state = HALF_OPEN;       // 熔断时间到期 → 半开
                    health.halfOpenInFlight = true;  // CAS 放行一次探测
                    return true;
                }
                return false;                       // 仍在熔断期
            case HALF_OPEN:
                return !health.halfOpenInFlight;    // 只允许一次探测
        }
    }

    public void markSuccess(String id) {
        ModelHealth health = store.get(id);
        health.consecutiveFailures = 0;
        health.state = CLOSED;                      // 恢复健康
    }

    public void markFailure(String id) {
        ModelHealth health = store.computeIfAbsent(id, k -> new ModelHealth());
        health.consecutiveFailures++;
        if (health.consecutiveFailures >= failureThreshold) {
            health.state = OPEN;
            health.openUntil = System.currentTimeMillis() + openDurationMs;
        }
    }
}
```

### 3.2 流式首包探测 — FirstPacketAwaiter

```java
// 文件：infra-ai/.../chat/FirstPacketAwaiter.java

public class FirstPacketAwaiter {
    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean hasContent = new AtomicBoolean(false);
    private volatile Throwable error;

    public enum Result { SUCCESS, ERROR, TIMEOUT, NO_CONTENT }

    public void markContent() {
        hasContent.set(true);
        latch.countDown();          // 首包到达，立即释放等待
    }

    public void markError(Throwable t) {
        this.error = t;
        latch.countDown();          // 错误也释放，让调用方快速感知
    }

    public void markComplete() {
        latch.countDown();          // 流结束（可能无内容）
    }

    public Result await(long timeout, TimeUnit unit) throws InterruptedException {
        boolean reached = latch.await(timeout, unit);
        if (!reached)      return Result.TIMEOUT;
        if (error != null) return Result.ERROR;
        if (hasContent.get()) return Result.SUCCESS;
        return Result.NO_CONTENT;                   // 完成但空内容
    }
}
```

### 3.3 缓冲回放 — ProbeBufferingCallback

```java
// 文件：infra-ai/.../chat/RoutingLLMService.java（内部类）

private static class ProbeBufferingCallback implements StreamCallback {
    private final StreamCallback delegate;           // 原始回调（真正写 SSE）
    private final FirstPacketAwaiter awaiter;
    private final List<BufferedEvent> buffer = new ArrayList<>();
    private volatile boolean committed = false;

    @Override
    public void onContent(String content) {
        awaiter.markContent();                       // 通知首包到达
        if (committed) {
            delegate.onContent(content);             // 已提交，直接透传
        } else {
            buffer.add(new BufferedEvent(Type.CONTENT, content));  // 未提交，缓冲
        }
    }

    // 首包探测成功后调用
    public void commit() {
        committed = true;
        for (BufferedEvent event : buffer) {         // 回放所有缓冲事件
            switch (event.type) {
                case CONTENT  -> delegate.onContent(event.data);
                case THINKING -> delegate.onThinking(event.data);
            }
        }
        buffer.clear();
    }

    // 首包探测失败，丢弃所有缓冲
    public void cancel() {
        buffer.clear();                              // 关键：零脏数据
    }
}
```

### 3.4 路由主流程 — streamChat

```java
// 文件：infra-ai/.../chat/RoutingLLMService.java

public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
    List<ModelTarget> candidates = modelSelector.selectChatCandidates(request.getThinking());

    for (ModelTarget target : candidates) {
        if (!healthStore.allowCall(target.id())) continue;  // 熔断中，跳过

        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        ProbeBufferingCallback probe = new ProbeBufferingCallback(callback, awaiter);

        StreamCancellationHandle handle = chatClient.streamChat(request, target, probe);

        Result result = awaiter.await(60, TimeUnit.SECONDS);

        switch (result) {
            case SUCCESS:
                probe.commit();                     // ← 回放缓冲，开始正式输出
                healthStore.markSuccess(target.id());
                return handle;
            case ERROR, TIMEOUT, NO_CONTENT:
                probe.cancel();                     // ← 丢弃缓冲，零脏数据
                handle.cancel();
                healthStore.markFailure(target.id());
                continue;                           // ← 尝试下一个候选
        }
    }
    callback.onError(new RemoteException("所有模型均不可用"));
    return StreamCancellationHandles.noop();
}
```

### 3.5 同步模式降级 — ModelRoutingExecutor

```java
// 文件：infra-ai/.../model/ModelRoutingExecutor.java

public <C, T> T executeWithFallback(
        List<ModelTarget> candidates,
        Function<String, C> clientResolver,
        ModelCaller<C, T> caller) {

    Throwable lastError = null;
    for (ModelTarget target : candidates) {
        if (!healthStore.allowCall(target.id())) continue;
        try {
            C client = clientResolver.apply(target.provider());
            T result = caller.call(client, target);
            healthStore.markSuccess(target.id());
            return result;                          // 成功即返回
        } catch (Exception e) {
            healthStore.markFailure(target.id());
            lastError = e;                          // 记录最后错误，继续
        }
    }
    throw new RemoteException("所有候选模型均失败", lastError);
}
```

## 4. 设计意图

### 为什么不直接重试而是用熔断器？

| 方案 | 优点 | 缺点 |
|------|------|------|
| 固定重试 3 次 | 简单 | 模型持续不可用时浪费时间和 token |
| **三态熔断**（本方案） | 快速跳过故障模型，自动恢复 | 实现稍复杂 |

熔断器的核心价值是**快速失败 + 自动恢复**。连续失败 N 次后直接跳过（OPEN），等待 `openDurationMs` 后放行一次探测（HALF_OPEN），成功则恢复（CLOSED）。

### 为什么需要 ProbeBufferingCallback？

流式输出的特殊性：一旦 `delegate.onContent()` 被调用，内容就推送到前端了。如果模型在第 3 个 token 后崩溃，用户看到"半截话"——这就是脏数据。

`ProbeBufferingCallback` 的作用是**先缓冲、后回放**：
1. 首包到达前，所有事件存入 buffer
2. 首包确认成功后，commit() 一次性回放
3. 如果探测失败，cancel() 丢弃 buffer，前端零感知

### 为什么首包超时设为 60 秒？

大模型（尤其是深度思考模式）的首 token 延迟可能很长。60 秒足以覆盖绝大多数场景，同时不会让用户等待太久。

## 5. 面试话术

### 30 秒版

> 我实现了一套模型路由系统，支持多个 LLM 提供商的自动降级。核心是三态熔断器——连续失败达到阈值后熔断，超时后进入半开状态，探测成功即恢复。流式场景的难点是避免脏数据输出，我设计了 ProbeBufferingCallback：首包到达前所有输出缓冲在内存中，确认首包成功后才 commit 回放给前端；如果失败则 cancel 丢弃，切换到下一个模型，用户完全无感知。

### 追问应对

**Q：熔断器的参数怎么配的？**
> `failureThreshold` 默认 2 次，`openDurationMs` 默认 30 秒。阈值设为 2 是因为大模型调用本身就慢，一次失败可能是偶发，两次基本确认有问题。30 秒的熔断窗口让服务有足够恢复时间。这些参数都在 `application.yaml` 的 `ai.selection.*` 下可配置。

**Q：ProbeBufferingCallback 的 buffer 会不会内存溢出？**
> 不会。首先，缓冲的是 token 级增量文本（通常几个字），不是完整响应。其次，awaiter 的 60 秒超时保证了 buffer 的生命周期有限。最后，cancel() 会立即 clear() buffer。即使极端情况（模型快速输出大量 token 后崩溃），单个 buffer 的内存也在 KB 级别。

**Q：半开状态为什么只放行一次探测？**
> 如果一次性放行多个请求到故障模型，这些请求很可能全部失败，浪费资源。半开状态只允许一个"探针"请求通过——如果成功，说明模型恢复了，可以全部放行（回到 CLOSED）；如果失败，立即重新熔断（回到 OPEN），避免雪崩。

## 6. 关联亮点

- [亮点5：专用线程池](./05-thread-pool-ttl.md) — 使用 `modelStreamExecutor` 处理流式输出
- [亮点8：会话停止机制](./08-stream-cancellation.md) — `StreamCancellationHandle` 配合熔断取消
- [亮点7：全链路追踪](./07-rag-trace-aop.md) — ChatClient 标注 `@RagTraceNode`
- [亮点2：意图识别](./02-intent-recognition.md) — 意图分类也使用 RoutingLLMService
