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

import com.nageoffer.ai.ragent.rag.core.mcp.DefaultMCPToolRegistry;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MCPRegistryAndServiceTests {

    private MCPToolExecutor createExecutor(String toolId, String name) {
        return new MCPToolExecutor() {
            @Override
            public MCPTool getToolDefinition() {
                return MCPTool.builder().toolId(toolId).name(name)
                        .description("Test tool " + name).build();
            }

            @Override
            public MCPResponse execute(MCPRequest request) {
                return MCPResponse.builder().success(true)
                        .textResult("Executed " + toolId).build();
            }
        };
    }

    @Test
    void testAutoDiscoveryRegistersAllExecutors() {
        MCPToolExecutor exec1 = createExecutor("tool-1", "Tool One");
        MCPToolExecutor exec2 = createExecutor("tool-2", "Tool Two");

        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec1, exec2));
        registry.init();

        assertEquals(2, registry.size());
        assertTrue(registry.contains("tool-1"));
        assertTrue(registry.contains("tool-2"));
    }

    @Test
    void testGetExecutorByToolId() {
        MCPToolExecutor exec = createExecutor("attendance", "Attendance Query");
        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec));
        registry.init();

        Optional<MCPToolExecutor> found = registry.getExecutor("attendance");
        assertTrue(found.isPresent());
        assertEquals("attendance", found.get().getToolId());

        Optional<MCPToolExecutor> notFound = registry.getExecutor("nonexistent");
        assertFalse(notFound.isPresent());
    }

    @Test
    void testUnregisterRemovesTool() {
        MCPToolExecutor exec = createExecutor("temp-tool", "Temp");
        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec));
        registry.init();

        assertTrue(registry.contains("temp-tool"));
        registry.unregister("temp-tool");
        assertFalse(registry.contains("temp-tool"));
        assertEquals(0, registry.size());
    }

    @Test
    void testListAllTools() {
        MCPToolExecutor exec1 = createExecutor("t1", "Tool1");
        MCPToolExecutor exec2 = createExecutor("t2", "Tool2");
        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec1, exec2));
        registry.init();

        List<MCPTool> tools = registry.listAllTools();
        assertEquals(2, tools.size());

        List<MCPToolExecutor> executors = registry.listAllExecutors();
        assertEquals(2, executors.size());
    }

    @Test
    void testRegisterOverwritesExisting() {
        MCPToolExecutor exec1 = createExecutor("dup", "Version1");
        MCPToolExecutor exec2 = createExecutor("dup", "Version2");

        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(exec1));
        registry.init();
        registry.register(exec2);

        assertEquals(1, registry.size());
        assertEquals("Version2", registry.getExecutor("dup").get().getToolDefinition().getName());
    }

    @Test
    void testToolExecutorSupportsMethod() {
        MCPToolExecutor exec = createExecutor("my-tool", "My Tool");
        MCPRequest matching = MCPRequest.builder().toolId("my-tool").build();
        MCPRequest nonMatching = MCPRequest.builder().toolId("other-tool").build();

        assertTrue(exec.supports(matching));
        assertFalse(exec.supports(nonMatching));
    }
}
