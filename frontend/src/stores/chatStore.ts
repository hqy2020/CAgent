import { create } from "zustand";
import { toast } from "sonner";

import type {
  AgentConfirmPayload,
  AgentObservePayload,
  AgentPlanPayload,
  AgentReplanPayload,
  AgentStepPayload,
  AgentTimelineItem,
  CompletionPayload,
  Message,
  MessageFeedbackSubmission,
  MessageDeltaPayload,
  QueueStatusPayload,
  ReasoningTracePayload,
  ReferenceItem,
  Session,
  WorkflowEventPayload
} from "@/types";
import {
  type ConversationMessageVO,
  type ConversationVO,
  listMessages,
  listSessions,
  deleteSession as deleteSessionRequest,
  renameSession as renameSessionRequest
} from "@/services/sessionService";
import { stopTask, submitFeedback } from "@/services/chatService";
import { buildQuery } from "@/utils/helpers";
import { createStreamResponse } from "@/hooks/useStreamResponse";
import { storage } from "@/utils/storage";

interface ChatState {
  sessions: Session[];
  currentSessionId: string | null;
  messages: Message[];
  isLoading: boolean;
  sessionsLoaded: boolean;
  sessionLoadError: string | null;
  inputFocusKey: number;
  isStreaming: boolean;
  isCreatingNew: boolean;
  deepThinkingEnabled: boolean;
  thinkingStartAt: number | null;
  agentReasoningStartAt: number | null;
  streamTaskId: string | null;
  streamAbort: (() => void) | null;
  streamingMessageId: string | null;
  cancelRequested: boolean;
  fetchSessions: () => Promise<void>;
  createSession: () => Promise<string>;
  deleteSession: (sessionId: string) => Promise<void>;
  renameSession: (sessionId: string, title: string) => Promise<void>;
  selectSession: (sessionId: string) => Promise<void>;
  updateSessionTitle: (sessionId: string, title: string) => void;
  setDeepThinkingEnabled: (enabled: boolean) => void;
  sendMessage: (content: string) => Promise<void>;
  cancelGeneration: () => void;
  appendStreamContent: (delta: string) => void;
  appendThinkingContent: (delta: string) => void;
  submitFeedback: (messageId: string, feedback: MessageFeedbackSubmission) => Promise<void>;
}

function mapVoteToFeedback(vote?: number | null): Message["feedback"] {
  if (vote === 1) return "like";
  if (vote === -1) return "dislike";
  return null;
}

function unwrapArray<T>(value: T[] | { data?: T[] } | null | undefined): T[] {
  if (Array.isArray(value)) return value;
  if (value && Array.isArray(value.data)) return value.data;
  return [];
}

function mapConversationMessages(
  data: ConversationMessageVO[] | { data?: ConversationMessageVO[] } | null | undefined
): Message[] {
  return unwrapArray(data).map((item) => ({
    id: String(item.id),
    role: item.role === "assistant" ? "assistant" : "user",
    content: item.content,
    createdAt: item.createTime,
    feedback: mapVoteToFeedback(item.vote),
    feedbackReason: item.feedbackReason ?? null,
    feedbackComment: item.feedbackComment ?? null,
    status: "done"
  }));
}

function upsertSession(sessions: Session[], next: Session) {
  const index = sessions.findIndex((session) => session.id === next.id);
  const updated = [...sessions];
  if (index >= 0) {
    updated[index] = { ...sessions[index], ...next };
  } else {
    updated.unshift(next);
  }
  return updated.sort((a, b) => {
    const timeA = a.lastTime ? new Date(a.lastTime).getTime() : 0;
    const timeB = b.lastTime ? new Date(b.lastTime).getTime() : 0;
    return timeB - timeA;
  });
}

function computeThinkingDuration(startAt?: number | null) {
  if (!startAt) return undefined;
  const seconds = Math.round((Date.now() - startAt) / 1000);
  return Math.max(1, seconds);
}

function upsertStepTimeline(
  timeline: AgentTimelineItem[] | undefined,
  payload: AgentStepPayload
): AgentTimelineItem[] {
  const nextTimeline = [...(timeline ?? [])];
  const existingIndex = nextTimeline.findIndex(
    (item) =>
      item.kind === "step" &&
      item.payload.loop === payload.loop &&
      item.payload.stepIndex === payload.stepIndex
  );
  if (existingIndex >= 0) {
    const currentItem = nextTimeline[existingIndex];
    if (currentItem.kind === "step") {
      nextTimeline[existingIndex] = {
        kind: "step",
        at: Date.now(),
        payload: {
          ...currentItem.payload,
          ...payload,
          summary: payload.summary ?? currentItem.payload.summary,
          references: payload.references ?? currentItem.payload.references,
          error: payload.error ?? currentItem.payload.error,
          instruction: payload.instruction ?? currentItem.payload.instruction,
          query: payload.query ?? currentItem.payload.query,
          toolId: payload.toolId ?? currentItem.payload.toolId,
          detail: payload.detail ?? currentItem.payload.detail,
          model: payload.model ?? currentItem.payload.model
        }
      } satisfies AgentTimelineItem;
    }
    return nextTimeline;
  }
  const nextItem = {
    kind: "step",
    at: Date.now(),
    payload
  } satisfies AgentTimelineItem;
  nextTimeline.push(nextItem);
  return nextTimeline;
}

function summarizeQueue(payload: QueueStatusPayload) {
  const ahead = Math.max(0, (payload.position ?? 1) - 1);
  return ahead > 0
    ? `前方还有 ${ahead} 个请求，当前队列共 ${payload.total} 个。`
    : `已进入队列，当前队列共 ${payload.total} 个。`;
}

function narrateAgentEvent(
  kind: "observe" | "plan" | "step" | "replan",
  payload: AgentObservePayload | AgentPlanPayload | AgentStepPayload | AgentReplanPayload
): string {
  switch (kind) {
    case "observe": {
      const p = payload as AgentObservePayload;
      let text = `[观察] ${p.summary || ""}`;
      if (p.items && p.items.length > 0) {
        for (const item of p.items) {
          text += `\n  - ${item.source}: ${item.summary}`;
        }
      }
      return text + "\n";
    }
    case "plan": {
      const p = payload as AgentPlanPayload;
      let text = `[规划] ${p.goal}`;
      if (p.thought) {
        text += `\n  思考: ${p.thought}`;
      }
      if (p.steps && p.steps.length > 0) {
        for (const step of p.steps) {
          text += `\n  ${step.stepIndex}. ${step.type}: ${step.instruction}`;
        }
      }
      return text + "\n";
    }
    case "step": {
      const p = payload as AgentStepPayload;
      let text = `[执行] ${p.type} - ${p.status}`;
      if (p.summary) {
        text += `: ${p.summary}`;
      }
      return text + "\n";
    }
    case "replan": {
      const p = payload as AgentReplanPayload;
      let text = `[重规划]`;
      if (p.reason) {
        text += ` ${p.reason}`;
      }
      if (p.nextSteps && p.nextSteps.length > 0) {
        for (const ns of p.nextSteps) {
          text += `\n  - ${ns}`;
        }
      }
      return text + "\n";
    }
  }
}

function upsertQueueTimeline(
  timeline: AgentTimelineItem[] | undefined,
  payload: QueueStatusPayload
): AgentTimelineItem[] {
  return upsertStepTimeline(timeline, {
    loop: 0,
    stepIndex: 0,
    type: "排队",
    status: "RUNNING",
    summary: summarizeQueue(payload)
  });
}

function updateTimelineStepStatus(
  timeline: AgentTimelineItem[] | undefined,
  stepIndex: number,
  status: string,
  summary?: string
): AgentTimelineItem[] | undefined {
  if (!timeline || timeline.length === 0) return timeline;
  return timeline.map((item) =>
    item.kind === "step" && item.payload.loop === 0 && item.payload.stepIndex === stepIndex
      ? {
          ...item,
          at: Date.now(),
          payload: {
            ...item.payload,
            status,
            summary: summary ?? item.payload.summary
          }
        }
      : item
  );
}

function finalizeLatestRunningStep(
  timeline: AgentTimelineItem[] | undefined,
  status: "SUCCESS" | "FAILED",
  summary: string
): AgentTimelineItem[] | undefined {
  if (!timeline || timeline.length === 0) return timeline;
  let targetIndex = -1;
  for (let index = timeline.length - 1; index >= 0; index -= 1) {
    const item = timeline[index];
    if (item.kind === "step" && item.payload.status === "RUNNING") {
      targetIndex = index;
      break;
    }
  }
  if (targetIndex < 0) return timeline;
  return timeline.map((item, index) =>
    index === targetIndex && item.kind === "step"
      ? {
          ...item,
          at: Date.now(),
          payload: {
            ...item.payload,
            status,
            summary
          }
        }
      : item
  );
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
let fetchSessionsTask: Promise<void> | null = null;

export const useChatStore = create<ChatState>((set, get) => ({
  sessions: [],
  currentSessionId: null,
  messages: [],
  isLoading: false,
  sessionsLoaded: false,
  sessionLoadError: null,
  inputFocusKey: 0,
  isStreaming: false,
  isCreatingNew: false,
  deepThinkingEnabled: false,
  thinkingStartAt: null,
  agentReasoningStartAt: null,
  streamTaskId: null,
  streamAbort: null,
  streamingMessageId: null,
  cancelRequested: false,
  fetchSessions: async () => {
    if (fetchSessionsTask) {
      return fetchSessionsTask;
    }
    fetchSessionsTask = (async () => {
      set({ isLoading: true, sessionLoadError: null });
      try {
        const data = await listSessions();
        const sessions = unwrapArray<ConversationVO>(data)
          .map((item) => ({
            id: item.conversationId,
            title: item.title || "新对话",
            lastTime: item.lastTime
          }))
          .sort((a, b) => {
            const timeA = a.lastTime ? new Date(a.lastTime).getTime() : 0;
            const timeB = b.lastTime ? new Date(b.lastTime).getTime() : 0;
            return timeB - timeA;
          });
        set({
          sessions,
          isLoading: false,
          sessionsLoaded: true,
          sessionLoadError: null
        });
      } catch (error) {
        const message = (error as Error).message || "加载会话失败";
        set({
          isLoading: false,
          sessionsLoaded: true,
          sessionLoadError: message
        });
        toast.error(message);
      } finally {
        fetchSessionsTask = null;
      }
    })();
    return fetchSessionsTask;
  },
  createSession: async () => {
    const state = get();
    if (state.messages.length === 0 && !state.currentSessionId) {
      set({
        isCreatingNew: true,
        isLoading: false,
        thinkingStartAt: null,
        agentReasoningStartAt: null,
        deepThinkingEnabled: false
      });
      return "";
    }
    if (state.isStreaming) {
      get().cancelGeneration();
    }
    set({
      currentSessionId: null,
      messages: [],
      isStreaming: false,
      isLoading: false,
      isCreatingNew: true,
      deepThinkingEnabled: false,
      thinkingStartAt: null,
      agentReasoningStartAt: null,
      streamTaskId: null,
      streamAbort: null,
      streamingMessageId: null,
      cancelRequested: false
    });
    return "";
  },
  deleteSession: async (sessionId) => {
    try {
      await deleteSessionRequest(sessionId);
      set((state) => ({
        sessions: state.sessions.filter((session) => session.id !== sessionId),
        messages: state.currentSessionId === sessionId ? [] : state.messages,
        currentSessionId: state.currentSessionId === sessionId ? null : state.currentSessionId
      }));
      toast.success("删除成功");
    } catch (error) {
      toast.error((error as Error).message || "删除会话失败");
    }
  },
  renameSession: async (sessionId, title) => {
    const nextTitle = title.trim();
    if (!nextTitle) return;
    try {
      await renameSessionRequest(sessionId, nextTitle);
      set((state) => ({
        sessions: state.sessions.map((session) =>
          session.id === sessionId ? { ...session, title: nextTitle } : session
        )
      }));
      toast.success("已重命名");
    } catch (error) {
      toast.error((error as Error).message || "重命名失败");
    }
  },
  selectSession: async (sessionId) => {
    if (!sessionId) return;
    if (get().currentSessionId === sessionId && get().messages.length > 0) return;
    if (get().isStreaming) {
      get().cancelGeneration();
    }
    set({
      isLoading: true,
      currentSessionId: sessionId,
      isCreatingNew: false,
      thinkingStartAt: null,
      agentReasoningStartAt: null
    });
    try {
      const data = await listMessages(sessionId);
      if (get().currentSessionId !== sessionId) {
        return;
      }
      set({ messages: mapConversationMessages(data) });
    } catch (error) {
      toast.error((error as Error).message || "加载消息失败");
    } finally {
      if (get().currentSessionId !== sessionId) {
        set({ isLoading: false });
        return;
      }
      set({
        isLoading: false,
        isStreaming: false,
        streamTaskId: null,
        streamAbort: null,
        streamingMessageId: null,
        cancelRequested: false
      });
    }
  },
  updateSessionTitle: (sessionId, title) => {
    set((state) => ({
      sessions: state.sessions.map((session) =>
        session.id === sessionId ? { ...session, title } : session
      )
    }));
  },
  setDeepThinkingEnabled: (enabled) => {
    set({ deepThinkingEnabled: enabled });
  },
  sendMessage: async (content) => {
    const trimmed = content.trim();
    if (!trimmed) return;
    if (get().isStreaming) return;
    const deepThinkingEnabled = get().deepThinkingEnabled;
    const inputFocusKey = Date.now();

    const userMessage: Message = {
      id: `user-${Date.now()}`,
      role: "user",
      content: trimmed,
      status: "done",
      createdAt: new Date().toISOString()
    };
    const assistantId = `assistant-${Date.now()}`;
    const assistantMessage: Message = {
      id: assistantId,
      role: "assistant",
      content: "",
      thinking: deepThinkingEnabled ? "" : undefined,
      isDeepThinking: deepThinkingEnabled,
      isThinking: deepThinkingEnabled,
      status: "streaming",
      feedback: null,
      createdAt: new Date().toISOString()
    };

    set((state) => ({
      messages: [...state.messages, userMessage, assistantMessage],
      isStreaming: true,
      streamingMessageId: assistantId,
      thinkingStartAt: deepThinkingEnabled ? Date.now() : null,
      inputFocusKey,
      streamTaskId: null,
      cancelRequested: false
    }));

    const conversationId = get().currentSessionId;
    const query = buildQuery({
      question: trimmed,
      conversationId: conversationId || undefined,
      deepThinking: deepThinkingEnabled ? true : undefined
    });
    const url = `${API_BASE_URL}/rag/v4/chat${query}`;
    const token = storage.getToken();

    const handlers = {
      onMeta: (payload: { conversationId: string; taskId: string }) => {
        if (get().streamingMessageId !== assistantId) return;
        const nextId = payload.conversationId || get().currentSessionId;
        if (!nextId) return;
        const lastTime = new Date().toISOString();
        const existing = get().sessions.find((session) => session.id === nextId);
        set((state) => ({
          currentSessionId: nextId,
          isCreatingNew: false,
          streamTaskId: payload.taskId,
          messages: state.messages.map((msg) =>
            msg.id === state.streamingMessageId
              ? {
                  ...msg,
                  agentTimeline: updateTimelineStepStatus(
                    msg.agentTimeline,
                    0,
                    "SUCCESS",
                    "已开始处理，进入执行阶段。"
                  )
                }
              : msg
          ),
          sessions: upsertSession(state.sessions, {
            id: nextId,
            title: existing?.title || "新对话",
            lastTime
          })
        }));
        if (get().cancelRequested) {
          stopTask(payload.taskId).catch(() => null);
        }
      },
      onQueue: (payload: QueueStatusPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload || typeof payload !== "object") return;
        set((state) => ({
          messages: state.messages.map((msg) =>
            msg.id === state.streamingMessageId
              ? {
                  ...msg,
                  agentTimeline: upsertQueueTimeline(msg.agentTimeline, payload)
                }
              : msg
          )
        }));
      },
      onReferences: (payload: ReferenceItem[]) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload || !Array.isArray(payload) || payload.length === 0) return;
        set((state) => ({
          messages: state.messages.map((msg) =>
            msg.id === state.streamingMessageId
              ? { ...msg, references: payload }
              : msg
          )
        }));
      },
      onWorkflow: (payload: WorkflowEventPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload || typeof payload !== "object") return;
        set((state) => ({
          messages: state.messages.map((msg) =>
            msg.id === state.streamingMessageId
              ? { ...msg, workflow: payload }
              : msg
          )
        }));
      },
      onAgentObserve: (payload: AgentObservePayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload || typeof payload !== "object") return;
        const narrative = narrateAgentEvent("observe", payload);
        set((state) => ({
          agentReasoningStartAt: state.agentReasoningStartAt ?? Date.now(),
          messages: state.messages.map((msg) =>
            msg.id === state.streamingMessageId
              ? {
                  ...msg,
                  agentReasoning: (msg.agentReasoning ?? "") + narrative,
                  isAgentReasoning: true,
                  agentTimeline: [
                    ...(msg.agentTimeline ?? []),
                    {
                      kind: "observe",
                      at: Date.now(),
                      payload
                    } satisfies AgentTimelineItem
                  ]
                }
              : msg
          )
        }));
      },
      onAgentPlan: (payload: AgentPlanPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload || typeof payload !== "object") return;
        const narrative = narrateAgentEvent("plan", payload);
        set((state) => ({
          agentReasoningStartAt: state.agentReasoningStartAt ?? Date.now(),
          messages: state.messages.map((msg) =>
            msg.id === state.streamingMessageId
              ? {
                  ...msg,
                  agentReasoning: (msg.agentReasoning ?? "") + narrative,
                  isAgentReasoning: true,
                  agentTimeline: [
                    ...(msg.agentTimeline ?? []),
                    {
                      kind: "plan",
                      at: Date.now(),
                      payload
                    } satisfies AgentTimelineItem
                  ]
                }
              : msg
          )
        }));
      },
      onAgentStep: (payload: AgentStepPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload || typeof payload !== "object") return;
        const narrative = narrateAgentEvent("step", payload);
        set((state) => ({
          agentReasoningStartAt: state.agentReasoningStartAt ?? Date.now(),
          messages: state.messages.map((msg) =>
            msg.id === state.streamingMessageId
              ? {
                  ...msg,
                  agentReasoning: (msg.agentReasoning ?? "") + narrative,
                  isAgentReasoning: true,
                  agentTimeline: upsertStepTimeline(msg.agentTimeline, payload)
                }
              : msg
          )
        }));
      },
      onAgentReplan: (payload: AgentReplanPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload || typeof payload !== "object") return;
        const narrative = narrateAgentEvent("replan", payload);
        set((state) => ({
          agentReasoningStartAt: state.agentReasoningStartAt ?? Date.now(),
          messages: state.messages.map((msg) =>
            msg.id === state.streamingMessageId
              ? {
                  ...msg,
                  agentReasoning: (msg.agentReasoning ?? "") + narrative,
                  isAgentReasoning: true,
                  agentTimeline: [
                    ...(msg.agentTimeline ?? []),
                    {
                      kind: "replan",
                      at: Date.now(),
                      payload
                    } satisfies AgentTimelineItem
                  ]
                }
              : msg
          )
        }));
      },
      onAgentConfirmRequired: (payload: AgentConfirmPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload || typeof payload !== "object") return;
        set((state) => ({
          messages: state.messages.map((msg) =>
            msg.id === state.streamingMessageId
              ? { ...msg, pendingProposal: payload }
              : msg
          )
        }));
      },
      onReasoningTrace: (payload: ReasoningTracePayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload || typeof payload !== "object") return;
        set((state) => ({
          messages: state.messages.map((msg) =>
            msg.id === state.streamingMessageId
              ? {
                  ...msg,
                  reasoningTraces: [...(msg.reasoningTraces ?? []), payload]
                }
              : msg
          )
        }));
      },
      onMessage: (payload: MessageDeltaPayload) => {
        if (!payload || typeof payload !== "object") return;
        if (payload.type !== "response") return;
        get().appendStreamContent(payload.delta);
      },
      onThinking: (payload: MessageDeltaPayload) => {
        if (!payload || typeof payload !== "object") return;
        if (payload.type !== "think") return;
        get().appendThinkingContent(payload.delta);
      },
      onReject: (payload: MessageDeltaPayload) => {
        if (!payload || typeof payload !== "object") return;
        set((state) => ({
          messages: state.messages.map((msg) =>
            msg.id === state.streamingMessageId
              ? {
                  ...msg,
                  agentTimeline: updateTimelineStepStatus(
                    msg.agentTimeline,
                    0,
                    "FAILED",
                    payload.delta || "排队超时，系统繁忙。"
                  )
                }
              : msg
          )
        }));
        get().appendStreamContent(payload.delta);
      },
      onFinish: (payload: CompletionPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload) return;
        if (payload.title && get().currentSessionId) {
          get().updateSessionTitle(get().currentSessionId as string, payload.title);
        }
        const currentId = get().currentSessionId;
        if (currentId) {
          const lastTime = new Date().toISOString();
          const existingTitle =
            get().sessions.find((session) => session.id === currentId)?.title || "新对话";
          const nextTitle = payload.title || existingTitle;
          set((state) => ({
            sessions: upsertSession(state.sessions, {
              id: currentId,
              title: nextTitle,
              lastTime
            })
          }));
        }
        if (payload.messageId) {
          set((state) => ({
            messages: state.messages.map((message) =>
              message.id === state.streamingMessageId
                ? {
                    ...message,
                    id: String(payload.messageId),
                    status: "done",
                    isThinking: false,
                    tokenUsage: payload.totalUsage ?? message.tokenUsage,
                    agentTimeline: finalizeLatestRunningStep(
                      message.agentTimeline,
                      "SUCCESS",
                      "当前阶段已完成。"
                    ),
                    thinkingDuration:
                      message.thinkingDuration ?? computeThinkingDuration(state.thinkingStartAt)
                  }
                : message
            )
          }));
        } else {
          set((state) => ({
            messages: state.messages.map((message) =>
              message.id === state.streamingMessageId
                ? {
                    ...message,
                    status: "done",
                    isThinking: false,
                    tokenUsage: payload.totalUsage ?? message.tokenUsage,
                    agentTimeline: finalizeLatestRunningStep(
                      message.agentTimeline,
                      "SUCCESS",
                      "当前阶段已完成。"
                    ),
                    thinkingDuration:
                      message.thinkingDuration ?? computeThinkingDuration(state.thinkingStartAt)
                  }
                : message
            )
          }));
        }
      },
      onCancel: (payload: CompletionPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (payload?.title && get().currentSessionId) {
          get().updateSessionTitle(get().currentSessionId as string, payload.title);
        }
        set((state) => ({
          messages: state.messages.map((message) => {
            if (message.id !== state.streamingMessageId) return message;
            const suffix = message.content.includes("（已停止生成）")
              ? ""
              : "\n\n（已停止生成）";
            const nextId = payload?.messageId ? String(payload.messageId) : message.id;
            return {
              ...message,
              id: nextId,
              content: message.content + suffix,
              status: "cancelled",
              isThinking: false,
              agentTimeline: finalizeLatestRunningStep(
                message.agentTimeline,
                "FAILED",
                "请求已取消。"
              ),
              thinkingDuration:
                message.thinkingDuration ?? computeThinkingDuration(state.thinkingStartAt)
            };
          }),
          isStreaming: false,
          thinkingStartAt: null,
          agentReasoningStartAt: null,
          streamTaskId: null,
          streamAbort: null,
          streamingMessageId: null,
          cancelRequested: false
        }));
      },
      onDone: () => {
        if (get().streamingMessageId !== assistantId) return;
        set((state) => ({
          isStreaming: false,
          thinkingStartAt: null,
          agentReasoningStartAt: null,
          streamTaskId: null,
          streamAbort: null,
          streamingMessageId: null,
          cancelRequested: false,
          messages: state.messages.map((message) =>
            message.id === state.streamingMessageId &&
            message.status === "streaming"
              ? {
                  ...message,
                  status: "done",
                  isThinking: false,
                  agentTimeline: finalizeLatestRunningStep(
                    message.agentTimeline,
                    "SUCCESS",
                    "当前阶段已完成。"
                  ),
                  thinkingDuration:
                    message.thinkingDuration ?? computeThinkingDuration(state.thinkingStartAt)
                }
              : message
          )
        }));
      },
      onTitle: (payload: { title: string }) => {
        if (get().streamingMessageId !== assistantId) return;
        if (payload?.title && get().currentSessionId) {
          get().updateSessionTitle(get().currentSessionId as string, payload.title);
        }
      },
      onMemorySaved: (payload: { content: string }) => {
        if (payload?.content) {
          toast.success(`已记住：${payload.content}`, { duration: 3000 });
        }
      },
      onError: (error: Error) => {
        if (get().streamingMessageId !== assistantId) return;
        const state = get();
        const currentSessionId = state.currentSessionId;
        const snapshotMessages = state.messages;
        const snapshotLength = snapshotMessages.length;
        const snapshotAssistant = snapshotMessages.find((message) => message.id === assistantId);
        const snapshotAssistantLength = snapshotAssistant?.content.length ?? 0;

        set((currentState) => ({
          isStreaming: false,
          thinkingStartAt: null,
          agentReasoningStartAt: null,
          streamTaskId: null,
          streamAbort: null,
          cancelRequested: false,
          messages: currentState.messages.map((message) =>
            message.id === currentState.streamingMessageId
              ? {
                  ...message,
                  status: "error",
                  isThinking: false,
                  agentTimeline: finalizeLatestRunningStep(
                    message.agentTimeline,
                    "FAILED",
                    error.message || "生成失败"
                  ),
                  thinkingDuration:
                    message.thinkingDuration ?? computeThinkingDuration(currentState.thinkingStartAt)
                }
              : message
          )
        }));

        if (!currentSessionId) {
          toast.error(error.message || "生成失败");
          return;
        }

        void (async () => {
          let recovered = false;
          try {
            const remote = await listMessages(currentSessionId);
            const remoteMessages = mapConversationMessages(remote);
            const remoteLast = remoteMessages.at(-1);
            const hasRecoveredAnswer =
              remoteMessages.length >= snapshotLength &&
              remoteLast?.role === "assistant" &&
              remoteLast.content.length >= snapshotAssistantLength;
            if (hasRecoveredAnswer) {
              set((currentState) => {
                if (currentState.currentSessionId !== currentSessionId) {
                  return {};
                }
                if (currentState.messages.length > snapshotLength) {
                  return {};
                }
                return {
                  messages: remoteMessages,
                  isStreaming: false,
                  thinkingStartAt: null,
                  streamTaskId: null,
                  streamAbort: null,
                  streamingMessageId: null,
                  cancelRequested: false
                };
              });
              recovered = true;
            }
          } catch {
            recovered = false;
          }
          if (!recovered) {
            toast.error(error.message || "生成失败");
          }
        })();
      }
    };

    const { start, cancel } = createStreamResponse(
      {
        url,
        headers: token ? { Authorization: token } : undefined,
        retryCount: 1
      },
      handlers
    );

    set({ streamAbort: cancel });

    try {
      await start();
    } catch (error) {
      if ((error as Error).name === "AbortError") {
        return;
      }
      handlers.onError?.(error as Error);
    } finally {
      if (get().streamingMessageId === assistantId) {
        set({
          isStreaming: false,
          streamTaskId: null,
          streamAbort: null,
          streamingMessageId: null,
          cancelRequested: false
        });
      }
    }
  },
  cancelGeneration: () => {
    const { isStreaming, streamTaskId } = get();
    if (!isStreaming) return;
    set({ cancelRequested: true });
    if (streamTaskId) {
      stopTask(streamTaskId).catch(() => null);
    }
  },
  appendStreamContent: (delta) => {
    if (!delta) return;
    set((state) => {
      const shouldFinalizeThinking = state.thinkingStartAt != null;
      const thinkDuration = computeThinkingDuration(state.thinkingStartAt);
      const shouldFinalizeReasoning = state.agentReasoningStartAt != null;
      const reasoningDuration = computeThinkingDuration(state.agentReasoningStartAt);
      return {
        thinkingStartAt: shouldFinalizeThinking ? null : state.thinkingStartAt,
        agentReasoningStartAt: shouldFinalizeReasoning ? null : state.agentReasoningStartAt,
        messages: state.messages.map((message) => {
          if (message.id !== state.streamingMessageId) return message;
          if (message.status === "cancelled" || message.status === "error") return message;
          return {
            ...message,
            content: message.content + delta,
            isThinking: shouldFinalizeThinking ? false : message.isThinking,
            thinkingDuration:
              shouldFinalizeThinking && !message.thinkingDuration ? thinkDuration : message.thinkingDuration,
            isAgentReasoning: shouldFinalizeReasoning ? false : message.isAgentReasoning,
            agentReasoningDuration:
              shouldFinalizeReasoning && !message.agentReasoningDuration
                ? reasoningDuration
                : message.agentReasoningDuration
          };
        })
      };
    });
  },
  appendThinkingContent: (delta) => {
    if (!delta) return;
    set((state) => ({
      thinkingStartAt: state.thinkingStartAt ?? Date.now(),
      messages: state.messages.map((message) =>
        message.id === state.streamingMessageId &&
        message.status !== "cancelled" &&
        message.status !== "error"
          ? {
              ...message,
              thinking: `${message.thinking ?? ""}${delta}`,
              isThinking: true
            }
          : message
      )
    }));
  },
  submitFeedback: async (messageId, feedback) => {
    const vote = feedback.value === "like" ? 1 : -1;
    const prevMessage = get().messages.find((message) => message.id === messageId) ?? null;
    const nextReason = vote === -1 ? feedback.reason?.trim() || null : null;
    const nextComment = vote === -1 ? feedback.comment?.trim() || null : null;
    set((state) => ({
      messages: state.messages.map((message) =>
        message.id === messageId
          ? {
              ...message,
              feedback: feedback.value,
              feedbackReason: nextReason,
              feedbackComment: nextComment
            }
          : message
      )
    }));
    try {
      await submitFeedback(messageId, {
        vote,
        reason: nextReason ?? undefined,
        comment: nextComment ?? undefined
      });
      toast.success(feedback.value === "like" ? "点赞成功" : "反馈已提交");
    } catch (error) {
      set((state) => ({
        messages: state.messages.map((message) =>
          message.id === messageId
            ? {
                ...message,
                feedback: prevMessage?.feedback ?? null,
                feedbackReason: prevMessage?.feedbackReason ?? null,
                feedbackComment: prevMessage?.feedbackComment ?? null
              }
            : message
        )
      }));
      toast.error((error as Error).message || "反馈保存失败");
    }
  }
}));
