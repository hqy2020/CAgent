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

package com.openingcloud.ai.ragent.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openingcloud.ai.ragent.rag.controller.vo.PromptTemplateVO;
import com.openingcloud.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.openingcloud.ai.ragent.rag.dao.entity.PromptTemplateDO;
import com.openingcloud.ai.ragent.rag.dao.mapper.PromptTemplateMapper;
import com.openingcloud.ai.ragent.rag.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 提示词模板管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateServiceImpl implements PromptTemplateService {

    private final PromptTemplateMapper promptTemplateMapper;
    private final PromptTemplateLoader promptTemplateLoader;

    @Override
    public List<PromptTemplateVO> listTemplates(String category) {
        LambdaQueryWrapper<PromptTemplateDO> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(category)) {
            wrapper.eq(PromptTemplateDO::getCategory, category);
        }
        wrapper.orderByAsc(PromptTemplateDO::getCategory).orderByAsc(PromptTemplateDO::getPromptKey);
        List<PromptTemplateDO> list = promptTemplateMapper.selectList(wrapper);
        return list.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public PromptTemplateVO getTemplate(String promptKey) {
        PromptTemplateDO entity = findByKey(promptKey);
        return convertToVO(entity);
    }

    @Override
    public PromptTemplateVO updateTemplate(String promptKey, String content) {
        PromptTemplateDO entity = findByKey(promptKey);
        entity.setContent(content);
        entity.setVersion(entity.getVersion() != null ? entity.getVersion() + 1 : 1);
        entity.setEnabled(1);
        promptTemplateMapper.updateById(entity);
        promptTemplateLoader.invalidateCache(promptKey);
        log.info("提示词模板已更新: key={}, version={}", promptKey, entity.getVersion());
        return convertToVO(entity);
    }

    @Override
    public void resetTemplate(String promptKey) {
        PromptTemplateDO entity = findByKey(promptKey);
        entity.setEnabled(0);
        promptTemplateMapper.updateById(entity);
        promptTemplateLoader.invalidateCache(promptKey);
        log.info("提示词模板已重置为文件版本: key={}", promptKey);
    }

    @Override
    public void toggleTemplate(String promptKey) {
        PromptTemplateDO entity = findByKey(promptKey);
        int newEnabled = (entity.getEnabled() != null && entity.getEnabled() == 1) ? 0 : 1;
        entity.setEnabled(newEnabled);
        promptTemplateMapper.updateById(entity);
        promptTemplateLoader.invalidateCache(promptKey);
        log.info("提示词模板启用状态已切换: key={}, enabled={}", promptKey, newEnabled);
    }

    private PromptTemplateDO findByKey(String promptKey) {
        PromptTemplateDO entity = promptTemplateMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplateDO>()
                        .eq(PromptTemplateDO::getPromptKey, promptKey));
        if (entity == null) {
            throw new IllegalArgumentException("提示词模板不存在: " + promptKey);
        }
        return entity;
    }

    private PromptTemplateVO convertToVO(PromptTemplateDO entity) {
        return PromptTemplateVO.builder()
                .id(String.valueOf(entity.getId()))
                .promptKey(entity.getPromptKey())
                .name(entity.getName())
                .category(entity.getCategory())
                .content(entity.getContent())
                .filePath(entity.getFilePath())
                .variables(entity.getVariables())
                .description(entity.getDescription())
                .version(entity.getVersion())
                .enabled(entity.getEnabled() != null && entity.getEnabled() == 1)
                .updatedBy(entity.getUpdatedBy())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }
}
