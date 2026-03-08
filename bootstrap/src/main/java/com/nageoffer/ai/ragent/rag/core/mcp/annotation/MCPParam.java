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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP 工具参数定义注解
 * <p>
 * 作为 {@link MCPToolDeclare#parameters()} 的嵌套注解使用，
 * 描述工具的输入参数。
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface MCPParam {

    /**
     * 参数名称
     */
    String name();

    /**
     * 参数描述
     */
    String description();

    /**
     * 参数类型：string, number, boolean, array, object
     */
    String type() default "string";

    /**
     * 是否必填
     */
    boolean required() default false;

    /**
     * 默认值（空字符串表示无默认值）
     */
    String defaultValue() default "";

    /**
     * 示例值
     */
    String example() default "";

    /**
     * 格式校验正则
     */
    String pattern() default "";

    /**
     * 枚举值（可选）
     */
    String[] enumValues() default {};
}
