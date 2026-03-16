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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.framework.exception.ClientException;
import com.openingcloud.ai.ragent.core.parser.TextCleanupOptions;
import com.openingcloud.ai.ragent.core.parser.TextCleanupUtil;
import com.openingcloud.ai.ragent.ingestion.domain.context.IngestionContext;
import com.openingcloud.ai.ragent.ingestion.domain.context.StructuredDocument;
import com.openingcloud.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.openingcloud.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.openingcloud.ai.ragent.ingestion.domain.result.NodeResult;
import com.openingcloud.ai.ragent.ingestion.domain.settings.ParserSettings;
import com.openingcloud.ai.ragent.ingestion.util.MimeTypeDetector;
import com.openingcloud.ai.ragent.core.parser.DocumentParser;
import com.openingcloud.ai.ragent.core.parser.DocumentParserSelector;
import com.openingcloud.ai.ragent.core.parser.ParseResult;
import com.openingcloud.ai.ragent.core.parser.ParserType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档解析节点
 * 负责将输入的字节流（如 PDF、Word、Excel 等）解析为结构化的文本或文档对象
 */
@Component
public class ParserNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final DocumentParserSelector parserSelector;

    public ParserNode(ObjectMapper objectMapper, DocumentParserSelector parserSelector) {
        this.objectMapper = objectMapper;
        this.parserSelector = parserSelector;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.PARSER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        if (context.getRawBytes() == null || context.getRawBytes().length == 0) {
            return NodeResult.fail(new ClientException("解析器缺少原始字节"));
        }

        String mimeType = context.getMimeType();
        if (!StringUtils.hasText(mimeType)) {
            String fileName = context.getSource() == null ? null : context.getSource().getFileName();
            mimeType = MimeTypeDetector.detect(context.getRawBytes(), fileName);
            context.setMimeType(mimeType);
        }

        ParserSettings settings = parseSettings(config.getSettings());
        String fileName = context.getSource() == null ? null : context.getSource().getFileName();

        // 验证文件类型是否符合配置
        validateMimeType(settings, mimeType, fileName);

        ParserSettings.ParserRule rule = matchRule(settings, mimeType, fileName);
        DocumentParser parser = resolveParser(rule, mimeType);
        if (parser == null) {
            return NodeResult.fail(new ClientException("未找到可用文档解析器"));
        }

        Map<String, Object> options = rule == null ? Collections.emptyMap() : rule.getOptions();
        ParseResult result = parser.parse(context.getRawBytes(), mimeType, options);
        TextCleanupOptions cleanupOptions = resolveCleanupOptions(options, mimeType, fileName);
        String cleanedText = TextCleanupUtil.cleanup(result.text(), cleanupOptions);
        context.setRawText(cleanedText);

        // 将 ParseResult 转换为 StructuredDocument
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("cleanupProfile", cleanupOptions.getProfile());
        metadata.put("parserType", parser.getParserType());
        StructuredDocument document = StructuredDocument.builder()
                .text(cleanedText)
                .metadata(metadata)
                .build();
        context.setDocument(document);
        if (context.getMetadata() == null) {
            context.setMetadata(new LinkedHashMap<>());
        }
        context.getMetadata().putAll(metadata);

        return NodeResult.ok("解析文本长度=" + cleanedText.length());
    }

    /**
     * 验证文件类型是否符合配置的规则
     * 如果配置了规则但文件类型不匹配，则抛出异常
     */
    private void validateMimeType(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            // 没有配置规则，允许所有类型
            return;
        }

        String resolvedType = resolveType(mimeType, fileName);

        // 检查是否有匹配的规则
        boolean hasMatch = false;
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                hasMatch = true;
                break;
            }
        }

        if (!hasMatch) {
            // 构建允许的类型列表用于错误提示
            List<String> allowedTypes = settings.getRules().stream()
                    .filter(rule -> rule != null && StringUtils.hasText(rule.getMimeType()))
                    .map(rule -> normalizeType(rule.getMimeType()))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();

            throw new ClientException(
                    String.format("文件类型不符合要求。当前文件类型: %s，允许的类型: %s",
                            resolvedType,
                            String.join(", ", allowedTypes))
            );
        }
    }

    private ParserSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return ParserSettings.builder().rules(List.of()).build();
        }
        return objectMapper.convertValue(node, ParserSettings.class);
    }

    private DocumentParser resolveParser(ParserSettings.ParserRule rule, String mimeType) {
        Map<String, Object> options = rule == null || rule.getOptions() == null
                ? Collections.emptyMap()
                : rule.getOptions();
        Object parserType = options.get("parserType");
        if (parserType instanceof String parserTypeValue && StringUtils.hasText(parserTypeValue)) {
            DocumentParser parser = parserSelector.select(resolveParserType(parserTypeValue));
            if (parser != null) {
                return parser;
            }
        }
        return parserSelector.selectByMimeType(mimeType);
    }

    private String resolveParserType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return ParserType.TIKA.getType();
        }
        String normalized = raw.trim();
        for (ParserType parserType : ParserType.values()) {
            if (parserType.getType().equalsIgnoreCase(normalized)
                    || parserType.name().equalsIgnoreCase(normalized)) {
                return parserType.getType();
            }
        }
        throw new ClientException("未知解析器类型: " + raw);
    }

    private TextCleanupOptions resolveCleanupOptions(Map<String, Object> options, String mimeType, String fileName) {
        String resolvedType = resolveType(mimeType, fileName);
        String cleanupProfile = options == null ? null : asText(options.get("cleanupProfile"));
        TextCleanupOptions cleanupOptions = parseCleanupOptions(options == null ? null : options.get("cleanup"));
        if (!StringUtils.hasText(cleanupProfile) && cleanupOptions.getProfile() == null) {
            if ("MARKDOWN".equalsIgnoreCase(resolvedType)) {
                cleanupOptions.setProfile(TextCleanupOptions.PROFILE_MARKDOWN_STANDARD);
            } else if ("PDF".equalsIgnoreCase(resolvedType)) {
                cleanupOptions.setProfile(TextCleanupOptions.PROFILE_PDF_STANDARD);
            } else {
                cleanupOptions.setProfile(TextCleanupOptions.PROFILE_DEFAULT);
            }
        } else if (StringUtils.hasText(cleanupProfile)) {
            cleanupOptions.setProfile(cleanupProfile.trim());
        }
        return cleanupOptions;
    }

    private TextCleanupOptions parseCleanupOptions(Object raw) {
        if (raw == null) {
            return TextCleanupOptions.builder().build();
        }
        return objectMapper.convertValue(raw, TextCleanupOptions.class);
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private ParserSettings.ParserRule matchRule(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            return null;
        }
        String resolvedType = resolveType(mimeType, fileName);
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                return rule;
            }
        }
        return null;
    }

    private String resolveType(String mimeType, String fileName) {
        String byName = resolveTypeByName(fileName);
        if (StringUtils.hasText(byName)) {
            return byName;
        }
        if (!StringUtils.hasText(mimeType)) {
            return "UNKNOWN";
        }
        String lower = mimeType.trim().toLowerCase();
        if (lower.contains("pdf")) {
            return "PDF";
        }
        if (lower.contains("markdown")) {
            return "MARKDOWN";
        }
        if (lower.contains("word") || lower.contains("msword") || lower.contains("wordprocessingml")) {
            return "WORD";
        }
        if (lower.contains("excel") || lower.contains("spreadsheetml")) {
            return "EXCEL";
        }
        if (lower.contains("powerpoint") || lower.contains("presentation")) {
            return "PPT";
        }
        if (lower.startsWith("image/")) {
            return "IMAGE";
        }
        if (lower.startsWith("text/")) {
            return "TEXT";
        }
        return "UNKNOWN";
    }

    private String resolveTypeByName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "MARKDOWN";
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "WORD";
        }
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            return "EXCEL";
        }
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "PPT";
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return "IMAGE";
        }
        if (lower.endsWith(".txt")) {
            return "TEXT";
        }
        return null;
    }

    private String normalizeType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "*", "ALL", "DEFAULT" -> "ALL";
            case "MD", "MARKDOWN" -> "MARKDOWN";
            case "DOC", "DOCX", "WORD" -> "WORD";
            case "XLS", "XLSX", "EXCEL" -> "EXCEL";
            case "PPT", "PPTX", "POWERPOINT" -> "PPT";
            case "TXT", "TEXT" -> "TEXT";
            case "PNG", "JPG", "JPEG", "GIF", "BMP", "WEBP", "IMAGE", "IMG" -> "IMAGE";
            case "PDF" -> "PDF";
            default -> value;
        };
    }
}
