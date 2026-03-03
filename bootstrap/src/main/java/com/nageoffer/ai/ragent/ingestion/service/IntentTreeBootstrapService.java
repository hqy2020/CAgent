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

package com.nageoffer.ai.ragent.ingestion.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.IntentBootstrapProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.enums.IntentLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 意图树冷启动初始化服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentTreeBootstrapService {

    private static final String BOOTSTRAP_LOCK_KEY = "ragent:intent:bootstrap:lock";
    private static final String SYSTEM_OPERATOR = "system";
    private static final long LOCK_WAIT_SECONDS = 0L;
    private static final long LOCK_LEASE_SECONDS = 30L;

    private static final Gson GSON = new Gson();

    private final IntentNodeMapper intentNodeMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final IntentTreeCacheManager intentTreeCacheManager;
    private final RedissonClient redissonClient;
    private final IntentBootstrapProperties bootstrapProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapOnStartup() {
        int created = initialize(false);
        if (created > 0) {
            log.info("意图树冷启动完成，创建节点数: {}", created);
        }
    }

    /**
     * 手动触发初始化（幂等）
     *
     * @return 本次创建节点数
     */
    public int initializeManually() {
        return initialize(true);
    }

    /**
     * 空树时初始化（幂等）
     */
    private int initialize(boolean force) {
        if (!force && !bootstrapProperties.isEnabled()) {
            log.info("意图树自动初始化已禁用，跳过");
            return 0;
        }
        String strategy = StrUtil.blankToDefault(bootstrapProperties.getStrategy(), "from-existing-kb");
        if (!"from-existing-kb".equalsIgnoreCase(strategy)) {
            log.warn("意图树初始化策略不支持: {}，跳过", strategy);
            return 0;
        }
        if (hasActiveIntentNodes()) {
            log.info("意图树已有节点，初始化无需执行");
            return 0;
        }

        RLock lock = redissonClient.getLock(BOOTSTRAP_LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                log.info("意图树初始化未获取到分布式锁，跳过本次执行");
                return 0;
            }
            if (hasActiveIntentNodes()) {
                return 0;
            }
            int created = doInitialize();
            if (created > 0) {
                intentTreeCacheManager.clearIntentTreeCache();
            }
            return created;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("意图树初始化被中断", ex);
            return 0;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private boolean hasActiveIntentNodes() {
        return intentNodeMapper.selectCount(
                Wrappers.lambdaQuery(IntentNodeDO.class)
                        .eq(IntentNodeDO::getDeleted, 0)
        ) > 0;
    }

    private int doInitialize() {
        List<IntentNodeDO> toCreate = new ArrayList<>();
        int sortOrder = 0;

        if (bootstrapProperties.isIncludeSystem()) {
            toCreate.add(buildSystemRoot(sortOrder++));
            toCreate.add(buildSystemGreeting(sortOrder++));
            toCreate.add(buildSystemAboutAssistant(sortOrder++));
        }

        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
                        .orderByAsc(KnowledgeBaseDO::getId)
        );

        for (KnowledgeBaseDO kb : kbList) {
            if (kb.getId() == null) {
                continue;
            }
            if (StrUtil.isBlank(kb.getCollectionName())) {
                log.warn("知识库 {} 缺少 collectionName，跳过意图初始化", kb.getId());
                continue;
            }
            String kbCode = "kb-" + kb.getId();
            String kbName = StrUtil.blankToDefault(kb.getName(), "知识库-" + kb.getId());
            toCreate.add(buildKbRoot(kb, kbCode, kbName, sortOrder++));
            toCreate.add(buildKbLeaf(kb, kbCode, kbName, sortOrder++));
        }

        int created = 0;
        for (IntentNodeDO each : toCreate) {
            created += intentNodeMapper.insert(each);
        }
        return created;
    }

    private IntentNodeDO buildSystemRoot(int sortOrder) {
        return IntentNodeDO.builder()
                .intentCode("sys-root")
                .name("系统交互")
                .level(IntentLevel.DOMAIN.getCode())
                .description("问候语与助手能力说明")
                .kind(IntentKind.SYSTEM.getCode())
                .sortOrder(sortOrder)
                .enabled(1)
                .createBy(SYSTEM_OPERATOR)
                .updateBy(SYSTEM_OPERATOR)
                .deleted(0)
                .build();
    }

    private IntentNodeDO buildSystemGreeting(int sortOrder) {
        return IntentNodeDO.builder()
                .intentCode("sys-greeting")
                .name("欢迎与问候")
                .level(IntentLevel.TOPIC.getCode())
                .parentCode("sys-root")
                .description("用户打招呼、寒暄")
                .examples(GSON.toJson(List.of("你好", "hi", "在吗")))
                .kind(IntentKind.SYSTEM.getCode())
                .sortOrder(sortOrder)
                .enabled(1)
                .createBy(SYSTEM_OPERATOR)
                .updateBy(SYSTEM_OPERATOR)
                .deleted(0)
                .build();
    }

    private IntentNodeDO buildSystemAboutAssistant(int sortOrder) {
        return IntentNodeDO.builder()
                .intentCode("sys-about-assistant")
                .name("助手能力说明")
                .level(IntentLevel.TOPIC.getCode())
                .parentCode("sys-root")
                .description("介绍助手能力、边界与使用方式")
                .examples(GSON.toJson(List.of("你能做什么", "你是谁")))
                .kind(IntentKind.SYSTEM.getCode())
                .sortOrder(sortOrder)
                .enabled(1)
                .createBy(SYSTEM_OPERATOR)
                .updateBy(SYSTEM_OPERATOR)
                .deleted(0)
                .build();
    }

    private IntentNodeDO buildKbRoot(KnowledgeBaseDO kb, String kbCode, String kbName, int sortOrder) {
        return IntentNodeDO.builder()
                .kbId(kb.getId())
                .intentCode(kbCode)
                .name(kbName)
                .level(IntentLevel.DOMAIN.getCode())
                .description("知识库领域入口：" + kbName)
                .collectionName(kb.getCollectionName())
                .kind(IntentKind.KB.getCode())
                .sortOrder(sortOrder)
                .enabled(1)
                .createBy(SYSTEM_OPERATOR)
                .updateBy(SYSTEM_OPERATOR)
                .deleted(0)
                .build();
    }

    private IntentNodeDO buildKbLeaf(KnowledgeBaseDO kb, String kbCode, String kbName, int sortOrder) {
        return IntentNodeDO.builder()
                .kbId(kb.getId())
                .intentCode(kbCode + "-topic")
                .name(kbName + "检索")
                .level(IntentLevel.TOPIC.getCode())
                .parentCode(kbCode)
                .description("面向 " + kbName + " 的知识检索")
                .examples(GSON.toJson(List.of(
                        kbName + "里有什么内容",
                        "请根据" + kbName + "回答我的问题"
                )))
                .collectionName(kb.getCollectionName())
                .kind(IntentKind.KB.getCode())
                .sortOrder(sortOrder)
                .enabled(1)
                .createBy(SYSTEM_OPERATOR)
                .updateBy(SYSTEM_OPERATOR)
                .deleted(0)
                .build();
    }
}
