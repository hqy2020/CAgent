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

package com.openingcloud.ai.ragent.ingestion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.openingcloud.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.openingcloud.ai.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.openingcloud.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import com.openingcloud.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;

import java.util.List;

public interface IntentTreeService extends IService<IntentNodeDO> {

    /**
     * 查询整棵意图树（包含 RAG + SYSTEM）
     */
    List<IntentNodeTreeVO> getFullTree();

    /**
     * 新增节点
     */
    String createNode(IntentNodeCreateRequest requestParam);

    /**
     * 更新节点
     */
    void updateNode(String id, IntentNodeUpdateRequest requestParam);

    /**
     * 删除节点（逻辑删除）
     */
    void deleteNode(String id);

    /**
     * 批量启用节点
     */
    void batchEnableNodes(List<Long> ids);

    /**
     * 批量停用节点
     */
    void batchDisableNodes(List<Long> ids);

    /**
     * 批量删除节点（逻辑删除）
     */
    void batchDeleteNodes(List<Long> ids);

    /**
     * 从 IntentTreeFactory 初始化全量 Tree 到数据库
     */
    int initFromFactory();

    /**
     * 将 IntentTreeFactory 中的代码托管字段同步到数据库已有节点
     */
    IntentTreeSyncResult syncFromFactory();
}
