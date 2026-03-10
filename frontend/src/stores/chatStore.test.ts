import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useChatStore } from "@/stores/chatStore";

// Mock 外部依赖
vi.mock("@/services/sessionService", () => ({
  listSessions: vi.fn().mockResolvedValue([]),
  listMessages: vi.fn().mockResolvedValue([]),
  deleteSession: vi.fn().mockResolvedValue(undefined),
  renameSession: vi.fn().mockResolvedValue(undefined)
}));

vi.mock("@/services/chatService", () => ({
  stopTask: vi.fn().mockResolvedValue(undefined),
  submitFeedback: vi.fn().mockResolvedValue(undefined)
}));

vi.mock("@/utils/storage", () => ({
  storage: {
    getToken: vi.fn().mockReturnValue("test-token")
  }
}));

vi.mock("sonner", () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn()
  }
}));

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

describe("chatStore - 深度思考模式", () => {
  beforeEach(() => {
    // 重置 store 到初始状态
    useChatStore.setState({
      sessions: [],
      currentSessionId: null,
      messages: [],
      isLoading: false,
      sessionsLoaded: false,
      isStreaming: false,
      isCreatingNew: false,
      deepThinkingEnabled: false,
      thinkingStartAt: null,
      streamTaskId: null,
      streamAbort: null,
      streamingMessageId: null,
      cancelRequested: false
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  // ─── setDeepThinkingEnabled ───

  it("setDeepThinkingEnabled 应正确切换深度思考开关", () => {
    const store = useChatStore.getState();
    expect(store.deepThinkingEnabled).toBe(false);

    store.setDeepThinkingEnabled(true);
    expect(useChatStore.getState().deepThinkingEnabled).toBe(true);

    store.setDeepThinkingEnabled(false);
    expect(useChatStore.getState().deepThinkingEnabled).toBe(false);
  });

  // ─── appendThinkingContent ───

  it("appendThinkingContent 应累积思考内容", () => {
    // 模拟流式消息已创建
    useChatStore.setState({
      streamingMessageId: "a1",
      messages: [
        { id: "a1", role: "assistant", content: "", thinking: "", isThinking: true, status: "streaming" }
      ]
    });

    const store = useChatStore.getState();
    store.appendThinkingContent("让我");
    store.appendThinkingContent("分析一下");

    const msg = useChatStore.getState().messages.find((m) => m.id === "a1");
    expect(msg?.thinking).toBe("让我分析一下");
    expect(msg?.isThinking).toBe(true);
  });

  it("appendThinkingContent 应自动初始化 thinkingStartAt", () => {
    useChatStore.setState({
      streamingMessageId: "a1",
      thinkingStartAt: null,
      messages: [
        { id: "a1", role: "assistant", content: "", thinking: "", status: "streaming" }
      ]
    });

    useChatStore.getState().appendThinkingContent("思考中");

    expect(useChatStore.getState().thinkingStartAt).not.toBeNull();
    expect(typeof useChatStore.getState().thinkingStartAt).toBe("number");
  });

  it("appendThinkingContent 不应修改 cancelled 状态的消息", () => {
    useChatStore.setState({
      streamingMessageId: "a1",
      messages: [
        { id: "a1", role: "assistant", content: "", thinking: "", status: "cancelled" }
      ]
    });

    useChatStore.getState().appendThinkingContent("不应追加");

    const msg = useChatStore.getState().messages.find((m) => m.id === "a1");
    expect(msg?.thinking).toBe("");
  });

  it("appendThinkingContent 应忽略空 delta", () => {
    useChatStore.setState({
      streamingMessageId: "a1",
      messages: [
        { id: "a1", role: "assistant", content: "", thinking: "已有", status: "streaming" }
      ]
    });

    useChatStore.getState().appendThinkingContent("");

    const msg = useChatStore.getState().messages.find((m) => m.id === "a1");
    expect(msg?.thinking).toBe("已有");
  });

  // ─── appendStreamContent（思考→回答状态转换）───

  it("appendStreamContent 应结束思考状态并计算耗时", () => {
    const fakeStartAt = Date.now() - 3000; // 3秒前
    useChatStore.setState({
      streamingMessageId: "a1",
      thinkingStartAt: fakeStartAt,
      messages: [
        { id: "a1", role: "assistant", content: "", thinking: "思考内容", isThinking: true, status: "streaming" }
      ]
    });

    useChatStore.getState().appendStreamContent("开始回答");

    const state = useChatStore.getState();
    const msg = state.messages.find((m) => m.id === "a1");
    expect(msg?.content).toBe("开始回答");
    expect(msg?.isThinking).toBe(false); // 思考结束
    expect(msg?.thinkingDuration).toBeGreaterThanOrEqual(1); // 至少1秒
    expect(state.thinkingStartAt).toBeNull(); // 清除计时器
  });

  it("appendStreamContent 无 thinkingStartAt 时不应设置 thinkingDuration", () => {
    useChatStore.setState({
      streamingMessageId: "a1",
      thinkingStartAt: null,
      messages: [
        { id: "a1", role: "assistant", content: "", status: "streaming" }
      ]
    });

    useChatStore.getState().appendStreamContent("普通回答");

    const msg = useChatStore.getState().messages.find((m) => m.id === "a1");
    expect(msg?.content).toBe("普通回答");
    expect(msg?.thinkingDuration).toBeUndefined();
  });

  // ─── sendMessage 端到端流式测试 ───

  it("sendMessage(deepThinking=true) 应创建含 thinking 字段的助手消息", async () => {
    const chunks = [
      'event:meta\ndata:{"conversationId":"c1","taskId":"t1"}\n\n',
      'event:message\ndata:{"type":"think","delta":"思考"}\n\n',
      'event:message\ndata:{"type":"response","delta":"回答"}\n\n',
      'event:finish\ndata:{"messageId":"m1","title":"测试"}\n\n',
      "event:done\ndata:[DONE]\n\n"
    ];
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    useChatStore.getState().setDeepThinkingEnabled(true);
    await useChatStore.getState().sendMessage("测试深度思考");

    const state = useChatStore.getState();
    const assistantMsg = state.messages.find((m) => m.role === "assistant");

    expect(assistantMsg).toBeDefined();
    expect(assistantMsg?.thinking).toContain("思考");
    expect(assistantMsg?.content).toContain("回答");
    expect(assistantMsg?.isDeepThinking).toBe(true);
    expect(assistantMsg?.isThinking).toBe(false); // 流结束后为 false
    expect(assistantMsg?.thinkingDuration).toBeGreaterThanOrEqual(1);
    expect(assistantMsg?.status).toBe("done");
  });

  it("sendMessage(deepThinking=false) 应创建无 thinking 字段的助手消息", async () => {
    const chunks = [
      'event:meta\ndata:{"conversationId":"c1","taskId":"t1"}\n\n',
      'event:message\ndata:{"type":"response","delta":"普通回答"}\n\n',
      'event:finish\ndata:{"messageId":"m1","title":"测试"}\n\n',
      "event:done\ndata:[DONE]\n\n"
    ];
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    useChatStore.getState().setDeepThinkingEnabled(false);
    await useChatStore.getState().sendMessage("测试普通模式");

    const state = useChatStore.getState();
    const assistantMsg = state.messages.find((m) => m.role === "assistant");

    expect(assistantMsg).toBeDefined();
    expect(assistantMsg?.thinking).toBeUndefined();
    expect(assistantMsg?.isDeepThinking).toBe(false);
    expect(assistantMsg?.content).toContain("普通回答");
  });

  it("sendMessage(deepThinking=true) 应在 URL 中包含 deepThinking=true", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      buildSseResponse([
        'event:meta\ndata:{"conversationId":"c1","taskId":"t1"}\n\n',
        'event:finish\ndata:{"messageId":"m1"}\n\nevent:done\ndata:[DONE]\n\n'
      ])
    );
    vi.stubGlobal("fetch", fetchMock);

    useChatStore.getState().setDeepThinkingEnabled(true);
    await useChatStore.getState().sendMessage("深度思考查询");

    const calledUrl = fetchMock.mock.calls[0][0];
    expect(calledUrl).toContain("deepThinking=true");
  });

  it("sendMessage(deepThinking=false) URL 中不应包含 deepThinking 参数", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      buildSseResponse([
        'event:meta\ndata:{"conversationId":"c1","taskId":"t1"}\n\n',
        'event:finish\ndata:{"messageId":"m1"}\n\nevent:done\ndata:[DONE]\n\n'
      ])
    );
    vi.stubGlobal("fetch", fetchMock);

    useChatStore.getState().setDeepThinkingEnabled(false);
    await useChatStore.getState().sendMessage("普通查询");

    const calledUrl = fetchMock.mock.calls[0][0];
    expect(calledUrl).not.toContain("deepThinking");
  });

  it("sendMessage 应显示排队进度并在进入执行后收敛为已处理", async () => {
    const chunks = [
      'event:queue\ndata:{"position":2,"total":5}\n\n',
      'event:meta\ndata:{"conversationId":"c1","taskId":"t1"}\n\n',
      'event:agent_step\ndata:{"loop":0,"stepIndex":1,"type":"分析问题","status":"RUNNING","summary":"开始分析"}\n\n',
      'event:finish\ndata:{"messageId":"m1","title":"天气"}\n\n',
      "event:done\ndata:[DONE]\n\n"
    ];
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    await useChatStore.getState().sendMessage("今天上海天气怎么样");

    const assistantMsg = useChatStore.getState().messages.find((m) => m.role === "assistant");
    const queueStep = assistantMsg?.agentTimeline?.find(
      (item) => item.kind === "step" && item.payload.stepIndex === 0
    );
    const analyzeStep = assistantMsg?.agentTimeline?.find(
      (item) => item.kind === "step" && item.payload.stepIndex === 1
    );

    expect(queueStep && queueStep.kind === "step" ? queueStep.payload.status : undefined).toBe("SUCCESS");
    expect(queueStep && queueStep.kind === "step" ? queueStep.payload.summary : undefined).toBe(
      "已开始处理，进入执行阶段。"
    );
    expect(analyzeStep && analyzeStep.kind === "step" ? analyzeStep.payload.status : undefined).toBe("SUCCESS");
  });

  it("sendMessage 应对同一步骤做增量更新而不是重复追加", async () => {
    const chunks = [
      'event:meta\ndata:{"conversationId":"c1","taskId":"t1"}\n\n',
      'event:agent_step\ndata:{"loop":0,"stepIndex":2,"type":"识别意图","status":"RUNNING","summary":"正在识别"}\n\n',
      'event:agent_step\ndata:{"loop":0,"stepIndex":2,"type":"识别意图","status":"SUCCESS","summary":"识别完成"}\n\n',
      'event:finish\ndata:{"messageId":"m1","title":"天气"}\n\n',
      "event:done\ndata:[DONE]\n\n"
    ];
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    await useChatStore.getState().sendMessage("今天上海天气怎么样");

    const assistantMsg = useChatStore.getState().messages.find((m) => m.role === "assistant");
    const intentSteps =
      assistantMsg?.agentTimeline?.filter(
        (item) => item.kind === "step" && item.payload.stepIndex === 2
      ) ?? [];

    expect(intentSteps).toHaveLength(1);
    expect(intentSteps[0] && intentSteps[0].kind === "step" ? intentSteps[0].payload.status : undefined).toBe(
      "SUCCESS"
    );
    expect(intentSteps[0] && intentSteps[0].kind === "step" ? intentSteps[0].payload.summary : undefined).toBe(
      "识别完成"
    );
  });

  // ─── 取消生成时的思考状态清理 ───

  it("取消生成后应清理思考状态", async () => {
    // SSE 流中直接返回 cancel 事件
    const chunks = [
      'event:meta\ndata:{"conversationId":"c1","taskId":"t1"}\n\n',
      'event:message\ndata:{"type":"think","delta":"部分思考"}\n\n',
      'event:cancel\ndata:{"messageId":"m1","title":"已取消"}\n\n'
    ];
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(buildSseResponse(chunks)));

    useChatStore.getState().setDeepThinkingEnabled(true);
    await useChatStore.getState().sendMessage("会被取消的消息");

    const state = useChatStore.getState();
    expect(state.thinkingStartAt).toBeNull();
    expect(state.isStreaming).toBe(false);

    const assistantMsg = state.messages.find((m) => m.role === "assistant");
    expect(assistantMsg?.isThinking).toBe(false);
    expect(assistantMsg?.status).toBe("cancelled");
    expect(assistantMsg?.thinkingDuration).toBeGreaterThanOrEqual(1);
  });
});
