# 亮点4：分布式队列式限流

> Redis ZSET 排队 + Lua 原子出队 + Semaphore 许可 + SSE 回调绑定 + 三路统一释放，实现全局并发控制与公平排队。

## 1. 问题背景

### 为什么需要？

RAG 对话是 SSE 长连接 + LLM 推理的重资源操作：

- **LLM 推理昂贵**：每个并发占用一个模型 slot，GPU 资源有限
- **SSE 长连接**：一个对话可能持续 30-60 秒，大量并发会耗尽服务器线程
- **公平性**：先到的用户应该先被服务，不能因为后端突发压力导致"插队"

### 不做会怎样？

- 无限并发导致所有用户的响应都变慢（LLM 排队）
- 服务器线程池满，新请求直接 503
- 无排队机制，用户只能反复重试

## 2. 设计方案

### 架构图

```
@ChatRateLimit 注解
    │
    ▼
ChatRateLimitAspect.around()
    │  提取 question, conversationId, emitter
    ▼
ChatQueueLimiter.enqueue(emitter, onAcquire)
    │
    ├─ 禁用? → 直接执行 onAcquire
    │
    ├─ 1. ZSET.add(requestId, timestamp)    ← Redis 入队
    │
    ├─ 2. scheduleQueuePoll()               ← 启动轮询
    │     │
    │     ▼
    │     ┌─── 每 200ms 轮询一次 ───┐
    │     │                          │
    │     │  tryAcquireIfReady()     │
    │     │    │                     │
    │     │    ├─ claimIfReady()     │
    │     │    │  执行 Lua 脚本:     │
    │     │    │  ZRANK < maxRank?   │
    │     │    │  → ZREM (出队)     │
    │     │    │                     │
    │     │    ├─ Semaphore.tryAcquire()
    │     │    │  获取本地许可       │
    │     │    │                     │
    │     │    └─ 成功 → 执行业务    │
    │     │       失败 → 重新入队    │
    │     │                          │
    │     └──────────────────────────┘
    │
    └─ 3. releaseOnce (三路释放)
          ├─ onCompletion (SSE 正常结束)
          ├─ onTimeout    (SSE 超时)
          └─ onError      (SSE 异常)
```

### 设计模式

- **AOP 切面**：`@ChatRateLimit` 注解标记限流入口
- **命令模式**：`onAcquire` 回调封装业务逻辑
- **FIFO 公平队列**：ZSET 按时间戳排序，先到先服务

## 3. 核心代码解析

### 3.1 入队逻辑

```java
// 文件：bootstrap/.../rag/aop/ChatQueueLimiter.java

public void enqueue(SseEmitter emitter, Runnable onAcquire) {
    if (!rateLimitProperties.isEnabled()) {
        onAcquire.run();                    // 禁用时直接执行
        return;
    }

    String requestId = IdUtil.fastSimpleUUID();
    double score = System.currentTimeMillis();  // 时间戳作为排序分数

    // Redis ZSET 入队：按时间戳排序 = FIFO 队列
    stringRedisTemplate.opsForZSet().add(queueKey, requestId, score);

    // 绑定 SSE 生命周期回调
    AtomicBoolean released = new AtomicBoolean(false);
    Runnable releaseOnce = () -> {
        if (released.compareAndSet(false, true)) {  // CAS 保证只释放一次
            semaphore.release();
            stringRedisTemplate.opsForZSet().remove(queueKey, requestId);
        }
    };
    emitter.onCompletion(releaseOnce);      // 正常结束释放
    emitter.onTimeout(releaseOnce);         // 超时释放
    emitter.onError(e -> releaseOnce.run()); // 异常释放

    // 启动排队轮询
    scheduleQueuePoll(requestId, score, emitter, onAcquire, releaseOnce);
}
```

### 3.2 Lua 原子出队脚本

```lua
-- 文件：bootstrap/src/main/resources/lua/queue_claim_atomic.lua

local queueKey = KEYS[1]
local requestId = ARGV[1]
local maxRank = tonumber(ARGV[2])

-- 获取排名（0-based）
local rank = redis.call('ZRANK', queueKey, requestId)

-- 不在队列 或 排名超出许可窗口 → 不出队
if not rank then return {0} end
if rank >= maxRank then return {0} end

-- 保存原始 score（可能需要重入队）
local score = redis.call('ZSCORE', queueKey, requestId)

-- 原子出队
redis.call('ZREM', queueKey, requestId)

return {1, score}
```

### 3.3 尝试获取许可

```java
// 文件：bootstrap/.../rag/aop/ChatQueueLimiter.java

private boolean tryAcquireIfReady(String requestId, double score) {
    // 1. Lua 原子检查排名 + 出队
    ClaimResult claim = claimIfReady(requestId);
    if (!claim.claimed) return false;

    // 2. 本地 Semaphore 获取许可（双重保障）
    boolean acquired = semaphore.tryAcquire(leaseSeconds, TimeUnit.SECONDS);
    if (!acquired) {
        // Semaphore 满，重新入队（用原始 score 保持顺序）
        stringRedisTemplate.opsForZSet().add(queueKey, requestId, score);
        return false;
    }
    return true;
}

private ClaimResult claimIfReady(String requestId) {
    int availablePermits = semaphore.availablePermits();
    if (availablePermits <= 0) return ClaimResult.NOT_CLAIMED;

    // 执行 Lua 脚本
    List<Long> result = stringRedisTemplate.execute(
            claimScript,
            List.of(queueKey),
            requestId,
            String.valueOf(availablePermits)     // maxRank = 可用许可数
    );
    return new ClaimResult(result.get(0) == 1, ...);
}
```

### 3.4 定时轮询

```java
// 文件：bootstrap/.../rag/aop/ChatQueueLimiter.java

private void scheduleQueuePoll(String requestId, double score,
        SseEmitter emitter, Runnable onAcquire, Runnable releaseOnce) {

    long deadline = System.currentTimeMillis()
            + rateLimitProperties.getMaxWaitSeconds() * 1000L;

    scheduledExecutor.scheduleAtFixedRate(() -> {
        // 超时检查
        if (System.currentTimeMillis() > deadline) {
            rejectWithTimeout(emitter, requestId);
            return;
        }

        // 尝试出队 + 获取许可
        if (tryAcquireIfReady(requestId, score)) {
            chatEntryExecutor.execute(onAcquire);   // ← 获取到许可，执行业务
            cancel();                                // 停止轮询
        }
    }, 0, rateLimitProperties.getPollIntervalMs(), TimeUnit.MILLISECONDS);
}
```

### 3.5 三路统一释放 — releaseOnce

```java
// 关键：无论 SSE 如何结束，许可都必须被释放

AtomicBoolean released = new AtomicBoolean(false);
Runnable releaseOnce = () -> {
    if (released.compareAndSet(false, true)) {  // CAS 幂等
        semaphore.release();
        stringRedisTemplate.opsForZSet().remove(queueKey, requestId);
    }
};

emitter.onCompletion(releaseOnce);   // 正常完成
emitter.onTimeout(releaseOnce);      // 超时
emitter.onError(e -> releaseOnce.run()); // 异常
```

## 4. 设计意图

### 为什么用 ZSET 而非 List？

| 方案 | 优点 | 缺点 |
|------|------|------|
| `LPUSH/RPOP` | 简单 | 无法检查排名，无法跳过指定元素 |
| **ZSET** | ZRANK 查排名，ZREM 原子出队 | 稍复杂 |
| Stream | 消费组语义丰富 | 过于复杂 |

ZSET 的 score 是时间戳，天然 FIFO。`ZRANK` 获取排名判断是否在"许可窗口"内，`ZREM` 原子出队。

### 为什么需要 Lua 脚本？

`ZRANK` + `ZREM` 必须原子执行。如果分两步：
1. `ZRANK` 返回 rank=0（在窗口内）
2. 另一个进程也看到 rank=0
3. 两者都 `ZREM`，但只有一个许可

Lua 脚本在 Redis 单线程中执行，保证 ZRANK→ZREM 的原子性。

### 为什么 Semaphore + ZSET 双重保障？

- **ZSET** 提供全局排队和公平性（跨节点）
- **Semaphore** 提供本地并发控制和许可计时（leaseSeconds 自动过期）
- 单独用 ZSET 无法限制并发上限；单独用 Semaphore 无法跨节点排队

### 为什么 releaseOnce 用 CAS？

SSE 的 `onCompletion`、`onTimeout`、`onError` 可能被多次触发（如超时后再触发 error）。`compareAndSet(false, true)` 保证许可只释放一次，避免 Semaphore 泄漏（多 release 导致许可数膨胀）。

## 5. 面试话术

### 30 秒版

> 我实现了一套分布式队列式限流。用户请求先通过 `@ChatRateLimit` AOP 拦截，进入 Redis ZSET 排队（score 是时间戳，保证 FIFO）。后台每 200ms 轮询一次，执行 Lua 脚本原子检查排名——如果排名在可用许可窗口内就 ZREM 出队。出队后还要获取本地 Semaphore（带过期时间的双重保障）。业务执行完成后，通过 CAS 幂等的 releaseOnce 释放许可——绑定在 SSE 的 onCompletion/onTimeout/onError 三个生命周期回调上，保证无论连接如何结束都能正确释放。

### 追问应对

**Q：为什么不用 Redis Semaphore 代替本地 Semaphore？**
> Redis Semaphore 每次获取/释放都需要网络往返，而 200ms 的轮询频率意味着每秒 5 次 Semaphore 操作。本地 Semaphore 无网络开销。ZSET 已经提供了全局排队，Semaphore 只是做本地并发卡控和许可过期兜底（leaseSeconds），两者各司其职。

**Q：轮询 200ms 会不会太频繁？有没有更好的方案？**
> 200ms 是可配置的（`poll-interval-ms`）。确实可以用 Redis Pub/Sub 做事件驱动——有许可释放时发布通知，减少空轮询。但 Pub/Sub 有丢失风险（subscriber 短暂断线），轮询是最可靠的兜底方案。实际上 200ms 的 Redis 轮询开销很低（一次 Lua 执行 < 1ms），在当前规模下完全可以接受。

## 6. 关联亮点

- [亮点5：专用线程池](./05-thread-pool-ttl.md) — 使用 `chatEntryExecutor` 执行业务
- [亮点7：全链路追踪](./07-rag-trace-aop.md) — 限流切面集成 RagTraceContext
- [亮点8：会话停止机制](./08-stream-cancellation.md) — 停止也会触发 releaseOnce
