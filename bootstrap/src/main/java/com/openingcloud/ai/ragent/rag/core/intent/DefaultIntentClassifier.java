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

package com.openingcloud.ai.ragent.rag.core.intent;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.openingcloud.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.infra.chat.LLMService;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.openingcloud.ai.ragent.rag.constant.RAGConstant.INTENT_CLASSIFIER_PROMPT_PATH;

/**
 * LLM 树形意图分类器（串行实现）
 * <p>
 * 将所有意图节点一次性发送给 LLM 进行识别打分，适用于意图数量较少的场景
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultIntentClassifier implements IntentClassifier, IntentNodeRegistry {

    private final LLMService llmService;
    private final IntentNodeMapper intentNodeMapper;
    private final PromptTemplateLoader promptTemplateLoader;
    private final IntentTreeCacheManager intentTreeCacheManager;
    private final MCPToolRegistry mcpToolRegistry;

    private volatile IntentTreeData cachedTreeData;
    private volatile long cachedTreeDataTimestamp;
    private static final long LOCAL_CACHE_TTL_MS = 5_000;

    @PostConstruct
    public void init() {
        // 初始化时确保Redis缓存存在
        ensureIntentTreeCached();
        log.info("意图分类器初始化完成");
    }

    /**
     * 确保Redis缓存中有意图树数据
     * 如果缓存不存在，从数据库加载并保存到Redis
     */
    private void ensureIntentTreeCached() {
        if (!intentTreeCacheManager.isCacheExists()) {
            List<IntentNode> roots = loadIntentTreeFromDB();
            if (!roots.isEmpty()) {
                intentTreeCacheManager.saveIntentTreeToCache(roots);
                log.info("意图树已从数据库加载并缓存到Redis");
            }
        }
    }

    /**
     * 从Redis加载意图树并构建内存结构
     * 每次调用都会重新从Redis读取，确保数据是最新的
     */
    private IntentTreeData loadIntentTreeData() {
        // 检查本地缓存是否命中
        IntentTreeData cached = cachedTreeData;
        if (cached != null && (System.currentTimeMillis() - cachedTreeDataTimestamp) < LOCAL_CACHE_TTL_MS) {
            return cached;
        }

        // 1. 从Redis读取（如果不存在会自动从数据库加载）
        List<IntentNode> roots = intentTreeCacheManager.getIntentTreeFromCache();

        // 2. 如果Redis也没有，从数据库加载并缓存
        if (CollUtil.isEmpty(roots)) {
            roots = loadIntentTreeFromDB();
            if (!roots.isEmpty()) {
                intentTreeCacheManager.saveIntentTreeToCache(roots);
            }
        }

        // 3. 构建内存结构（临时使用）
        if (CollUtil.isEmpty(roots)) {
            return new IntentTreeData(List.of(), List.of(), Map.of());
        }

        roots = pruneUnavailableMcpNodes(roots);

        List<IntentNode> allNodes = flatten(roots);
        List<IntentNode> leafNodes = allNodes.stream()
                .filter(IntentNode::isLeaf)
                .collect(Collectors.toList());
        Map<String, IntentNode> id2Node = allNodes.stream()
                .collect(Collectors.toMap(IntentNode::getId, n -> n));

        log.debug("意图树数据加载完成, 总节点数: {}, 叶子节点数: {}", allNodes.size(), leafNodes.size());

        IntentTreeData result = new IntentTreeData(allNodes, leafNodes, id2Node);
        cachedTreeData = result;
        cachedTreeDataTimestamp = System.currentTimeMillis();
        return result;
    }

    private List<IntentNode> pruneUnavailableMcpNodes(List<IntentNode> roots) {
        if (CollUtil.isEmpty(roots)) {
            return List.of();
        }
        List<IntentNode> result = new ArrayList<>();
        for (IntentNode root : roots) {
            IntentNode pruned = pruneUnavailableMcpNode(root);
            if (pruned != null) {
                result.add(pruned);
            }
        }
        return result;
    }

    private IntentNode pruneUnavailableMcpNode(IntentNode node) {
        if (node == null) {
            return null;
        }
        List<IntentNode> children = node.getChildren();
        if (CollUtil.isNotEmpty(children)) {
            List<IntentNode> nextChildren = new ArrayList<>();
            for (IntentNode child : children) {
                IntentNode prunedChild = pruneUnavailableMcpNode(child);
                if (prunedChild != null) {
                    nextChildren.add(prunedChild);
                }
            }
            node.setChildren(nextChildren);
        }
        if (!node.isMCP()) {
            return node;
        }
        if (StrUtil.isNotBlank(node.getMcpToolId())) {
            return mcpToolRegistry.contains(node.getMcpToolId()) ? node : null;
        }
        return CollUtil.isNotEmpty(node.getChildren()) ? node : null;
    }

    @Override
    public IntentNode getNodeById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        IntentTreeData data = loadIntentTreeData();
        return data.id2Node.get(id);
    }

    /**
     * 意图树数据结构（临时对象，不持久化）
     */
    private record IntentTreeData(
            List<IntentNode> allNodes,
            List<IntentNode> leafNodes,
            Map<String, IntentNode> id2Node
    ) {
    }

    private List<IntentNode> flatten(List<IntentNode> roots) {
        List<IntentNode> result = new ArrayList<>();
        Deque<IntentNode> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            IntentNode n = stack.pop();
            result.add(n);
            if (n.getChildren() != null) {
                for (IntentNode child : n.getChildren()) {
                    stack.push(child);
                }
            }
        }
        return result;
    }

    /**
     * 对所有"叶子分类节点"做意图识别，由 LLM 输出每个分类的 score
     * - 返回结果已按 score 从高到低排序
     */
    @Override
    public List<NodeScore> classifyTargets(String question) {
        // 每次都从Redis读取最新数据
        IntentTreeData data = loadIntentTreeData();

        String systemPrompt = buildPrompt(data.leafNodes);
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(systemPrompt),
                        ChatMessage.user(question)
                ))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();

        String raw = llmService.chat(request);
        IntentClassificationResponseParser.ParseResult parseResult =
                IntentClassificationResponseParser.parse(raw, data.id2Node);

        if (parseResult.mode() == IntentClassificationResponseParser.ParseMode.SALVAGED) {
            log.warn(
                    "意图分类响应已部分恢复, recoveredCount={}, reason={}, raw={}",
                    parseResult.scores().size(),
                    parseResult.errorSummary(),
                    parseResult.rawSummary()
            );
        } else if (parseResult.mode() == IntentClassificationResponseParser.ParseMode.FAILED) {
            log.warn(
                    "解析 LLM 响应失败且无法恢复, reason={}, raw={}",
                    parseResult.errorSummary(),
                    parseResult.rawSummary()
            );
            return List.of();
        }

        List<NodeScore> scores = new ArrayList<>(parseResult.scores());

        // 降序排序
        scores.sort(Comparator.comparingDouble(NodeScore::getScore).reversed());

        if (log.isInfoEnabled()) {
            List<Map<String, Object>> logEntries = scores.stream().map(each -> {
                IntentNode n = each.getNode();
                Map<String, Object> m = new HashMap<>();
                m.put("id", n.getId());
                m.put("name", n.getName());
                m.put("fullPath", n.getFullPath());
                m.put("kind", n.getKind());
                m.put("score", each.getScore());
                return m;
            }).toList();
            log.info("当前问题：{}\n意图识别结果：{}\n", question, JSONUtil.toJsonPrettyStr(logEntries));
        }
        return scores;
    }

    /**
     * 方便使用：
     * - 只取前 topN
     * - 过滤掉 score < minScore 的分类
     */
    @Override
    public List<NodeScore> topKAboveThreshold(String question, int topN, double minScore) {
        return classifyTargets(question).stream()
                .filter(ns -> ns.getScore() >= minScore)
                .limit(topN)
                .toList();
    }

    /**
     * 构造给 LLM 的 Prompt：
     * - 列出所有【叶子节点】的 id / 路径 / 描述 / 示例问题
     * - 要求 LLM 只在这些 id 中选择，输出 JSON 数组：[{"id": "...", "score": 0.9, "reason": "..."}]
     * - 特别强调：如果问题里明确提到某个项目或笔记空间，不要跳到其他无关分类
     * - 如果存在 MCP 类型节点，使用增强版 Prompt 并添加 type/toolId 标识
     */
    private String buildPrompt(List<IntentNode> leafNodes) {
        StringBuilder sb = new StringBuilder();

        for (IntentNode node : leafNodes) {
            sb.append("- id=").append(node.getId()).append("\n");
            sb.append("  path=").append(node.getFullPath()).append("\n");
            sb.append("  description=").append(node.getDescription()).append("\n");

            // 添加节点类型标识（V3 Enterprise 支持 MCP）
            if (node.isMCP()) {
                sb.append("  type=MCP\n");
                if (node.getMcpToolId() != null) {
                    sb.append("  toolId=").append(node.getMcpToolId()).append("\n");
                }
            } else if (node.isSystem()) {
                sb.append("  type=SYSTEM\n");
            } else {
                sb.append("  type=KB\n");
            }

            if (node.getExamples() != null && !node.getExamples().isEmpty()) {
                sb.append("  examples=");
                sb.append(String.join(" / ", node.getExamples()));
                sb.append("\n");
            }
            sb.append("\n");
        }

        return promptTemplateLoader.render(
                INTENT_CLASSIFIER_PROMPT_PATH,
                Map.of("intent_list", sb.toString())
        );
    }

    private List<IntentNode> loadIntentTreeFromDB() {
        // 1. 查出所有未删除节点（扁平结构）
        List<IntentNodeDO> intentNodeDOList = intentNodeMapper.selectList(
                Wrappers.lambdaQuery(IntentNodeDO.class)
                        .eq(IntentNodeDO::getDeleted, 0)
                        .eq(IntentNodeDO::getEnabled, 1)
        );

        if (intentNodeDOList.isEmpty()) {
            return List.of();
        }

        // 2. DO -> IntentNode（第一遍：先把所有节点建出来，放到 map 里）
        Map<String, IntentNode> id2Node = new HashMap<>();
        for (IntentNodeDO each : intentNodeDOList) {
            IntentNode node = BeanUtil.toBean(each, IntentNode.class);
            // 数据库中的 code 映射到 IntentNode 的 id/parentId
            node.setId(each.getIntentCode());
            node.setParentId(each.getParentCode());
            node.setMcpToolId(each.getMcpToolId());
            node.setParamPromptTemplate(each.getParamPromptTemplate());
            // 确保 children 不为 null（避免后面 add NPE）
            if (node.getChildren() == null) {
                node.setChildren(new ArrayList<>());
            }
            id2Node.put(node.getId(), node);
        }

        // 3. 第二遍：根据 parentId 组装 parent -> children
        List<IntentNode> roots = new ArrayList<>();
        for (IntentNode node : id2Node.values()) {
            String parentId = node.getParentId();
            if (parentId == null || parentId.isBlank()) {
                // 没有 parentId，当作根节点
                roots.add(node);
                continue;
            }

            IntentNode parent = id2Node.get(parentId);
            if (parent == null) {
                // 找不到父节点，兜底也当作根节点，避免节点丢失
                roots.add(node);
                continue;
            }

            // 追加到父节点的 children
            if (parent.getChildren() == null) {
                parent.setChildren(new ArrayList<>());
            }
            parent.getChildren().add(node);
        }

        // 4. 填充 fullPath（跟你原来的 fillFullPath 一样的逻辑）
        fillFullPath(roots, null);

        return roots;
    }

    /**
     * 填充 fullPath 字段，效果类似：
     * - 集团信息化
     * - 集团信息化 > 人事
     * - 知识问答 > 项目实战 > RAG系统设计
     */
    private void fillFullPath(List<IntentNode> nodes, IntentNode parent) {
        if (nodes == null) return;

        for (IntentNode node : nodes) {
            if (parent == null) {
                node.setFullPath(node.getName());
            } else {
                node.setFullPath(parent.getFullPath() + " > " + node.getName());
            }

            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                fillFullPath(node.getChildren(), node);
            }
        }
    }
}
