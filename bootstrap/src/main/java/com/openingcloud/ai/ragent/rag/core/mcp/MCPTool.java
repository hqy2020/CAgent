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

package com.openingcloud.ai.ragent.rag.core.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具定义
 * <p>
 * 描述一个可被调用的外部工具/API，包含工具元信息和参数定义
 * 类似于 Function Calling 中的 function definition
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPTool {

    public enum OperationType {
        READ,
        WRITE
    }

    public enum Sensitivity {
        LOW,
        MEDIUM,
        HIGH
    }

    /**
     * 工具唯一标识
     * 例如：web_news_search、obsidian_read、obsidian_update
     */
    private String toolId;

    /**
     * 工具名称（用于展示）
     */
    private String name;

    /**
     * 工具描述（用于 LLM 理解工具用途）
     */
    private String description;

    /**
     * 何时应优先使用该工具
     */
    private String useWhen;

    /**
     * 哪些场景不应使用该工具
     */
    private String avoidWhen;

    /**
     * 示例问题（帮助意图识别匹配）
     */
    private List<String> examples;

    /**
     * 参数定义
     * key: 参数名, value: 参数描述
     */
    private Map<String, ParameterDef> parameters;

    /**
     * 场景关键词（供编排器和规则层快速匹配）
     */
    @Builder.Default
    private List<String> sceneKeywords = List.of();

    /**
     * 工具操作类型
     */
    @Builder.Default
    private OperationType operationType = OperationType.READ;

    /**
     * 是否需要用户身份（调用时自动注入 userId）
     */
    @Builder.Default
    private boolean requireUserId = false;

    /**
     * 是否需要显式确认后才能执行
     */
    @Builder.Default
    private boolean confirmationRequired = false;

    /**
     * 单次执行超时时间（秒）
     */
    @Builder.Default
    private int timeoutSeconds = 15;

    /**
     * 瞬时错误最大重试次数
     */
    @Builder.Default
    private int maxRetries = 0;

    /**
     * 敏感级别（影响执行门槛和日志）
     */
    @Builder.Default
    private Sensitivity sensitivity = Sensitivity.MEDIUM;

    /**
     * 需要脱敏的参数名
     */
    @Builder.Default
    private List<String> sensitiveParams = List.of();

    /**
     * 降级提示文案
     */
    private String fallbackMessage;

    /**
     * 是否允许使用缓存降级
     */
    @Builder.Default
    private boolean cacheableFallback = false;

    /**
     * 缓存降级 TTL（秒）
     */
    @Builder.Default
    private int fallbackCacheTtlSeconds = 300;

    /**
     * 是否对模型暴露，兼容别名工具可隐藏。
     */
    @Builder.Default
    private boolean visibleToModel = true;

    /**
     * MCP Server 地址（可选，用于远程调用）
     */
    private String mcpServerUrl;

    /**
     * 参数定义
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterDef {

        /**
         * 参数描述
         */
        private String description;

        /**
         * 参数类型：string, number, boolean, array, object
         */
        @Builder.Default
        private String type = "string";

        /**
         * 是否必填
         */
        @Builder.Default
        private boolean required = false;

        /**
         * 默认值
         */
        private Object defaultValue;

        /**
         * 示例值
         */
        private String example;

        /**
         * 参数格式约束（正则）
         */
        private String pattern;

        /**
         * 枚举值（可选）
         */
        private List<String> enumValues;
    }
}
