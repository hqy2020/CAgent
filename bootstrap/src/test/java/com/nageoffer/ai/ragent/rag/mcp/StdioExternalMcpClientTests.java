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

package com.nageoffer.ai.ragent.rag.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.config.ExternalMcpProperties;
import com.nageoffer.ai.ragent.rag.config.RagMcpExecutionProperties;
import com.nageoffer.ai.ragent.rag.core.mcp.external.ExternalMcpCallRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.external.ExternalMcpCallResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.external.StdioExternalMcpClient;
import com.nageoffer.ai.ragent.rag.core.mcp.governance.MCPToolHealthStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StdioExternalMcpClientTests {

    @Test
    void shouldRejectUnknownServerCommand() {
        StdioExternalMcpClient client = new StdioExternalMcpClient(properties(), new ObjectMapper(), healthStore());

        ExternalMcpCallResponse response = client.callTool(ExternalMcpCallRequest.builder()
                .serverCommand("rm -rf /")
                .toolName("obsidian_get_file_contents")
                .arguments(Map.of())
                .timeoutSeconds(5)
                .build());

        assertEquals("SECURITY_VIOLATION", response.errorCode());
    }

    @Test
    void shouldRejectNonAllowlistedCwd() {
        ExternalMcpProperties properties = properties();
        String allowedCommand = properties.getObsidian().getCommand() + " " + String.join(" ", properties.getObsidian().getArgs());
        StdioExternalMcpClient client = new StdioExternalMcpClient(properties, new ObjectMapper(), healthStore());

        ExternalMcpCallResponse response = client.callTool(ExternalMcpCallRequest.builder()
                .serverCommand(allowedCommand.trim())
                .toolName("obsidian_get_file_contents")
                .arguments(Map.of())
                .cwd("/tmp")
                .timeoutSeconds(5)
                .build());

        assertEquals("SECURITY_VIOLATION", response.errorCode());
    }

    @Test
    void shouldRejectEnvOutsideAllowlist() {
        ExternalMcpProperties properties = properties();
        String allowedCommand = properties.getObsidian().getCommand() + " " + String.join(" ", properties.getObsidian().getArgs());
        StdioExternalMcpClient client = new StdioExternalMcpClient(properties, new ObjectMapper(), healthStore());

        ExternalMcpCallResponse response = client.callTool(ExternalMcpCallRequest.builder()
                .serverCommand(allowedCommand.trim())
                .toolName("obsidian_get_file_contents")
                .arguments(Map.of())
                .env(Map.of("UNSAFE_TOKEN", "123"))
                .timeoutSeconds(5)
                .build());

        assertEquals("SECURITY_VIOLATION", response.errorCode());
    }

    private ExternalMcpProperties properties() {
        ExternalMcpProperties properties = new ExternalMcpProperties();
        properties.getObsidian().setCommand("npx");
        properties.getObsidian().setArgs(List.of("-y", "mcp-obsidian"));
        properties.getObsidian().setEnv(Map.of("OBSIDIAN_VAULT_PATH", "/safe/vault"));
        properties.getBrowsermcp().setCommand("npx");
        properties.getBrowsermcp().setArgs(List.of("-y", "@browsermcp/server"));
        return properties;
    }

    private MCPToolHealthStore healthStore() {
        return new MCPToolHealthStore(new RagMcpExecutionProperties());
    }
}
