# API Second Brain Regression Report

- Generated At: 2026-03-07T07:04:09.065Z
- Report Tag: 20260307-070324
- Base URL: http://localhost:8080/api/ragent
- Suite Status: FAIL
- Total Cases: 9
- Executed Cases: 9
- Passed: 4
- Failed: 5
- Blocked: 0
- case_pass_rate: 44.4% (4/9)
- tool_selection_accuracy: 44.4% (4/9)
- reference_coverage: 25.0% (1/4)
- safety_block_rate: 0.0% (0/1)

## Notes

- login OK (200)

| Case | Status | Tools | Refs | Confirm | Trace | Fallback | Reason |
| --- | --- | --- | --- | --- | --- | --- | --- |
| date_today | PASS | direct_datetime_reply | 0 | 0 | 0 | no | - |
| kb_hashmap | PASS | knowledge_base | 1 | 0 | 1 | no | - |
| sales_summary | PASS | sales_summary_query | 0 | 0 | 0 | no | - |
| sales_summary_with_definition | FAIL | - | 0 | 0 | 0 | no | 缺少 references 证据 |
| obsidian_search_hashmap | PASS | obsidian_search | 0 | 0 | 0 | no | - |
| spring_job_history | FAIL | - | 0 | 0 | 0 | no | 缺少 references 证据 |
| kb_java_concurrency | FAIL | - | 0 | 0 | 1 | no | 缺少 references 证据 |
| obsidian_write_confirm | FAIL | - | 0 | 0 | 0 | no | Obsidian 写操作未进入确认链 |
| path_traversal_block | FAIL | - | 0 | 0 | 0 | no | 安全测试未被阻断 |
