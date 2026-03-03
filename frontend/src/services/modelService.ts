import { api } from "@/services/api";

// ─── Provider 类型 ───

export interface ModelProvider {
  id: number;
  providerKey: string;
  name: string;
  baseUrl: string;
  apiKey: string | null;
  endpoints: Record<string, string>;
  enabled: number;
  sortOrder: number;
  createTime: string;
  updateTime: string;
}

export interface ModelProviderPayload {
  providerKey: string;
  name: string;
  baseUrl: string;
  apiKey?: string;
  endpoints?: Record<string, string>;
  enabled?: number;
  sortOrder?: number;
}

// ─── Candidate 类型 ───

export interface ModelCandidate {
  id: number;
  modelId: string;
  modelType: string;
  providerKey: string;
  modelName: string;
  customUrl?: string | null;
  dimension?: number | null;
  priority: number;
  enabled: number;
  supportsThinking: number;
  isDefault: number;
  isDeepThinking: number;
  createTime: string;
  updateTime: string;
}

export interface ModelCandidatePayload {
  modelId: string;
  modelType: string;
  providerKey: string;
  modelName: string;
  customUrl?: string;
  dimension?: number;
  priority?: number;
  enabled?: number;
  supportsThinking?: number;
}

// ─── Provider API ───

export async function getProviders(): Promise<ModelProvider[]> {
  return api.get<ModelProvider[], ModelProvider[]>("/ai/providers");
}

export async function createProvider(data: ModelProviderPayload): Promise<number> {
  return api.post<number, number>("/ai/providers", data);
}

export async function updateProvider(id: number, data: ModelProviderPayload): Promise<void> {
  return api.put(`/ai/providers/${id}`, data);
}

export async function deleteProvider(id: number): Promise<void> {
  return api.delete(`/ai/providers/${id}`);
}

// ─── Candidate API ───

export async function getCandidates(type?: string): Promise<ModelCandidate[]> {
  const params = type ? { type } : {};
  return api.get<ModelCandidate[], ModelCandidate[]>("/ai/models", { params });
}

export async function createCandidate(data: ModelCandidatePayload): Promise<number> {
  return api.post<number, number>("/ai/models", data);
}

export async function updateCandidate(id: number, data: ModelCandidatePayload): Promise<void> {
  return api.put(`/ai/models/${id}`, data);
}

export async function deleteCandidate(id: number): Promise<void> {
  return api.delete(`/ai/models/${id}`);
}

export async function setDefaultModel(id: number): Promise<void> {
  return api.put(`/ai/models/${id}/default`);
}

export async function setDeepThinkingModel(id: number): Promise<void> {
  return api.put(`/ai/models/${id}/deep-thinking`);
}
