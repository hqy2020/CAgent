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
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.study.controller.request.StudyModuleCreateRequest;
import com.nageoffer.ai.ragent.study.controller.request.StudyModuleUpdateRequest;
import com.nageoffer.ai.ragent.study.controller.vo.StudyModuleTreeVO;
import com.nageoffer.ai.ragent.study.controller.vo.StudyModuleVO;
import com.nageoffer.ai.ragent.study.dao.entity.StudyChapterDO;
import com.nageoffer.ai.ragent.study.dao.entity.StudyDocumentDO;
import com.nageoffer.ai.ragent.study.dao.entity.StudyModuleDO;
import com.nageoffer.ai.ragent.study.dao.mapper.StudyChapterMapper;
import com.nageoffer.ai.ragent.study.dao.mapper.StudyDocumentMapper;
import com.nageoffer.ai.ragent.study.dao.mapper.StudyModuleMapper;
import com.nageoffer.ai.ragent.study.service.StudyModuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudyModuleServiceImpl implements StudyModuleService {

    private final StudyModuleMapper studyModuleMapper;
    private final StudyChapterMapper studyChapterMapper;
    private final StudyDocumentMapper studyDocumentMapper;

    @Override
    public IPage<StudyModuleVO> pageModules(int pageNo, int pageSize) {
        Page<StudyModuleDO> page = new Page<>(pageNo, pageSize);
        IPage<StudyModuleDO> result = studyModuleMapper.selectPage(
                page,
                Wrappers.lambdaQuery(StudyModuleDO.class)
                        .orderByAsc(StudyModuleDO::getSortOrder)
                        .orderByDesc(StudyModuleDO::getCreateTime)
        );
        return result.convert(each -> {
            StudyModuleVO vo = BeanUtil.toBean(each, StudyModuleVO.class);
            Long count = studyChapterMapper.selectCount(
                    Wrappers.lambdaQuery(StudyChapterDO.class)
                            .eq(StudyChapterDO::getModuleId, each.getId())
            );
            vo.setChapterCount(count != null ? count.intValue() : 0);
            return vo;
        });
    }

    @Override
    public List<StudyModuleTreeVO> tree() {
        // 查询所有启用的模块
        List<StudyModuleDO> modules = studyModuleMapper.selectList(
                Wrappers.lambdaQuery(StudyModuleDO.class)
                        .eq(StudyModuleDO::getEnabled, 1)
                        .orderByAsc(StudyModuleDO::getSortOrder)
        );
        if (modules.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> moduleIds = modules.stream().map(StudyModuleDO::getId).collect(Collectors.toList());

        // 查询所有章节
        List<StudyChapterDO> chapters = studyChapterMapper.selectList(
                Wrappers.lambdaQuery(StudyChapterDO.class)
                        .in(StudyChapterDO::getModuleId, moduleIds)
                        .orderByAsc(StudyChapterDO::getSortOrder)
        );

        List<Long> chapterIds = chapters.stream().map(StudyChapterDO::getId).collect(Collectors.toList());

        // 查询所有文档标题
        List<StudyDocumentDO> documents = new ArrayList<>();
        if (!chapterIds.isEmpty()) {
            documents = studyDocumentMapper.selectList(
                    Wrappers.lambdaQuery(StudyDocumentDO.class)
                            .in(StudyDocumentDO::getChapterId, chapterIds)
                            .select(StudyDocumentDO::getId, StudyDocumentDO::getTitle, StudyDocumentDO::getChapterId)
            );
        }

        // 按章节ID分组文档
        Map<Long, List<StudyModuleTreeVO.DocumentNode>> docMap = documents.stream()
                .collect(Collectors.groupingBy(
                        StudyDocumentDO::getChapterId,
                        Collectors.mapping(
                                doc -> StudyModuleTreeVO.DocumentNode.builder()
                                        .id(doc.getId())
                                        .title(doc.getTitle())
                                        .build(),
                                Collectors.toList()
                        )
                ));

        // 按模块ID分组章节
        Map<Long, List<StudyModuleTreeVO.ChapterNode>> chapterMap = chapters.stream()
                .collect(Collectors.groupingBy(
                        StudyChapterDO::getModuleId,
                        Collectors.mapping(
                                ch -> StudyModuleTreeVO.ChapterNode.builder()
                                        .id(ch.getId())
                                        .title(ch.getTitle())
                                        .documents(docMap.getOrDefault(ch.getId(), new ArrayList<>()))
                                        .build(),
                                Collectors.toList()
                        )
                ));

        // 组装树形结构
        return modules.stream()
                .map(module -> StudyModuleTreeVO.builder()
                        .id(module.getId())
                        .name(module.getName())
                        .icon(module.getIcon())
                        .chapters(chapterMap.getOrDefault(module.getId(), new ArrayList<>()))
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public void createModule(StudyModuleCreateRequest request) {
        StudyModuleDO moduleDO = StudyModuleDO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .icon(request.getIcon())
                .sortOrder(request.getSortOrder())
                .enabled(request.getEnabled())
                .deleted(0)
                .build();
        studyModuleMapper.insert(moduleDO);
    }

    @Override
    public void updateModule(StudyModuleUpdateRequest request) {
        StudyModuleDO moduleDO = new StudyModuleDO();
        moduleDO.setId(request.getId());
        moduleDO.setName(request.getName());
        moduleDO.setDescription(request.getDescription());
        moduleDO.setIcon(request.getIcon());
        moduleDO.setSortOrder(request.getSortOrder());
        moduleDO.setEnabled(request.getEnabled());
        studyModuleMapper.updateById(moduleDO);
    }

    @Override
    public void deleteModule(Long id) {
        studyModuleMapper.deleteById(id);
    }
}
