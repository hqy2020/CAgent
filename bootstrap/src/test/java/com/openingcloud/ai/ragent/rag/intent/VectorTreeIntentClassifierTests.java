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

package com.openingcloud.ai.ragent.rag.intent;

import com.openingcloud.ai.ragent.rag.core.intent.IntentNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

@Slf4j
@Tag("live")
@SpringBootTest
@ActiveProfiles("live")
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class VectorTreeIntentClassifierTests {

    private final VectorIntentClassifier intentClassifier;

    /**
     * 场景 1：个人知识管理问题
     * 期望：能命中某个知识整理相关节点。
     */
    @Test
    public void classifyKnowledgeWorkflowQuestion() {
        String question = "如何整理每日复盘笔记？";
        runCase(question);
    }

    /**
     * 场景 2：知识工具使用问题
     */
    @Test
    public void classifyToolUsageQuestion() {
        String question = "Obsidian 里怎么建立双向链接？";
        runCase(question);
    }

    /**
     * 场景 3：技术知识问题
     */
    @Test
    public void classifyTechnicalKnowledgeQuestion() {
        String question = "Redis 的持久化机制有哪些？";
        runCase(question);
    }

    /**
     * 场景 4：第二大脑系统能力
     */
    @Test
    public void classifySecondBrainQuestion() {
        String question = "第二大脑系统里可以管理哪些知识内容？";
        runCase(question);
    }

    // ======================== 工具方法 ========================

    private void runCase(String question) {
        // 你可以根据实际情况调这两个参数
        double MIN_SCORE = 0.35; // 低于这个就认为“不太像”，可以不检索
        int TOP_N = 5;           // 最多只看前 5 个候选

        long start = System.nanoTime();
        List<VectorIntentClassifier.NodeScore> allScores = intentClassifier.classifyTargets(question);
        long end = System.nanoTime();

        double totalMs = (end - start) / 1_000_000.0;
        double maxScore = allScores.isEmpty() ? 0.0 : allScores.get(0).score();

        System.out.println("==================================================");
        System.out.println("[TreeIntentClassifier] Question: " + question);
        System.out.println("--------------------------------------------------");
        System.out.println("MaxScore : " + maxScore);
        System.out.println("Need RAG : " + (maxScore >= MIN_SCORE));
        System.out.println("Top " + TOP_N + " targets (score >= " + MIN_SCORE + "):");

        allScores.stream()
                .filter(ns -> ns.score() >= MIN_SCORE)
                .limit(TOP_N)
                .forEach(ns -> {
                    IntentNode n = ns.node();
                    System.out.printf("  - %.4f  |  %s  (id=%s)%n",
                            ns.score(),
                            safeFullPath(n),
                            n.getId());
                });

        if (allScores.stream().noneMatch(ns -> ns.score() >= MIN_SCORE)) {
            System.out.println("  (no target above threshold, 可以考虑不走向量检索或走 fallback)");
        }

        System.out.println("---- Perf ----");
        System.out.println("Total cost: " + totalMs + " ms");
        System.out.println("==================================================\n");
    }

    private String safeFullPath(IntentNode node) {
        if (node == null) return "null";
        return node.getFullPath() != null ? node.getFullPath() : node.getName();
    }
}
