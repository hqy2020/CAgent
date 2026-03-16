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

package com.openingcloud.ai.ragent.infra.chat;

import com.alibaba.ttl.TransmittableThreadLocal;

public class ReasoningTraceContext {

    private static final TransmittableThreadLocal<ReasoningTraceListener> LISTENER = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<String> STEP_LABEL = new TransmittableThreadLocal<>();

    public static void setListener(ReasoningTraceListener listener) {
        LISTENER.set(listener);
    }

    public static ReasoningTraceListener getListener() {
        return LISTENER.get();
    }

    public static void setStepLabel(String label) {
        STEP_LABEL.set(label);
    }

    public static String getStepLabel() {
        return STEP_LABEL.get();
    }

    public static void clear() {
        LISTENER.remove();
        STEP_LABEL.remove();
    }
}
