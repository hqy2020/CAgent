import { api } from "@/services/api";

export type UserMemory = {
  id: string;
  userId: string;
  memoryType: string;
  content: string;
  sourceConversationId: string | null;
  weight: number;
  state: string;
  tags: string | null;
  createTime: string;
  updateTime: string;
};

export type UserProfile = {
  id: string;
  userId: string;
  displayName: string | null;
  occupation: string | null;
  interests: string | null;
  preferences: string | null;
  facts: string | null;
  summary: string | null;
  version: number;
  createTime: string;
  updateTime: string;
};

export async function listUserMemories(userId: number, type?: string): Promise<UserMemory[]> {
  return api.get<UserMemory[], UserMemory[]>("/admin/memories", {
    params: type ? { userId, type } : { userId }
  });
}

export async function getUserMemory(id: string): Promise<UserMemory> {
  return api.get<UserMemory, UserMemory>(`/admin/memories/${id}`);
}

export async function updateUserMemory(id: string, content: string): Promise<void> {
  return api.put(`/admin/memories/${id}`, { content });
}

export async function archiveUserMemory(id: string): Promise<void> {
  return api.post(`/admin/memories/${id}/archive`);
}

export async function deleteUserMemory(id: string): Promise<void> {
  return api.delete(`/admin/memories/${id}`);
}

export async function getUserProfile(userId: number): Promise<UserProfile> {
  return api.get<UserProfile, UserProfile>("/admin/profiles", {
    params: { userId }
  });
}

export async function updateUserProfile(userId: number, data: Partial<UserProfile>): Promise<void> {
  return api.put("/admin/profiles", data, {
    params: { userId }
  });
}
