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

package com.nageoffer.ai.ragent.study.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.study.controller.request.StudyChapterCreateRequest;
import com.nageoffer.ai.ragent.study.controller.request.StudyChapterUpdateRequest;
import com.nageoffer.ai.ragent.study.controller.vo.StudyChapterVO;
import com.nageoffer.ai.ragent.study.dao.entity.StudyChapterDO;
import com.nageoffer.ai.ragent.study.dao.entity.StudyDocumentDO;
import com.nageoffer.ai.ragent.study.dao.mapper.StudyChapterMapper;
import com.nageoffer.ai.ragent.study.dao.mapper.StudyDocumentMapper;
import com.nageoffer.ai.ragent.study.service.StudyChapterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudyChapterServiceImpl implements StudyChapterService {

    private final StudyChapterMapper studyChapterMapper;
    private final StudyDocumentMapper studyDocumentMapper;

    @Override
    public List<StudyChapterVO> listByModuleId(Long moduleId) {
        List<StudyChapterDO> chapters = studyChapterMapper.selectList(
                Wrappers.lambdaQuery(StudyChapterDO.class)
                        .eq(StudyChapterDO::getModuleId, moduleId)
                        .orderByAsc(StudyChapterDO::getSortOrder)
        );
        return chapters.stream().map(each -> {
            StudyChapterVO vo = BeanUtil.toBean(each, StudyChapterVO.class);
            Long count = studyDocumentMapper.selectCount(
                    Wrappers.lambdaQuery(StudyDocumentDO.class)
                            .eq(StudyDocumentDO::getChapterId, each.getId())
            );
            vo.setDocumentCount(count != null ? count.intValue() : 0);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public void createChapter(StudyChapterCreateRequest request) {
        StudyChapterDO chapterDO = StudyChapterDO.builder()
                .moduleId(request.getModuleId())
                .title(request.getTitle())
                .summary(request.getSummary())
                .sortOrder(request.getSortOrder())
                .deleted(0)
                .build();
        studyChapterMapper.insert(chapterDO);
    }

    @Override
    public void updateChapter(StudyChapterUpdateRequest request) {
        StudyChapterDO chapterDO = new StudyChapterDO();
        chapterDO.setId(request.getId());
        chapterDO.setModuleId(request.getModuleId());
        chapterDO.setTitle(request.getTitle());
        chapterDO.setSummary(request.getSummary());
        chapterDO.setSortOrder(request.getSortOrder());
        studyChapterMapper.updateById(chapterDO);
    }

    @Override
    public void deleteChapter(Long id) {
        studyChapterMapper.deleteById(id);
    }
}
