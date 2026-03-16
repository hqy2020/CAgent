// @vitest-environment jsdom

import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

import { MessageItem } from "@/components/chat/MessageItem";
import type { Message } from "@/types";

vi.mock("@/hooks/useAnimatedText", () => ({
  useAnimatedText: (content: string) => content
}));

vi.mock("@/components/chat/MarkdownRenderer", () => ({
  MarkdownRenderer: ({ content }: { content: string }) => <div>{content}</div>
}));

vi.mock("@/components/chat/FeedbackButtons", () => ({
  FeedbackButtons: () => null
}));

vi.mock("@/components/chat/ReferenceDetailDialog", () => ({
  ReferenceDetailDialog: () => null
}));

vi.mock("@/components/chat/AgentReasoningIndicator", () => ({
  AgentReasoningIndicator: ({ content, isStreaming }: { content?: string; isStreaming?: boolean }) => (
    <div data-testid="agent-reasoning-indicator">
      <span>{isStreaming ? "推理中" : "已完成"}</span>
      <span data-testid="agent-reasoning-content">{content}</span>
    </div>
  )
}));

describe("MessageItem reference preview", () => {
  let container: HTMLDivElement;
  let root: Root;

  const buildMessage = (textPreview: string): Message => ({
    id: "m-1",
    role: "assistant",
    content: "回答内容",
    status: "done",
    references: [
      {
        documentId: "doc-1",
        documentName: "测试文档",
        knowledgeBaseName: "测试知识库",
        score: 0.91,
        textPreview,
        chunks: [{ content: textPreview, score: 0.91 }]
      }
    ]
  });

  const renderMessage = async (textPreview: string) => {
    await act(async () => {
      root.render(<MessageItem message={buildMessage(textPreview)} />);
    });
  };

  const expandReferencePanel = async () => {
    const toggle = Array.from(container.querySelectorAll("button")).find((button) =>
      button.textContent?.includes("参考文档")
    );
    expect(toggle).toBeTruthy();
    await act(async () => {
      toggle?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    });
  };

  beforeAll(() => {
    // Let React know we're running under a test environment with act() support.
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  });

  afterAll(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = false;
  });

  beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(async () => {
    await act(async () => {
      root.unmount();
    });
    container.remove();
  });

  it("should expand and collapse long reference preview text", async () => {
    const longText = "这是一个用于测试相关材料完整展示能力的长文本。".repeat(12);
    await renderMessage(longText);
    await expandReferencePanel();

    const preview = container.querySelector("[data-testid='reference-preview-doc-1']");
    const previewToggle = container.querySelector("[data-testid='reference-preview-toggle-doc-1']");
    expect(preview).toBeTruthy();
    expect(previewToggle?.textContent).toContain("展开全文");
    expect(preview?.className).toContain("line-clamp-2");

    await act(async () => {
      previewToggle?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    });
    expect(previewToggle?.textContent).toContain("收起");
    expect(preview?.className).not.toContain("line-clamp-2");

    await act(async () => {
      previewToggle?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    });
    expect(previewToggle?.textContent).toContain("展开全文");
    expect(preview?.className).toContain("line-clamp-2");
  });

  it("should not render expand button for short preview text", async () => {
    await renderMessage("短文本，不需要展开。");
    await expandReferencePanel();

    const previewToggle = container.querySelector("[data-testid='reference-preview-toggle-doc-1']");
    expect(previewToggle).toBeNull();
  });

  it("should render agent reasoning before final answer content", async () => {
    const message: Message = {
      id: "m-2",
      role: "assistant",
      content: "最终回答",
      status: "done",
      agentReasoning: "[规划] 回答天气问题\n[执行] WEB_SEARCH - SUCCESS: 已获取天气结果。\n",
      agentReasoningDuration: 5
    };

    await act(async () => {
      root.render(<MessageItem message={message} />);
    });

    const indicator = container.querySelector("[data-testid='agent-reasoning-indicator']");
    expect(indicator).toBeTruthy();
    expect(indicator?.textContent).toContain("回答天气问题");
    const html = container.innerHTML;
    expect(html.indexOf("agent-reasoning-indicator")).toBeLessThan(html.indexOf("最终回答"));
  });

  it("should render thinking content expanded by default after completion", async () => {
    const message: Message = {
      id: "m-3",
      role: "assistant",
      content: "主答案",
      thinking: "第一步分析\n第二步归纳",
      thinkingDuration: 9,
      status: "done"
    };

    await act(async () => {
      root.render(<MessageItem message={message} />);
    });

    const toggle = container.querySelector("[data-testid='thinking-toggle']");
    const content = container.querySelector("[data-testid='thinking-content']");

    expect(toggle?.textContent).toContain("收起思考");
    expect(content?.textContent).toContain("第一步分析");
    expect(content?.textContent).toContain("第二步归纳");
    expect(container.textContent).toContain("主答案");
  });

  it("should collapse thinking content without affecting final answer", async () => {
    const message: Message = {
      id: "m-4",
      role: "assistant",
      content: "这是主答案内容",
      thinking: "这是完整思考过程",
      status: "done"
    };

    await act(async () => {
      root.render(<MessageItem message={message} />);
    });

    const toggle = container.querySelector("[data-testid='thinking-toggle']");
    expect(toggle).toBeTruthy();

    await act(async () => {
      toggle?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    });

    const collapsedNote = container.querySelector("[data-testid='thinking-collapsed-note']");
    const content = container.querySelector("[data-testid='thinking-content']");

    expect(toggle?.textContent).toContain("展开思考");
    expect(collapsedNote?.textContent).toContain("思考过程已折叠");
    expect(content).toBeNull();
    expect(container.textContent).toContain("这是主答案内容");
  });

  it("should render agent reasoning narrative text", async () => {
    const message: Message = {
      id: "m-5",
      role: "assistant",
      content: "最终答案",
      status: "done",
      agentReasoning:
        "[规划] 回答天气问题\n  思考: 先看联网 observation，再决定是否直接输出。\n  1. WEB_SEARCH: 搜索天气信息\n[执行] WEB_SEARCH - SUCCESS: 已获取天气结果。\n",
      agentReasoningDuration: 3
    };

    await act(async () => {
      root.render(<MessageItem message={message} />);
    });

    const content = container.querySelector("[data-testid='agent-reasoning-content']");
    expect(content?.textContent).toContain("回答天气问题");
    expect(content?.textContent).toContain("先看联网 observation");
    expect(content?.textContent).toContain("WEB_SEARCH");
    expect(container.textContent).toContain("最终答案");
  });
});
