# MCP 工具集成用户测试样例

目标：验证以下场景是否成立

1. 用户意图是业务系统查询时，能够自动抽取参数并调用工具
2. 纯业务工具场景不被知识库检索干扰
3. 知识检索与工具调用可在同一流程中融合

## 前置条件

- 后端服务已启动：`http://localhost:8080/api/ragent`
- 已存在可用账号（默认示例：`admin/admin`）
- 意图树中存在：
  - `kind=MCP` 且 `mcpToolId=sales_query` 的叶子节点
  - 至少一个 `kind=KB` 叶子节点

## 样例问题

### Case 1：纯业务工具（MCP Only）

- 问题：`华东区这个月销售额多少？`
- 预期：
  - 命中 MCP 意图，执行 `sales_query`
  - 自动抽取参数（如 `region=华东`、`period=本月`）
  - `RetrievalContext` 中 `hasMcp=true` 且 `hasKb=false`

### Case 2：纯知识检索（KB Only）

- 问题：`公司数据安全规范里，敏感字段脱敏要求是什么？`
- 预期：
  - 命中 KB 意图，走知识检索
  - `RetrievalContext` 中 `hasKb=true` 且 `hasMcp=false`

### Case 3：混合场景（MCP + KB）

- 问题：`华东区这个月销售额多少，并说明销售口径定义。`
- 预期：
  - 同时命中 MCP 与 KB 意图
  - `RetrievalContext` 中 `hasKb=true` 且 `hasMcp=true`
  - 最终回答同时包含动态数据与文档规则

## 执行方式

推荐直接执行集成矩阵脚本（包含 SSE 协议断言 + Trace 阶段断言）：

```bash
BASE_URL=http://localhost:8080/api/ragent \
LOGIN_USERNAME=admin \
LOGIN_PASSWORD=admin \
./scripts/mcp_kb_integration_test.sh
```

若需单条问题调试，可使用全链路脚本并指定期望阈值：

```bash
BASE_URL=http://localhost:8080/api/ragent \
LOGIN_USERNAME=admin \
LOGIN_PASSWORD=admin \
QUESTION="华东区这个月销售额多少？" \
EXPECT_MCP_MIN=1 \
EXPECT_RETRIEVE_CHANNEL_MIN=0 \
EXPECT_RETRIEVE_CHANNEL_MAX=0 \
./scripts/trace_fullchain_smoke.sh
```

## 结果核验建议

- 接口级（所有 case）：
  - SSE 流正常返回 `meta/message/finish/done`
- Trace 级（`/rag/traces/runs/{traceId}/nodes`）：
  - Case 1（MCP Only）：
    - `REWRITE>=1, INTENT>=1, RETRIEVE>=1, MCP>=1, RETRIEVE_CHANNEL=0, LLM>=1`
  - Case 2（KB Only）：
    - `REWRITE>=1, INTENT>=1, RETRIEVE>=1, MCP=0, RETRIEVE_CHANNEL>=1, LLM>=1`
  - Case 3（Mixed）：
    - `REWRITE>=1, INTENT>=1, RETRIEVE>=1, MCP>=1, RETRIEVE_CHANNEL>=1, LLM>=1`
