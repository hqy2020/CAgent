/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.cancel;

import com.nageoffer.ai.ragent.rag.exception.TaskCancelledException;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 协作式取消令牌
 * <p>
 * 与 {@link com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager} 中的
 * StreamTaskInfo.cancelled 共享同一个 AtomicBoolean 实例，使得用户点击"停止生成"后，
 * 所有持有该 token 的异步阶段（意图识别、检索、MCP 工具执行）能够尽早感知取消信号并退出。
 */
public final class CancellationToken {

    /** 永不取消的空令牌，供测试和非聊天调用方使用 */
    public static final CancellationToken NONE = new CancellationToken(new AtomicBoolean(false));

    private final AtomicBoolean cancelled;

    public CancellationToken(AtomicBoolean cancelled) {
        this.cancelled = cancelled;
    }

    /**
     * 检查是否已被取消（volatile read，~1ns）
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 如果已取消则抛出 {@link TaskCancelledException}
     */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new TaskCancelledException();
        }
    }
}
