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

package com.nageoffer.ai.ragent.rag.util;

import cn.hutool.core.util.StrUtil;

import java.util.regex.Pattern;

/**
 * 笔记写入意图识别辅助类
 */
public final class NoteWriteIntentHelper {

    private static final Pattern NOTE_TARGET_HINT = Pattern.compile(
            "(笔记|obsidian|日记|note|markdown|(?<![a-zA-Z])md(?![a-zA-Z]))",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CREATE_HINT = Pattern.compile(
            "(创建|新建|建立|写一篇|写篇|写个|写一份|create)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern UPDATE_HINT = Pattern.compile(
            "(写入|记录|追加|添加|加入|插入|补充|修改|更新|append|add|insert|supplement|update|write|record)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CONTEXTUAL_ADD_HINT = Pattern.compile(
            "(加一条|加个|加到|加在|加进|加上)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CONTAINER_WRITE_HINT = Pattern.compile(
            "(?:往|在|到).{0,16}(?:笔记|日记).{0,6}(?:里|中).{0,8}(?:写|加|补)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DELETE_HINT = Pattern.compile(
            "(删除|移除|delete|remove)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REPLACE_HINT = Pattern.compile(
            "(替换|replace)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TRANSCRIPT_HINT = Pattern.compile(
            "(转录|transcript)",
            Pattern.CASE_INSENSITIVE
    );

    private NoteWriteIntentHelper() {
    }

    public static boolean isLikelyNoteWriteQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        String normalized = StrUtil.trim(question);
        return containsNoteTarget(normalized) && containsWriteIntent(normalized);
    }

    public static String suggestWriteToolId(String question) {
        String normalized = StrUtil.trim(StrUtil.blankToDefault(question, ""));
        if (normalized.isEmpty()) {
            return "obsidian_create";
        }
        if (TRANSCRIPT_HINT.matcher(normalized).find()) {
            return "obsidian_video_transcript";
        }
        if (DELETE_HINT.matcher(normalized).find()) {
            return "obsidian_delete";
        }
        if (REPLACE_HINT.matcher(normalized).find()) {
            return "obsidian_replace";
        }
        if (CREATE_HINT.matcher(normalized).find()) {
            return "obsidian_create";
        }
        if (isUpdateIntent(normalized)) {
            return "obsidian_update";
        }
        return "obsidian_create";
    }

    private static boolean containsWriteIntent(String normalizedQuestion) {
        return CREATE_HINT.matcher(normalizedQuestion).find()
                || isUpdateIntent(normalizedQuestion)
                || DELETE_HINT.matcher(normalizedQuestion).find()
                || REPLACE_HINT.matcher(normalizedQuestion).find()
                || TRANSCRIPT_HINT.matcher(normalizedQuestion).find();
    }

    private static boolean isUpdateIntent(String normalizedQuestion) {
        return UPDATE_HINT.matcher(normalizedQuestion).find()
                || CONTEXTUAL_ADD_HINT.matcher(normalizedQuestion).find()
                || CONTAINER_WRITE_HINT.matcher(normalizedQuestion).find();
    }

    private static boolean containsNoteTarget(String normalizedQuestion) {
        return NOTE_TARGET_HINT.matcher(normalizedQuestion).find();
    }
}
