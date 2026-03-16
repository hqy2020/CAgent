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

package com.openingcloud.ai.ragent.evaluation.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.openingcloud.ai.ragent.evaluation.controller.request.EvalDatasetCaseCreateRequest;
import com.openingcloud.ai.ragent.evaluation.controller.request.EvalDatasetCreateRequest;
import com.openingcloud.ai.ragent.evaluation.controller.vo.ConversationQAPairVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalDatasetCaseVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalDatasetVO;

import java.util.List;

public interface EvalDatasetService {

    EvalDatasetVO createDataset(EvalDatasetCreateRequest request);

    List<EvalDatasetVO> listDatasets();

    EvalDatasetVO getDataset(Long id);

    void deleteDataset(Long id);

    EvalDatasetCaseVO addCase(Long datasetId, EvalDatasetCaseCreateRequest request);

    int batchImportCases(Long datasetId, List<EvalDatasetCaseCreateRequest> cases);

    List<EvalDatasetCaseVO> listCases(Long datasetId);

    void deleteCase(Long datasetId, Long caseId);

    IPage<ConversationQAPairVO> listConversationQAPairs(String keyword, int current, int size);

    int importFromChat(Long datasetId, List<Long> messageIds);
}
