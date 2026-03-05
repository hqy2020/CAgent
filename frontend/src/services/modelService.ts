import { api } from "@/services/api";

// ─── Provider 类型 ───

export interface ModelProvider {
  id: string;
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
  id: string;
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

export interface ModelTestPayload {
  input?: string;
  query?: string;
  candidates?: string[];
  topN?: number;
  thinking?: boolean;
}

export interface ModelTestResult {
  success: boolean;
  modelType: string;
  modelId: string;
  providerKey: string;
  elapsedMs: number;
  message: string;
  responsePreview?: string | null;
  vectorDimension?: number | null;
  vectorPreview?: number[] | null;
  rerankResults?: Array<{
    rank: number;
    score?: number | null;
    text: string;
  }> | null;
  errorMessage?: string | null;
}

// ─── Provider API ───

export async function getProviders(): Promise<ModelProvider[]> {
  return api.get<ModelProvider[], ModelProvider[]>("/ai/providers");
}

export async function createProvider(data: ModelProviderPayload): Promise<string> {
  return api.post<string, string>("/ai/providers", data);
}

export async function updateProvider(id: string, data: ModelProviderPayload): Promise<void> {
  return api.put(`/ai/providers/${id}`, data);
}

export async function deleteProvider(id: string): Promise<void> {
  return api.delete(`/ai/providers/${id}`);
}

// ─── Candidate API ───

export async function getCandidates(type?: string): Promise<ModelCandidate[]> {
  const params = type ? { type } : {};
  return api.get<ModelCandidate[], ModelCandidate[]>("/ai/models", { params });
}

export async function createCandidate(data: ModelCandidatePayload): Promise<string> {
  return api.post<string, string>("/ai/models", data);
}

export async function updateCandidate(id: string, data: ModelCandidatePayload): Promise<void> {
  return api.put(`/ai/models/${id}`, data);
}

export async function deleteCandidate(id: string): Promise<void> {
  return api.delete(`/ai/models/${id}`);
}

export async function setDefaultModel(id: string): Promise<void> {
  return api.put(`/ai/models/${id}/default`);
}

export async function setDeepThinkingModel(id: string): Promise<void> {
  return api.put(`/ai/models/${id}/deep-thinking`);
}

export async function testModelCandidate(id: string, data: ModelTestPayload): Promise<ModelTestResult> {
  return api.post<ModelTestResult, ModelTestResult>(`/ai/models/${id}/test`, data);
}
