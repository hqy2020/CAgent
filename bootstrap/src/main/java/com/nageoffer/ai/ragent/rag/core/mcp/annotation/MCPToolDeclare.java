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

package com.nageoffer.ai.ragent.rag.core.mcp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP 工具声明注解
 * <p>
 * 标注在类上，声明该类为一个 MCP 工具。
 * 配合 {@link MCPExecute} 标注执行方法，即可自动注册到 MCPToolRegistry。
 * 无需实现 MCPToolExecutor 接口。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MCPToolDeclare {

    /**
     * 工具唯一标识
     */
    String toolId();

    /**
     * 工具名称（用于展示）
     */
    String name();

    /**
     * 工具描述（用于 LLM 理解工具用途）
     */
    String description();

    /**
     * 示例问题（帮助意图识别匹配）
     */
    String[] examples() default {};

    /**
     * 是否需要用户身份
     */
    boolean requireUserId() default true;

    /**
     * 参数定义
     */
    MCPParam[] parameters() default {};
}
