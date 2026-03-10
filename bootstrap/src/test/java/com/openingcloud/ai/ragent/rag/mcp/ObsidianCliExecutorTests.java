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

package com.openingcloud.ai.ragent.rag.mcp;

import com.openingcloud.ai.ragent.rag.config.ObsidianProperties;
import com.openingcloud.ai.ragent.rag.core.mcp.executor.obsidian.ObsidianCliExecutor;
import com.openingcloud.ai.ragent.rag.core.mcp.executor.obsidian.ObsidianExternalMcpGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObsidianCliExecutorTests {

    private static final String DAILY_DIR = "2-Resource（参考资源）/80_生活记录/DailyNote/日记";

    @TempDir
    Path tempDir;

    @Test
    void shouldVerifyDailyAppendSuccess() throws IOException {
        ObsidianCliExecutor executor = new ObsidianCliExecutor(buildProperties(tempDir));

        ObsidianCliExecutor.CliResult result = executor.execute("daily:append",
                List.of("date=2026-03-04", "content=- [ ] 语义修复验证"));

        assertTrue(result.isSuccess());
        Path dailyFile = tempDir.resolve(DAILY_DIR).resolve("2026-03-04.md");
        assertTrue(Files.exists(dailyFile));
        String text = Files.readString(dailyFile, StandardCharsets.UTF_8);
        assertTrue(text.contains("- [ ] 语义修复验证"));
    }

    @Test
    void shouldReturnWriteVerifyFailedWhenVerificationDoesNotMatch() {
        ObsidianCliExecutor executor = new BrokenVerifyExecutor(buildProperties(tempDir));

        ObsidianCliExecutor.CliResult result = executor.execute("daily:append",
                List.of("date=2026-03-04", "content=- [ ] 校验失败"));

        assertFalse(result.isSuccess());
        assertTrue(result.stderr().startsWith("WRITE_VERIFY_FAILED:"));
    }

    @Test
    void shouldFallbackToLocalModeWhenExternalCallFails() throws IOException {
        Path note = tempDir.resolve("demo.md");
        Files.writeString(note, "本地内容", StandardCharsets.UTF_8);

        ObsidianExternalMcpGateway gateway = mock(ObsidianExternalMcpGateway.class);
        when(gateway.tryExecute(eq("read"), anyMap())).thenReturn(
                ObsidianExternalMcpGateway.ExternalExecuteResult.builder()
                        .attempted(true)
                        .success(false)
                        .errorCode("TIMEOUT")
                        .errorMessage("external timeout")
                        .build()
        );
        when(gateway.externalOnly()).thenReturn(false);

        ObsidianCliExecutor executor = new ObsidianCliExecutor(buildProperties(tempDir), gateway);
        ObsidianCliExecutor.CliResult result = executor.execute("read", List.of("file=demo"));

        assertTrue(result.isSuccess());
        assertTrue(result.stdout().contains("本地内容"));
        assertTrue(result.stdout().contains("[fallback]"));
        assertTrue(result.stdout().contains("error=TIMEOUT"));
    }

    @Test
    void shouldFailFastWhenExternalOnlyModeAndExternalCallFails() {
        ObsidianExternalMcpGateway gateway = mock(ObsidianExternalMcpGateway.class);
        when(gateway.tryExecute(eq("read"), anyMap())).thenReturn(
                ObsidianExternalMcpGateway.ExternalExecuteResult.builder()
                        .attempted(true)
                        .success(false)
                        .errorCode("PROCESS_ERROR")
                        .errorMessage("external unavailable")
                        .build()
        );
        when(gateway.externalOnly()).thenReturn(true);

        ObsidianCliExecutor executor = new ObsidianCliExecutor(buildProperties(tempDir), gateway);
        ObsidianCliExecutor.CliResult result = executor.execute("read", List.of("file=missing"));

        assertFalse(result.isSuccess());
        assertTrue(result.stderr().contains("EXTERNAL_MCP_ERROR"));
    }

    @Test
    void shouldReturnExternalResultDirectlyWhenExternalCallSucceeds() {
        ObsidianExternalMcpGateway gateway = mock(ObsidianExternalMcpGateway.class);
        when(gateway.tryExecute(eq("read"), anyMap())).thenReturn(
                ObsidianExternalMcpGateway.ExternalExecuteResult.builder()
                        .attempted(true)
                        .success(true)
                        .textResult("external content")
                        .build()
        );

        ObsidianCliExecutor executor = new ObsidianCliExecutor(buildProperties(tempDir), gateway);
        ObsidianCliExecutor.CliResult result = executor.execute("read", List.of("file=any"));

        assertTrue(result.isSuccess());
        assertEquals("external content", result.stdout());
    }

    @Test
    void shouldBlockPathTraversalReadAttempt() {
        ObsidianCliExecutor executor = new ObsidianCliExecutor(buildProperties(tempDir));

        ObsidianCliExecutor.CliResult result = executor.execute("read", List.of("path=../../etc/passwd"));

        assertFalse(result.isSuccess());
        assertTrue(result.stderr().startsWith("SECURITY_VIOLATION:"));
    }

    @Test
    void shouldBlockHiddenDirectoryWriteAttempt() {
        ObsidianCliExecutor executor = new ObsidianCliExecutor(buildProperties(tempDir));

        ObsidianCliExecutor.CliResult result = executor.execute(
                "create",
                List.of("name=demo", "path=.obsidian/plugins", "content=test")
        );

        assertFalse(result.isSuccess());
        assertTrue(result.stderr().startsWith("SECURITY_VIOLATION:"));
    }

    private ObsidianProperties buildProperties(Path vaultPath) {
        ObsidianProperties properties = new ObsidianProperties();
        properties.setEnabled(true);
        properties.setVaultPath(vaultPath.toString());
        return properties;
    }

    private static class BrokenVerifyExecutor extends ObsidianCliExecutor {

        private BrokenVerifyExecutor(ObsidianProperties properties) {
            super(properties);
        }

        @Override
        protected String readFileForVerification(Path path) {
            return "tampered";
        }
    }
}
