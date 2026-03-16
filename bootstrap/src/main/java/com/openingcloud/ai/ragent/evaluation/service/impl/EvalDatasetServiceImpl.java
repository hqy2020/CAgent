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

package com.openingcloud.ai.ragent.evaluation.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openingcloud.ai.ragent.evaluation.controller.request.EvalDatasetCaseCreateRequest;
import com.openingcloud.ai.ragent.evaluation.controller.request.EvalDatasetCreateRequest;
import com.openingcloud.ai.ragent.evaluation.controller.vo.ConversationQAPairVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalDatasetCaseVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalDatasetVO;
import com.openingcloud.ai.ragent.evaluation.dao.entity.EvalDatasetCaseDO;
import com.openingcloud.ai.ragent.evaluation.dao.entity.EvalDatasetDO;
import com.openingcloud.ai.ragent.evaluation.dao.mapper.EvalDatasetCaseMapper;
import com.openingcloud.ai.ragent.evaluation.dao.mapper.EvalDatasetMapper;
import com.openingcloud.ai.ragent.evaluation.service.EvalDatasetService;
import com.openingcloud.ai.ragent.rag.dao.entity.ConversationDO;
import com.openingcloud.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.openingcloud.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.openingcloud.ai.ragent.rag.dao.mapper.MessageFeedbackMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetServiceImpl implements EvalDatasetService {

    private final EvalDatasetMapper evalDatasetMapper;
    private final EvalDatasetCaseMapper evalDatasetCaseMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final ConversationMapper conversationMapper;
    private final MessageFeedbackMapper messageFeedbackMapper;

    @Override
    public EvalDatasetVO createDataset(EvalDatasetCreateRequest request) {
        EvalDatasetDO datasetDO = EvalDatasetDO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .caseCount(0)
                .build();
        evalDatasetMapper.insert(datasetDO);
        return convertToDatasetVO(datasetDO);
    }

    @Override
    public List<EvalDatasetVO> listDatasets() {
        LambdaQueryWrapper<EvalDatasetDO> queryWrapper = new LambdaQueryWrapper<EvalDatasetDO>()
                .orderByDesc(EvalDatasetDO::getCreateTime);
        List<EvalDatasetDO> datasets = evalDatasetMapper.selectList(queryWrapper);
        return datasets.stream().map(this::convertToDatasetVO).collect(Collectors.toList());
    }

    @Override
    public EvalDatasetVO getDataset(Long id) {
        EvalDatasetDO datasetDO = evalDatasetMapper.selectById(id);
        if (datasetDO == null) {
            throw new RuntimeException("评测数据集不存在: " + id);
        }
        return convertToDatasetVO(datasetDO);
    }

    @Override
    public void deleteDataset(Long id) {
        evalDatasetMapper.deleteById(id);
    }

    @Override
    public EvalDatasetCaseVO addCase(Long datasetId, EvalDatasetCaseCreateRequest request) {
        EvalDatasetCaseDO caseDO = EvalDatasetCaseDO.builder()
                .datasetId(datasetId)
                .query(request.getQuery())
                .expectedAnswer(request.getExpectedAnswer())
                .relevantChunkIds(JSON.toJSONString(request.getRelevantChunkIds()))
                .intent(request.getIntent())
                .build();
        evalDatasetCaseMapper.insert(caseDO);
        updateCaseCount(datasetId);
        return convertToCaseVO(caseDO);
    }

    @Override
    public int batchImportCases(Long datasetId, List<EvalDatasetCaseCreateRequest> cases) {
        int count = 0;
        for (EvalDatasetCaseCreateRequest request : cases) {
            EvalDatasetCaseDO caseDO = EvalDatasetCaseDO.builder()
                    .datasetId(datasetId)
                    .query(request.getQuery())
                    .expectedAnswer(request.getExpectedAnswer())
                    .relevantChunkIds(JSON.toJSONString(request.getRelevantChunkIds()))
                    .intent(request.getIntent())
                    .build();
            evalDatasetCaseMapper.insert(caseDO);
            count++;
        }
        updateCaseCount(datasetId);
        return count;
    }

    @Override
    public List<EvalDatasetCaseVO> listCases(Long datasetId) {
        LambdaQueryWrapper<EvalDatasetCaseDO> queryWrapper = new LambdaQueryWrapper<EvalDatasetCaseDO>()
                .eq(EvalDatasetCaseDO::getDatasetId, datasetId)
                .orderByAsc(EvalDatasetCaseDO::getCreateTime);
        List<EvalDatasetCaseDO> cases = evalDatasetCaseMapper.selectList(queryWrapper);
        return cases.stream().map(this::convertToCaseVO).collect(Collectors.toList());
    }

    @Override
    public void deleteCase(Long datasetId, Long caseId) {
        evalDatasetCaseMapper.deleteById(caseId);
        updateCaseCount(datasetId);
    }

    @Override
    public IPage<ConversationQAPairVO> listConversationQAPairs(String keyword, int current, int size) {
        // 1. 分页查询 user 消息
        LambdaQueryWrapper<ConversationMessageDO> userMsgQuery = new LambdaQueryWrapper<ConversationMessageDO>()
                .eq(ConversationMessageDO::getRole, "user")
                .like(StringUtils.hasText(keyword), ConversationMessageDO::getContent, keyword)
                .orderByDesc(ConversationMessageDO::getCreateTime);
        IPage<ConversationMessageDO> userMsgPage = conversationMessageMapper.selectPage(
                new Page<>(current, size), userMsgQuery);

        if (userMsgPage.getRecords().isEmpty()) {
            IPage<ConversationQAPairVO> emptyPage = new Page<>(current, size, 0);
            emptyPage.setRecords(Collections.emptyList());
            return emptyPage;
        }

        // 2. 收集 conversationIds，批量查询 assistant 回复
        Set<String> conversationIds = userMsgPage.getRecords().stream()
                .map(ConversationMessageDO::getConversationId)
                .collect(Collectors.toSet());

        List<ConversationMessageDO> assistantMsgs = conversationMessageMapper.selectList(
                new LambdaQueryWrapper<ConversationMessageDO>()
                        .eq(ConversationMessageDO::getRole, "assistant")
                        .in(ConversationMessageDO::getConversationId, conversationIds));
        // 按 conversationId 分组，保留时间排序以便配对
        Map<String, List<ConversationMessageDO>> assistantByConv = assistantMsgs.stream()
                .collect(Collectors.groupingBy(ConversationMessageDO::getConversationId));

        // 3. 批量查询会话标题
        List<ConversationDO> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<ConversationDO>()
                        .in(ConversationDO::getConversationId, conversationIds));
        Map<String, String> titleByConvId = conversations.stream()
                .collect(Collectors.toMap(ConversationDO::getConversationId, c -> c.getTitle() != null ? c.getTitle() : "", (a, b) -> a));

        // 4. 收集所有 user 消息的 ID，批量查询反馈（feedback 的 messageId 关联 assistant 消息）
        // 反馈是针对 assistant 消息的，需要用 assistant 消息 ID 查询
        Set<Long> allAssistantIds = assistantMsgs.stream()
                .map(ConversationMessageDO::getId)
                .collect(Collectors.toSet());
        Map<Long, Integer> voteByMsgId = Collections.emptyMap();
        if (!allAssistantIds.isEmpty()) {
            List<MessageFeedbackDO> feedbacks = messageFeedbackMapper.selectList(
                    new LambdaQueryWrapper<MessageFeedbackDO>()
                            .in(MessageFeedbackDO::getMessageId, allAssistantIds));
            voteByMsgId = feedbacks.stream()
                    .collect(Collectors.toMap(MessageFeedbackDO::getMessageId, MessageFeedbackDO::getVote, (a, b) -> a));
        }

        // 5. 组装 Q&A 对
        Map<Long, Integer> finalVoteByMsgId = voteByMsgId;
        List<ConversationQAPairVO> records = userMsgPage.getRecords().stream().map(userMsg -> {
            // 找紧随其后的 assistant 消息
            List<ConversationMessageDO> convAssistants = assistantByConv.getOrDefault(
                    userMsg.getConversationId(), Collections.emptyList());
            ConversationMessageDO matchedAssistant = convAssistants.stream()
                    .filter(a -> a.getCreateTime() != null && userMsg.getCreateTime() != null
                            && !a.getCreateTime().before(userMsg.getCreateTime()))
                    .min((a, b) -> a.getCreateTime().compareTo(b.getCreateTime()))
                    .orElse(null);

            String answer = matchedAssistant != null ? matchedAssistant.getContent() : null;
            Integer vote = matchedAssistant != null ? finalVoteByMsgId.get(matchedAssistant.getId()) : null;

            return ConversationQAPairVO.builder()
                    .messageId(String.valueOf(userMsg.getId()))
                    .conversationId(userMsg.getConversationId())
                    .conversationTitle(titleByConvId.getOrDefault(userMsg.getConversationId(), ""))
                    .query(userMsg.getContent())
                    .answer(answer)
                    .vote(vote)
                    .createTime(userMsg.getCreateTime())
                    .build();
        }).collect(Collectors.toList());

        IPage<ConversationQAPairVO> resultPage = new Page<>(current, size, userMsgPage.getTotal());
        resultPage.setRecords(records);
        return resultPage;
    }

    @Override
    public int importFromChat(Long datasetId, List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }

        // 查询选中的 user 消息
        List<ConversationMessageDO> userMsgs = conversationMessageMapper.selectList(
                new LambdaQueryWrapper<ConversationMessageDO>()
                        .in(ConversationMessageDO::getId, messageIds)
                        .eq(ConversationMessageDO::getRole, "user"));
        if (userMsgs.isEmpty()) {
            return 0;
        }

        // 查出对应会话的 assistant 消息
        Set<String> conversationIds = userMsgs.stream()
                .map(ConversationMessageDO::getConversationId)
                .collect(Collectors.toSet());
        List<ConversationMessageDO> assistantMsgs = conversationMessageMapper.selectList(
                new LambdaQueryWrapper<ConversationMessageDO>()
                        .eq(ConversationMessageDO::getRole, "assistant")
                        .in(ConversationMessageDO::getConversationId, conversationIds));
        Map<String, List<ConversationMessageDO>> assistantByConv = assistantMsgs.stream()
                .collect(Collectors.groupingBy(ConversationMessageDO::getConversationId));

        int count = 0;
        for (ConversationMessageDO userMsg : userMsgs) {
            List<ConversationMessageDO> convAssistants = assistantByConv.getOrDefault(
                    userMsg.getConversationId(), Collections.emptyList());
            ConversationMessageDO matchedAssistant = convAssistants.stream()
                    .filter(a -> a.getCreateTime() != null && userMsg.getCreateTime() != null
                            && !a.getCreateTime().before(userMsg.getCreateTime()))
                    .min((a, b) -> a.getCreateTime().compareTo(b.getCreateTime()))
                    .orElse(null);

            EvalDatasetCaseDO caseDO = EvalDatasetCaseDO.builder()
                    .datasetId(datasetId)
                    .query(userMsg.getContent())
                    .expectedAnswer(matchedAssistant != null ? matchedAssistant.getContent() : null)
                    .build();
            evalDatasetCaseMapper.insert(caseDO);
            count++;
        }
        updateCaseCount(datasetId);
        return count;
    }

    private void updateCaseCount(Long datasetId) {
        LambdaQueryWrapper<EvalDatasetCaseDO> queryWrapper = new LambdaQueryWrapper<EvalDatasetCaseDO>()
                .eq(EvalDatasetCaseDO::getDatasetId, datasetId);
        Long count = evalDatasetCaseMapper.selectCount(queryWrapper);
        EvalDatasetDO update = new EvalDatasetDO();
        update.setId(datasetId);
        update.setCaseCount(count.intValue());
        evalDatasetMapper.updateById(update);
    }

    private EvalDatasetVO convertToDatasetVO(EvalDatasetDO datasetDO) {
        return EvalDatasetVO.builder()
                .id(String.valueOf(datasetDO.getId()))
                .name(datasetDO.getName())
                .description(datasetDO.getDescription())
                .caseCount(datasetDO.getCaseCount())
                .createdBy(datasetDO.getCreatedBy())
                .createTime(datasetDO.getCreateTime())
                .updateTime(datasetDO.getUpdateTime())
                .build();
    }

    private EvalDatasetCaseVO convertToCaseVO(EvalDatasetCaseDO caseDO) {
        List<String> chunkIds = caseDO.getRelevantChunkIds() != null
                ? JSON.parseArray(caseDO.getRelevantChunkIds(), String.class)
                : Collections.emptyList();
        return EvalDatasetCaseVO.builder()
                .id(String.valueOf(caseDO.getId()))
                .datasetId(String.valueOf(caseDO.getDatasetId()))
                .query(caseDO.getQuery())
                .expectedAnswer(caseDO.getExpectedAnswer())
                .relevantChunkIds(chunkIds)
                .intent(caseDO.getIntent())
                .createTime(caseDO.getCreateTime())
                .build();
    }
}
