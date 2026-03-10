import { afterEach, describe, expect, it, vi } from "vitest";

import { createStreamResponse } from "@/hooks/useStreamResponse";

function buildSseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    }
  });
  return new Response(stream, {
    status: 200,
    headers: { "Content-Type": "text/event-stream" }
  });
}

describe("createStreamResponse", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("should parse fragmented/sticky/tail SSE packets and emit finish+done", async () => {
    const chunks = [
      "event:meta\ndata:{\"conversationId\":\"c1\",\"taskId\":\"t1\"}\n\nevent:message\ndata:{\"type\":\"response\",\"delta\":\"你\"}\n\n",
      "event:message\ndata:{\"type\":\"response\",\"delta\":\"好",
      "呀\"}\n\n",
      "event:finish\ndata:{\"messageId\":\"m1\",\"title\":\"新标题\"}\n\nevent:done\ndata:[DONE]"
    ];

    const onMeta = vi.fn();
    const onMessage = vi.fn();
    const onFinish = vi.fn();
    const onDone = vi.fn();
    const onError = vi.fn();

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    const stream = createStreamResponse(
      { url: "http://localhost/sse" },
      { onMeta, onMessage, onFinish, onDone, onError }
    );
    await stream.start();

    expect(onMeta).toHaveBeenCalledWith({ conversationId: "c1", taskId: "t1" });
    expect(onMessage).toHaveBeenCalledTimes(2);
    expect(onMessage.mock.calls[0][0]).toEqual({ type: "response", delta: "你" });
    expect(onMessage.mock.calls[1][0]).toEqual({ type: "response", delta: "好呀" });
    expect(onFinish).toHaveBeenCalledWith({ messageId: "m1", title: "新标题" });
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it("should emit error and done terminal callbacks", async () => {
    const chunks = [
      "event:error\ndata:{\"error\":\"boom\"}\n\nevent:done\ndata:[DONE]\n\n"
    ];

    const onError = vi.fn();
    const onDone = vi.fn();
    const onCancel = vi.fn();

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    const stream = createStreamResponse(
      { url: "http://localhost/sse" },
      { onError, onDone, onCancel }
    );
    await stream.start();

    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError.mock.calls[0][0]).toBeInstanceOf(Error);
    expect(onError.mock.calls[0][0].message).toBe("boom");
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
  });

  it("should emit cancel terminal callback without abnormal-end error", async () => {
    const chunks = ["event:cancel\ndata:{\"messageId\":\"m2\",\"title\":\"已取消\"}\n\n"];

    const onCancel = vi.fn();
    const onError = vi.fn();

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    const stream = createStreamResponse(
      { url: "http://localhost/sse" },
      { onCancel, onError }
    );
    await stream.start();

    expect(onCancel).toHaveBeenCalledWith({ messageId: "m2", title: "已取消" });
    expect(onError).not.toHaveBeenCalled();
  });

  it("should dispatch queue events before meta", async () => {
    const chunks = [
      'event:queue\ndata:{"position":2,"total":4}\n\n',
      'event:meta\ndata:{"conversationId":"c1","taskId":"t1"}\n\n',
      'event:done\ndata:[DONE]\n\n'
    ];

    const onQueue = vi.fn();
    const onMeta = vi.fn();

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    const stream = createStreamResponse(
      { url: "http://localhost/sse" },
      { onQueue, onMeta }
    );
    await stream.start();

    expect(onQueue).toHaveBeenCalledWith({ position: 2, total: 4 });
    expect(onMeta).toHaveBeenCalledWith({ conversationId: "c1", taskId: "t1" });
  });

  // ─── 深度思考模式 SSE 事件测试 ───

  it("should dispatch onThinking for think-type message events", async () => {
    const chunks = [
      'event:meta\ndata:{"conversationId":"c1","taskId":"t1"}\n\n',
      'event:message\ndata:{"type":"think","delta":"让我分析"}\n\n',
      'event:message\ndata:{"type":"think","delta":"一下这个问题"}\n\n',
      'event:message\ndata:{"type":"response","delta":"Spring 是"}\n\n',
      'event:message\ndata:{"type":"response","delta":"一个框架"}\n\n',
      'event:finish\ndata:{"messageId":"m1","title":"Spring介绍"}\n\nevent:done\ndata:[DONE]\n\n'
    ];

    const onThinking = vi.fn();
    const onMessage = vi.fn();
    const onFinish = vi.fn();
    const onDone = vi.fn();
    const onError = vi.fn();

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    const stream = createStreamResponse(
      { url: "http://localhost/sse" },
      { onThinking, onMessage, onFinish, onDone, onError }
    );
    await stream.start();

    // onThinking 应被 think 类型事件触发
    expect(onThinking).toHaveBeenCalledTimes(2);
    expect(onThinking.mock.calls[0][0]).toEqual({ type: "think", delta: "让我分析" });
    expect(onThinking.mock.calls[1][0]).toEqual({ type: "think", delta: "一下这个问题" });

    // onMessage 应收到所有 message 事件（think + response）
    expect(onMessage).toHaveBeenCalledTimes(4);

    // 终端事件正常
    expect(onFinish).toHaveBeenCalledTimes(1);
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it("should not dispatch onThinking for response-only events", async () => {
    const chunks = [
      'event:meta\ndata:{"conversationId":"c1","taskId":"t1"}\n\n',
      'event:message\ndata:{"type":"response","delta":"普通回答"}\n\n',
      'event:finish\ndata:{"messageId":"m1"}\n\nevent:done\ndata:[DONE]\n\n'
    ];

    const onThinking = vi.fn();
    const onMessage = vi.fn();

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    const stream = createStreamResponse(
      { url: "http://localhost/sse" },
      { onThinking, onMessage }
    );
    await stream.start();

    expect(onThinking).not.toHaveBeenCalled();
    expect(onMessage).toHaveBeenCalledTimes(1);
    expect(onMessage.mock.calls[0][0]).toEqual({ type: "response", delta: "普通回答" });
  });

  it("should handle fragmented think events across SSE chunks", async () => {
    const chunks = [
      'event:message\ndata:{"type":"think","delta":"思考',
      '过程"}\n\n',
      'event:message\ndata:{"type":"response","delta":"结论"}\n\n',
      'event:finish\ndata:{"messageId":"m1"}\n\nevent:done\ndata:[DONE]\n\n'
    ];

    const onThinking = vi.fn();
    const onMessage = vi.fn();

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    const stream = createStreamResponse(
      { url: "http://localhost/sse" },
      { onThinking, onMessage }
    );
    await stream.start();

    // 碎片化 think 事件应被正确拼接后触发
    expect(onThinking).toHaveBeenCalledTimes(1);
    expect(onThinking.mock.calls[0][0]).toEqual({ type: "think", delta: "思考过程" });
  });

  it("should handle onThinking=undefined gracefully (no handler registered)", async () => {
    const chunks = [
      'event:message\ndata:{"type":"think","delta":"思考内容"}\n\n',
      'event:message\ndata:{"type":"response","delta":"回答"}\n\n',
      'event:finish\ndata:{"messageId":"m1"}\n\nevent:done\ndata:[DONE]\n\n'
    ];

    const onMessage = vi.fn();
    const onDone = vi.fn();

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    // 不注册 onThinking handler
    const stream = createStreamResponse(
      { url: "http://localhost/sse" },
      { onMessage, onDone }
    );
    await stream.start();

    // 不应抛出异常，onMessage 仍然收到所有事件
    expect(onMessage).toHaveBeenCalledTimes(2);
    expect(onDone).toHaveBeenCalledTimes(1);
  });
});
