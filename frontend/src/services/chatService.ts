import { api } from "@/services/api";

export interface MessageFeedbackRequestPayload {
  vote: 1 | -1;
  reason?: string;
  comment?: string;
}

export async function stopTask(taskId: string) {
  return api.post<void>(`/rag/v4/stop?taskId=${encodeURIComponent(taskId)}`);
}

export async function submitFeedback(messageId: string, payload: MessageFeedbackRequestPayload) {
  return api.post<void>(`/conversations/messages/${messageId}/feedback`, {
    vote: payload.vote,
    reason: payload.reason,
    comment: payload.comment
  });
}
