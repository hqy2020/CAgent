# 第二大脑优化扫描报告

- 时间：2026-03-11 02:15
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

- Ragent
- 功能概览
- 技术栈
- 仓库结构
- 启动前准备
- 方式一：macOS 一键启动
- 1. 配置环境变量
- 2. 启动项目

## 疑似回答 / 知识 / 工具入口文件

- `bootstrap/src/main/java/com/openingcloud/ai/ragent/RagentApplication.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/admin/controller/HotspotController.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/admin/controller/request/HotspotMonitorSaveRequest.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/admin/controller/vo/HotspotMonitorEventVO.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/admin/controller/vo/HotspotMonitorRunVO.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/admin/controller/vo/HotspotMonitorVO.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/admin/service/HotspotMonitorScheduleJob.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/admin/service/HotspotMonitorService.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/core/chunk/AbstractEmbeddingChunker.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/core/chunk/ChunkingMode.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/core/chunk/ChunkingOptions.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/core/chunk/ChunkingStrategy.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/core/chunk/ChunkingStrategyFactory.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/core/chunk/VectorChunk.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/core/chunk/strategy/FixedSizeTextChunker.java` | 命中：agent
- `bootstrap/src/main/java/com/openingcloud/ai/ragent/core/chunk/strategy/ParagraphChunker.java` | 命中：agent

## 建议的最小切口

- 先从 `README`、`docs/quick-start.md` 与命中最多的入口文件连出回答链路草图。
- 下一步优先验证：聊天入口 -> 检索入口 -> 工具调用入口 是否能形成最短闭环。
