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

package com.nageoffer.ai.ragent.rag.exception;

/**
 * 任务被取消时抛出的异常
 * <p>
 * 继承 RuntimeException（非 ServiceException），避免被 GlobalExceptionHandler 拦截。
 * 覆写 fillInStackTrace() 跳过堆栈采集，因为取消是预期的流控行为，无需堆栈信息。
 */
public class TaskCancelledException extends RuntimeException {

    public TaskCancelledException() {
        super("任务已被取消");
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
