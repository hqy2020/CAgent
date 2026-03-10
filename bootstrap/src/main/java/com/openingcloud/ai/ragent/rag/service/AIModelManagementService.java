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

package com.openingcloud.ai.ragent.rag.service;

import com.openingcloud.ai.ragent.rag.controller.request.ModelCandidateRequest;
import com.openingcloud.ai.ragent.rag.controller.request.ModelProviderRequest;
import com.openingcloud.ai.ragent.rag.dao.entity.AIModelCandidateDO;
import com.openingcloud.ai.ragent.rag.dao.entity.AIModelProviderDO;

import java.util.List;

/**
 * AI 模型管理服务
 */
public interface AIModelManagementService {

    List<AIModelProviderDO> listProviders();

    Long createProvider(ModelProviderRequest request);

    void updateProvider(Long id, ModelProviderRequest request);

    void deleteProvider(Long id);

    List<AIModelCandidateDO> listCandidates(String type);

    Long createCandidate(ModelCandidateRequest request);

    void updateCandidate(Long id, ModelCandidateRequest request);

    void deleteCandidate(Long id);

    void setDefaultModel(Long id);

    void setDeepThinkingModel(Long id);
}
