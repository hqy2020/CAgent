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

package com.openingcloud.ai.ragent.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.openingcloud.ai.ragent.framework.context.UserContext;
import com.openingcloud.ai.ragent.framework.exception.ClientException;
import com.openingcloud.ai.ragent.knowledge.controller.request.BatchUploadCreateRequest;
import com.openingcloud.ai.ragent.knowledge.controller.vo.BatchUploadItemVO;
import com.openingcloud.ai.ragent.knowledge.controller.vo.BatchUploadTaskVO;
import com.openingcloud.ai.ragent.knowledge.dao.entity.BatchUploadItemDO;
import com.openingcloud.ai.ragent.knowledge.dao.entity.BatchUploadTaskDO;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.openingcloud.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.BatchUploadItemMapper;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.BatchUploadTaskMapper;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.openingcloud.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.openingcloud.ai.ragent.knowledge.enums.BatchItemStatus;
import com.openingcloud.ai.ragent.knowledge.enums.BatchTaskStatus;
import com.openingcloud.ai.ragent.knowledge.enums.DocumentStatus;
import com.openingcloud.ai.ragent.knowledge.service.BatchUploadService;
import com.openingcloud.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.openingcloud.ai.ragent.rag.dto.StoredFileDTO;
import com.openingcloud.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchUploadServiceImpl implements BatchUploadService {

    private final BatchUploadTaskMapper taskMapper;
    private final BatchUploadItemMapper itemMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final KnowledgeDocumentService documentService;
    private final FileStorageService fileStorageService;
    private final RedissonClient redissonClient;
    @Qualifier("batchUploadExecutor")
    private final Executor batchUploadExecutor;

    @Value("${rag.knowledge.batch.max-concurrent:3}")
    private int maxConcurrent;

    @Value("${rag.knowledge.batch.max-files-per-batch:50}")
    private int maxFilesPerBatch;

    @Override
    public BatchUploadTaskVO createBatchTask(String kbId, BatchUploadCreateRequest request) {
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        List<String> fileNames = request.getFileNames();
        if (CollUtil.isEmpty(fileNames)) {
            throw new ClientException("文件名列表不能为空");
        }
        if (fileNames.size() > maxFilesPerBatch) {
            throw new ClientException("单批最多上传 " + maxFilesPerBatch + " 个文件");
        }

        // 构建分块配置 JSON
        String chunkConfig = request.getChunkConfig();

        BatchUploadTaskDO taskDO = BatchUploadTaskDO.builder()
                .kbId(Long.parseLong(kbId))
                .totalCount(fileNames.size())
                .uploadedCount(0)
                .successCount(0)
                .failedCount(0)
                .status(BatchTaskStatus.UPLOADING.getCode())
                .processMode(request.getProcessMode())
                .chunkStrategy(request.getChunkStrategy())
                .chunkConfig(chunkConfig)
                .pipelineId(request.getPipelineId() != null ? Long.parseLong(request.getPipelineId()) : null)
                .createdBy(UserContext.getUsername())
                .build();
        taskMapper.insert(taskDO);

        List<BatchUploadItemDO> items = new ArrayList<>();
        for (String fileName : fileNames) {
            BatchUploadItemDO itemDO = BatchUploadItemDO.builder()
                    .batchId(taskDO.getId())
                    .fileName(fileName)
                    .status(BatchItemStatus.PENDING.getCode())
                    .build();
            itemMapper.insert(itemDO);
            items.add(itemDO);
        }

        return toTaskVO(taskDO, items);
    }

    @Override
    public void uploadItem(String batchId, String itemId, MultipartFile file) {
        BatchUploadTaskDO taskDO = taskMapper.selectById(batchId);
        Assert.notNull(taskDO, () -> new ClientException("批量任务不存在"));

        BatchUploadItemDO itemDO = itemMapper.selectById(itemId);
        Assert.notNull(itemDO, () -> new ClientException("上传子项不存在"));
        if (!itemDO.getBatchId().equals(taskDO.getId())) {
            throw new ClientException("子项不属于该批量任务");
        }

        KnowledgeBaseDO kbDO = kbMapper.selectById(taskDO.getKbId());
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        String bucketName = kbDO.getCollectionName().replace('_', '-');
        StoredFileDTO stored = fileStorageService.upload(bucketName, file);

        LambdaUpdateWrapper<BatchUploadItemDO> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BatchUploadItemDO::getId, itemDO.getId())
                .set(BatchUploadItemDO::getFileUrl, stored.getUrl())
                .set(BatchUploadItemDO::getFileSize, stored.getSize())
                .set(BatchUploadItemDO::getFileType, stored.getDetectedType())
                .set(BatchUploadItemDO::getStatus, BatchItemStatus.UPLOADED.getCode());
        itemMapper.update(null, updateWrapper);

        taskMapper.incrementUploadedCount(taskDO.getId());
    }

    @Override
    public void startBatchProcess(String batchId) {
        BatchUploadTaskDO taskDO = taskMapper.selectById(batchId);
        Assert.notNull(taskDO, () -> new ClientException("批量任务不存在"));

        if (!BatchTaskStatus.UPLOADING.getCode().equals(taskDO.getStatus())) {
            throw new ClientException("任务状态不允许启动处理");
        }

        // 验证所有 item 均已上传
        List<BatchUploadItemDO> items = itemMapper.selectList(
                new LambdaQueryWrapper<BatchUploadItemDO>()
                        .eq(BatchUploadItemDO::getBatchId, taskDO.getId()));

        long notUploaded = items.stream()
                .filter(i -> !BatchItemStatus.UPLOADED.getCode().equals(i.getStatus()))
                .count();
        if (notUploaded > 0) {
            throw new ClientException("还有 " + notUploaded + " 个文件未上传完成");
        }

        // 更新任务状态为 processing
        LambdaUpdateWrapper<BatchUploadTaskDO> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BatchUploadTaskDO::getId, taskDO.getId())
                .set(BatchUploadTaskDO::getStatus, BatchTaskStatus.PROCESSING.getCode());
        taskMapper.update(null, updateWrapper);

        // 异步提交批量处理
        batchUploadExecutor.execute(() -> processBatchItems(taskDO, items));
    }

    @Override
    public BatchUploadTaskVO getProgress(String batchId) {
        BatchUploadTaskDO taskDO = taskMapper.selectById(batchId);
        Assert.notNull(taskDO, () -> new ClientException("批量任务不存在"));

        List<BatchUploadItemDO> items = itemMapper.selectList(
                new LambdaQueryWrapper<BatchUploadItemDO>()
                        .eq(BatchUploadItemDO::getBatchId, taskDO.getId()));

        return toTaskVO(taskDO, items);
    }

    private void processBatchItems(BatchUploadTaskDO taskDO, List<BatchUploadItemDO> items) {
        String semaphoreKey = "batch:upload:semaphore:" + taskDO.getId();
        RSemaphore semaphore = redissonClient.getSemaphore(semaphoreKey);
        semaphore.trySetPermits(maxConcurrent);

        CountDownLatch latch = new CountDownLatch(items.size());

        for (BatchUploadItemDO item : items) {
            batchUploadExecutor.execute(() -> {
                try {
                    boolean acquired = semaphore.tryAcquire(5, TimeUnit.MINUTES);
                    if (!acquired) {
                        markItemFailed(item.getId(), "等待处理槽位超时（5分钟）");
                        taskMapper.incrementFailedCount(taskDO.getId());
                        return;
                    }

                    try {
                        processSingleItem(taskDO, item);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    markItemFailed(item.getId(), "处理被中断");
                    taskMapper.incrementFailedCount(taskDO.getId());
                } catch (Exception e) {
                    log.error("批量处理子项异常: itemId={}", item.getId(), e);
                    markItemFailed(item.getId(), e.getMessage());
                    taskMapper.incrementFailedCount(taskDO.getId());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("批量处理等待被中断: batchId={}", taskDO.getId());
        }

        // 刷新计数并更新最终状态
        BatchUploadTaskDO latest = taskMapper.selectById(taskDO.getId());
        String finalStatus = latest.getFailedCount() > 0
                ? BatchTaskStatus.PARTIAL_FAILED.getCode()
                : BatchTaskStatus.COMPLETED.getCode();

        LambdaUpdateWrapper<BatchUploadTaskDO> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BatchUploadTaskDO::getId, taskDO.getId())
                .set(BatchUploadTaskDO::getStatus, finalStatus);
        taskMapper.update(null, updateWrapper);

        // 清理信号量
        semaphore.delete();
    }

    private void processSingleItem(BatchUploadTaskDO taskDO, BatchUploadItemDO item) {
        // 更新子项状态为 processing
        markItemStatus(item.getId(), BatchItemStatus.PROCESSING.getCode());

        try {
            // 创建文档记录
            KnowledgeDocumentDO documentDO = KnowledgeDocumentDO.builder()
                    .kbId(taskDO.getKbId())
                    .docName(item.getFileName())
                    .enabled(1)
                    .chunkCount(0)
                    .fileUrl(item.getFileUrl())
                    .fileType(item.getFileType())
                    .fileSize(item.getFileSize())
                    .status(DocumentStatus.PENDING.getCode())
                    .sourceType("file")
                    .processMode(taskDO.getProcessMode())
                    .chunkStrategy(taskDO.getChunkStrategy())
                    .chunkConfig(taskDO.getChunkConfig())
                    .pipelineId(taskDO.getPipelineId())
                    .createdBy(taskDO.getCreatedBy())
                    .updatedBy(taskDO.getCreatedBy())
                    .build();
            docMapper.insert(documentDO);

            // 关联文档 ID 到子项
            LambdaUpdateWrapper<BatchUploadItemDO> docUpdate = new LambdaUpdateWrapper<>();
            docUpdate.eq(BatchUploadItemDO::getId, item.getId())
                    .set(BatchUploadItemDO::getDocId, documentDO.getId());
            itemMapper.update(null, docUpdate);

            // 更新文档状态为 running
            LambdaUpdateWrapper<KnowledgeDocumentDO> statusUpdate = new LambdaUpdateWrapper<>();
            statusUpdate.eq(KnowledgeDocumentDO::getId, documentDO.getId())
                    .set(KnowledgeDocumentDO::getStatus, "running");
            docMapper.update(null, statusUpdate);

            // 同步执行分块入库
            documentService.runChunkTask(documentDO);

            // 更新子项为成功
            markItemStatus(item.getId(), BatchItemStatus.SUCCESS.getCode());
            taskMapper.incrementSuccessCount(taskDO.getId());

        } catch (Exception e) {
            log.error("批量处理单项失败: itemId={}, fileName={}", item.getId(), item.getFileName(), e);
            markItemFailed(item.getId(), e.getMessage());
            taskMapper.incrementFailedCount(taskDO.getId());
        }
    }

    private void markItemStatus(Long itemId, String status) {
        LambdaUpdateWrapper<BatchUploadItemDO> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BatchUploadItemDO::getId, itemId)
                .set(BatchUploadItemDO::getStatus, status);
        itemMapper.update(null, wrapper);
    }

    private void markItemFailed(Long itemId, String errorMessage) {
        LambdaUpdateWrapper<BatchUploadItemDO> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BatchUploadItemDO::getId, itemId)
                .set(BatchUploadItemDO::getStatus, BatchItemStatus.FAILED.getCode())
                .set(BatchUploadItemDO::getErrorMessage, errorMessage != null && errorMessage.length() > 500
                        ? errorMessage.substring(0, 500)
                        : errorMessage);
        itemMapper.update(null, wrapper);
    }

    private BatchUploadTaskVO toTaskVO(BatchUploadTaskDO taskDO, List<BatchUploadItemDO> items) {
        BatchUploadTaskVO vo = new BatchUploadTaskVO();
        vo.setId(String.valueOf(taskDO.getId()));
        vo.setKbId(String.valueOf(taskDO.getKbId()));
        vo.setTotalCount(taskDO.getTotalCount());
        vo.setUploadedCount(taskDO.getUploadedCount());
        vo.setSuccessCount(taskDO.getSuccessCount());
        vo.setFailedCount(taskDO.getFailedCount());
        vo.setStatus(taskDO.getStatus());
        vo.setProcessMode(taskDO.getProcessMode());
        vo.setChunkStrategy(taskDO.getChunkStrategy());
        vo.setCreateTime(taskDO.getCreateTime());

        if (CollUtil.isNotEmpty(items)) {
            List<BatchUploadItemVO> itemVOs = items.stream().map(item -> {
                BatchUploadItemVO itemVO = new BatchUploadItemVO();
                itemVO.setId(String.valueOf(item.getId()));
                itemVO.setFileName(item.getFileName());
                itemVO.setFileSize(item.getFileSize());
                itemVO.setFileType(item.getFileType());
                itemVO.setStatus(item.getStatus());
                itemVO.setErrorMessage(item.getErrorMessage());
                itemVO.setCreateTime(item.getCreateTime());
                return itemVO;
            }).toList();
            vo.setItems(itemVOs);
        }

        return vo;
    }
}
