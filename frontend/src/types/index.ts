export type Role = "user" | "assistant";

export type FeedbackValue = "like" | "dislike" | null;

export const MESSAGE_FEEDBACK_REASONS = [
  "路由判别错误",
  "检索材料不对",
  "应该联网搜索但没搜",
  "结论过于武断",
  "回答与问题无关",
  "其他"
] as const;

export type MessageFeedbackReason = (typeof MESSAGE_FEEDBACK_REASONS)[number];

export interface MessageFeedbackSubmission {
  value: Exclude<FeedbackValue, null>;
  reason?: string | null;
  comment?: string | null;
}

export type MessageStatus = "streaming" | "done" | "cancelled" | "error";

export interface User {
  userId: string;
  username?: string;
  role: string;
  token: string;
  avatar?: string;
}

export type CurrentUser = Omit<User, "token">;

export interface Session {
  id: string;
  title: string;
  lastTime?: string;
}

export interface Message {
  id: string;
  role: Role;
  content: string;
  thinking?: string;
  workflow?: WorkflowEventPayload;
  agentTimeline?: AgentTimelineItem[];
  pendingProposal?: AgentConfirmPayload;
  thinkingDuration?: number;
  isDeepThinking?: boolean;
  isThinking?: boolean;
  createdAt?: string;
  feedback?: FeedbackValue;
  feedbackReason?: string | null;
  feedbackComment?: string | null;
  status?: MessageStatus;
  references?: ReferenceItem[];
  agentReasoning?: string;
  isAgentReasoning?: boolean;
  agentReasoningDuration?: number;
  reasoningTraces?: ReasoningTracePayload[];
  tokenUsage?: TokenUsagePayload | null;
}

export interface StreamMetaPayload {
  conversationId: string;
  taskId: string;
}

export interface MessageDeltaPayload {
  type: string;
  delta: string;
}

export interface CompletionPayload {
  messageId?: string | null;
  title?: string | null;
  totalUsage?: TokenUsagePayload | null;
}

export interface TokenUsagePayload {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

export interface ReasoningTracePayload {
  step: string;
  stepLabel: string;
  messages: { role: string; content: string }[];
  response: string | null;
  usage: TokenUsagePayload | null;
}

export interface QueueStatusPayload {
  position: number;
  total: number;
}

export interface WorkflowEventPayload {
  workflowId: string;
  changedFiles?: string[];
  opsCount?: number;
  warnings?: string[];
}

export interface AgentModelPayload {
  modelId?: string;
  provider?: string;
}

export interface AgentPlanStepPayload {
  stepIndex: number;
  type: string;
  instruction: string;
  query?: string;
  toolId?: string;
}

export interface AgentObserveItemPayload {
  source: string;
  status: string;
  summary: string;
  detail?: string;
}

export interface AgentObservePayload {
  loop: number;
  stepIndex: number;
  summary?: string;
  items?: AgentObserveItemPayload[];
}

export interface AgentPlanPayload {
  loop: number;
  goal: string;
  thought?: string;
  model?: AgentModelPayload;
  steps?: AgentPlanStepPayload[];
}

export interface AgentStepPayload {
  loop: number;
  stepIndex: number;
  type: string;
  status: string;
  summary?: string;
  references?: ReferenceItem[];
  error?: string;
  instruction?: string;
  query?: string;
  toolId?: string;
  detail?: string;
  model?: AgentModelPayload;
}

export interface AgentReplanPayload {
  loop: number;
  reason?: string;
  nextSteps?: string[];
}

export interface AgentConfirmPayload {
  proposalId: string;
  toolId: string;
  parameters?: Record<string, unknown>;
  targetPath?: string;
  riskHint?: string;
  expiresAt?: number;
}

export type AgentTimelineItem =
  | { kind: "observe"; at: number; payload: AgentObservePayload }
  | { kind: "plan"; at: number; payload: AgentPlanPayload }
  | { kind: "step"; at: number; payload: AgentStepPayload }
  | { kind: "replan"; at: number; payload: AgentReplanPayload };

export interface ChunkDetail {
  content: string;
  score?: number;
}

export interface ReferenceItem {
  documentId: string;
  documentName: string;
  knowledgeBaseId?: string;
  knowledgeBaseName?: string;
  score?: number;
  documentUrl?: string;
  textPreview?: string;
  chunks?: ChunkDetail[];
}
