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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 知识图谱配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.knowledge-graph")
public class KnowledgeGraphProperties {

    /**
     * 总开关，默认关闭
     */
    private boolean enabled = false;

    /**
     * Neo4j 连接 URI
     */
    private String uri = "bolt://localhost:7687";

    /**
     * Neo4j 用户名
     */
    private String username = "neo4j";

    /**
     * Neo4j 密码
     */
    private String password = "ragent123";

    /**
     * 入库时每篇文档最多抽取的三元组数量
     */
    private int extractMaxTriples = 30;

    /**
     * 图遍历最大跳数
     */
    private int traversalMaxHops = 2;

    /**
     * 图遍历最大返回节点数
     */
    private int traversalMaxNodes = 20;

    /**
     * 是否启用图检索通道
     */
    private boolean searchChannelEnabled = true;

    /**
     * 是否启用 Agent GRAPH_QUERY 步骤
     */
    private boolean agentStepEnabled = true;
}
