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

package com.openingcloud.ai.ragent.rag.core.mcp.annotation;

import com.openingcloud.ai.ragent.rag.core.mcp.MCPRequest;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPResponse;
import com.openingcloud.ai.ragent.rag.core.mcp.MCPToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * MCP 工具注解处理器
 * <p>
 * 实现 {@link BeanPostProcessor}，在 Bean 初始化后扫描 {@link MCPToolDeclare} 注解，
 * 查找 {@link MCPExecute} 方法并创建 {@link AnnotatedMCPToolAdapter} 注册到 {@link MCPToolRegistry}。
 * <p>
 * 与接口式 {@code MCPToolExecutor} 的自动注册互不干扰，两条路径注册到同一个 Registry。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MCPToolAnnotationProcessor implements BeanPostProcessor {

    private final MCPToolRegistry toolRegistry;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        MCPToolDeclare annotation = AnnotationUtils.findAnnotation(bean.getClass(), MCPToolDeclare.class);
        if (annotation == null) {
            return bean;
        }

        Method executeMethod = findExecuteMethod(bean.getClass(), beanName);
        if (executeMethod == null) {
            return bean;
        }

        AnnotatedMCPToolAdapter adapter = new AnnotatedMCPToolAdapter(bean, annotation, executeMethod);
        toolRegistry.register(adapter);

        log.info("MCP 注解式工具注册成功, toolId: {}, beanName: {}, 工具名称: {}",
                annotation.toolId(), beanName, annotation.name());

        return bean;
    }

    private Method findExecuteMethod(Class<?> clazz, String beanName) {
        Method found = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(MCPExecute.class)) {
                if (found != null) {
                    log.error("Bean {} 存在多个 @MCPExecute 方法，仅允许一个，跳过注册", beanName);
                    return null;
                }
                // 验证方法签名
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length != 1 || !MCPRequest.class.isAssignableFrom(paramTypes[0])) {
                    log.error("Bean {} 的 @MCPExecute 方法参数签名不合法，期望 (MCPRequest)，跳过注册", beanName);
                    return null;
                }
                if (!MCPResponse.class.isAssignableFrom(method.getReturnType())) {
                    log.error("Bean {} 的 @MCPExecute 方法返回类型不合法，期望 MCPResponse，跳过注册", beanName);
                    return null;
                }
                found = method;
            }
        }

        if (found == null) {
            log.error("Bean {} 标注了 @MCPToolDeclare 但未找到 @MCPExecute 方法，跳过注册", beanName);
        }
        return found;
    }
}
