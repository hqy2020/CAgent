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

package com.openingcloud.ai.ragent.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * AI 模型候选配置实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_ai_model_candidate")
public class AIModelCandidateDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 模型唯一标识，如 minimax-m2.5
     */
    private String modelId;

    /**
     * 模型类型：chat / embedding / rerank
     */
    private String modelType;

    /**
     * 关联提供方标识
     */
    private String providerKey;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 自定义 URL
     */
    private String customUrl;

    /**
     * 向量维度（embedding 专用）
     */
    private Integer dimension;

    /**
     * 优先级，数值越小越高
     */
    private Integer priority;

    /**
     * 是否启用
     */
    private Integer enabled;

    /**
     * 是否支持思考链（chat 专用）
     */
    private Integer supportsThinking;

    /**
     * 是否为默认模型
     */
    private Integer isDefault;

    /**
     * 是否为深度思考默认模型
     */
    private Integer isDeepThinking;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
