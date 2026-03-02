# 亮点8：会话停止机制

> AtomicBoolean CAS 幂等取消 + Redis Pub/Sub 跨节点协调 + Guava Cache 本地管理 + 竞态安全绑定，实现流式会话的优雅停止。

## 1. 问题背景

### 为什么需要？

流式对话（SSE）可能持续 30-60 秒。用户随时可能点"停止生成"：

- **底层需要取消**：LLM 正在推理，不取消就继续消耗 GPU 和 token
- **SSE 需要关闭**：前端停止接收后，后端仍在推送会导致连接异常
- **跨节点**：用户请求可能被负载均衡到 A 节点，停止请求到 B 节点

### 不做会怎样？

- LLM 继续推理到结束，浪费资源
- 前端断开后后端报 IOException，日志充满噪音
- 多节点部署时无法可靠停止

## 2. 设计方案

### 架构图

```
用户点击"停止生成"
    │
    ▼
POST /rag/v3/stop?taskId=xxx
    │
    ▼
StreamTaskManager.cancel(taskId)
    │
    ├─ 1. 本地 Cache 查找
    │     ├─ 找到 → CAS 设置 cancelled=true
    │     │        → 调用 handle.cancel() (取消 OkHttp Call)
    │     │        → 调用 onCancel() (发送 CANCEL SSE 事件)
    │     │
    │     └─ 未找到（可能在其他节点）
    │
    ├─ 2. Redis 标记: SET task:cancel:{id} "1" EX 600
    │
    └─ 3. Redis Pub/Sub 发布取消消息
              │
              ▼
         其他节点收到通知
              │
              ▼
         本地 Cache 查找 → 执行取消

─── 注册/绑定流程 ───

StreamTaskManager.register(taskId, sender, onCancelSupplier)
    │
    ▼
Guava Cache 存储 StreamTaskInfo {
    cancelled: AtomicBoolean(false),
    handle: null,           ← 稍后绑定
    sender: SseSender,
    onCancel: Supplier
}

StreamTaskManager.bindHandle(taskId, handle)
    │
    ├─ 设置 handle
    └─ 检查是否已取消 → 如果是，立即 handle.cancel()
                         (处理注册后、绑定前就收到取消的竞态)
```

### 设计模式

- **策略模式**：`StreamCancellationHandle` 接口，不同 HTTP 客户端有不同取消实现
- **观察者模式**：Redis Pub/Sub 通知所有节点
- **工厂模式**：`StreamCancellationHandles.fromOkHttp()` 创建具体句柄

## 3. 核心代码解析

### 3.1 StreamCancellationHandle 接口

```java
// 文件：infra-ai/.../chat/StreamCancellationHandle.java

public interface StreamCancellationHandle {
    void cancel();  // 取消底层流式推理
}
```

### 3.2 OkHttp 取消实现 — CAS 幂等

```java
// 文件：infra-ai/.../chat/StreamCancellationHandles.java

public final class StreamCancellationHandles {
    private static final StreamCancellationHandle NOOP = () -> {};

    public static StreamCancellationHandle noop() { return NOOP; }

    public static StreamCancellationHandle fromOkHttp(Call call, AtomicBoolean cancelled) {
        return new OkHttpCancellationHandle(call, cancelled);
    }

    private record OkHttpCancellationHandle(Call call, AtomicBoolean cancelled)
            implements StreamCancellationHandle {
        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {  // CAS 幂等
                call.cancel();                           // 取消 HTTP 连接
            }
        }
    }
}
```

### 3.3 StreamTaskManager — 本地管理 + 跨节点协调

```java
// 文件：bootstrap/.../rag/service/handler/StreamTaskManager.java

@Service
public class StreamTaskManager {

    // Guava Cache：TTL 30min，最大 10000 条
    private final Cache<String, StreamTaskInfo> localCache = CacheBuilder.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

    /** 注册任务（对话开始时调用） */
    public void register(String taskId, SseSender sender,
                         Supplier<Runnable> onCancelSupplier) {
        localCache.put(taskId, new StreamTaskInfo(
                new AtomicBoolean(false),    // cancelled
                null,                         // handle（稍后绑定）
                sender,
                onCancelSupplier
        ));
    }

    /** 绑定流式句柄（模型开始推理后调用） */
    public void bindHandle(String taskId, StreamCancellationHandle handle) {
        StreamTaskInfo info = localCache.getIfPresent(taskId);
        if (info == null) return;

        info.handle = handle;

        // 关键：检查绑定前是否已收到取消请求
        if (info.cancelled.get()) {
            handle.cancel();                 // 竞态处理：立即取消
        }
    }

    /** 取消任务 */
    public void cancel(String taskId) {
        // 1. 本地取消
        StreamTaskInfo info = localCache.getIfPresent(taskId);
        if (info != null && info.cancelled.compareAndSet(false, true)) {
            if (info.handle != null) {
                info.handle.cancel();        // 取消底层 HTTP 连接
            }
            Runnable onCancel = info.onCancelSupplier.get();
            if (onCancel != null) {
                onCancel.run();              // 发送 CANCEL + DONE SSE 事件
            }
        }

        // 2. Redis 持久化标记（跨节点可查）
        stringRedisTemplate.opsForValue().set(
                "task:cancel:" + taskId, "1",
                600, TimeUnit.SECONDS
        );

        // 3. Pub/Sub 通知其他节点
        stringRedisTemplate.convertAndSend("channel:task:cancel", taskId);
    }

    /** 查询是否已取消 */
    public boolean isCancelled(String taskId) {
        StreamTaskInfo info = localCache.getIfPresent(taskId);
        if (info != null && info.cancelled.get()) return true;

        // 本地没有，查 Redis（可能是其他节点标记的）
        return Boolean.TRUE.equals(
                stringRedisTemplate.hasKey("task:cancel:" + taskId));
    }

    /** 清理（对话结束后调用） */
    public void unregister(String taskId) {
        localCache.invalidate(taskId);
        stringRedisTemplate.delete("task:cancel:" + taskId);
    }
}
```

### 3.4 竞态处理 — bindHandle 后检查

```java
// 核心竞态场景：
// T1: register(taskId)      → cancelled = false, handle = null
// T2: cancel(taskId)        → cancelled = true, handle = null (无法取消)
// T1: bindHandle(taskId, h) → 设置 handle = h
//                            → 检查 cancelled.get() == true
//                            → 立即 h.cancel()

public void bindHandle(String taskId, StreamCancellationHandle handle) {
    StreamTaskInfo info = localCache.getIfPresent(taskId);
    if (info == null) return;
    info.handle = handle;
    if (info.cancelled.get()) {      // ← 关键检查
        handle.cancel();              // 竞态修复：cancel 比 bind 先到
    }
}
```

### 3.5 Redis Pub/Sub 监听（其他节点）

```java
// 其他节点收到取消消息后：
@Override
public void onMessage(Message message, byte[] pattern) {
    String taskId = new String(message.getBody());
    StreamTaskInfo info = localCache.getIfPresent(taskId);
    if (info != null && info.cancelled.compareAndSet(false, true)) {
        if (info.handle != null) {
            info.handle.cancel();
        }
        // 发送 SSE 取消事件
        Runnable onCancel = info.onCancelSupplier.get();
        if (onCancel != null) onCancel.run();
    }
}
```

## 4. 设计意图

### 为什么不直接关闭 SSE 连接来取消？

关闭连接只能停止 SSE 推送，但 LLM 推理仍在继续（OkHttp Call 还在读取流）。必须调用 `Call.cancel()` 才能真正中断底层 HTTP 连接，释放模型资源。

### 为什么用 Guava Cache 而非 ConcurrentHashMap？

- Guava Cache 提供**自动过期**（30min TTL），不用担心内存泄漏
- **maximumSize** 上限保护，极端情况下自动淘汰最旧条目
- ConcurrentHashMap 没有过期机制，如果 unregister 遗漏，条目永远不会被清理

### 为什么 cancel 需要 Redis + Pub/Sub？

| 机制 | 作用 |
|------|------|
| **Redis SET** | 持久化标记，后续 `isCancelled()` 查询 |
| **Pub/Sub** | 实时通知，让持有任务的节点立即取消 |

仅用 SET 无法实时通知（需要轮询）。仅用 Pub/Sub 有丢失风险且不可查。两者配合实现"实时通知 + 可靠查询"。

### 为什么 bindHandle 后要检查 cancelled？

这是一个经典的竞态条件。时间线：
1. `register()` 创建 info，handle=null
2. 用户点"停止"，`cancel()` 设置 cancelled=true，但 handle 还是 null，无法取消底层连接
3. 模型开始推理，`bindHandle()` 设置 handle

如果 bindHandle 不检查 cancelled，步骤 2 的取消就"丢失"了，LLM 继续推理直到结束。加了检查后，bindHandle 发现 cancelled=true，立即取消。

## 5. 面试话术

### 30 秒版

> 我设计了一套流式会话停止机制。每个对话任务注册到 Guava Cache（自动过期 30min），包含 AtomicBoolean 取消标志和 StreamCancellationHandle。用户点"停止"时，先 CAS 设置取消标志，再调用 handle.cancel() 中断底层 OkHttp 连接。多节点场景下，通过 Redis SET 持久化标记 + Pub/Sub 实时通知其他节点。关键的竞态处理是 bindHandle 方法——模型开始推理后绑定 handle 时，会检查 cancelled 标志，如果发现已取消就立即调用 cancel()，防止"取消在绑定之前到达"的竞态导致取消丢失。

### 追问应对

**Q：CAS 幂等的必要性在哪？**
> cancel() 可能被多次调用——用户连续点击停止、超时触发、Pub/Sub 重复通知。`compareAndSet(false, true)` 保证 cancel 逻辑只执行一次：只发一次 CANCEL SSE 事件、只调一次 Call.cancel()。OkHttpCancellationHandle 内部也有 CAS，双重保护。

**Q：Pub/Sub 消息丢了怎么办？**
> Redis Pub/Sub 的语义是"尽力而为"，subscriber 断线期间的消息会丢失。但我们有兜底机制：Redis SET 持久化了取消标记，`isCancelled()` 会查 Redis。在模型推理的关键循环中会周期性调用 `isCancelled()`，即使 Pub/Sub 丢失，轮询也能发现取消标记。

## 6. 关联亮点

- [亮点3：模型路由](./03-model-routing-circuit-breaker.md) — `StreamCancellationHandle` 由 ChatClient 返回
- [亮点4：分布式限流](./04-distributed-rate-limiting.md) — 取消也触发 releaseOnce 释放许可
- [亮点7：全链路追踪](./07-rag-trace-aop.md) — 取消事件记录到 trace
