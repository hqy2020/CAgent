# 相关材料全文展示 UI 测试方案

## 目标

验证聊天区「参考文档（相关材料）」在以下场景可用：

1. 长文本默认折叠展示，避免消息区过高。
2. 用户可点击「展开全文 / 收起」切换完整文本显示。
3. 详情弹窗仍可正常打开并展示完整片段内容。

## 测试范围

- 页面：`/chat/:sessionId`
- 组件：`MessageItem` + `ReferenceDetailDialog`
- 数据：`message.references[*].textPreview` 与 `chunks[*].content`

## 用例矩阵

| ID | 用例 | 步骤 | 预期 |
|---|---|---|---|
| RM-UI-01 | 长文本默认折叠 | 展开「参考文档」面板 | 预览文本带折叠样式（`line-clamp-2`），按钮显示「展开全文」 |
| RM-UI-02 | 长文本可展开 | 点击「展开全文」 | 折叠样式移除，按钮变为「收起」 |
| RM-UI-03 | 长文本可收起 | 点击「收起」 | 恢复折叠样式，按钮回到「展开全文」 |
| RM-UI-04 | 短文本不显示开关 | 使用短 `textPreview` 数据渲染 | 不出现展开/收起按钮 |
| RM-UI-05 | 详情弹窗完整性 | 点击引用条目打开详情 | 弹窗显示文档名、片段内容（完整 chunk） |

## 自动化覆盖

- 组件测试：`frontend/src/components/chat/MessageItem.test.tsx`
  - 覆盖 RM-UI-01 ~ RM-UI-04
- 浏览器验证（Playwright CLI）：覆盖 RM-UI-01 ~ RM-UI-05

## 本次执行记录（2026-03-04）

### 1) 前端单测

命令：

```bash
cd frontend
npm run test -- src/components/chat/MessageItem.test.tsx src/hooks/useStreamResponse.test.ts src/stores/chatStore.test.ts
```

结果：通过（21/21）。

### 2) 浏览器验证（前端运行 + 注入测试数据）

说明：后端当前存在编译错误（`SSEEventType.WORKFLOW` 缺失），因此浏览器验证通过注入 chat store 测试数据完成 UI 行为确认。

关键验证点：

- 折叠态 class：`text-xs leading-relaxed text-gray-500 line-clamp-2`
- 展开后 class：`text-xs leading-relaxed text-gray-500`
- 展开按钮文本：`展开全文 -> 收起`
- 点击引用条目可打开详情弹窗并看到完整片段

截图产物：

- `output/playwright/reference-preview-collapsed.png`
- `output/playwright/reference-preview-expanded.png`
- `output/playwright/reference-preview-expanded-2.png`
