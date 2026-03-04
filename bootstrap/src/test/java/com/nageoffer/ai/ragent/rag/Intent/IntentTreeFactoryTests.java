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
    void shouldContainSalesQueryMcpLeafNode() {
        List<IntentNode> roots = IntentTreeFactory.buildIntentTree();
        List<IntentNode> allNodes = flatten(roots);

        Optional<IntentNode> salesNodeOpt = allNodes.stream()
                .filter(node -> "sales_query".equals(node.getMcpToolId()))
                .findFirst();

        assertTrue(salesNodeOpt.isPresent(), "意图树应包含 mcpToolId=sales_query 的节点");

        IntentNode salesNode = salesNodeOpt.get();
        assertEquals(IntentKind.MCP, salesNode.getKind(), "sales_query 节点应为 MCP 类型");
        assertTrue(salesNode.isLeaf(), "sales_query 节点应为叶子节点");
        assertTrue(
                salesNode.getExamples().stream().anyMatch(each -> each.contains("销售")),
                "sales_query 节点应包含销售问句示例"
        );
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
