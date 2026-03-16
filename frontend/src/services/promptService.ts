import { api } from "@/services/api";

export type PromptTemplate = {
  id: string;
  promptKey: string;
  name: string;
  category: string;
  content: string;
  filePath: string | null;
  variables: string | null;
  description: string | null;
  version: number;
  enabled: boolean;
  updatedBy: string | null;
  createTime: string;
  updateTime: string;
};

export async function listPromptTemplates(category?: string): Promise<PromptTemplate[]> {
  return api.get<PromptTemplate[], PromptTemplate[]>("/admin/prompts", {
    params: category ? { category } : {}
  });
}

export async function getPromptTemplate(key: string): Promise<PromptTemplate> {
  return api.get<PromptTemplate, PromptTemplate>(`/admin/prompts/${key}`);
}

export async function updatePromptTemplate(key: string, content: string): Promise<PromptTemplate> {
  return api.put<PromptTemplate, PromptTemplate>(`/admin/prompts/${key}`, { content });
}

export async function resetPromptTemplate(key: string): Promise<void> {
  return api.post(`/admin/prompts/${key}/reset`);
}

export async function togglePromptTemplate(key: string): Promise<void> {
  return api.post(`/admin/prompts/${key}/toggle`);
}
