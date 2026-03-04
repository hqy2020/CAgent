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

vi.mock("@/components/chat/ThinkingIndicator", () => ({
  ThinkingIndicator: () => null
}));

vi.mock("@/components/chat/ReferenceDetailDialog", () => ({
  ReferenceDetailDialog: () => null
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
});
