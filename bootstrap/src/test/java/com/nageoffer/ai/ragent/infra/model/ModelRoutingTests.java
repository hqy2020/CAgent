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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.chat.FirstPacketAwaiter;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ModelRoutingTests {

    private ModelHealthStore createStore(int failureThreshold, long openDurationMs) {
        AIModelProperties props = new AIModelProperties();
        AIModelProperties.Selection selection = new AIModelProperties.Selection();
        selection.setFailureThreshold(failureThreshold);
        selection.setOpenDurationMs(openDurationMs);
        selection.setMaxOpenDurationMs(openDurationMs * 16);
        props.setSelection(selection);
        return new ModelHealthStore(props);
    }

    @Test
    void testCircuitBreakerClosedToOpen() {
        ModelHealthStore store = createStore(2, 5000L);
        String modelId = "model-a";

        assertTrue(store.allowCall(modelId));
        assertFalse(store.isOpen(modelId));

        store.markFailure(modelId);
        assertFalse(store.isOpen(modelId));

        store.markFailure(modelId);
        assertTrue(store.isOpen(modelId));
        assertFalse(store.allowCall(modelId));
    }

    @Test
    void testCircuitBreakerOpenToHalfOpenToClosed() throws InterruptedException {
        ModelHealthStore store = createStore(1, 50L);
        String modelId = "model-b";

        store.markFailure(modelId);
        assertTrue(store.isOpen(modelId));

        Thread.sleep(80);

        assertFalse(store.isOpen(modelId));
        assertTrue(store.allowCall(modelId));

        store.markSuccess(modelId);
        assertFalse(store.isOpen(modelId));
        assertTrue(store.allowCall(modelId));
    }

    @Test
    void testCircuitBreakerHalfOpenFailureReOpens() throws InterruptedException {
        ModelHealthStore store = createStore(1, 50L);
        String modelId = "model-c";

        store.markFailure(modelId);
        assertTrue(store.isOpen(modelId));

        Thread.sleep(80);
        assertTrue(store.allowCall(modelId));

        store.markFailure(modelId);
        assertTrue(store.isOpen(modelId));
    }

    @Test
    void testFirstPacketAwaiterSuccess() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        awaiter.markContent();

        FirstPacketAwaiter.Result result = awaiter.await(1, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertEquals(FirstPacketAwaiter.Result.Type.SUCCESS, result.getType());
    }

    @Test
    void testFirstPacketAwaiterTimeout() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();

        FirstPacketAwaiter.Result result = awaiter.await(50, TimeUnit.MILLISECONDS);

        assertEquals(FirstPacketAwaiter.Result.Type.TIMEOUT, result.getType());
        assertFalse(result.isSuccess());
    }

    @Test
    void testFirstPacketAwaiterError() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        RuntimeException ex = new RuntimeException("connection refused");
        awaiter.markError(ex);

        FirstPacketAwaiter.Result result = awaiter.await(1, TimeUnit.SECONDS);

        assertEquals(FirstPacketAwaiter.Result.Type.ERROR, result.getType());
        assertSame(ex, result.getError());
    }

    @Test
    void testFirstPacketAwaiterNoContent() throws InterruptedException {
        FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
        awaiter.markComplete();

        FirstPacketAwaiter.Result result = awaiter.await(1, TimeUnit.SECONDS);

        assertEquals(FirstPacketAwaiter.Result.Type.NO_CONTENT, result.getType());
    }

    @Test
    void testAllowCallReturnsFalseForNull() {
        ModelHealthStore store = createStore(2, 5000L);
        assertFalse(store.allowCall(null));
    }

    // ── A 组: ModelHealthStore null 安全 & 状态补充 ──

    @Test
    void testIsOpenNullReturnsFalseNotNPE() {
        ModelHealthStore store = createStore(2, 5000L);
        assertFalse(store.isOpen(null));
    }

    @Test
    void testMarkSuccessAndMarkFailureNullSafe() {
        ModelHealthStore store = createStore(2, 5000L);
        assertDoesNotThrow(() -> store.markSuccess(null));
        assertDoesNotThrow(() -> store.markFailure(null));
    }

    @Test
    void testHalfOpenStateIsOpenReturnsFalse() throws InterruptedException {
        ModelHealthStore store = createStore(1, 50L);
        String modelId = "model-halfopen";
        store.markFailure(modelId);
        assertTrue(store.isOpen(modelId));

        Thread.sleep(80);
        // OPEN 已过期，isOpen 应返回 false（此时状态尚未被 allowCall 推入 HALF_OPEN，但 isOpen 不再为 true）
        assertFalse(store.isOpen(modelId));
    }

    @Test
    void testHalfOpenInflightBlocksSecondCall() throws InterruptedException {
        ModelHealthStore store = createStore(1, 50L);
        String modelId = "model-inflight";
        store.markFailure(modelId);
        Thread.sleep(80);

        // 第一次 allowCall 进入 HALF_OPEN 并设置 inflight
        assertTrue(store.allowCall(modelId));
        // 第二次应被阻止
        assertFalse(store.allowCall(modelId));
    }

    @Test
    void testMarkSuccessResetState() {
        ModelHealthStore store = createStore(2, 5000L);
        String modelId = "model-d";

        store.markFailure(modelId);
        store.markSuccess(modelId);

        store.markFailure(modelId);
        assertFalse(store.isOpen(modelId));
    }

    @Test
    void testExponentialBackoffOnRepeatedHalfOpenFailures() throws InterruptedException {
        // base=50ms, max=800ms (50*16)
        ModelHealthStore store = createStore(1, 50L);
        String modelId = "model-backoff";

        // 第1次: CLOSED -> OPEN (50ms)
        store.markFailure(modelId);
        assertTrue(store.isOpen(modelId));

        // 等待 OPEN 到期 -> HALF_OPEN
        Thread.sleep(80);
        assertTrue(store.allowCall(modelId));

        // HALF_OPEN 失败 -> OPEN，退避 50ms (base * 2^0)
        store.markFailure(modelId);
        assertTrue(store.isOpen(modelId));

        // 等待第1次退避到期
        Thread.sleep(80);
        assertTrue(store.allowCall(modelId));

        // HALF_OPEN 再次失败 -> OPEN，退避 100ms (base * 2^1)
        store.markFailure(modelId);
        assertTrue(store.isOpen(modelId));

        // 50ms 后仍应 OPEN（因为退避是100ms）
        Thread.sleep(60);
        assertFalse(store.allowCall(modelId));

        // 再等 60ms 应该到期
        Thread.sleep(60);
        assertTrue(store.allowCall(modelId));

        // HALF_OPEN 成功 -> 退避重置
        store.markSuccess(modelId);
        assertFalse(store.isOpen(modelId));
        assertTrue(store.allowCall(modelId));
    }

    // ── B 组: ModelSelector 全覆盖 ──

    private AIModelProperties.ModelCandidate candidate(String id, String provider, int priority) {
        return candidate(id, provider, priority, true, false);
    }

    private AIModelProperties.ModelCandidate candidate(String id, String provider, int priority,
                                                        boolean enabled, boolean supportsThinking) {
        AIModelProperties.ModelCandidate c = new AIModelProperties.ModelCandidate();
        c.setId(id);
        c.setProvider(provider);
        c.setModel(id);
        c.setPriority(priority);
        c.setEnabled(enabled);
        c.setSupportsThinking(supportsThinking);
        return c;
    }

    private ModelSelector createSelector(AIModelProperties props) {
        return new ModelSelector(props);
    }

    private AIModelProperties buildProps(String defaultModel, String deepThinkingModel,
                                          List<AIModelProperties.ModelCandidate> candidates,
                                          Map<String, AIModelProperties.ProviderConfig> providers) {
        AIModelProperties props = new AIModelProperties();
        AIModelProperties.ModelGroup chat = new AIModelProperties.ModelGroup();
        chat.setDefaultModel(defaultModel);
        chat.setDeepThinkingModel(deepThinkingModel);
        chat.setCandidates(candidates);
        props.setChat(chat);
        props.setProviders(providers);
        props.setSelection(new AIModelProperties.Selection());
        return props;
    }

    private Map<String, AIModelProperties.ProviderConfig> defaultProviders(String... names) {
        Map<String, AIModelProperties.ProviderConfig> map = new HashMap<>();
        for (String name : names) {
            AIModelProperties.ProviderConfig pc = new AIModelProperties.ProviderConfig();
            pc.setUrl("http://localhost");
            map.put(name, pc);
        }
        return map;
    }

    @Test
    void testSelectorSortsByPriority() {
        List<AIModelProperties.ModelCandidate> candidates = new ArrayList<>(List.of(
                candidate("low", "p1", 100),
                candidate("high", "p1", 1),
                candidate("mid", "p1", 50)
        ));
        AIModelProperties props = buildProps(null, null, candidates, defaultProviders("p1"));
        ModelSelector selector = createSelector(props);

        List<ModelTarget> result = selector.selectChatCandidates(false);
        assertEquals(3, result.size());
        assertEquals("high", result.get(0).id());
        assertEquals("mid", result.get(1).id());
        assertEquals("low", result.get(2).id());
    }

    @Test
    void testSelectorPromotesDefaultModel() {
        List<AIModelProperties.ModelCandidate> candidates = new ArrayList<>(List.of(
                candidate("m1", "p1", 1),
                candidate("m2", "p1", 50),
                candidate("m3", "p1", 100)
        ));
        AIModelProperties props = buildProps("m3", null, candidates, defaultProviders("p1"));
        ModelSelector selector = createSelector(props);

        List<ModelTarget> result = selector.selectChatCandidates(false);
        assertEquals("m3", result.get(0).id());
    }

    @Test
    void testSelectorFiltersDisabledCandidates() {
        List<AIModelProperties.ModelCandidate> candidates = new ArrayList<>(List.of(
                candidate("m1", "p1", 1, false, false),
                candidate("m2", "p1", 2)
        ));
        AIModelProperties props = buildProps(null, null, candidates, defaultProviders("p1"));
        ModelSelector selector = createSelector(props);

        List<ModelTarget> result = selector.selectChatCandidates(false);
        assertEquals(1, result.size());
        assertEquals("m2", result.get(0).id());
    }

    @Test
    void testSelectorDeepThinkingOnlyReturnsSupportsThinking() {
        List<AIModelProperties.ModelCandidate> candidates = new ArrayList<>(List.of(
                candidate("normal", "p1", 1, true, false),
                candidate("thinker", "p1", 2, true, true)
        ));
        AIModelProperties props = buildProps(null, "thinker", candidates, defaultProviders("p1"));
        ModelSelector selector = createSelector(props);

        List<ModelTarget> result = selector.selectChatCandidates(true);
        assertEquals(1, result.size());
        assertEquals("thinker", result.get(0).id());
    }

    @Test
    void testSelectorDefaultModelDisabledDoesNotNPE() {
        List<AIModelProperties.ModelCandidate> candidates = new ArrayList<>(List.of(
                candidate("m1", "p1", 1, false, false),
                candidate("m2", "p1", 2)
        ));
        AIModelProperties props = buildProps("m1", null, candidates, defaultProviders("p1"));
        ModelSelector selector = createSelector(props);

        List<ModelTarget> result = selector.selectChatCandidates(false);
        assertEquals(1, result.size());
        assertEquals("m2", result.get(0).id());
    }

    @Test
    void testSelectorDeepThinkingModelDisabledDoesNotNPE() {
        List<AIModelProperties.ModelCandidate> candidates = new ArrayList<>(List.of(
                candidate("thinker", "p1", 1, false, true),
                candidate("normal", "p1", 2, true, true)
        ));
        AIModelProperties props = buildProps(null, "thinker", candidates, defaultProviders("p1"));
        ModelSelector selector = createSelector(props);

        List<ModelTarget> result = selector.selectChatCandidates(true);
        assertEquals(1, result.size());
        assertEquals("normal", result.get(0).id());
    }

    @Test
    void testSelectorAllDisabledReturnsEmptyList() {
        List<AIModelProperties.ModelCandidate> candidates = new ArrayList<>(List.of(
                candidate("m1", "p1", 1, false, false),
                candidate("m2", "p1", 2, false, false)
        ));
        AIModelProperties props = buildProps(null, null, candidates, defaultProviders("p1"));
        ModelSelector selector = createSelector(props);

        List<ModelTarget> result = selector.selectChatCandidates(false);
        assertTrue(result.isEmpty());
    }

    @Test
    void testSelectorProviderMissingFiltersCandidates() {
        List<AIModelProperties.ModelCandidate> candidates = new ArrayList<>(List.of(
                candidate("m1", "missing-provider", 1),
                candidate("m2", "p1", 2)
        ));
        // 只提供 p1，不提供 missing-provider
        AIModelProperties props = buildProps(null, null, candidates, defaultProviders("p1"));
        ModelSelector selector = createSelector(props);

        List<ModelTarget> result = selector.selectChatCandidates(false);
        assertEquals(1, result.size());
        assertEquals("m2", result.get(0).id());
    }

    // ── C 组: ModelRoutingExecutor 测试 ──

    private ModelTarget target(String id, String provider) {
        AIModelProperties.ModelCandidate c = candidate(id, provider, 1);
        AIModelProperties.ProviderConfig pc = new AIModelProperties.ProviderConfig();
        pc.setUrl("http://localhost");
        return new ModelTarget(id, c, pc);
    }

    @Test
    void testExecutorFirstCandidateSucceeds() {
        ModelHealthStore store = createStore(2, 5000L);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(store);

        List<ModelTarget> targets = List.of(target("m1", "p1"), target("m2", "p1"));
        String result = executor.executeWithFallback(
                ModelCapability.CHAT,
                targets,
                t -> "fake-client",
                (client, t) -> "response-from-" + t.id()
        );

        assertEquals("response-from-m1", result);
    }

    @Test
    void testExecutorFallbackToSecondOnFailure() {
        ModelHealthStore store = createStore(2, 5000L);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(store);

        List<ModelTarget> targets = List.of(target("m1", "p1"), target("m2", "p1"));
        String result = executor.executeWithFallback(
                ModelCapability.CHAT,
                targets,
                t -> "fake-client",
                (client, t) -> {
                    if ("m1".equals(t.id())) {
                        throw new RuntimeException("m1 failed");
                    }
                    return "response-from-" + t.id();
                }
        );

        assertEquals("response-from-m2", result);
    }

    @Test
    void testExecutorAllFailedThrowsRemoteException() {
        ModelHealthStore store = createStore(2, 5000L);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(store);

        List<ModelTarget> targets = List.of(target("m1", "p1"));
        RemoteException ex = assertThrows(RemoteException.class, () ->
                executor.executeWithFallback(
                        ModelCapability.CHAT,
                        targets,
                        t -> "fake-client",
                        (client, t) -> {
                            throw new RuntimeException("boom");
                        }
                )
        );
        assertTrue(ex.getMessage().contains("boom"));
    }

    @Test
    void testExecutorClientMissingSkipsCandidateWithDiagnosis() {
        ModelHealthStore store = createStore(2, 5000L);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(store);

        List<ModelTarget> targets = List.of(target("m1", "p1"), target("m2", "p1"));
        // m1 返回 null client，m2 正常
        String result = executor.executeWithFallback(
                ModelCapability.CHAT,
                targets,
                t -> "m1".equals(t.id()) ? null : "fake-client",
                (client, t) -> "response-from-" + t.id()
        );

        assertEquals("response-from-m2", result);
    }

    @Test
    void testExecutorEmptyTargetsThrowsException() {
        ModelHealthStore store = createStore(2, 5000L);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(store);

        assertThrows(RemoteException.class, () ->
                executor.executeWithFallback(
                        ModelCapability.CHAT,
                        List.of(),
                        t -> "fake-client",
                        (client, t) -> "response"
                )
        );
    }

    // ── E 组: 端到端场景测试 ──

    @Test
    void testHalfOpenInflightModelGracefullySkipped() throws InterruptedException {
        ModelHealthStore store = createStore(1, 50L);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(store);

        // 让 m1 进入 HALF_OPEN+inflight 状态
        store.markFailure("m1");  // OPEN
        Thread.sleep(80);         // 等待 OPEN 过期
        store.allowCall("m1");    // 进入 HALF_OPEN，设置 inflight=true

        // m1 此时 HALF_OPEN+inflight，allowCall 应返回 false
        // m2 正常可用
        List<ModelTarget> targets = List.of(target("m1", "p1"), target("m2", "p1"));
        String result = executor.executeWithFallback(
                ModelCapability.CHAT,
                targets,
                t -> "fake-client",
                (client, t) -> "response-from-" + t.id()
        );

        assertEquals("response-from-m2", result);
    }

    @Test
    void testAllCandidatesSkippedErrorContainsDiagnosis() {
        ModelHealthStore store = createStore(1, 5000L);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(store);

        // 让 m1 进入 OPEN 状态
        store.markFailure("m1");

        List<ModelTarget> targets = List.of(target("m1", "p1"));
        // m1 client 返回 null（模拟 client 缺失）
        RemoteException ex = assertThrows(RemoteException.class, () ->
                executor.executeWithFallback(
                        ModelCapability.CHAT,
                        targets,
                        t -> null,
                        (client, t) -> "response"
                )
        );
        // 错误信息应包含 skipped 诊断
        assertTrue(ex.getMessage().contains("skipped="), "错误信息应包含 skipped 诊断: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("client missing"), "错误信息应包含 client missing: " + ex.getMessage());
    }
}
