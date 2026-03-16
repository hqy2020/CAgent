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

package com.openingcloud.ai.ragent.core.parser;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文本清理配置
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TextCleanupOptions {

    public static final String PROFILE_DEFAULT = "default";
    public static final String PROFILE_MARKDOWN_STANDARD = "markdown_standard";
    public static final String PROFILE_PDF_STANDARD = "pdf_standard";

    /**
     * 预设清理档位
     */
    private String profile;

    /**
     * 是否移除 UTF-8 BOM
     */
    private Boolean removeBOM;

    /**
     * 是否统一换行符为 \n
     */
    private Boolean normalizeLineEndings;

    /**
     * 是否移除控制字符（保留 \n\t）
     */
    private Boolean stripControlChars;

    /**
     * 是否规范化 Unicode 空白
     */
    private Boolean normalizeUnicodeSpaces;

    /**
     * 是否裁剪行尾空格
     */
    private Boolean trimTrailingSpaces;

    /**
     * 是否压缩连续空行
     */
    private Boolean compressEmptyLines;

    /**
     * 最多保留的连续空行数
     */
    private Integer maxConsecutiveEmptyLines;

    /**
     * 是否移除独立页码行
     */
    private Boolean removeStandalonePageNumbers;

    /**
     * 是否尝试合并被错误硬换行打断的段落
     */
    private Boolean mergeWrappedLines;

    public static TextCleanupOptions defaultOptions() {
        return builder()
                .profile(PROFILE_DEFAULT)
                .removeBOM(true)
                .normalizeLineEndings(true)
                .stripControlChars(true)
                .normalizeUnicodeSpaces(true)
                .trimTrailingSpaces(true)
                .compressEmptyLines(true)
                .maxConsecutiveEmptyLines(2)
                .removeStandalonePageNumbers(false)
                .mergeWrappedLines(false)
                .build();
    }

    public static TextCleanupOptions markdownStandard() {
        TextCleanupOptions options = defaultOptions();
        options.setProfile(PROFILE_MARKDOWN_STANDARD);
        // Markdown 中行尾两个空格可能是合法语义，默认不做裁剪。
        options.setTrimTrailingSpaces(false);
        options.setMergeWrappedLines(false);
        return options;
    }

    public static TextCleanupOptions pdfStandard() {
        TextCleanupOptions options = defaultOptions();
        options.setProfile(PROFILE_PDF_STANDARD);
        options.setTrimTrailingSpaces(true);
        options.setRemoveStandalonePageNumbers(true);
        options.setMergeWrappedLines(true);
        return options;
    }
}
