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

package com.nageoffer.ai.ragent.rag.core.mcp.executor;

import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 销售数据查询工具执行器
 * <p>
 * 支持按地区、时间、产品、销售人员等维度查询销售数据
 * 支持汇总、排名、明细、趋势等多种查询类型
 */
@Slf4j
@Component
public class SalesMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "sales_query";

    // 地区列表
    private static final List<String> REGIONS = List.of("华东", "华南", "华北", "西南", "西北");

    // 产品列表
    private static final List<String> PRODUCTS = List.of("企业版", "专业版", "基础版");

    // 销售人员（按地区分配）
    private static final Map<String, List<String>> SALES_BY_REGION = Map.of(
            "华东", List.of("张三", "李四", "王五"),
            "华南", List.of("赵六", "钱七", "孙八"),
            "华北", List.of("周九", "吴十", "郑冬"),
            "西南", List.of("陈春", "林夏", "黄秋"),
            "西北", List.of("刘一", "杨二", "马三")
    );

    // 客户名称池
    private static final List<String> CUSTOMER_POOL = List.of(
            "腾讯科技", "阿里巴巴", "字节跳动", "美团点评", "京东集团",
            "百度在线", "网易公司", "小米科技", "华为技术", "中兴通讯",
            "用友网络", "金蝶软件", "浪潮集团", "东软集团", "科大讯飞",
            "三一重工", "中联重科", "格力电器", "美的集团", "海尔智家"
    );

    // 模拟数据缓存（保证同一会话数据一致）
    private List<SalesRecord> cachedData;
    private String cacheKey;

    @Override
    public MCPTool getToolDefinition() {
        Map<String, MCPTool.ParameterDef> params = new LinkedHashMap<>();

        params.put("region", MCPTool.ParameterDef.builder()
                .description("地区筛选：华东、华南、华北、西南、西北，不填则查询全国")
                .type("string")
                .required(false)
                .enumValues(REGIONS)
                .build());

        params.put("period", MCPTool.ParameterDef.builder()
                .description("时间段：本月、上月、本季度、上季度、本年，默认本月")
                .type("string")
                .required(false)
                .defaultValue("本月")
                .enumValues(List.of("本月", "上月", "本季度", "上季度", "本年"))
                .build());

        params.put("product", MCPTool.ParameterDef.builder()
                .description("产品筛选：企业版、专业版、基础版，不填则查询全部产品")
                .type("string")
                .required(false)
                .enumValues(PRODUCTS)
                .build());

        params.put("salesPerson", MCPTool.ParameterDef.builder()
                .description("销售人员姓名，不填则查询全部销售")
                .type("string")
                .required(false)
                .build());

        params.put("queryType", MCPTool.ParameterDef.builder()
                .description("查询类型：summary(汇总)、ranking(排名)、detail(明细)、trend(趋势)")
                .type("string")
                .required(false)
                .defaultValue("summary")
                .enumValues(List.of("summary", "ranking", "detail", "trend"))
                .build());

        params.put("limit", MCPTool.ParameterDef.builder()
                .description("返回记录数限制，默认10")
                .type("number")
                .required(false)
                .defaultValue(10)
                .build());

        return MCPTool.builder()
                .toolId(TOOL_ID)
                .name("销售数据查询")
                .description("查询软件销售数据，支持按地区、时间、产品、销售人员等维度筛选，支持汇总统计、排名、明细列表等多种查询")
                .examples(List.of(
                        "华东区这个月销售额多少？",
                        "张三这个月业绩怎么样？",
                        "哪个地区销售最好？",
                        "本月销售排名前五是谁？",
                        "企业版这个月卖了多少？",
                        "上个月各产品销售情况"
                ))
                .parameters(params)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPResponse execute(MCPRequest request) {
        // 解析参数
        String region = request.getStringParameter("region");
        String period = request.getStringParameter("period");
        String product = request.getStringParameter("product");
        String salesPerson = request.getStringParameter("salesPerson");
        String queryType = request.getStringParameter("queryType");
        Number limitValue = request.getParameter("limit");
        Integer limit = limitValue == null ? null : limitValue.intValue();

        // 默认值
        if (period == null || period.isBlank()) period = "本月";
        if (queryType == null || queryType.isBlank()) queryType = "summary";
        if (limit == null || limit <= 0) limit = 10;

        // 生成或获取模拟数据
        List<SalesRecord> allData = getOrGenerateData(period);

        // 过滤数据
        List<SalesRecord> filtered = filterData(allData, region, product, salesPerson);

        // 根据查询类型生成结果
        Map<String, Object> resultData = new HashMap<>();
        String textResult;

        switch (queryType) {
            case "ranking" -> textResult = buildRankingResult(filtered, region, period, limit, resultData);
            case "detail" -> textResult = buildDetailResult(filtered, region, period, limit, resultData);
            case "trend" -> textResult = buildTrendResult(filtered, region, period, resultData);
            default -> textResult = buildSummaryResult(filtered, region, period, product, salesPerson, resultData);
        }

        return MCPResponse.success(TOOL_ID, textResult, resultData);
    }

    /**
     * 汇总统计
     */
    private String buildSummaryResult(List<SalesRecord> data, String region, String period,
                                      String product, String salesPerson, Map<String, Object> resultData) {
        double totalAmount = data.stream().mapToDouble(r -> r.amount).sum();
        int orderCount = data.size();
        double avgAmount = orderCount > 0 ? totalAmount / orderCount : 0;

        // 按产品统计
        Map<String, Double> byProduct = data.stream()
                .collect(Collectors.groupingBy(r -> r.product, Collectors.summingDouble(r -> r.amount)));

        // 按地区统计
        Map<String, Double> byRegion = data.stream()
                .collect(Collectors.groupingBy(r -> r.region, Collectors.summingDouble(r -> r.amount)));

        resultData.put("totalAmount", totalAmount);
        resultData.put("orderCount", orderCount);
        resultData.put("avgAmount", avgAmount);
        resultData.put("byProduct", byProduct);
        resultData.put("byRegion", byRegion);

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(period).append(" 销售数据汇总】\n\n");

        // 筛选条件说明
        List<String> filters = new ArrayList<>();
        if (region != null) filters.add("地区: " + region);
        if (product != null) filters.add("产品: " + product);
        if (salesPerson != null) filters.add("销售: " + salesPerson);
        if (!filters.isEmpty()) {
            sb.append("筛选条件: ").append(String.join("，", filters)).append("\n\n");
        }

        sb.append(String.format("总销售额: ¥%.2f 万\n", totalAmount));
        sb.append(String.format("成交订单: %d 笔\n", orderCount));
        sb.append(String.format("平均单价: ¥%.2f 万\n", avgAmount));

        if (product == null && !byProduct.isEmpty()) {
            sb.append("\n【按产品分布】\n");
            byProduct.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append(String.format("  %s: ¥%.2f 万 (%.1f%%)\n",
                            e.getKey(), e.getValue(), e.getValue() / totalAmount * 100)));
        }

        if (region == null && !byRegion.isEmpty()) {
            sb.append("\n【按地区分布】\n");
            byRegion.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append(String.format("  %s: ¥%.2f 万 (%.1f%%)\n",
                            e.getKey(), e.getValue(), e.getValue() / totalAmount * 100)));
        }

        return sb.toString().trim();
    }

    /**
     * 排名统计
     */
    private String buildRankingResult(List<SalesRecord> data, String region, String period,
                                      int limit, Map<String, Object> resultData) {
        // 按销售人员汇总
        Map<String, Double> bySales = data.stream()
                .collect(Collectors.groupingBy(r -> r.salesPerson, Collectors.summingDouble(r -> r.amount)));

        List<Map.Entry<String, Double>> ranking = bySales.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .toList();

        resultData.put("ranking", ranking.stream()
                .map(e -> Map.of("name", e.getKey(), "amount", e.getValue()))
                .toList());

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(period);
        if (region != null) sb.append(" ").append(region);
        sb.append(" 销售排名】\n\n");

        if (ranking.isEmpty()) {
            sb.append("暂无销售数据");
        } else {
            for (int i = 0; i < ranking.size(); i++) {
                Map.Entry<String, Double> entry = ranking.get(i);
                String medal = i == 0 ? "🥇" : i == 1 ? "🥈" : i == 2 ? "🥉" : "  ";
                sb.append(String.format("%s 第%d名: %s - ¥%.2f 万\n",
                        medal, i + 1, entry.getKey(), entry.getValue()));
            }
        }

        return sb.toString().trim();
    }

    /**
     * 明细列表
     */
    private String buildDetailResult(List<SalesRecord> data, String region, String period,
                                     int limit, Map<String, Object> resultData) {
        List<SalesRecord> topRecords = data.stream()
                .sorted((a, b) -> Double.compare(b.amount, a.amount))
                .limit(limit)
                .toList();

        resultData.put("records", topRecords.stream().map(SalesRecord::toMap).toList());
        resultData.put("total", data.size());

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(period);
        if (region != null) sb.append(" ").append(region);
        sb.append(" 销售明细】\n\n");

        sb.append(String.format("共 %d 条记录，显示金额最高的 %d 条：\n\n", data.size(), topRecords.size()));

        for (int i = 0; i < topRecords.size(); i++) {
            SalesRecord r = topRecords.get(i);
            sb.append(String.format("%d. %s\n", i + 1, r.customer));
            sb.append(String.format("   产品: %s | 金额: ¥%.2f 万\n", r.product, r.amount));
            sb.append(String.format("   销售: %s | 地区: %s | 日期: %s\n\n", r.salesPerson, r.region, r.date));
        }

        return sb.toString().trim();
    }

    /**
     * 趋势分析
     */
    private String buildTrendResult(List<SalesRecord> data, String region, String period,
                                    Map<String, Object> resultData) {
        // 按周汇总
        Map<String, Double> byWeek = data.stream()
                .collect(Collectors.groupingBy(
                        r -> "第" + ((LocalDate.parse(r.date).getDayOfMonth() - 1) / 7 + 1) + "周",
                        Collectors.summingDouble(r -> r.amount)
                ));

        resultData.put("byWeek", byWeek);

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(period);
        if (region != null) sb.append(" ").append(region);
        sb.append(" 销售趋势】\n\n");

        if (byWeek.isEmpty()) {
            sb.append("暂无数据");
        } else {
            double total = byWeek.values().stream().mapToDouble(d -> d).sum();
            byWeek.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        int bars = (int) (e.getValue() / total * 20);
                        sb.append(String.format("%s: ¥%.2f 万 %s\n",
                                e.getKey(), e.getValue(), "█".repeat(Math.max(1, bars))));
                    });
        }

        return sb.toString().trim();
    }

    /**
     * 过滤数据
     */
    private List<SalesRecord> filterData(List<SalesRecord> data, String region, String product, String salesPerson) {
        return data.stream()
                .filter(r -> region == null || region.equals(r.region))
                .filter(r -> product == null || product.equals(r.product))
                .filter(r -> salesPerson == null || salesPerson.equals(r.salesPerson))
                .toList();
    }

    /**
     * 生成或获取缓存的模拟数据
     */
    private List<SalesRecord> getOrGenerateData(String period) {
        String key = period + "_" + LocalDate.now();
        if (cachedData != null && key.equals(cacheKey)) {
            return cachedData;
        }

        LocalDate[] dateRange = getDateRange(period);
        cachedData = generateMockData(dateRange[0], dateRange[1]);
        cacheKey = key;
        return cachedData;
    }

    /**
     * 获取日期范围
     */
    private LocalDate[] getDateRange(String period) {
        LocalDate now = LocalDate.now();
        return switch (period) {
            case "上月" -> new LocalDate[]{now.minusMonths(1).withDayOfMonth(1),
                    now.withDayOfMonth(1).minusDays(1)};
            case "本季度" -> {
                int quarter = (now.getMonthValue() - 1) / 3;
                LocalDate start = now.withMonth(quarter * 3 + 1).withDayOfMonth(1);
                yield new LocalDate[]{start, now};
            }
            case "上季度" -> {
                int quarter = (now.getMonthValue() - 1) / 3;
                LocalDate end = now.withMonth(quarter * 3 + 1).withDayOfMonth(1).minusDays(1);
                LocalDate start = end.withMonth(((quarter - 1 + 4) % 4) * 3 + 1).withDayOfMonth(1);
                yield new LocalDate[]{start, end};
            }
            case "本年" -> new LocalDate[]{now.withDayOfYear(1), now};
            default -> new LocalDate[]{now.withDayOfMonth(1), now}; // 本月
        };
    }

    /**
     * 生成模拟销售数据
     */
    private List<SalesRecord> generateMockData(LocalDate start, LocalDate end) {
        List<SalesRecord> records = new ArrayList<>();
        Random random = new Random(start.toEpochDay()); // 固定种子保证一致性

        long days = end.toEpochDay() - start.toEpochDay() + 1;

        // 每天生成 3-8 笔订单
        for (long d = 0; d < days; d++) {
            LocalDate date = start.plusDays(d);
            if (date.getDayOfWeek().getValue() > 5) continue; // 跳过周末

            int ordersPerDay = 3 + random.nextInt(6);
            for (int i = 0; i < ordersPerDay; i++) {
                SalesRecord record = new SalesRecord();
                record.region = REGIONS.get(random.nextInt(REGIONS.size()));
                record.salesPerson = SALES_BY_REGION.get(record.region).get(random.nextInt(3));
                record.product = PRODUCTS.get(random.nextInt(PRODUCTS.size()));
                record.customer = CUSTOMER_POOL.get(random.nextInt(CUSTOMER_POOL.size())) + date.getDayOfMonth();

                // 金额：企业版 50-200 万，专业版 10-50 万，基础版 1-10 万
                record.amount = switch (record.product) {
                    case "企业版" -> 50 + random.nextDouble() * 150;
                    case "专业版" -> 10 + random.nextDouble() * 40;
                    default -> 1 + random.nextDouble() * 9;
                };
                record.amount = Math.round(record.amount * 100) / 100.0;

                record.date = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
                records.add(record);
            }
        }

        return records;
    }

    /**
     * 销售记录
     */
    private static class SalesRecord {
        String region;
        String salesPerson;
        String product;
        String customer;
        double amount;
        String date;

        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("region", region);
            map.put("salesPerson", salesPerson);
            map.put("product", product);
            map.put("customer", customer);
            map.put("amount", amount);
            map.put("date", date);
            return map;
        }
    }
}
