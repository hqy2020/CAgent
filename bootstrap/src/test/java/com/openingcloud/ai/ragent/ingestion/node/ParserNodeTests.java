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

package com.openingcloud.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.core.parser.DocumentParser;
import com.openingcloud.ai.ragent.core.parser.DocumentParserSelector;
import com.openingcloud.ai.ragent.core.parser.ParseResult;
import com.openingcloud.ai.ragent.core.parser.ParserType;
import com.openingcloud.ai.ragent.ingestion.domain.context.DocumentSource;
import com.openingcloud.ai.ragent.ingestion.domain.context.IngestionContext;
import com.openingcloud.ai.ragent.ingestion.domain.enums.SourceType;
import com.openingcloud.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParserNodeTests {

    @Test
    void shouldHandleParserRuleWithoutOptions() {
        DocumentParserSelector parserSelector = mock(DocumentParserSelector.class);
        DocumentParser parser = new DocumentParser() {
            @Override
            public String getParserType() {
                return ParserType.TIKA.getType();
            }

            @Override
            public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
                return ParseResult.of("# title\n\ncontent", Map.of());
            }

            @Override
            public String extractText(InputStream stream, String fileName) {
                return "# title\n\ncontent";
            }
        };
        when(parserSelector.selectByMimeType(anyString())).thenReturn(parser);

        ParserNode parserNode = new ParserNode(new ObjectMapper(), parserSelector);
        NodeConfig config = NodeConfig.builder()
                .nodeId("parser-1")
                .nodeType("parser")
                .settings(new ObjectMapper().valueToTree(Map.of(
                        "rules", java.util.List.of(Map.of("mimeType", "ALL"))
                )))
                .build();
        IngestionContext context = IngestionContext.builder()
                .rawBytes("# title\n\ncontent".getBytes(StandardCharsets.UTF_8))
                .mimeType("text/markdown")
                .source(DocumentSource.builder().type(SourceType.FILE).fileName("sample.md").build())
                .build();

        assertTrue(parserNode.execute(context, config).isSuccess());
    }
}
