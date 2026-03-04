export type Role = "user" | "assistant";

export type FeedbackValue = "like" | "dislike" | null;

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
  status?: MessageStatus;
  references?: ReferenceItem[];
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
}

export interface WorkflowEventPayload {
  workflowId: string;
  changedFiles?: string[];
  opsCount?: number;
  warnings?: string[];
}

export interface AgentPlanStepPayload {
  stepIndex: number;
  type: string;
  instruction: string;
}

export interface AgentPlanPayload {
  loop: number;
  goal: string;
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
