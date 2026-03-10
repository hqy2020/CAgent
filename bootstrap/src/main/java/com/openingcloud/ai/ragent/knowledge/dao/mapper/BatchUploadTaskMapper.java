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

package com.openingcloud.ai.ragent.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openingcloud.ai.ragent.knowledge.dao.entity.BatchUploadTaskDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 批量上传任务 Mapper
 */
public interface BatchUploadTaskMapper extends BaseMapper<BatchUploadTaskDO> {

    @Update("UPDATE t_batch_upload_task SET uploaded_count = uploaded_count + 1 WHERE id = #{id}")
    int incrementUploadedCount(@Param("id") Long id);

    @Update("UPDATE t_batch_upload_task SET success_count = success_count + 1 WHERE id = #{id}")
    int incrementSuccessCount(@Param("id") Long id);

    @Update("UPDATE t_batch_upload_task SET failed_count = failed_count + 1 WHERE id = #{id}")
    int incrementFailedCount(@Param("id") Long id);
}
