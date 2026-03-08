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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 意图分类响应解析器。
 * 先尝试严格解析，失败后仅挽救已闭合且字段完整的对象片段。
 */
final class IntentClassificationResponseParser {

    private static final int RAW_SUMMARY_LIMIT = 240;

    private IntentClassificationResponseParser() {
    }

    static ParseResult parse(String raw, Map<String, IntentNode> id2Node) {
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        String jsonBody = extractFirstJsonBody(cleaned);

        try {
            JsonElement root = JsonParser.parseString(jsonBody);
            JsonArray array = extractResultArray(root);
            if (array == null) {
                return ParseResult.failed(summarize(raw), "unexpected-json-shape");
            }
            return ParseResult.strict(extractScores(array, id2Node));
        } catch (Exception e) {
            List<NodeScore> salvaged = salvageScores(jsonBody, id2Node);
            if (!salvaged.isEmpty()) {
                return ParseResult.salvaged(salvaged, summarize(raw), summarizeError(e));
            }
            return ParseResult.failed(summarize(raw), summarizeError(e));
        }
    }

    private static JsonArray extractResultArray(JsonElement root) {
        if (root == null) {
            return null;
        }
        if (root.isJsonArray()) {
            return root.getAsJsonArray();
        }
        if (root.isJsonObject() && root.getAsJsonObject().has("results")) {
            JsonElement results = root.getAsJsonObject().get("results");
            if (results != null && results.isJsonArray()) {
                return results.getAsJsonArray();
            }
        }
        return null;
    }

    private static List<NodeScore> extractScores(JsonArray array, Map<String, IntentNode> id2Node) {
        List<NodeScore> scores = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            NodeScore nodeScore = toNodeScore(element.getAsJsonObject(), id2Node);
            if (nodeScore != null) {
                scores.add(nodeScore);
            }
        }
        return scores;
    }

    private static NodeScore toNodeScore(JsonObject obj, Map<String, IntentNode> id2Node) {
        if (!obj.has("id") || !obj.has("score")) {
            return null;
        }
        try {
            String id = obj.get("id").getAsString();
            double score = obj.get("score").getAsDouble();
            if (Double.isNaN(score) || Double.isInfinite(score)) {
                return null;
            }

            IntentNode node = id2Node.get(id);
            if (node == null) {
                return null;
            }
            return new NodeScore(node, score);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<NodeScore> salvageScores(String jsonBody, Map<String, IntentNode> id2Node) {
        String arraySegment = extractCandidateArraySegment(jsonBody);
        if (arraySegment == null || arraySegment.isBlank()) {
            return List.of();
        }

        List<NodeScore> scores = new ArrayList<>();
        for (String fragment : extractClosedObjectFragments(arraySegment)) {
            try {
                JsonElement element = JsonParser.parseString(fragment);
                if (!element.isJsonObject()) {
                    continue;
                }
                NodeScore nodeScore = toNodeScore(element.getAsJsonObject(), id2Node);
                if (nodeScore != null) {
                    scores.add(nodeScore);
                }
            } catch (Exception ignored) {
                // salvage 模式下只保留可独立解析的完整对象
            }
        }
        return scores;
    }

    private static String extractCandidateArraySegment(String jsonBody) {
        if (jsonBody == null) {
            return null;
        }
        String trimmed = jsonBody.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (trimmed.charAt(0) == '[') {
            return trimmed;
        }
        if (trimmed.charAt(0) != '{') {
            return null;
        }

        int arrayStart = findResultsArrayStart(trimmed);
        return arrayStart >= 0 ? trimmed.substring(arrayStart).trim() : null;
    }

    private static int findResultsArrayStart(String jsonBody) {
        int fromIndex = 0;
        while (fromIndex < jsonBody.length()) {
            int keyIndex = jsonBody.indexOf("\"results\"", fromIndex);
            if (keyIndex < 0) {
                return -1;
            }
            int colonIndex = skipWhitespace(jsonBody, keyIndex + "\"results\"".length());
            if (colonIndex >= jsonBody.length() || jsonBody.charAt(colonIndex) != ':') {
                fromIndex = keyIndex + 1;
                continue;
            }
            int valueIndex = skipWhitespace(jsonBody, colonIndex + 1);
            if (valueIndex < jsonBody.length() && jsonBody.charAt(valueIndex) == '[') {
                return valueIndex;
            }
            fromIndex = keyIndex + 1;
        }
        return -1;
    }

    private static int skipWhitespace(String text, int index) {
        int cursor = index;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static List<String> extractClosedObjectFragments(String arraySegment) {
        List<String> fragments = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        int braceDepth = 0;
        int objectStart = -1;

        for (int i = 0; i < arraySegment.length(); i++) {
            char c = arraySegment.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '{') {
                if (braceDepth == 0) {
                    objectStart = i;
                }
                braceDepth++;
                continue;
            }

            if (c == '}' && braceDepth > 0) {
                braceDepth--;
                if (braceDepth == 0 && objectStart >= 0) {
                    fragments.add(arraySegment.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            }
        }
        return fragments;
    }

    private static String extractFirstJsonBody(String raw) {
        if (raw == null) {
            return "";
        }

        int start = findFirstJsonStart(raw);
        if (start < 0) {
            return raw.trim();
        }

        int end = findMatchingJsonEnd(raw, start);
        if (end >= start) {
            return raw.substring(start, end + 1).trim();
        }
        return raw.substring(start).trim();
    }

    private static int findFirstJsonStart(String raw) {
        int objStart = raw.indexOf('{');
        int arrStart = raw.indexOf('[');
        if (objStart < 0) {
            return arrStart;
        }
        if (arrStart < 0) {
            return objStart;
        }
        return Math.min(objStart, arrStart);
    }

    private static int findMatchingJsonEnd(String raw, int start) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '{' || c == '[') {
                stack.push(c);
                continue;
            }

            if (c == '}' || c == ']') {
                if (stack.isEmpty()) {
                    return -1;
                }
                char open = stack.pop();
                if (!isMatchingPair(open, c)) {
                    return -1;
                }
                if (stack.isEmpty()) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isMatchingPair(char open, char close) {
        return (open == '{' && close == '}') || (open == '[' && close == ']');
    }

    static String summarize(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= RAW_SUMMARY_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, RAW_SUMMARY_LIMIT) + "...";
    }

    private static String summarizeError(Exception e) {
        if (e == null) {
            return "unknown";
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    enum ParseMode {
        STRICT,
        SALVAGED,
        FAILED
    }

    record ParseResult(
            ParseMode mode,
            List<NodeScore> scores,
            String rawSummary,
            String errorSummary
    ) {

        static ParseResult strict(List<NodeScore> scores) {
            return new ParseResult(ParseMode.STRICT, scores, "", "");
        }

        static ParseResult salvaged(List<NodeScore> scores, String rawSummary, String errorSummary) {
            return new ParseResult(ParseMode.SALVAGED, scores, rawSummary, errorSummary);
        }

        static ParseResult failed(String rawSummary, String errorSummary) {
            return new ParseResult(ParseMode.FAILED, List.of(), rawSummary, errorSummary);
        }
    }
}
