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

package com.nageoffer.ai.ragent.rag.intent;

import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeFactory;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentTreeFactoryTests {

    @Test
    void shouldNotContainRemovedSalesMcpLeafNodes() {
        List<IntentNode> roots = IntentTreeFactory.buildIntentTree();
        List<IntentNode> allNodes = flatten(roots);

        assertTrue(allNodes.stream().noneMatch(node -> "sales_summary_query".equals(node.getMcpToolId())));
        assertTrue(allNodes.stream().noneMatch(node -> "sales_ranking_query".equals(node.getMcpToolId())));
        assertTrue(allNodes.stream().noneMatch(node -> "sales_detail_query".equals(node.getMcpToolId())));
        assertTrue(allNodes.stream().noneMatch(node -> "sales_trend_query".equals(node.getMcpToolId())));
    }

    @Test
    void shouldContainVideoTranscriptMcpLeafNode() {
        List<IntentNode> roots = IntentTreeFactory.buildIntentTree();
        List<IntentNode> allNodes = flatten(roots);

        Optional<IntentNode> transcriptNodeOpt = allNodes.stream()
                .filter(node -> "obsidian_video_transcript".equals(node.getMcpToolId()))
                .filter(IntentNode::isLeaf)
                .findFirst();

        assertTrue(transcriptNodeOpt.isPresent(), "意图树应包含 mcpToolId=obsidian_video_transcript 的叶子节点");

        IntentNode transcriptNode = transcriptNodeOpt.get();
        assertEquals(IntentKind.MCP, transcriptNode.getKind(), "视频转录节点应为 MCP 类型");
        assertTrue(
                transcriptNode.getExamples().stream().anyMatch(each -> each.contains("转录")),
                "视频转录节点应包含转录问句示例"
        );
    }

    @Test
    void shouldContainSystemRealtimeCategory() {
        List<IntentNode> roots = IntentTreeFactory.buildIntentTree();
        List<IntentNode> allNodes = flatten(roots);

        Optional<IntentNode> realtimeNodeOpt = allNodes.stream()
                .filter(node -> "sys-realtime-query".equals(node.getId()))
                .findFirst();

        assertTrue(realtimeNodeOpt.isPresent(), "意图树应包含 sys-realtime-query 节点");

        IntentNode realtimeNode = realtimeNodeOpt.get();
        assertEquals(IntentKind.SYSTEM, realtimeNode.getKind(), "实时信息节点应为 SYSTEM 类型");
        assertTrue(
                realtimeNode.getExamples().stream().anyMatch(each -> each.contains("今天几号")),
                "实时信息节点应包含日期时间问句示例"
        );
        assertTrue(
                realtimeNode.getExamples().stream().noneMatch(each -> each.contains("联网搜索")),
                "实时信息节点不应包含联网搜索问句，避免覆盖 MCP 联网工具意图"
        );
    }

    @Test
    void shouldContainWebNewsSearchMcpLeafNode() {
        List<IntentNode> roots = IntentTreeFactory.buildIntentTree();
        List<IntentNode> allNodes = flatten(roots);

        Optional<IntentNode> webNewsNodeOpt = allNodes.stream()
                .filter(node -> "web_news_search".equals(node.getMcpToolId()))
                .findFirst();

        assertTrue(webNewsNodeOpt.isPresent(), "意图树应包含 mcpToolId=web_news_search 的节点");

        IntentNode webNewsNode = webNewsNodeOpt.get();
        assertEquals(IntentKind.MCP, webNewsNode.getKind(), "web_news_search 节点应为 MCP 类型");
        assertTrue(
                webNewsNode.getExamples().stream().anyMatch(each -> each.contains("联网")),
                "web_news_search 节点应包含联网问句示例"
        );
    }

    private List<IntentNode> flatten(List<IntentNode> roots) {
        List<IntentNode> result = new ArrayList<>();
        Deque<IntentNode> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            IntentNode node = stack.pop();
            result.add(node);
            List<IntentNode> children = node.getChildren();
            if (children == null || children.isEmpty()) {
                continue;
            }
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        return result;
    }
}
