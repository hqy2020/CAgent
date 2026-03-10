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

package com.openingcloud.ai.ragent.admin.controller.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class HotspotMonitorSaveRequest {

    @NotBlank(message = "监控关键词不能为空")
    private String keyword;

    private List<String> sources;

    private Boolean enabled;

    private String email;

    private Boolean emailEnabled;

    private Boolean websocketEnabled;

    @Min(value = 5, message = "扫描间隔不能低于 5 分钟")
    @Max(value = 1440, message = "扫描间隔不能超过 1440 分钟")
    private Integer scanIntervalMinutes;

    @DecimalMin(value = "0.1", message = "相关性阈值不能低于 0.1")
    @DecimalMax(value = "1.0", message = "相关性阈值不能高于 1.0")
    private BigDecimal relevanceThreshold;

    @DecimalMin(value = "0.1", message = "可信度阈值不能低于 0.1")
    @DecimalMax(value = "1.0", message = "可信度阈值不能高于 1.0")
    private BigDecimal credibilityThreshold;
}
