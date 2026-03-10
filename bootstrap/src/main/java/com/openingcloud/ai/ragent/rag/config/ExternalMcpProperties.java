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

package com.openingcloud.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 MCP 服务配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.external-mcp")
public class ExternalMcpProperties {

    /**
     * Node MCP Bridge 脚本路径（相对项目根目录）。
     */
    private String bridgeScriptPath = "scripts/mcp-bridge/call.mjs";

    /**
     * Node MCP Bridge 工作目录（相对项目根目录）。
     */
    private String bridgeWorkingDir = "scripts/mcp-bridge";

    /**
     * 是否自动安装 bridge 依赖。
     */
    private boolean autoInstallBridgeDeps = true;

    /**
     * 安装 bridge 依赖超时时间。
     */
    private int bridgeInstallTimeoutSeconds = 120;

    private ExternalServerConfig obsidian = new ExternalServerConfig();

    private BrowserMcpConfig browsermcp = new BrowserMcpConfig();

    @Data
    public static class ExternalServerConfig {

        private boolean enabled = false;

        /**
         * 默认使用 stdio 命令启动外部 MCP server。
         */
        private String command = "";

        private List<String> args = new ArrayList<>();

        private Map<String, String> env = new LinkedHashMap<>();

        private int timeoutSeconds = 30;

        /**
         * external-first / local-only / external-only
         */
        private String mode = "external-first";
    }

    @Data
    public static class BrowserMcpConfig extends ExternalServerConfig {

        /**
         * llm / strict
         */
        private String fallbackPolicy = "llm";
    }
}
