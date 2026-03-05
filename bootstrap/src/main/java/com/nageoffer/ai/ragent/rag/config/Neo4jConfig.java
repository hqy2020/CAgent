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

package com.nageoffer.ai.ragent.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j Driver 配置
 * 仅在 rag.knowledge-graph.enabled=true 时激活
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.knowledge-graph", name = "enabled", havingValue = "true")
public class Neo4jConfig {

    private final KnowledgeGraphProperties properties;

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver() {
        log.info("初始化 Neo4j Driver: {}", properties.getUri());
        return GraphDatabase.driver(
                properties.getUri(),
                AuthTokens.basic(properties.getUsername(), properties.getPassword())
        );
    }

    @PostConstruct
    public void initConstraints() {
        try (Driver driver = GraphDatabase.driver(
                properties.getUri(),
                AuthTokens.basic(properties.getUsername(), properties.getPassword()));
             Session session = driver.session()) {
            session.run("CREATE CONSTRAINT IF NOT EXISTS FOR (e:Entity) REQUIRE (e.name, e.kbId) IS UNIQUE");
            log.info("Neo4j 约束索引初始化完成");
        } catch (Exception e) {
            log.warn("Neo4j 约束索引初始化失败（Neo4j 可能未启动），应用将继续运行: {}", e.getMessage());
        }
    }
}
