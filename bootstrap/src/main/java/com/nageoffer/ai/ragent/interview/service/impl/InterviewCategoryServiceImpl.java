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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.interview.controller.request.InterviewCategoryCreateRequest;
import com.nageoffer.ai.ragent.interview.controller.request.InterviewCategoryUpdateRequest;
import com.nageoffer.ai.ragent.interview.controller.vo.InterviewCategoryVO;
import com.nageoffer.ai.ragent.interview.dao.entity.InterviewCategoryDO;
import com.nageoffer.ai.ragent.interview.dao.entity.InterviewQuestionDO;
import com.nageoffer.ai.ragent.interview.dao.mapper.InterviewCategoryMapper;
import com.nageoffer.ai.ragent.interview.dao.mapper.InterviewQuestionMapper;
import com.nageoffer.ai.ragent.interview.service.InterviewCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 面试分类服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewCategoryServiceImpl implements InterviewCategoryService {

    private final InterviewCategoryMapper interviewCategoryMapper;
    private final InterviewQuestionMapper interviewQuestionMapper;

    @Override
    public List<InterviewCategoryVO> listCategories() {
        List<InterviewCategoryDO> categories = interviewCategoryMapper.selectList(
                Wrappers.lambdaQuery(InterviewCategoryDO.class)
                        .orderByAsc(InterviewCategoryDO::getSortOrder)
                        .orderByDesc(InterviewCategoryDO::getCreateTime)
        );
        return categories.stream().map(each -> {
            Long count = interviewQuestionMapper.selectCount(
                    Wrappers.lambdaQuery(InterviewQuestionDO.class)
                            .eq(InterviewQuestionDO::getCategoryId, each.getId())
            );
            return InterviewCategoryVO.builder()
                    .id(each.getId())
                    .name(each.getName())
                    .description(each.getDescription())
                    .icon(each.getIcon())
                    .sortOrder(each.getSortOrder())
                    .questionCount(count != null ? count.intValue() : 0)
                    .createTime(each.getCreateTime())
                    .build();
        }).collect(Collectors.toList());
    }

    @Override
    public void createCategory(InterviewCategoryCreateRequest request) {
        InterviewCategoryDO categoryDO = InterviewCategoryDO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .icon(request.getIcon())
                .sortOrder(request.getSortOrder())
                .deleted(0)
                .build();
        interviewCategoryMapper.insert(categoryDO);
    }

    @Override
    public void updateCategory(InterviewCategoryUpdateRequest request) {
        InterviewCategoryDO categoryDO = new InterviewCategoryDO();
        categoryDO.setId(request.getId());
        categoryDO.setName(request.getName());
        categoryDO.setDescription(request.getDescription());
        categoryDO.setIcon(request.getIcon());
        categoryDO.setSortOrder(request.getSortOrder());
        interviewCategoryMapper.updateById(categoryDO);
    }

    @Override
    public void deleteCategory(Long id) {
        interviewCategoryMapper.deleteById(id);
    }
}
