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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IntentTreeBootstrapServiceTests {

    private IntentTreeBootstrapService service;

    @BeforeEach
    void setUp() {
        service = new IntentTreeBootstrapService();
    }

    @Test
    void initializeManually_shouldReturnZero() {
        int result = service.initializeManually();
        assertEquals(0, result);
    }

    @Test
    void syncManually_shouldReturnNoChanges() {
        IntentTreeSyncResult result = service.syncManually();
        assertEquals(0, result.created());
        assertEquals(0, result.updated());
        assertEquals(0, result.repaired());
        assertFalse(result.hasChanges());
    }
}
