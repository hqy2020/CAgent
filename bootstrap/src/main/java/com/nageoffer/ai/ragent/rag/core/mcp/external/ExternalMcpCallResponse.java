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

package com.nageoffer.ai.ragent.rag.core.mcp.external;

import lombok.Builder;

import java.util.Map;

@Builder
public record ExternalMcpCallResponse(
        boolean success,
        String textResult,
        String errorCode,
        String errorMessage,
        Map<String, Object> rawResult,
        String rawStdout,
        String rawStderr) {

    public static ExternalMcpCallResponse ok(String textResult,
                                             Map<String, Object> rawResult,
                                             String rawStdout) {
        return ExternalMcpCallResponse.builder()
                .success(true)
                .textResult(textResult)
                .rawResult(rawResult)
                .rawStdout(rawStdout)
                .build();
    }

    public static ExternalMcpCallResponse error(String errorCode,
                                                String errorMessage,
                                                String rawStdout,
                                                String rawStderr) {
        return ExternalMcpCallResponse.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .rawStdout(rawStdout)
                .rawStderr(rawStderr)
                .build();
    }
}
