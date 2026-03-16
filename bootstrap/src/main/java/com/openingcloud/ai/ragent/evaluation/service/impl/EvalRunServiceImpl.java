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

package com.openingcloud.ai.ragent.evaluation.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalRunCompareVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalRunReportVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalRunResultVO;
import com.openingcloud.ai.ragent.evaluation.controller.vo.EvalRunVO;
import com.openingcloud.ai.ragent.evaluation.dao.entity.EvalDatasetCaseDO;
import com.openingcloud.ai.ragent.evaluation.dao.entity.EvalDatasetDO;
import com.openingcloud.ai.ragent.evaluation.dao.entity.EvalRunDO;
import com.openingcloud.ai.ragent.evaluation.dao.entity.EvalRunResultDO;
import com.openingcloud.ai.ragent.evaluation.dao.mapper.EvalDatasetCaseMapper;
import com.openingcloud.ai.ragent.evaluation.dao.mapper.EvalDatasetMapper;
import com.openingcloud.ai.ragent.evaluation.dao.mapper.EvalRunMapper;
import com.openingcloud.ai.ragent.evaluation.dao.mapper.EvalRunResultMapper;
import com.openingcloud.ai.ragent.evaluation.engine.EvalCaseExecutor;
import com.openingcloud.ai.ragent.evaluation.service.EvalRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 评测运行服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalRunServiceImpl implements EvalRunService {

    private final EvalRunMapper evalRunMapper;
    private final EvalRunResultMapper evalRunResultMapper;
    private final EvalDatasetMapper evalDatasetMapper;
    private final EvalDatasetCaseMapper evalDatasetCaseMapper;
    private final EvalCaseExecutor evalCaseExecutor;

    @Qualifier("evaluationThreadPoolExecutor")
    private final Executor evaluationExecutor;

    @Override
    public EvalRunVO triggerRun(Long datasetId) {
        // 查询数据集
        EvalDatasetDO dataset = evalDatasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new RuntimeException("数据集不存在: " + datasetId);
        }

        // 查询所有用例
        List<EvalDatasetCaseDO> cases = evalDatasetCaseMapper.selectList(
                new LambdaQueryWrapper<EvalDatasetCaseDO>()
                        .eq(EvalDatasetCaseDO::getDatasetId, datasetId));
        if (cases.isEmpty()) {
            throw new RuntimeException("数据集中没有用例: " + datasetId);
        }

        // 创建运行记录
        EvalRunDO run = EvalRunDO.builder()
                .datasetId(datasetId)
                .status("RUNNING")
                .totalCases(cases.size())
                .completedCases(0)
                .startedAt(new Date())
                .build();
        evalRunMapper.insert(run);

        // 异步执行评测任务
        final Long runId = run.getId();
        CompletableFuture.runAsync(() -> executeRun(runId, cases), evaluationExecutor);

        return convertRunToVO(run, dataset.getName());
    }

    private void executeRun(Long runId, List<EvalDatasetCaseDO> cases) {
        try {
            int completed = 0;
            for (EvalDatasetCaseDO evalCase : cases) {
                try {
                    EvalRunResultDO result = evalCaseExecutor.execute(evalCase);
                    result.setRunId(runId);
                    evalRunResultMapper.insert(result);

                    completed++;
                    EvalRunDO update = new EvalRunDO();
                    update.setId(runId);
                    update.setCompletedCases(completed);
                    evalRunMapper.updateById(update);
                } catch (Exception e) {
                    log.error("评测用例执行异常, runId={}, caseId={}", runId, evalCase.getId(), e);
                }
            }

            // 计算聚合指标
            List<EvalRunResultDO> results = evalRunResultMapper.selectList(
                    new LambdaQueryWrapper<EvalRunResultDO>()
                            .eq(EvalRunResultDO::getRunId, runId));

            EvalRunDO finalUpdate = new EvalRunDO();
            finalUpdate.setId(runId);
            finalUpdate.setStatus("COMPLETED");
            finalUpdate.setFinishedAt(new Date());
            finalUpdate.setCompletedCases(results.size());
            finalUpdate.setAvgHitRate(avgOf(results, EvalRunResultDO::getHitRate));
            finalUpdate.setAvgMrr(avgOf(results, EvalRunResultDO::getMrr));
            finalUpdate.setAvgRecall(avgOf(results, EvalRunResultDO::getRecallScore));
            finalUpdate.setAvgPrecision(avgOf(results, EvalRunResultDO::getPrecisionScore));
            finalUpdate.setAvgFaithfulness(avgOf(results, EvalRunResultDO::getFaithfulnessScore));
            finalUpdate.setAvgRelevancy(avgOf(results, EvalRunResultDO::getRelevancyScore));
            finalUpdate.setAvgCorrectness(avgOf(results, EvalRunResultDO::getCorrectnessScore));
            finalUpdate.setBadCaseCount((int) results.stream()
                    .filter(r -> r.getIsBadCase() != null && r.getIsBadCase() == 1)
                    .count());
            evalRunMapper.updateById(finalUpdate);

        } catch (Exception e) {
            log.error("评测运行异常, runId={}", runId, e);
            EvalRunDO failUpdate = new EvalRunDO();
            failUpdate.setId(runId);
            failUpdate.setStatus("FAILED");
            failUpdate.setErrorMessage(e.getMessage());
            failUpdate.setFinishedAt(new Date());
            evalRunMapper.updateById(failUpdate);
        }
    }

    @Override
    public List<EvalRunVO> listRuns() {
        List<EvalRunDO> runs = evalRunMapper.selectList(
                new LambdaQueryWrapper<EvalRunDO>()
                        .orderByDesc(EvalRunDO::getCreateTime));

        // 批量查询数据集名称
        Map<Long, String> datasetNames = runs.stream()
                .map(EvalRunDO::getDatasetId)
                .distinct()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> {
                            EvalDatasetDO ds = evalDatasetMapper.selectById(id);
                            return ds != null ? ds.getName() : "";
                        }
                ));

        return runs.stream()
                .map(run -> convertRunToVO(run, datasetNames.getOrDefault(run.getDatasetId(), "")))
                .toList();
    }

    @Override
    public EvalRunVO getRun(Long runId) {
        EvalRunDO run = evalRunMapper.selectById(runId);
        if (run == null) {
            throw new RuntimeException("运行记录不存在: " + runId);
        }
        EvalDatasetDO dataset = evalDatasetMapper.selectById(run.getDatasetId());
        String datasetName = dataset != null ? dataset.getName() : "";
        return convertRunToVO(run, datasetName);
    }

    @Override
    public EvalRunReportVO getReport(Long runId) {
        EvalRunVO runVO = getRun(runId);

        List<EvalRunResultDO> results = evalRunResultMapper.selectList(
                new LambdaQueryWrapper<EvalRunResultDO>()
                        .eq(EvalRunResultDO::getRunId, runId));

        if (results.isEmpty()) {
            return EvalRunReportVO.builder().run(runVO).build();
        }

        int total = results.size();

        // 计算各维度均值
        BigDecimal hitRate = avgOf(results, EvalRunResultDO::getHitRate);
        BigDecimal mrr = avgOf(results, EvalRunResultDO::getMrr);
        BigDecimal recall = avgOf(results, EvalRunResultDO::getRecallScore);
        BigDecimal precision = avgOf(results, EvalRunResultDO::getPrecisionScore);
        BigDecimal faithfulness = avgOf(results, EvalRunResultDO::getFaithfulnessScore);
        BigDecimal relevancy = avgOf(results, EvalRunResultDO::getRelevancyScore);
        BigDecimal correctness = avgOf(results, EvalRunResultDO::getCorrectnessScore);

        // 幻觉率：faithfulness <= 2 的比例
        long hallucinationCount = results.stream()
                .filter(r -> r.getFaithfulnessScore() != null && r.getFaithfulnessScore().intValue() <= 2)
                .count();
        BigDecimal hallucinationRate = BigDecimal.valueOf(hallucinationCount)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);

        // 正确性通过率：correctness >= 4 的比例
        long correctnessPassCount = results.stream()
                .filter(r -> r.getCorrectnessScore() != null && r.getCorrectnessScore().intValue() >= 4)
                .count();
        BigDecimal correctnessPassRate = BigDecimal.valueOf(correctnessPassCount)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);

        // 兜底率
        long fallbackCount = results.stream()
                .filter(r -> r.getIsFallback() != null && r.getIsFallback() == 1)
                .count();
        BigDecimal fallbackRate = BigDecimal.valueOf(fallbackCount)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);

        // 分数分布
        Map<Integer, Integer> faithfulnessDist = buildScoreDistribution(results, EvalRunResultDO::getFaithfulnessScore);
        Map<Integer, Integer> relevancyDist = buildScoreDistribution(results, EvalRunResultDO::getRelevancyScore);
        Map<Integer, Integer> correctnessDist = buildScoreDistribution(results, EvalRunResultDO::getCorrectnessScore);

        return EvalRunReportVO.builder()
                .run(runVO)
                .hitRate(hitRate)
                .mrr(mrr)
                .recall(recall)
                .precision(precision)
                .faithfulness(faithfulness)
                .relevancy(relevancy)
                .hallucinationRate(hallucinationRate)
                .correctness(correctness)
                .correctnessPassRate(correctnessPassRate)
                .fallbackRate(fallbackRate)
                .faithfulnessDistribution(faithfulnessDist)
                .relevancyDistribution(relevancyDist)
                .correctnessDistribution(correctnessDist)
                .build();
    }

    @Override
    public List<EvalRunResultVO> getResults(Long runId, Integer page, Integer size) {
        Page<EvalRunResultDO> pageObj = new Page<>(page, size);
        Page<EvalRunResultDO> resultPage = evalRunResultMapper.selectPage(pageObj,
                new LambdaQueryWrapper<EvalRunResultDO>()
                        .eq(EvalRunResultDO::getRunId, runId)
                        .orderByAsc(EvalRunResultDO::getId));

        return resultPage.getRecords().stream()
                .map(this::convertResultToVO)
                .toList();
    }

    @Override
    public List<EvalRunResultVO> getBadCases(Long runId) {
        List<EvalRunResultDO> results = evalRunResultMapper.selectList(
                new LambdaQueryWrapper<EvalRunResultDO>()
                        .eq(EvalRunResultDO::getRunId, runId)
                        .eq(EvalRunResultDO::getIsBadCase, 1));

        return results.stream()
                .map(this::convertResultToVO)
                .toList();
    }

    // ==================== 辅助方法 ====================

    @Override
    public EvalRunCompareVO compareRuns(Long baseRunId, Long compareRunId) {
        EvalRunDO baseRun = evalRunMapper.selectById(baseRunId);
        EvalRunDO compareRun = evalRunMapper.selectById(compareRunId);
        if (baseRun == null || compareRun == null) {
            throw new IllegalArgumentException("运行记录不存在");
        }

        EvalDatasetDO baseDataset = evalDatasetMapper.selectById(baseRun.getDatasetId());
        EvalDatasetDO compareDataset = evalDatasetMapper.selectById(compareRun.getDatasetId());

        EvalRunVO baseVO = convertRunToVO(baseRun, baseDataset != null ? baseDataset.getName() : "");
        EvalRunVO compareVO = convertRunToVO(compareRun, compareDataset != null ? compareDataset.getName() : "");

        return EvalRunCompareVO.builder()
                .baseRun(baseVO)
                .compareRun(compareVO)
                .hitRateDelta(safeDelta(compareRun.getAvgHitRate(), baseRun.getAvgHitRate()))
                .mrrDelta(safeDelta(compareRun.getAvgMrr(), baseRun.getAvgMrr()))
                .recallDelta(safeDelta(compareRun.getAvgRecall(), baseRun.getAvgRecall()))
                .precisionDelta(safeDelta(compareRun.getAvgPrecision(), baseRun.getAvgPrecision()))
                .faithfulnessDelta(safeDelta(compareRun.getAvgFaithfulness(), baseRun.getAvgFaithfulness()))
                .relevancyDelta(safeDelta(compareRun.getAvgRelevancy(), baseRun.getAvgRelevancy()))
                .correctnessDelta(safeDelta(compareRun.getAvgCorrectness(), baseRun.getAvgCorrectness()))
                .badCaseCountDelta(safeIntDelta(compareRun.getBadCaseCount(), baseRun.getBadCaseCount()))
                .build();
    }

    private BigDecimal safeDelta(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return null;
        }
        return a.subtract(b);
    }

    private Integer safeIntDelta(Integer a, Integer b) {
        if (a == null || b == null) {
            return null;
        }
        return a - b;
    }

    private EvalRunVO convertRunToVO(EvalRunDO run, String datasetName) {
        return EvalRunVO.builder()
                .id(String.valueOf(run.getId()))
                .datasetId(String.valueOf(run.getDatasetId()))
                .datasetName(datasetName)
                .status(run.getStatus())
                .totalCases(run.getTotalCases())
                .completedCases(run.getCompletedCases())
                .avgHitRate(run.getAvgHitRate())
                .avgMrr(run.getAvgMrr())
                .avgRecall(run.getAvgRecall())
                .avgPrecision(run.getAvgPrecision())
                .avgFaithfulness(run.getAvgFaithfulness())
                .avgRelevancy(run.getAvgRelevancy())
                .avgCorrectness(run.getAvgCorrectness())
                .badCaseCount(run.getBadCaseCount())
                .errorMessage(run.getErrorMessage())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .createTime(run.getCreateTime())
                .build();
    }

    private EvalRunResultVO convertResultToVO(EvalRunResultDO result) {
        // 查询关联的用例信息
        EvalDatasetCaseDO evalCase = evalDatasetCaseMapper.selectById(result.getCaseId());

        List<String> chunkIds = List.of();
        if (StrUtil.isNotBlank(result.getRetrievedChunkIds())) {
            try {
                chunkIds = JSON.parseArray(result.getRetrievedChunkIds(), String.class);
            } catch (Exception ignored) {
            }
        }

        return EvalRunResultVO.builder()
                .id(String.valueOf(result.getId()))
                .runId(String.valueOf(result.getRunId()))
                .caseId(String.valueOf(result.getCaseId()))
                .query(evalCase != null ? evalCase.getQuery() : null)
                .expectedAnswer(evalCase != null ? evalCase.getExpectedAnswer() : null)
                .hitRate(result.getHitRate())
                .mrr(result.getMrr())
                .recallScore(result.getRecallScore())
                .precisionScore(result.getPrecisionScore())
                .retrievedChunkIds(chunkIds)
                .generatedAnswer(result.getGeneratedAnswer())
                .faithfulnessScore(result.getFaithfulnessScore())
                .faithfulnessReason(result.getFaithfulnessReason())
                .relevancyScore(result.getRelevancyScore())
                .relevancyReason(result.getRelevancyReason())
                .correctnessScore(result.getCorrectnessScore())
                .correctnessReason(result.getCorrectnessReason())
                .isFallback(result.getIsFallback() != null && result.getIsFallback() == 1)
                .isBadCase(result.getIsBadCase() != null && result.getIsBadCase() == 1)
                .rootCause(result.getRootCause())
                .latencyMs(result.getLatencyMs())
                .build();
    }

    private BigDecimal avgOf(List<EvalRunResultDO> results,
                             java.util.function.Function<EvalRunResultDO, BigDecimal> getter) {
        List<BigDecimal> values = results.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private Map<Integer, Integer> buildScoreDistribution(
            List<EvalRunResultDO> results,
            java.util.function.Function<EvalRunResultDO, BigDecimal> getter) {
        Map<Integer, Integer> distribution = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            distribution.put(i, 0);
        }
        for (EvalRunResultDO result : results) {
            BigDecimal score = getter.apply(result);
            if (score != null) {
                int key = Math.max(1, Math.min(5, score.intValue()));
                distribution.merge(key, 1, Integer::sum);
            }
        }
        return distribution;
    }
}
