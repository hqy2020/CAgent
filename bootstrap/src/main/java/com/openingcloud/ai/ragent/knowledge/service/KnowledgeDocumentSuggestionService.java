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

package com.openingcloud.ai.ragent.knowledge.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openingcloud.ai.ragent.core.parser.DocumentParser;
import com.openingcloud.ai.ragent.core.parser.DocumentParserSelector;
import com.openingcloud.ai.ragent.core.parser.TextCleanupOptions;
import com.openingcloud.ai.ragent.core.parser.TextCleanupUtil;
import com.openingcloud.ai.ragent.framework.exception.ClientException;
import com.openingcloud.ai.ragent.knowledge.controller.request.KnowledgeDocumentSuggestRequest;
import com.openingcloud.ai.ragent.knowledge.controller.vo.KnowledgeDocumentSuggestionVO;
import com.openingcloud.ai.ragent.ingestion.controller.vo.IngestionPipelineVO;
import com.openingcloud.ai.ragent.ingestion.service.IngestionPipelineService;
import com.openingcloud.ai.ragent.ingestion.util.HttpClientHelper;
import com.openingcloud.ai.ragent.ingestion.util.MimeTypeDetector;
import com.openingcloud.ai.ragent.infra.chat.ChatClient;
import com.openingcloud.ai.ragent.infra.convention.ChatMessage;
import com.openingcloud.ai.ragent.infra.convention.ChatRequest;
import com.openingcloud.ai.ragent.infra.model.ModelSelector;
import com.openingcloud.ai.ragent.infra.model.ModelTarget;
import com.openingcloud.ai.ragent.infra.util.LLMResponseCleaner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档类型识别与默认数据通道推荐
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentSuggestionService {

    private static final int MAX_SAMPLE_CHARS = 4000;

    private static final Map<String, RecommendationSpec> RECOMMENDATIONS = Map.of(
            "knowledge_base", new RecommendationSpec("knowledge_base", "知识库", "知识库数据通道",
                    "recursive", 500, 50, null, null, null, null),
            "faq", new RecommendationSpec("faq", "FAQ", "FAQ 数据通道",
                    "recursive", 500, 50, null, null, null, null),
            "contract", new RecommendationSpec("contract", "合同", "合同数据通道",
                    "semantic", null, null, 800, 1000, 300, 120),
            "log", new RecommendationSpec("log", "日志", "日志数据通道",
                    "fixed_size", 500, 0, null, null, null, null),
            "ocr", new RecommendationSpec("ocr", "OCR", "OCR 数据通道",
                    "overlap", 500, 100, null, null, null, null),
            "html", new RecommendationSpec("html", "HTML", "HTML 数据通道",
                    "recursive", 500, 50, null, null, null, null),
            "code", new RecommendationSpec("code", "代码", "代码数据通道",
                    "recursive", 500, 50, null, null, null, null),
            "mixed", new RecommendationSpec("mixed", "混合", "混合文档数据通道",
                    "recursive", 500, 50, null, null, null, null)
    );

    private final DocumentParserSelector parserSelector;
    private final HttpClientHelper httpClientHelper;
    private final ModelSelector modelSelector;
    private final List<ChatClient> chatClients;
    private final ObjectMapper objectMapper;
    private final IngestionPipelineService pipelineService;

    public KnowledgeDocumentSuggestionVO suggest(KnowledgeDocumentSuggestRequest request, MultipartFile file) {
        SourcePayload source = resolveSource(request, file);
        String preview = extractPreview(source);
        ClassificationResult classification = classify(source, preview);
        RecommendationSpec recommendation = RECOMMENDATIONS.getOrDefault(
                classification.docType(),
                RECOMMENDATIONS.get("mixed")
        );
        IngestionPipelineVO pipeline = resolvePipeline(recommendation.pipelineName());

        return KnowledgeDocumentSuggestionVO.builder()
                .sourceType(source.sourceType())
                .fileName(source.fileName())
                .mimeType(source.mimeType())
                .docType(recommendation.key())
                .docTypeLabel(recommendation.label())
                .reason(classification.reason())
                .confidence(classification.confidence())
                .processMode("pipeline")
                .pipelineId(pipeline == null ? null : pipeline.getId())
                .pipelineName(recommendation.pipelineName())
                .chunkStrategy(recommendation.chunkStrategy())
                .chunkSize(recommendation.chunkSize())
                .overlapSize(recommendation.overlapSize())
                .targetChars(recommendation.targetChars())
                .maxChars(recommendation.maxChars())
                .minChars(recommendation.minChars())
                .overlapChars(recommendation.overlapChars())
                .build();
    }

    private SourcePayload resolveSource(KnowledgeDocumentSuggestRequest request, MultipartFile file) {
        if (file != null && !file.isEmpty()) {
            try {
                byte[] bytes = file.getBytes();
                String fileName = file.getOriginalFilename();
                String mimeType = MimeTypeDetector.detect(bytes, fileName);
                return new SourcePayload("file", fileName, mimeType, bytes);
            } catch (Exception e) {
                throw new ClientException("读取上传文件失败");
            }
        }
        if (request == null || !StringUtils.hasText(request.getSourceLocation())) {
            throw new ClientException("请先选择文件或填写来源地址");
        }
        HttpClientHelper.HttpFetchResponse response = httpClientHelper.get(request.getSourceLocation(), Map.of());
        String fileName = StringUtils.hasText(response.fileName()) ? response.fileName() : "remote-file";
        String mimeType = StringUtils.hasText(response.contentType())
                ? response.contentType()
                : MimeTypeDetector.detect(response.body(), fileName);
        return new SourcePayload("url", fileName, mimeType, response.body());
    }

    private String extractPreview(SourcePayload source) {
        if (source.bytes() == null || source.bytes().length == 0) {
            return "";
        }
        try {
            DocumentParser parser = parserSelector.selectByMimeType(source.mimeType());
            String raw = parser.parse(source.bytes(), source.mimeType(), Map.of()).text();
            TextCleanupOptions cleanupOptions = chooseCleanupProfile(source.mimeType(), source.fileName());
            String cleaned = TextCleanupUtil.cleanup(raw, cleanupOptions);
            if (cleaned.length() <= MAX_SAMPLE_CHARS) {
                return cleaned;
            }
            return cleaned.substring(0, MAX_SAMPLE_CHARS);
        } catch (Exception e) {
            log.warn("提取文档预览失败，回退到文件名启发式: fileName={}, mimeType={}",
                    source.fileName(), source.mimeType(), e);
            return source.fileName();
        }
    }

    private TextCleanupOptions chooseCleanupProfile(String mimeType, String fileName) {
        String lowerMime = mimeType == null ? "" : mimeType.toLowerCase();
        String lowerName = fileName == null ? "" : fileName.toLowerCase();
        if (lowerMime.contains("markdown") || lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) {
            return TextCleanupOptions.markdownStandard();
        }
        if (lowerMime.contains("pdf") || lowerName.endsWith(".pdf")) {
            return TextCleanupOptions.pdfStandard();
        }
        return TextCleanupOptions.defaultOptions();
    }

    private ClassificationResult classify(SourcePayload source, String preview) {
        try {
            ModelTarget target = modelSelector.selectChatCandidates(false).stream().findFirst().orElse(null);
            if (target == null) {
                return fallbackClassification(source, preview);
            }
            Map<String, ChatClient> clientMap = chatClients.stream()
                    .collect(Collectors.toMap(ChatClient::provider, Function.identity(), (left, right) -> left));
            ChatClient client = clientMap.get(target.candidate().getProvider());
            if (client == null) {
                return fallbackClassification(source, preview);
            }

            String response = client.chat(ChatRequest.builder()
                    .temperature(0.1)
                    .maxTokens(300)
                    .messages(List.of(
                            ChatMessage.system("""
                                    你是文档摄取分类器。请根据文件名、MIME 类型和文档片段，把文档严格归类到以下枚举之一：
                                    knowledge_base, faq, contract, log, ocr, html, code, mixed。
                                    输出必须是 JSON，对象字段仅允许：
                                    docType: string
                                    reason: string
                                    confidence: number(0-1)
                                    如果不确定，使用 mixed。
                                    """),
                            ChatMessage.user("""
                                    文件名: %s
                                    MIME 类型: %s
                                    文档片段:
                                    %s
                                    """.formatted(
                                    StrUtil.blankToDefault(source.fileName(), "unknown"),
                                    StrUtil.blankToDefault(source.mimeType(), "unknown"),
                                    StrUtil.blankToDefault(preview, "")
                            ))
                    ))
                    .build(), target);

            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(response);
            JsonNode json = objectMapper.readTree(cleaned);
            String docType = normalizeDocType(json.path("docType").asText());
            String reason = json.path("reason").asText("模型未返回原因，已使用默认说明");
            double confidence = json.path("confidence").asDouble(0.7D);
            return new ClassificationResult(docType, reason, confidence);
        } catch (Exception e) {
            log.warn("LLM 文档分类失败，使用兜底启发式: {}", e.getMessage());
            return fallbackClassification(source, preview);
        }
    }

    private ClassificationResult fallbackClassification(SourcePayload source, String preview) {
        String fileName = Optional.ofNullable(source.fileName()).orElse("").toLowerCase();
        String mimeType = Optional.ofNullable(source.mimeType()).orElse("").toLowerCase();
        String sample = Optional.ofNullable(preview).orElse("").toLowerCase();

        if (fileName.endsWith(".html") || mimeType.contains("html")) {
            return new ClassificationResult("html", "根据文件扩展名/内容识别为 HTML 页面", 0.65D);
        }
        if (looksLikeCode(fileName, sample)) {
            return new ClassificationResult("code", "根据扩展名或代码关键字识别为代码文档", 0.65D);
        }
        if (looksLikeLog(fileName, sample)) {
            return new ClassificationResult("log", "根据时间戳与日志级别识别为日志文档", 0.65D);
        }
        if (looksLikeContract(sample)) {
            return new ClassificationResult("contract", "根据条款/合同类关键词识别为合同文档", 0.65D);
        }
        if (looksLikeFaq(sample)) {
            return new ClassificationResult("faq", "根据问答结构识别为 FAQ 文档", 0.65D);
        }
        if (looksLikeOcr(sample)) {
            return new ClassificationResult("ocr", "根据断行与格式噪声识别为 OCR/杂乱文本", 0.6D);
        }
        if (mimeType.contains("markdown") || fileName.endsWith(".md") || fileName.endsWith(".markdown")) {
            return new ClassificationResult("knowledge_base", "Markdown 文档默认归为知识库/产品手册类型", 0.7D);
        }
        return new ClassificationResult("mixed", "未命中明确类型，使用混合文档通道兜底", 0.55D);
    }

    private boolean looksLikeFaq(String text) {
        return Pattern.compile("(?m)^(q[:：]|问[:：]).+").matcher(text).find()
                || Pattern.compile("(?m)^(a[:：]|答[:：]).+").matcher(text).find();
    }

    private boolean looksLikeContract(String text) {
        return text.contains("甲方") || text.contains("乙方") || text.contains("违约")
                || text.contains("合同") || text.contains("条款");
    }

    private boolean looksLikeLog(String fileName, String text) {
        return fileName.endsWith(".log")
                || Pattern.compile("(?m)^\\d{4}[-/]\\d{2}[-/]\\d{2}[ t]\\d{2}:\\d{2}:\\d{2}").matcher(text).find()
                || Pattern.compile("(?m)\\b(info|warn|error|debug|trace)\\b").matcher(text).find();
    }

    private boolean looksLikeCode(String fileName, String text) {
        return fileName.endsWith(".java") || fileName.endsWith(".ts") || fileName.endsWith(".js")
                || fileName.endsWith(".py") || fileName.endsWith(".go") || fileName.endsWith(".sql")
                || Pattern.compile("(?m)\\b(class|public|function|def|select\\s+.+from|import\\s+)\\b").matcher(text).find();
    }

    private boolean looksLikeOcr(String text) {
        return Pattern.compile("(?m).{1,8}\\n.{1,8}\\n.{1,8}").matcher(text).find()
                || Pattern.compile("(?m)^\\s*\\d+\\s*$").matcher(text).find();
    }

    private String normalizeDocType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "mixed";
        }
        String normalized = raw.trim().toLowerCase().replace('-', '_');
        return RECOMMENDATIONS.containsKey(normalized) ? normalized : "mixed";
    }

    private IngestionPipelineVO resolvePipeline(String pipelineName) {
        try {
            return pipelineService.page(new Page<>(1, 50), null, true)
                    .getRecords()
                    .stream()
                    .filter(each -> pipelineName.equals(each.getName()))
                    .findFirst()
                    .orElse(null);
        } catch (ClientException e) {
            log.warn("查询标准数据通道失败，降级返回推荐参数: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("查询标准数据通道异常，降级返回推荐参数: {}", e.getMessage(), e);
            return null;
        }
    }

    private record SourcePayload(String sourceType, String fileName, String mimeType, byte[] bytes) {
    }

    private record ClassificationResult(String docType, String reason, Double confidence) {
    }

    private record RecommendationSpec(
            String key,
            String label,
            String pipelineName,
            String chunkStrategy,
            Integer chunkSize,
            Integer overlapSize,
            Integer targetChars,
            Integer maxChars,
            Integer minChars,
            Integer overlapChars
    ) {
    }
}
