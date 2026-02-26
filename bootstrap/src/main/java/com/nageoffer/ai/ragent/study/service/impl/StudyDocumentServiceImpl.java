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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.study.controller.request.StudyDocumentCreateRequest;
import com.nageoffer.ai.ragent.study.controller.request.StudyDocumentUpdateRequest;
import com.nageoffer.ai.ragent.study.controller.vo.StudyDocumentListVO;
import com.nageoffer.ai.ragent.study.controller.vo.StudyDocumentVO;
import com.nageoffer.ai.ragent.study.dao.entity.StudyDocumentDO;
import com.nageoffer.ai.ragent.study.dao.mapper.StudyDocumentMapper;
import com.nageoffer.ai.ragent.study.service.StudyDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudyDocumentServiceImpl implements StudyDocumentService {

    private final StudyDocumentMapper studyDocumentMapper;

    @Override
    public IPage<StudyDocumentListVO> pageDocuments(int pageNo, int pageSize, Long chapterId, Long moduleId) {
        LambdaQueryWrapper<StudyDocumentDO> queryWrapper = Wrappers.lambdaQuery(StudyDocumentDO.class)
                .eq(chapterId != null, StudyDocumentDO::getChapterId, chapterId)
                .eq(moduleId != null, StudyDocumentDO::getModuleId, moduleId)
                .orderByDesc(StudyDocumentDO::getCreateTime);

        Page<StudyDocumentDO> page = new Page<>(pageNo, pageSize);
        IPage<StudyDocumentDO> result = studyDocumentMapper.selectPage(page, queryWrapper);
        return result.convert(each -> BeanUtil.toBean(each, StudyDocumentListVO.class));
    }

    @Override
    public StudyDocumentVO getById(Long id) {
        StudyDocumentDO documentDO = studyDocumentMapper.selectById(id);
        if (documentDO == null) {
            return null;
        }
        return BeanUtil.toBean(documentDO, StudyDocumentVO.class);
    }

    @Override
    public void createDocument(StudyDocumentCreateRequest request) {
        StudyDocumentDO documentDO = StudyDocumentDO.builder()
                .chapterId(request.getChapterId())
                .moduleId(request.getModuleId())
                .title(request.getTitle())
                .content(request.getContent())
                .deleted(0)
                .build();
        studyDocumentMapper.insert(documentDO);
    }

    @Override
    public void updateDocument(StudyDocumentUpdateRequest request) {
        StudyDocumentDO documentDO = new StudyDocumentDO();
        documentDO.setId(request.getId());
        documentDO.setTitle(request.getTitle());
        documentDO.setContent(request.getContent());
        studyDocumentMapper.updateById(documentDO);
    }

    @Override
    public void deleteDocument(Long id) {
        studyDocumentMapper.deleteById(id);
    }
}
