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

package com.openingcloud.ai.ragent.rag.core.usermemory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag.user-memory")
@Validated
public class UserMemoryProperties {

    private Boolean enabled = true;

    @Min(1)
    @Max(50)
    private Integer recallTopK = 10;

    @Min(200)
    @Max(8000)
    private Integer recallBudgetTokens = 1500;

    private Double reconcileSimilarityThreshold = 0.85;

    @Min(1)
    @Max(30)
    private Integer maxInsightsPerSession = 10;

    @Min(100)
    @Max(1000)
    private Integer digestMaxChars = 300;

    @Min(100)
    @Max(2000)
    private Integer profileSummaryMaxChars = 500;

    private String milvusCollectionName = "ragent_user_memory";
}
