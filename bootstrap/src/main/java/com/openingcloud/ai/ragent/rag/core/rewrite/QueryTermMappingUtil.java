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

package com.openingcloud.ai.ragent.rag.core.rewrite;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class QueryTermMappingUtil {

    /**
     * 匹配类型常量
     */
    public static final int MATCH_EXACT = 1;
    public static final int MATCH_PREFIX = 2;
    public static final int MATCH_REGEX = 3;
    public static final int MATCH_WHOLE_WORD = 4;

    /**
     * 根据匹配类型执行归一化替换
     */
    public static String applyMapping(String text, String sourceTerm, String targetTerm, int matchType) {
        if (text == null || text.isEmpty() || sourceTerm == null || sourceTerm.isEmpty()) {
            return text;
        }
        return switch (matchType) {
            case MATCH_PREFIX -> applyPrefixMapping(text, sourceTerm, targetTerm);
            case MATCH_REGEX -> applyRegexMapping(text, sourceTerm, targetTerm);
            case MATCH_WHOLE_WORD -> applyWholeWordMapping(text, sourceTerm, targetTerm);
            default -> applyMapping(text, sourceTerm, targetTerm);
        };
    }

    /**
     * 安全归一化替换（精确子串匹配，match_type=1）：
     * - 只替换 sourceTerm
     * - 如果当前位置本身已经是 targetTerm 起始（例如文本中已经是”平安保司”），则不重复替换
     */
    public static String applyMapping(String text, String sourceTerm, String targetTerm) {
        if (text == null || text.isEmpty() || sourceTerm == null || sourceTerm.isEmpty()) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        int idx = 0;
        int len = text.length();
        int sourceLen = sourceTerm.length();
        int targetLen = targetTerm.length();

        while (idx < len) {
            int hit = text.indexOf(sourceTerm, idx);
            if (hit < 0) {
                // 后面没有命中，整体拷贝
                sb.append(text, idx, len);
                break;
            }

            // 先把命中之前的文本拷贝过去
            sb.append(text, idx, hit);

            // 判断当前位置是否已经是 targetTerm 的开头
            boolean alreadyTarget =
                    targetTerm != null
                            && hit + targetLen <= len
                            && text.startsWith(targetTerm, hit);

            if (alreadyTarget) {
                // 已经是目标词开头了，直接按原文拷贝 targetTerm，一次性跳过
                sb.append(text, hit, hit + targetLen);
                idx = hit + targetLen;
            } else {
                // 不是目标词开头，正常做归一化替换
                sb.append(targetTerm);
                idx = hit + sourceLen;
            }
        }

        return sb.toString();
    }

    /**
     * 前缀匹配（match_type=2）：
     * 将以 sourceTerm 开头的 token 替换为 targetTerm + 剩余部分
     */
    static String applyPrefixMapping(String text, String sourceTerm, String targetTerm) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        int len = text.length();
        int sourceLen = sourceTerm.length();

        while (idx < len) {
            int hit = text.indexOf(sourceTerm, idx);
            if (hit < 0) {
                sb.append(text, idx, len);
                break;
            }
            sb.append(text, idx, hit);
            // 前缀匹配：sourceTerm 必须出现在 token 起始位置（行首或前一个字符是空白/标点）
            boolean atTokenStart = hit == 0 || isTokenBoundary(text.charAt(hit - 1));
            if (atTokenStart) {
                sb.append(targetTerm);
                idx = hit + sourceLen;
            } else {
                sb.append(text.charAt(hit));
                idx = hit + 1;
            }
        }
        return sb.toString();
    }

    /**
     * 正则匹配（match_type=3）：
     * sourceTerm 作为正则表达式，匹配到的部分替换为 targetTerm（支持 $1 等反向引用）
     */
    static String applyRegexMapping(String text, String sourceTerm, String targetTerm) {
        try {
            Pattern pattern = Pattern.compile(sourceTerm);
            Matcher matcher = pattern.matcher(text);
            return matcher.replaceAll(targetTerm);
        } catch (PatternSyntaxException e) {
            // 正则语法错误，返回原文不做替换
            return text;
        }
    }

    /**
     * 整词匹配（match_type=4）：
     * 仅当 sourceTerm 两侧都是词边界时才替换，避免替换词的一部分
     */
    static String applyWholeWordMapping(String text, String sourceTerm, String targetTerm) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        int len = text.length();
        int sourceLen = sourceTerm.length();

        while (idx < len) {
            int hit = text.indexOf(sourceTerm, idx);
            if (hit < 0) {
                sb.append(text, idx, len);
                break;
            }
            sb.append(text, idx, hit);

            boolean leftBound = hit == 0 || isTokenBoundary(text.charAt(hit - 1));
            boolean rightBound = (hit + sourceLen) >= len || isTokenBoundary(text.charAt(hit + sourceLen));

            if (leftBound && rightBound) {
                sb.append(targetTerm);
                idx = hit + sourceLen;
            } else {
                sb.append(text.charAt(hit));
                idx = hit + 1;
            }
        }
        return sb.toString();
    }

    /**
     * 判断字符是否为 token 边界（空白、标点、CJK 字符边界）
     */
    private static boolean isTokenBoundary(char ch) {
        return Character.isWhitespace(ch)
                || !Character.isLetterOrDigit(ch);
    }
}
