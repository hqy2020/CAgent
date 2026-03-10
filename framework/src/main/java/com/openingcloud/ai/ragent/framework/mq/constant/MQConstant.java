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

package com.openingcloud.ai.ragent.framework.mq.constant;

public final class MQConstant {

    public static final String TOPIC_KNOWLEDGE_CHUNK = "ragent_knowledge_chunk";
    public static final String TOPIC_INGESTION_EXECUTE = "ragent_ingestion_execute";
    public static final String TOPIC_KNOWLEDGE_SCHEDULE_REFRESH = "ragent_knowledge_schedule_refresh";

    public static final String CG_KNOWLEDGE_CHUNK = "ragent_knowledge_chunk_cg";
    public static final String CG_INGESTION_EXECUTE = "ragent_ingestion_execute_cg";
    public static final String CG_KNOWLEDGE_SCHEDULE_REFRESH = "ragent_knowledge_schedule_cg";

    public static final String IDEM_PREFIX_CHUNK = "mq:idem:chunk:";
    public static final String IDEM_PREFIX_INGESTION = "mq:idem:ingestion:";
    public static final String IDEM_PREFIX_SCHEDULE = "mq:idem:schedule:";

    private MQConstant() {
    }
}
