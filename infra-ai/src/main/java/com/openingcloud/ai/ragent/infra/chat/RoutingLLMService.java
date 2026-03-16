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

package com.openingcloud.ai.ragent.infra.chat;

import cn.hutool.core.collection.CollUtil;
import com.openingcloud.ai.ragent.infra.config.AIModelProperties;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.framework.errorcode.BaseErrorCode;
import com.openingcloud.ai.ragent.framework.exception.RemoteException;
import com.openingcloud.ai.ragent.framework.trace.RagTraceNode;
import com.openingcloud.ai.ragent.infra.enums.ModelCapability;
import com.openingcloud.ai.ragent.infra.model.ModelHealthStore;
import com.openingcloud.ai.ragent.infra.model.ModelRoutingExecutor;
import com.openingcloud.ai.ragent.infra.model.ModelSelector;
import com.openingcloud.ai.ragent.infra.model.ModelTarget;
import com.openingcloud.ai.ragent.infra.token.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式 LLM 服务实现类
 * <p>
 * 该服务负责智能路由和调度大模型请求，主要功能包括：
 * 1. 根据请求特性选择最佳的大模型提供商
 * 2. 支持多模型候选的自动降级和故障转移
 * 3. 维护模型健康状态，优化路由策略
 * 4. 支持同步和流式两种调用方式
 */
@Slf4j
@Service
@Primary
public class RoutingLLMService implements ModelAwareLLMService {

    private static final String STREAM_INTERRUPTED_MESSAGE = "流式请求被中断";
    private static final String STREAM_NO_PROVIDER_MESSAGE = "无可用大模型提供者";
    private static final String STREAM_START_FAILED_MESSAGE = "流式请求启动失败";
    private static final String STREAM_TIMEOUT_MESSAGE = "流式首包超时";
    private static final String STREAM_NO_CONTENT_MESSAGE = "流式请求未返回内容";
    private static final String STREAM_ALL_FAILED_MESSAGE = "大模型调用失败，请稍后再试...";

    private final ModelSelector selector;
    private final ModelHealthStore healthStore;
    private final ModelRoutingExecutor executor;
    private final AIModelProperties aiModelProperties;
    private final Map<String, ChatClient> clientsByProvider;

    public RoutingLLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            AIModelProperties aiModelProperties,
            List<ChatClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.aiModelProperties = aiModelProperties;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request) {
        return chatWithMetadata(request).content();
    }

    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public ChatResultWithMetadata chatWithMetadata(ChatRequest request) {
        ChatResultWithMetadata result = executor.executeWithFallback(
                ModelCapability.CHAT,
                selector.selectChatCandidates(request.getThinking()),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> new ChatResultWithMetadata(
                        client.chat(request, target),
                        toModelMetadata(target),
                        null
                )
        );
        fireTraceIfPresent(request, result);
        return result;
    }

    @Override
    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        // 如果有 trace listener，包装 callback 以在流式完成时触发 trace
        StreamCallback effectiveCallback = wrapWithTracingIfNeeded(request, callback);

        List<ModelTarget> targets = selector.selectChatCandidates(request.getThinking());
        if (CollUtil.isEmpty(targets)) {
            throw new RemoteException(STREAM_NO_PROVIDER_MESSAGE);
        }

        String label = ModelCapability.CHAT.getDisplayName();
        Throwable lastError = null;
        List<String> skipReasons = new ArrayList<>();

        for (ModelTarget target : targets) {
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                skipReasons.add(String.format("%s: client missing (provider=%s)", target.id(), target.candidate().getProvider()));
                continue;
            }

            if (!healthStore.allowCall(target.id())) {
                skipReasons.add(String.format("%s: circuit breaker rejected (provider=%s)", target.id(), target.candidate().getProvider()));
                log.warn("{} 模型熔断器拒绝: modelId={}, provider={}", label, target.id(), target.candidate().getProvider());
                continue;
            }

            FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
            ProbeBufferingCallback wrapper = new ProbeBufferingCallback(effectiveCallback, awaiter);

            StreamCancellationHandle handle;
            try {
                handle = client.streamChat(request, wrapper, target);
            } catch (Exception e) {
                healthStore.markFailure(target.id());
                lastError = e;
                log.warn("{} 流式请求启动失败，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider(), e);
                continue;
            }
            if (handle == null) {
                healthStore.markFailure(target.id());
                lastError = new RemoteException(STREAM_START_FAILED_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 流式请求未返回取消句柄，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider());
                continue;
            }

            FirstPacketAwaiter.Result result = awaitFirstPacket(awaiter, handle, effectiveCallback);

            // 判断结果
            if (result.isSuccess()) {
                effectiveCallback.onModelSelected(toModelMetadata(target));
                wrapper.commit();
                healthStore.markSuccess(target.id());
                return handle;
            }

            // 失败处理
            healthStore.markFailure(target.id());
            handle.cancel();

            lastError = buildLastErrorAndLog(result, target, label);
        }

        // 所有模型都失败了，通知客户端错误
        throw notifyAllFailed(effectiveCallback, lastError, skipReasons);
    }

    private ChatClient resolveClient(ModelTarget target, String label) {
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }

    private FirstPacketAwaiter.Result awaitFirstPacket(FirstPacketAwaiter awaiter,
                                                       StreamCancellationHandle handle,
                                                       StreamCallback callback) {
        try {
            return awaiter.await(aiModelProperties.getStream().getFirstPacketTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.cancel();
            RemoteException interruptedException = new RemoteException(STREAM_INTERRUPTED_MESSAGE, e, BaseErrorCode.REMOTE_ERROR);
            callback.onError(interruptedException);
            throw interruptedException;
        }
    }

    private Throwable buildLastErrorAndLog(FirstPacketAwaiter.Result result, ModelTarget target, String label) {
        switch (result.getType()) {
            case ERROR -> {
                Throwable error = result.getError() != null
                        ? result.getError()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败，切换下一个模型",
                        label, target.id(), target.candidate().getProvider(), error);
                return error;
            }
            case TIMEOUT -> {
                RemoteException timeout = new RemoteException(STREAM_TIMEOUT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求超时，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return timeout;
            }
            case NO_CONTENT -> {
                RemoteException noContent = new RemoteException(STREAM_NO_CONTENT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求无内容完成，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return noContent;
            }
            default -> {
                RemoteException unknown = new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败（未知类型），切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return unknown;
            }
        }
    }

    private RemoteException notifyAllFailed(StreamCallback callback, Throwable lastError, List<String> skipReasons) {
        String diagnosis = skipReasons.isEmpty()
                ? ""
                : " skipped=[" + String.join("; ", skipReasons) + "]";
        if (!diagnosis.isEmpty() || lastError != null) {
            log.error("所有流式模型候选均失败。{}{}", diagnosis,
                    lastError != null ? " lastError=" + lastError.getMessage() : "");
        }
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }

    private ModelInvocationMetadata toModelMetadata(ModelTarget target) {
        if (target == null) {
            return null;
        }
        return new ModelInvocationMetadata(
                target.id(),
                target.candidate() == null ? null : target.candidate().getProvider()
        );
    }

    private void fireTraceIfPresent(ChatRequest request, ChatResultWithMetadata result) {
        ReasoningTraceListener listener = ReasoningTraceContext.getListener();
        if (listener == null) {
            return;
        }
        try {
            listener.onTrace(
                    ReasoningTraceContext.getStepLabel(),
                    request.getMessages(),
                    result.content(),
                    result.usage()
            );
        } catch (Exception e) {
            log.warn("触发 reasoning trace 回调失败", e);
        }
    }

    private StreamCallback wrapWithTracingIfNeeded(ChatRequest request, StreamCallback downstream) {
        ReasoningTraceListener listener = ReasoningTraceContext.getListener();
        if (listener == null) {
            return downstream;
        }
        String stepLabel = ReasoningTraceContext.getStepLabel();
        return new TracingStreamCallback(downstream, listener, stepLabel, request);
    }

    /**
     * 流式 Trace 装饰器：累积完整响应内容和 usage，在 onComplete 时触发 trace 回调
     */
    private static final class TracingStreamCallback implements StreamCallback {

        private final StreamCallback downstream;
        private final ReasoningTraceListener listener;
        private final String stepLabel;
        private final ChatRequest request;
        private final StringBuilder contentBuffer = new StringBuilder();
        private volatile TokenUsage capturedUsage;

        private TracingStreamCallback(StreamCallback downstream, ReasoningTraceListener listener,
                                       String stepLabel, ChatRequest request) {
            this.downstream = downstream;
            this.listener = listener;
            this.stepLabel = stepLabel;
            this.request = request;
        }

        @Override
        public void onContent(String content) {
            if (content != null) {
                contentBuffer.append(content);
            }
            downstream.onContent(content);
        }

        @Override
        public void onThinking(String content) {
            downstream.onThinking(content);
        }

        @Override
        public void onModelSelected(ModelInvocationMetadata model) {
            downstream.onModelSelected(model);
        }

        @Override
        public void onTokenUsage(TokenUsage usage) {
            this.capturedUsage = usage;
            downstream.onTokenUsage(usage);
        }

        @Override
        public void onComplete() {
            try {
                listener.onTrace(stepLabel, request.getMessages(), contentBuffer.toString(), capturedUsage);
            } catch (Exception ignored) {
                // trace 回调不应影响主流程
            }
            downstream.onComplete();
        }

        @Override
        public void onError(Throwable error) {
            downstream.onError(error);
        }
    }

    /**
     * 流式首包探测回调：
     * - 探测阶段先缓存事件，避免失败模型的内容污染下游输出
     * - 首包成功后 commit，按原始顺序回放缓存并转实时转发
     */
    private static final class ProbeBufferingCallback implements StreamCallback {

        private final StreamCallback downstream;
        private final FirstPacketAwaiter awaiter;
        private final Object lock = new Object();
        private final List<BufferedEvent> bufferedEvents = new ArrayList<>();
        private volatile boolean committed;

        private ProbeBufferingCallback(StreamCallback downstream, FirstPacketAwaiter awaiter) {
            this.downstream = downstream;
            this.awaiter = awaiter;
            this.committed = false;
        }

        @Override
        public void onContent(String content) {
            awaiter.markContent();
            bufferOrDispatch(BufferedEvent.content(content));
        }

        @Override
        public void onThinking(String content) {
            awaiter.markContent();
            bufferOrDispatch(BufferedEvent.thinking(content));
        }

        @Override
        public void onComplete() {
            awaiter.markComplete();
            bufferOrDispatch(BufferedEvent.complete());
        }

        @Override
        public void onError(Throwable t) {
            awaiter.markError(t);
            bufferOrDispatch(BufferedEvent.error(t));
        }

        @Override
        public void onTokenUsage(TokenUsage usage) {
            bufferOrDispatch(BufferedEvent.tokenUsage(usage));
        }

        /**
         * 首包探测成功后提交：
         * 1. 原子切换为 committed
         * 2. 按事件顺序回放缓存，保证时序一致
         */
        private void commit() {
            List<BufferedEvent> snapshot;
            synchronized (lock) {
                if (committed) {
                    return;
                }
                committed = true;
                if (bufferedEvents.isEmpty()) {
                    return;
                }
                snapshot = new ArrayList<>(bufferedEvents);
                bufferedEvents.clear();
            }
            for (BufferedEvent event : snapshot) {
                dispatch(event);
            }
        }

        private void bufferOrDispatch(BufferedEvent event) {
            boolean dispatchNow;
            synchronized (lock) {
                dispatchNow = committed;
                if (!dispatchNow) {
                    bufferedEvents.add(event);
                }
            }
            if (dispatchNow) {
                dispatch(event);
            }
        }

        private void dispatch(BufferedEvent event) {
            switch (event.type()) {
                case CONTENT -> downstream.onContent(event.content());
                case THINKING -> downstream.onThinking(event.content());
                case COMPLETE -> downstream.onComplete();
                case TOKEN_USAGE -> downstream.onTokenUsage(event.usage());
                case ERROR -> downstream.onError(event.error() != null
                        ? event.error()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR));
            }
        }

        private record BufferedEvent(EventType type, String content, Throwable error, TokenUsage usage) {

            private static BufferedEvent content(String content) {
                return new BufferedEvent(EventType.CONTENT, content, null, null);
            }

            private static BufferedEvent thinking(String content) {
                return new BufferedEvent(EventType.THINKING, content, null, null);
            }

            private static BufferedEvent complete() {
                return new BufferedEvent(EventType.COMPLETE, null, null, null);
            }

            private static BufferedEvent error(Throwable error) {
                return new BufferedEvent(EventType.ERROR, null, error, null);
            }

            private static BufferedEvent tokenUsage(TokenUsage usage) {
                return new BufferedEvent(EventType.TOKEN_USAGE, null, null, usage);
            }
        }

        private enum EventType {
            CONTENT,
            THINKING,
            COMPLETE,
            ERROR,
            TOKEN_USAGE
        }
    }
}
