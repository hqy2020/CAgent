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

package com.nageoffer.ai.ragent.interview.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.interview.controller.request.InterviewQuestionCreateRequest;
import com.nageoffer.ai.ragent.interview.controller.request.InterviewQuestionPageRequest;
import com.nageoffer.ai.ragent.interview.controller.request.InterviewQuestionUpdateRequest;
import com.nageoffer.ai.ragent.interview.controller.vo.InterviewQuestionListVO;
import com.nageoffer.ai.ragent.interview.controller.vo.InterviewQuestionVO;
import com.nageoffer.ai.ragent.interview.dao.entity.InterviewQuestionDO;
import com.nageoffer.ai.ragent.interview.dao.mapper.InterviewQuestionMapper;
import com.nageoffer.ai.ragent.interview.service.InterviewQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 面试题目服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewQuestionServiceImpl implements InterviewQuestionService {

    private final InterviewQuestionMapper interviewQuestionMapper;

    @Override
    public IPage<InterviewQuestionListVO> pageQuestions(InterviewQuestionPageRequest request) {
        Page<InterviewQuestionDO> page = new Page<>(request.getPageNo(), request.getPageSize());
        LambdaQueryWrapper<InterviewQuestionDO> queryWrapper = Wrappers.lambdaQuery(InterviewQuestionDO.class)
                .eq(request.getCategoryId() != null, InterviewQuestionDO::getCategoryId, request.getCategoryId())
                .eq(request.getDifficulty() != null, InterviewQuestionDO::getDifficulty, request.getDifficulty())
                .like(StrUtil.isNotBlank(request.getKeyword()), InterviewQuestionDO::getQuestion, request.getKeyword())
                .orderByDesc(InterviewQuestionDO::getCreateTime);
        IPage<InterviewQuestionDO> result = interviewQuestionMapper.selectPage(page, queryWrapper);
        return result.convert(each -> InterviewQuestionListVO.builder()
                .id(each.getId())
                .categoryId(each.getCategoryId())
                .question(each.getQuestion())
                .difficulty(each.getDifficulty())
                .tags(each.getTags())
                .createTime(each.getCreateTime())
                .build());
    }

    @Override
    public InterviewQuestionVO getById(Long id) {
        InterviewQuestionDO questionDO = interviewQuestionMapper.selectById(id);
        if (questionDO == null) {
            return null;
        }
        return BeanUtil.toBean(questionDO, InterviewQuestionVO.class);
    }

    @Override
    public void createQuestion(InterviewQuestionCreateRequest request) {
        InterviewQuestionDO questionDO = InterviewQuestionDO.builder()
                .categoryId(request.getCategoryId())
                .question(request.getQuestion())
                .answer(request.getAnswer())
                .difficulty(request.getDifficulty())
                .tags(request.getTags())
                .deleted(0)
                .build();
        interviewQuestionMapper.insert(questionDO);
    }

    @Override
    public void updateQuestion(InterviewQuestionUpdateRequest request) {
        InterviewQuestionDO questionDO = new InterviewQuestionDO();
        questionDO.setId(request.getId());
        questionDO.setCategoryId(request.getCategoryId());
        questionDO.setQuestion(request.getQuestion());
        questionDO.setAnswer(request.getAnswer());
        questionDO.setDifficulty(request.getDifficulty());
        questionDO.setTags(request.getTags());
        interviewQuestionMapper.updateById(questionDO);
    }

    @Override
    public void deleteQuestion(Long id) {
        interviewQuestionMapper.deleteById(id);
    }
}
