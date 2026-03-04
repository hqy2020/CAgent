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

import com.nageoffer.ai.ragent.rag.config.ObsidianProperties;
import com.nageoffer.ai.ragent.rag.core.mcp.executor.obsidian.ObsidianCliExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

