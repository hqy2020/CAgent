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

import com.nageoffer.ai.ragent.rag.enums.IntentKind;

import java.util.ArrayList;
import java.util.List;

import static com.nageoffer.ai.ragent.rag.enums.IntentLevel.CATEGORY;
import static com.nageoffer.ai.ragent.rag.enums.IntentLevel.DOMAIN;
import static com.nageoffer.ai.ragent.rag.enums.IntentLevel.TOPIC;

/**
 * 构造意图识别树 —— 适配第二大脑（GardenOfOpeningClouds）
 *
 * <p>结构：多 DOMAIN 组合，覆盖 KB、MCP 与 SYSTEM 场景
 * <ul>
 *   <li>知识问答（KB）：面试八股 / 项目实战 / 实习经验</li>
 *   <li>笔记检索（MCP）：读取 / 搜索 / 列出</li>
 *   <li>笔记编辑（MCP）：创建 / 更新 / 删除</li>
 *   <li>业务系统查询（MCP）：销售数据实时查询</li>
 *   <li>系统交互（SYSTEM）：欢迎 / 关于助手</li>
 * </ul>
 */
public class IntentTreeFactory {

    // ===================== KB Collection 常量 =====================
    private static final String COLLECTION_BAGU = "kb_bagu_prep";
    private static final String COLLECTION_ONECOUPON = "kb_onecoupon";
    private static final String COLLECTION_RAGENT = "kb_ragent_project";
    private static final String COLLECTION_ALIYUN = "kb_aliyun_intern";

    public static List<IntentNode> buildIntentTree() {
        List<IntentNode> roots = new ArrayList<>();

        roots.add(buildKbDomain());
        roots.add(buildObsQueryDomain());
        roots.add(buildObsEditDomain());
        roots.add(buildSalesQueryDomain());
        roots.add(buildSysDomain());

        // 填充 fullPath
        fillFullPath(roots, null);
        return roots;
    }

    // ========== 1. 知识问答 (kb-qa) ==========

    private static IntentNode buildKbDomain() {
        IntentNode domain = IntentNode.builder()
                .id("kb-qa")
                .name("知识问答")
                .level(DOMAIN)
                .kind(IntentKind.KB)
                .description("基于知识库的问答检索，涵盖面试八股、项目实战、实习经验等")
                .build();

        // --- 面试八股 ---
        IntentNode bagu = IntentNode.builder()
                .id("kb-qa-bagu")
                .name("面试八股")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.KB)
                .collectionName(COLLECTION_BAGU)
                .description("Java 后端面试八股文相关知识，涵盖 Java 核心、Spring 框架、中间件与数据库")
                .examples(List.of("HashMap 底层原理是什么？", "Spring AOP 和 IOC 的区别？", "Redis 的缓存穿透怎么解决？"))
                .build();

        IntentNode baguJava = IntentNode.builder()
                .id("kb-qa-bagu-java")
                .name("Java核心")
                .level(TOPIC)
                .parentId(bagu.getId())
                .kind(IntentKind.KB)
                .collectionName(COLLECTION_BAGU)
                .description("Java 语言核心知识：集合、并发、JVM、IO 等")
                .examples(List.of("ConcurrentHashMap 的实现原理？", "JVM 垃圾回收算法有哪些？", "volatile 关键字的作用？"))
                .build();

        IntentNode baguSpring = IntentNode.builder()
                .id("kb-qa-bagu-spring")
                .name("Spring框架")
                .level(TOPIC)
                .parentId(bagu.getId())
                .kind(IntentKind.KB)
                .collectionName(COLLECTION_BAGU)
                .description("Spring 生态相关：Spring Boot、Spring MVC、事务、Bean 生命周期等")
                .examples(List.of("Spring Bean 的生命周期？", "Spring Boot 自动配置原理？", "@Transactional 失效场景？"))
                .build();

        IntentNode baguMw = IntentNode.builder()
                .id("kb-qa-bagu-mw")
                .name("中间件与数据库")
                .level(TOPIC)
                .parentId(bagu.getId())
                .kind(IntentKind.KB)
                .collectionName(COLLECTION_BAGU)
                .description("Redis、MySQL、MQ、Elasticsearch 等中间件与数据库相关面试题")
                .examples(List.of("MySQL 索引的底层数据结构？", "RocketMQ 如何保证消息不丢失？", "Redis 持久化机制有哪些？"))
                .build();

        bagu.setChildren(List.of(baguJava, baguSpring, baguMw));

        // --- 项目实战 ---
        IntentNode project = IntentNode.builder()
                .id("kb-qa-project")
                .name("项目实战")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.KB)
                .description("实际项目的架构设计、技术选型和实现细节")
                .examples(List.of("牛券系统的优惠券分发怎么做的？", "RAG 系统的检索流程是怎样的？"))
                .build();

        IntentNode projCoupon = IntentNode.builder()
                .id("kb-qa-proj-coupon")
                .name("牛券优惠券系统")
                .level(TOPIC)
                .parentId(project.getId())
                .kind(IntentKind.KB)
                .collectionName(COLLECTION_ONECOUPON)
                .description("牛券（OneCoupon）优惠券平台的架构设计与实现")
                .examples(List.of("牛券系统的分库分表方案？", "优惠券秒杀的防超卖设计？", "牛券系统用了哪些设计模式？"))
                .build();

        IntentNode projRagent = IntentNode.builder()
                .id("kb-qa-proj-ragent")
                .name("RAG系统设计")
                .level(TOPIC)
                .parentId(project.getId())
                .kind(IntentKind.KB)
                .collectionName(COLLECTION_RAGENT)
                .description("Ragent 企业级 RAG 系统的架构设计与核心流程")
                .examples(List.of("RAG 系统的多路检索怎么做的？", "意图识别树的设计思路？", "模型路由和熔断机制？"))
                .build();

        project.setChildren(List.of(projCoupon, projRagent));

        // --- 实习经验 ---
        IntentNode intern = IntentNode.builder()
                .id("kb-qa-intern")
                .name("实习经验")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.KB)
                .collectionName(COLLECTION_ALIYUN)
                .description("实习工作中的项目经历、技术栈和收获总结")
                .examples(List.of("在阿里云实习做了什么项目？", "实习期间用到了哪些技术？"))
                .build();

        IntentNode internAliyun = IntentNode.builder()
                .id("kb-qa-intern-aliyun")
                .name("阿里云实习")
                .level(TOPIC)
                .parentId(intern.getId())
                .kind(IntentKind.KB)
                .collectionName(COLLECTION_ALIYUN)
                .description("阿里云实习期间的项目经历与技术实践")
                .examples(List.of("阿里云实习的主要工作内容？", "实习中遇到的技术挑战？", "阿里云实习收获了什么？"))
                .build();

        intern.setChildren(List.of(internAliyun));

        domain.setChildren(List.of(bagu, project, intern));
        return domain;
    }

    // ========== 2. 笔记检索 (obs-query) ==========

    private static IntentNode buildObsQueryDomain() {
        IntentNode domain = IntentNode.builder()
                .id("obs-query")
                .name("笔记检索")
                .level(DOMAIN)
                .kind(IntentKind.MCP)
                .description("通过 Obsidian 笔记库进行只读查询，包括读取、搜索和列出笔记")
                .build();

        // --- 读取笔记 ---
        IntentNode read = IntentNode.builder()
                .id("obs-query-read")
                .name("读取笔记")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_read")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("读取 Obsidian 笔记库中指定笔记的完整内容")
                .examples(List.of("读取我的日记", "打开笔记 README", "查看 RAG 笔记内容"))
                .build();

        IntentNode readFile = IntentNode.builder()
                .id("obs-read-file")
                .name("指定笔记阅读")
                .level(TOPIC)
                .parentId(read.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_read")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("按名称或路径读取特定笔记的完整内容")
                .examples(List.of("打开《Spring AOP 总结》这篇笔记", "读取 Projects/RAG/设计文档 笔记", "查看今天的日记内容"))
                .build();

        IntentNode readPath = IntentNode.builder()
                .id("obs-read-path")
                .name("按路径浏览")
                .level(TOPIC)
                .parentId(read.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_read")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("按文件路径浏览笔记目录或打开指定路径的笔记")
                .examples(List.of("浏览 Knowledge 目录下的笔记", "打开 Daily/2026-03-03 路径的笔记"))
                .build();

        read.setChildren(List.of(readFile, readPath));

        // --- 搜索笔记 ---
        IntentNode search = IntentNode.builder()
                .id("obs-query-search")
                .name("搜索笔记")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_search")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("在 Obsidian 笔记库中搜索笔记内容或标题")
                .examples(List.of("搜索关于 RAG 的笔记", "在笔记库中查找 Spring Boot", "搜索包含 TODO 的笔记"))
                .build();

        IntentNode searchFulltext = IntentNode.builder()
                .id("obs-search-fulltext")
                .name("全文搜索")
                .level(TOPIC)
                .parentId(search.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_search")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("在笔记库中按关键词进行全文内容搜索")
                .examples(List.of("搜索包含 HashMap 的笔记", "查找提到了线程池的笔记内容", "搜索所有带 TODO 标记的笔记"))
                .build();

        IntentNode searchFolder = IntentNode.builder()
                .id("obs-search-folder")
                .name("分类目录搜索")
                .level(TOPIC)
                .parentId(search.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_search")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("在指定分类目录或文件夹范围内搜索笔记")
                .examples(List.of("在面试准备文件夹里搜索 Redis", "在 Projects 目录下查找设计模式相关笔记"))
                .build();

        search.setChildren(List.of(searchFulltext, searchFolder));

        // --- 列出笔记 ---
        IntentNode listNode = IntentNode.builder()
                .id("obs-query-list")
                .name("列出笔记")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_list")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("列出 Obsidian 笔记库中的文件或文件夹结构")
                .examples(List.of("列出笔记库里的文件夹", "查看所有笔记", "列出知识库文件夹下的文件"))
                .build();

        IntentNode listFolders = IntentNode.builder()
                .id("obs-list-folders")
                .name("列出文件夹结构")
                .level(TOPIC)
                .parentId(listNode.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_list")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("查看笔记库的文件夹层级结构和目录树")
                .examples(List.of("展示笔记库的目录结构", "有哪些文件夹分类？", "查看笔记库的整体结构"))
                .build();

        IntentNode listFiles = IntentNode.builder()
                .id("obs-list-files")
                .name("列出文件列表")
                .level(TOPIC)
                .parentId(listNode.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_list")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("列出指定目录下的所有笔记文件")
                .examples(List.of("列出 Daily 文件夹下的所有笔记", "Knowledge 目录有哪些文件？", "最近创建的笔记有哪些？"))
                .build();

        listNode.setChildren(List.of(listFolders, listFiles));

        domain.setChildren(List.of(read, search, listNode));
        return domain;
    }

    // ========== 3. 笔记编辑 (obs-edit) ==========

    private static IntentNode buildObsEditDomain() {
        IntentNode domain = IntentNode.builder()
                .id("obs-edit")
                .name("笔记编辑")
                .level(DOMAIN)
                .kind(IntentKind.MCP)
                .description("对 Obsidian 笔记库进行写操作，包括创建、更新、删除和视频转录入库")
                .build();

        // --- 创建笔记 ---
        IntentNode create = IntentNode.builder()
                .id("obs-edit-create")
                .name("创建笔记")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_create")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("在 Obsidian 笔记库中创建新笔记")
                .examples(List.of("创建一个关于 Docker 的笔记", "新建一篇学习笔记", "在知识库文件夹创建笔记"))
                .build();

        IntentNode createNote = IntentNode.builder()
                .id("obs-create-note")
                .name("新建知识卡片")
                .level(TOPIC)
                .parentId(create.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_create")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("创建一篇新的知识笔记或学习卡片")
                .examples(List.of("帮我创建一篇关于设计模式的笔记", "新建一张 Redis 缓存策略的知识卡片", "创建 Spring Security 学习笔记"))
                .build();

        IntentNode createDaily = IntentNode.builder()
                .id("obs-create-daily")
                .name("新建日记")
                .level(TOPIC)
                .parentId(create.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_create")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("创建今日或指定日期的日记笔记")
                .examples(List.of("帮我新建今天的日记", "创建一篇明天的日记", "新建 2026-03-03 的日记"))
                .build();

        create.setChildren(List.of(createNote, createDaily));

        // --- 更新笔记 ---
        IntentNode update = IntentNode.builder()
                .id("obs-edit-update")
                .name("更新笔记")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_update")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("向已有 Obsidian 笔记追加或修改内容")
                .examples(List.of("在日记里追加一条待办", "往 README 末尾添加内容", "在笔记开头插入摘要"))
                .build();

        IntentNode updateAppend = IntentNode.builder()
                .id("obs-update-append")
                .name("追加内容")
                .level(TOPIC)
                .parentId(update.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_update")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("在指定笔记末尾追加新内容")
                .examples(List.of("在 RAG 设计文档末尾追加一段总结", "往学习笔记里添加新内容", "在笔记最后加上参考链接"))
                .build();

        IntentNode updateDaily = IntentNode.builder()
                .id("obs-update-daily")
                .name("日记追加")
                .level(TOPIC)
                .parentId(update.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_update")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("向今日日记追加内容，如待办、记录、感想")
                .examples(List.of("在今天的日记里加一条待办", "记录一下今天学了什么", "日记追加：完成了 RAG 意图树重构"))
                .build();

        update.setChildren(List.of(updateAppend, updateDaily));

        // --- 替换笔记内容 ---
        IntentNode replace = IntentNode.builder()
                .id("obs-edit-replace")
                .name("替换内容")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_replace")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("替换 Obsidian 笔记中的指定文本为新内容，用于语义化编辑修改笔记")
                .examples(List.of("把笔记里的旧标题替换为新标题", "修改笔记中的某段描述", "将笔记中的 A 替换为 B"))
                .build();

        IntentNode replaceText = IntentNode.builder()
                .id("obs-replace-text")
                .name("文本替换")
                .level(TOPIC)
                .parentId(replace.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_replace")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("按文本内容匹配并替换笔记中的指定片段")
                .examples(List.of("把 MOC-Knowledge 笔记里的「知识库」替换为「第二大脑知识库」",
                        "修改 README 中的项目描述", "将笔记中的旧链接替换为新链接"))
                .build();

        replace.setChildren(List.of(replaceText));

        // --- 删除笔记 ---
        IntentNode delete = IntentNode.builder()
                .id("obs-edit-delete")
                .name("删除笔记")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_delete")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("删除 Obsidian 笔记库中的指定笔记")
                .examples(List.of("删除草稿笔记", "把 temp 笔记删掉", "清理测试笔记"))
                .build();

        IntentNode deleteNote = IntentNode.builder()
                .id("obs-delete-note")
                .name("删除指定笔记")
                .level(TOPIC)
                .parentId(delete.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_delete")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("按名称或路径删除一篇指定的笔记")
                .examples(List.of("删除名为《草稿》的笔记", "把 Temp/test.md 删掉", "移除 Archive 里的过期笔记"))
                .build();

        delete.setChildren(List.of(deleteNote));

        // --- 视频转录入库 ---
        IntentNode transcript = IntentNode.builder()
                .id("obs-edit-video-transcript")
                .name("视频转录入库")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_video_transcript")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("将视频链接转录为文字并自动写入 Obsidian 笔记")
                .examples(List.of("把这个 B 站链接转录到 Obsidian", "转录这个 YouTube 视频并保存成笔记", "把小宇宙链接转成文字放进笔记库"))
                .build();

        IntentNode transcriptBilibili = IntentNode.builder()
                .id("obs-transcript-bilibili")
                .name("B站视频转录")
                .level(TOPIC)
                .parentId(transcript.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_video_transcript")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("将 Bilibili 视频链接转录并写入指定 Obsidian 目录")
                .examples(List.of("把 https://www.bilibili.com/video/... 转录成笔记", "B 站这个视频转录后放到学习输入目录", "把这条 b23.tv 链接整理成文字"))
                .build();

        IntentNode transcriptPodcast = IntentNode.builder()
                .id("obs-transcript-podcast")
                .name("播客/长视频转录")
                .level(TOPIC)
                .parentId(transcript.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("obsidian_video_transcript")
                .promptTemplate(OBSIDIAN_MCP_PROMPT_TEMPLATE)
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("将小宇宙、YouTube 等长内容链接转录并落盘到 Obsidian")
                .examples(List.of("转录这条小宇宙链接并写入 Obsidian", "把 YouTube 讲座链接转录后新建笔记", "这个视频转录完成后追加到已有笔记"))
                .build();

        transcript.setChildren(List.of(transcriptBilibili, transcriptPodcast));

        domain.setChildren(List.of(create, update, replace, delete, transcript));
        return domain;
    }

    // ========== 4. 业务系统查询 (biz-sales) ==========

    private static IntentNode buildSalesQueryDomain() {
        IntentNode domain = IntentNode.builder()
                .id("biz-sales")
                .name("业务系统查询")
                .level(DOMAIN)
                .kind(IntentKind.MCP)
                .description("通过业务系统查询实时销售数据，支持汇总、排名与趋势分析")
                .build();

        IntentNode salesQuery = IntentNode.builder()
                .id("biz-sales-query")
                .name("销售数据查询")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.MCP)
                .mcpToolId("sales_query")
                .paramPromptTemplate(MCP_PARAMETER_EXTRACT_PROMPT)
                .description("查询销售额、销售排名和销售趋势等经营指标")
                .examples(List.of(
                        "华东区这个月销售额多少？",
                        "本月销售排名前五是谁？",
                        "华东区这个月销售额多少，并说明销售口径定义。"
                ))
                .build();

        domain.setChildren(List.of(salesQuery));
        return domain;
    }

    // ========== 5. 系统交互 (sys) ==========

    private static IntentNode buildSysDomain() {
        IntentNode domain = IntentNode.builder()
                .id("sys")
                .name("系统交互")
                .level(DOMAIN)
                .kind(IntentKind.SYSTEM)
                .description("系统级交互，包括问候、关于助手等非业务意图")
                .build();

        IntentNode welcome = IntentNode.builder()
                .id("sys-welcome")
                .name("欢迎与问候")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.SYSTEM)
                .description("用户与助手打招呼，如：你好、早上好、hi、在吗 等")
                .examples(List.of("你好", "hello", "早上好", "在吗", "嗨"))
                .build();

        IntentNode aboutBot = IntentNode.builder()
                .id("sys-about-bot")
                .name("关于助手")
                .level(CATEGORY)
                .parentId(domain.getId())
                .kind(IntentKind.SYSTEM)
                .description("询问助手是做什么的、是谁、能做什么等")
                .examples(List.of("你是谁", "你是做什么的", "你能帮我做什么", "你是什么AI"))
                .build();

        domain.setChildren(List.of(welcome, aboutBot));
        return domain;
    }

    // ===================== 工具方法 =====================

    private static void fillFullPath(List<IntentNode> nodes, IntentNode parent) {
        for (IntentNode node : nodes) {
            if (parent == null) {
                node.setFullPath(node.getName());
            } else {
                node.setFullPath(parent.getFullPath() + " > " + node.getName());
            }
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                fillFullPath(node.getChildren(), node);
            }
        }
    }

    // ===================== Prompt 模板常量 =====================

    public static final String MCP_PARAMETER_EXTRACT_PROMPT = """
            Hello，你是一个高度专业且严谨的【工具参数提取器】。

            你的唯一任务是：严格按照提供的【工具定义】（Tool Definition）和【参数列表】（Parameters）的约束，从【用户问题】（User Query）中提取所有必要的参数，并以 JSON 格式输出。

            ---

            ### 核心提取逻辑

            1. **数据源限定**：只使用【用户问题】中的信息作为提取来源。
            2. **参数范围限定**：只提取 <parameters> 标签内定义的参数，**禁止**添加任何工具定义中不存在的额外字段。
            3. **必填参数处理（Strict Mode）**：
               - 如果参数是 **"required": true** 且在用户问题中无法找到明确值：
                 - 如果工具定义中提供了 **"default"** 值，请使用该默认值。
                 - 如果**没有**默认值，必须将该参数的值输出为 **null**。
            4. **非必填参数处理**：
               - 如果参数是 **"required": false** 且在用户问题中无法找到明确值：
                 - 如果有默认值，使用默认值。
                 - 如果没有默认值，**请忽略该参数，不要将其包含在最终的 JSON 输出中。**

            ### 通用数据类型处理规则

            1. **枚举/可选值（Enum）**：
               - **核心原则：意图映射**。将用户口语化、同义或模糊的表达，映射到工具定义中提供的 **enum** 列表中的**最接近的规范值**。
               - 示例：用户说"本周"或"这星期"，枚举值有 "current_week" → 输出 "current_week"。

            2. **日期/时间（Date/Time）**：
               - **相对时间**：将"今天"、"昨天"、"上个月"、"今年 Q3"等相对时间表述，**根据当前上下文**映射为工具所需的**规范化格式**或**枚举值**。
               - **时间范围**：如果工具需要 `start_date` 和 `end_date` 两个参数来定义范围，请从一个表述（如"上周"）中提取出两个边界值。
               - **日记日期防误提取**：仅当用户明确表达目标日记日期（如"3月7日日记"、"写到3.7日记里"）时，才可提取 `date`；像"3.7答辩"这类内容中的数字日期，必须保留在 `content`，不得映射到 `date`。

            3. **字符串（String）**：
               - **原样提取**：直接截取用户问题中提及的实体名称、人名、地名、产品 ID 等，不需要进行任何转换或缩写，除非工具定义明确要求。
               - **注意**：如果字符串是空或未提及，按必填/非必填规则处理。

            4. **数值（Number/Integer）**：
               - **格式统一**：将中文数字（如"三"、"前五"）转换为阿拉伯数字（3, 5）。
               - **提取限定词**：如问题包含"top 10"或"前五名"，提取 `10` 或 `5`。

            5. **布尔值（Boolean）**：
               - **肯定**：如"是"、"要"、"开启"、"需要查看" → 映射为 `true`。
               - **否定**：如"否"、"不"、"关闭"、"不需要" → 映射为 `false`。

            ---

            ### 输入数据与输出格式

            请勿在输出 JSON 对象之外添加任何解释、注释或其他文本。

            #### 【工具定义】
            <tool_definition>
            %s
            </tool_definition>

            #### 【用户问题】
            <user_query>
            %s
            </user_query>

            #### 【输出格式（JSON Object Only）】

            {"param_name_1": value_1, "param_name_2": value_2, ...}

            """;

    private static final String OBSIDIAN_MCP_PROMPT_TEMPLATE = """
            你是用户的第二大脑助手，帮助用户管理和使用 Obsidian 笔记库。系统已通过 Obsidian CLI 获取到了【操作结果】。
            你的任务是将结果以**清晰、易读的自然语言**回复给用户。

            【核心处理规则】
            1. **直接回答**：开门见山告知操作结果，不要使用技术性的开头。
            2. **格式化输出**：
               - **文件列表**：使用 Markdown 列表或树状结构展示，突出文件夹层级。
               - **笔记内容**：保留原始 Markdown 格式，适当添加引导语。
               - **搜索结果**：按相关度列出匹配项，显示匹配上下文。
            3. **操作确认**：创建/更新/删除操作完成后，明确告知用户操作结果。

            【异常处理】
            1. **数据为空**：如搜索无结果，友好提示"未找到匹配的笔记"并建议调整关键词。
            2. **操作失败**：用友好的语言告知失败原因，并给出可能的解决建议。

            {{INTENT_RULES}}

            【操作结果】
            %s

            【用户问题】
            %s
            """;

}
