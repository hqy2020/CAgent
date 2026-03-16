import { api } from "@/services/api";

export interface ConversationVO {
  conversationId: string;
  title: string;
  lastTime?: string;
}

export interface ConversationMessageVO {
  id: number | string;
  conversationId: string;
  role: string;
  content: string;
  vote: number | null;
  feedbackReason?: string | null;
  feedbackComment?: string | null;
  createTime?: string;
}

export async function listSessions(): Promise<ConversationVO[]> {
  return api.get<ConversationVO[]>("/conversations", { timeout: 8000 });
}

export async function deleteSession(conversationId: string): Promise<void> {
  return api.delete<void>(`/conversations/${conversationId}`);
}

export async function renameSession(conversationId: string, title: string): Promise<void> {
  return api.put<void>(`/conversations/${conversationId}`, { title });
}

export async function listMessages(conversationId: string): Promise<ConversationMessageVO[]> {
  return api.get<ConversationMessageVO[]>(`/conversations/${conversationId}/messages`);
}
