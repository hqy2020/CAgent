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
import com.nageoffer.ai.ragent.rag.config.IntentBootstrapProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 意图树冷启动初始化服务
 *
 * <p>委托 {@link IntentTreeService#initFromFactory()} 执行基于工厂的初始化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentTreeBootstrapService {

    private static final String BOOTSTRAP_LOCK_KEY = "ragent:intent:bootstrap:lock";
    private static final long LOCK_WAIT_SECONDS = 0L;
    private static final long LOCK_LEASE_SECONDS = 30L;

    private final IntentTreeCacheManager intentTreeCacheManager;
    private final RedissonClient redissonClient;
    private final IntentBootstrapProperties bootstrapProperties;
    private final IntentTreeService intentTreeService;

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
     * 启动或手动触发：补齐工厂中缺失节点（幂等）
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

        RLock lock = redissonClient.getLock(BOOTSTRAP_LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                log.info("意图树初始化未获取到分布式锁，跳过本次执行");
                return 0;
            }
            int created = intentTreeService.initFromFactory();
            if (created > 0) {
                intentTreeCacheManager.clearIntentTreeCache();
                log.info("意图树补齐完成，新增节点数: {}", created);
            } else {
                log.info("意图树已与工厂定义保持一致，无需补齐");
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
}
