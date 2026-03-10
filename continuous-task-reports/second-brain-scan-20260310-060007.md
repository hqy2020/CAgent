# 第二大脑优化扫描报告

- 时间：2026-03-10 06:00
- 扫描目录：`/Users/openingcloud/IdeaProjects/ragent`
- 顶层模块数：17

## 顶层模块

- `.claude`
- `.git`
- `.idea`
- `.mvn`
- `.playwright-cli`
- `bootstrap`
- `continuous-task-reports`
- `docs`
- `framework`
- `frontend`
- `infra-ai`
- `mcp-server`

## README 关键标题

- CAgent — 我的个人 AI 知识中枢
- 这是什么
- 我做了什么扩展
- 学习中心（Study Center）
- 面试题库（Interview Hub）
- 系统架构
- 总览
- 模块组成

## 疑似回答 / 知识 / 工具入口文件

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/DashboardController.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/HotspotController.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/ToolAuditController.java` | 命中：agent, tool
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/request/HotspotMonitorSaveRequest.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/vo/DashboardOverviewGroupVO.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/vo/DashboardOverviewKpiVO.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/vo/DashboardOverviewVO.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/vo/DashboardPerformanceVO.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/vo/DashboardTrendPointVO.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/vo/DashboardTrendSeriesVO.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/vo/DashboardTrendsVO.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/vo/HotspotMonitorEventVO.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/vo/HotspotMonitorRunVO.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/vo/HotspotMonitorVO.java` | 命中：agent
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/vo/MCPToolCallAuditVO.java` | 命中：agent, mcp, tool

## 建议的最小切口

- 先从 `README`、`docs/quick-start.md` 与命中最多的入口文件连出回答链路草图。
- 下一步优先验证：聊天入口 -> 检索入口 -> 工具调用入口 是否能形成最短闭环。
