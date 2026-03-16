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

package com.openingcloud.ai.ragent.ingestion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.core.parser.TextCleanupOptions;
import com.openingcloud.ai.ragent.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.openingcloud.ai.ragent.ingestion.controller.request.IngestionPipelineNodeRequest;
import com.openingcloud.ai.ragent.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.openingcloud.ai.ragent.ingestion.controller.vo.IngestionPipelineNodeVO;
import com.openingcloud.ai.ragent.ingestion.controller.vo.IngestionPipelineVO;
import com.openingcloud.ai.ragent.ingestion.dao.entity.IngestionPipelineDO;
import com.openingcloud.ai.ragent.ingestion.dao.entity.IngestionPipelineNodeDO;
import com.openingcloud.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.openingcloud.ai.ragent.ingestion.dao.mapper.IngestionPipelineNodeMapper;
import com.openingcloud.ai.ragent.framework.context.UserContext;
import com.openingcloud.ai.ragent.framework.exception.ClientException;
import com.openingcloud.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.openingcloud.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.openingcloud.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.openingcloud.ai.ragent.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据清洗流水线业务逻辑实现
 */
@Service
@RequiredArgsConstructor
public class IngestionPipelineServiceImpl implements IngestionPipelineService {

    private static final Object STANDARD_PIPELINES_LOCK = new Object();

    private static final List<StandardPipelineSpec> STANDARD_PIPELINES = List.of(
            new StandardPipelineSpec("knowledge_base", "知识库数据通道", "通用知识库/产品手册/Markdown 文档，采用递归分块入库链路",
                    "recursive", 500, 50, null, null, null, null),
            new StandardPipelineSpec("faq", "FAQ 数据通道", "FAQ / 问答对文档，采用递归分块保留自然问答单元",
                    "recursive", 500, 50, null, null, null, null),
            new StandardPipelineSpec("contract", "合同数据通道", "合同/法律文档，采用语义分块保证条款边界更稳定",
                    "semantic", null, null, 800, 1000, 300, 120),
            new StandardPipelineSpec("log", "日志数据通道", "日志类文档，采用固定大小分块作为默认起点",
                    "fixed_size", 500, 0, null, null, null, null),
            new StandardPipelineSpec("ocr", "OCR 数据通道", "OCR / 格式混乱文本，采用重叠分块缓解边界断裂",
                    "overlap", 500, 100, null, null, null, null),
            new StandardPipelineSpec("html", "HTML 数据通道", "HTML 页面解析清洗后，采用递归分块入库",
                    "recursive", 500, 50, null, null, null, null),
            new StandardPipelineSpec("code", "代码数据通道", "代码文件当前使用递归分块作为通用默认，保留用户手动覆盖能力",
                    "recursive", 500, 50, null, null, null, null),
            new StandardPipelineSpec("mixed", "混合文档数据通道", "多类型混合资料，使用递归分块作为默认混合策略",
                    "recursive", 500, 50, null, null, null, null)
    );

    private final IngestionPipelineMapper pipelineMapper;
    private final IngestionPipelineNodeMapper nodeMapper;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean standardPipelinesInitialized = new AtomicBoolean(false);

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionPipelineVO create(IngestionPipelineCreateRequest request) {
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        IngestionPipelineDO pipeline = IngestionPipelineDO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        try {
            pipelineMapper.insert(pipeline);
        } catch (DuplicateKeyException dke) {
            throw new ClientException("流水线名称已存在");
        }
        upsertNodes(pipeline.getId(), request.getNodes());
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionPipelineVO update(String pipelineId, IngestionPipelineUpdateRequest request) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));

        if (StringUtils.hasText(request.getName())) {
            pipeline.setName(request.getName());
        }
        if (request.getDescription() != null) {
            pipeline.setDescription(request.getDescription());
        }
        pipeline.setUpdatedBy(UserContext.getUsername());
        pipelineMapper.updateById(pipeline);

        if (request.getNodes() != null) {
            upsertNodes(pipeline.getId(), request.getNodes());
        }
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    @Override
    public IngestionPipelineVO get(String pipelineId) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    @Override
    public IPage<IngestionPipelineVO> page(Page<IngestionPipelineVO> page, String keyword, boolean standardOnly) {
        if (standardOnly) {
            return pageStandardPipelines(page, keyword);
        }
        Page<IngestionPipelineDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<IngestionPipelineDO> qw = new LambdaQueryWrapper<IngestionPipelineDO>()
                .eq(IngestionPipelineDO::getDeleted, 0)
                .like(StringUtils.hasText(keyword), IngestionPipelineDO::getName, keyword)
                .orderByDesc(IngestionPipelineDO::getUpdateTime);
        IPage<IngestionPipelineDO> result = pipelineMapper.selectPage(mpPage, qw);
        Page<IngestionPipelineVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream()
                .map(each -> toVO(each, fetchNodes(each.getId())))
                .toList());
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initializeStandardPipelines() {
        if (standardPipelinesInitialized.get()) {
            return;
        }
        synchronized (STANDARD_PIPELINES_LOCK) {
            if (standardPipelinesInitialized.get()) {
                return;
            }
            for (StandardPipelineSpec spec : STANDARD_PIPELINES) {
                upsertStandardPipeline(spec);
            }
            standardPipelinesInitialized.set(true);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String pipelineId) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));
        int affected = pipelineMapper.deleteById(pipeline.getId());
        if (affected < 1) {
            throw new ClientException("删除流水线失败");
        }

        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipeline.getId());
        nodeMapper.delete(qw);
    }

    @Override
    public PipelineDefinition getDefinition(String pipelineId) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));

        List<NodeConfig> nodes = fetchNodes(pipeline.getId()).stream()
                .map(this::toNodeConfig)
                .toList();
        return PipelineDefinition.builder()
                .id(String.valueOf(pipeline.getId()))
                .name(pipeline.getName())
                .description(pipeline.getDescription())
                .nodes(nodes)
                .build();
    }

    private void upsertNodes(Long pipelineId, List<IngestionPipelineNodeRequest> nodes) {
        if (nodes == null) {
            return;
        }
        // 物理删除旧节点，避免 @TableLogic 逻辑删除与唯一索引冲突
        nodeMapper.physicalDeleteByPipelineId(pipelineId);
        for (IngestionPipelineNodeRequest node : nodes) {
            if (node == null) {
                continue;
            }
            IngestionPipelineNodeDO entity = IngestionPipelineNodeDO.builder()
                    .pipelineId(pipelineId)
                    .nodeId(node.getNodeId())
                    .nodeType(normalizeNodeType(node.getNodeType()))
                    .nextNodeId(node.getNextNodeId())
                    .settingsJson(toJson(node.getSettings()))
                    .conditionJson(toJson(node.getCondition()))
                    .createdBy(UserContext.getUsername())
                    .updatedBy(UserContext.getUsername())
                    .build();
            nodeMapper.insert(entity);
        }
    }

    private List<IngestionPipelineNodeDO> fetchNodes(Long pipelineId) {
        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipelineId)
                .eq(IngestionPipelineNodeDO::getDeleted, 0);
        return nodeMapper.selectList(qw);
    }

    private IngestionPipelineVO toVO(IngestionPipelineDO pipeline, List<IngestionPipelineNodeDO> nodes) {
        IngestionPipelineVO vo = BeanUtil.toBean(pipeline, IngestionPipelineVO.class);
        vo.setNodes(nodes.stream().map(this::toNodeVO).toList());
        return vo;
    }

    private IngestionPipelineNodeVO toNodeVO(IngestionPipelineNodeDO node) {
        IngestionPipelineNodeVO vo = BeanUtil.toBean(node, IngestionPipelineNodeVO.class);
        vo.setNodeType(normalizeNodeTypeForOutput(node.getNodeType()));
        vo.setSettings(parseJson(node.getSettingsJson()));
        vo.setCondition(parseJson(node.getConditionJson()));
        return vo;
    }

    private NodeConfig toNodeConfig(IngestionPipelineNodeDO node) {
        return NodeConfig.builder()
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .settings(parseJson(node.getSettingsJson()))
                .condition(parseJson(node.getConditionJson()))
                .nextNodeId(node.getNextNodeId())
                .build();
    }

    private String toJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    private JsonNode parseJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private IPage<IngestionPipelineVO> pageStandardPipelines(Page<IngestionPipelineVO> page, String keyword) {
        LambdaQueryWrapper<IngestionPipelineDO> qw = new LambdaQueryWrapper<IngestionPipelineDO>()
                .eq(IngestionPipelineDO::getDeleted, 0)
                .in(IngestionPipelineDO::getName, STANDARD_PIPELINES.stream().map(StandardPipelineSpec::name).toList());
        List<IngestionPipelineDO> pipelines = pipelineMapper.selectList(qw);
        Map<String, Integer> orderMap = new HashMap<>();
        for (int i = 0; i < STANDARD_PIPELINES.size(); i++) {
            orderMap.put(STANDARD_PIPELINES.get(i).name(), i);
        }

        List<IngestionPipelineVO> records = pipelines.stream()
                .filter(each -> !StringUtils.hasText(keyword) || each.getName().contains(keyword))
                .sorted((left, right) -> Integer.compare(
                        orderMap.getOrDefault(left.getName(), Integer.MAX_VALUE),
                        orderMap.getOrDefault(right.getName(), Integer.MAX_VALUE)))
                .map(each -> toVO(each, fetchNodes(each.getId())))
                .toList();

        Page<IngestionPipelineVO> result = new Page<>(page.getCurrent(), page.getSize(), records.size());
        int fromIndex = (int) ((page.getCurrent() - 1) * page.getSize());
        int toIndex = Math.min(records.size(), fromIndex + (int) page.getSize());
        if (fromIndex >= records.size()) {
            result.setRecords(List.of());
            return result;
        }
        result.setRecords(records.subList(fromIndex, toIndex));
        return result;
    }

    private void upsertStandardPipeline(StandardPipelineSpec spec) {
        List<IngestionPipelineNodeRequest> standardNodes = buildStandardNodes(spec);
        LambdaQueryWrapper<IngestionPipelineDO> qw = new LambdaQueryWrapper<IngestionPipelineDO>()
                .eq(IngestionPipelineDO::getDeleted, 0)
                .eq(IngestionPipelineDO::getName, spec.name());
        IngestionPipelineDO existing = pipelineMapper.selectOne(qw);
        String operator = resolveOperator();

        if (existing == null) {
            IngestionPipelineDO created = IngestionPipelineDO.builder()
                    .name(spec.name())
                    .description(spec.description())
                    .createdBy(operator)
                    .updatedBy(operator)
                    .build();
            try {
                pipelineMapper.insert(created);
                upsertNodes(created.getId(), standardNodes);
                return;
            } catch (DuplicateKeyException ignore) {
                existing = pipelineMapper.selectOne(qw);
            }
        }

        Assert.notNull(existing, () -> new ClientException("标准数据通道初始化失败: " + spec.name()));

        boolean descriptionChanged = !Objects.equals(spec.description(), existing.getDescription());
        if (descriptionChanged) {
            existing.setDescription(spec.description());
            existing.setUpdatedBy(operator);
            pipelineMapper.updateById(existing);
        }
        if (!matchesStandardNodes(existing.getId(), standardNodes)) {
            upsertNodes(existing.getId(), standardNodes);
        }
    }

    private List<IngestionPipelineNodeRequest> buildStandardNodes(StandardPipelineSpec spec) {
        List<IngestionPipelineNodeRequest> nodes = new ArrayList<>();
        nodes.add(node("fetcher-1", "fetcher", null, "parser-1"));
        nodes.add(node("parser-1", "parser", objectMapper.valueToTree(Map.of(
                "rules", List.of(Map.of(
                        "mimeType", "ALL",
                        "options", Map.of("cleanupProfile", defaultCleanupProfile(spec))
                ))
        )), "chunker-1"));
        nodes.add(node("chunker-1", "chunker", objectMapper.valueToTree(buildChunkerSettings(spec)), "indexer-1"));
        nodes.add(node("indexer-1", "indexer", objectMapper.valueToTree(Map.of()), null));
        return nodes;
    }

    private String defaultCleanupProfile(StandardPipelineSpec spec) {
        if ("html".equals(spec.key())) {
            return TextCleanupOptions.PROFILE_MARKDOWN_STANDARD;
        }
        return TextCleanupOptions.PROFILE_DEFAULT;
    }

    private Map<String, Object> buildChunkerSettings(StandardPipelineSpec spec) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("strategy", spec.chunkStrategy());
        if (spec.chunkSize() != null) {
            settings.put("chunkSize", spec.chunkSize());
        }
        if (spec.overlapSize() != null) {
            settings.put("overlapSize", spec.overlapSize());
        }
        if (spec.targetChars() != null) {
            settings.put("targetChars", spec.targetChars());
        }
        if (spec.maxChars() != null) {
            settings.put("maxChars", spec.maxChars());
        }
        if (spec.minChars() != null) {
            settings.put("minChars", spec.minChars());
        }
        if (spec.overlapChars() != null) {
            settings.put("overlapChars", spec.overlapChars());
        }
        return settings;
    }

    private IngestionPipelineNodeRequest node(String nodeId, String nodeType, JsonNode settings, String nextNodeId) {
        IngestionPipelineNodeRequest node = new IngestionPipelineNodeRequest();
        node.setNodeId(nodeId);
        node.setNodeType(nodeType);
        node.setSettings(settings);
        node.setNextNodeId(nextNodeId);
        return node;
    }

    private String resolveOperator() {
        return StringUtils.hasText(UserContext.getUsername()) ? UserContext.getUsername() : "system";
    }

    private boolean matchesStandardNodes(Long pipelineId, List<IngestionPipelineNodeRequest> expectedNodes) {
        List<IngestionPipelineNodeDO> actualNodes = fetchNodes(pipelineId);
        if (actualNodes.size() != expectedNodes.size()) {
            return false;
        }
        Map<String, IngestionPipelineNodeDO> actualNodeMap = new HashMap<>();
        for (IngestionPipelineNodeDO actualNode : actualNodes) {
            actualNodeMap.put(actualNode.getNodeId(), actualNode);
        }
        for (IngestionPipelineNodeRequest expectedNode : expectedNodes) {
            IngestionPipelineNodeDO actualNode = actualNodeMap.get(expectedNode.getNodeId());
            if (actualNode == null) {
                return false;
            }
            if (!Objects.equals(normalizeNodeType(expectedNode.getNodeType()), actualNode.getNodeType())) {
                return false;
            }
            if (!Objects.equals(expectedNode.getNextNodeId(), actualNode.getNextNodeId())) {
                return false;
            }
            if (!jsonEquals(expectedNode.getSettings(), actualNode.getSettingsJson())) {
                return false;
            }
            if (!jsonEquals(expectedNode.getCondition(), actualNode.getConditionJson())) {
                return false;
            }
        }
        return true;
    }

    private boolean jsonEquals(JsonNode expected, String actualRaw) {
        JsonNode normalizedExpected = expected == null || expected.isNull() ? null : expected;
        JsonNode normalizedActual = parseJson(actualRaw);
        return Objects.equals(normalizedExpected, normalizedActual);
    }

    private String normalizeNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            throw new ClientException("未知节点类型: " + nodeType);
        }
    }

    private String normalizeNodeTypeForOutput(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }

    private record StandardPipelineSpec(
            String key,
            String name,
            String description,
            String chunkStrategy,
            Integer chunkSize,
            Integer overlapSize,
            Integer targetChars,
            Integer maxChars,
            Integer minChars,
            Integer overlapChars
    ) {
    }
}
