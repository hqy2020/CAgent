import { api } from "@/services/api";

export interface SystemSettings {
  rag: {
    default: {
      collectionName: string;
      dimension: number;
      metricType: string;
    };
    queryRewrite: {
      enabled: boolean;
      maxHistoryMessages: number;
      maxHistoryTokens: number;
      maxHistoryChars: number;
    };
    rateLimit: {
      global: {
        enabled: boolean;
        maxConcurrent: number;
        maxWaitSeconds: number;
        leaseSeconds: number;
        pollIntervalMs: number;
      };
    };
    memory: {
      historyKeepTurns: number;
      inputBudgetTokens: number;
      historyBudgetTokens: number;
      retrievalBudgetTokens: number;
      summaryStartTurns: number;
      summaryEnabled: boolean;
      summaryTriggerTokens: number;
      ttlMinutes: number;
      summaryMaxChars: number;
      titleMaxLength: number;
    };
  };
  ai: {
    providers: Record<
      string,
      {
        url: string;
        apiKey?: string | null;
        endpoints: Record<string, string>;
      }
    >;
    selection: {
      failureThreshold: number;
      openDurationMs: number;
    };
    stream: {
      messageChunkSize: number;
    };
    chat: ModelGroup;
    embedding: ModelGroup;
    rerank: ModelGroup;
  };
}

export interface ModelGroup {
  defaultModel?: string | null;
  deepThinkingModel?: string | null;
  candidates: ModelCandidate[];
}

export interface ModelCandidate {
  id: string;
  provider: string;
  model: string;
  url?: string | null;
  dimension?: number | null;
  priority?: number | null;
  enabled?: boolean | null;
  supportsThinking?: boolean | null;
}

export async function getSystemSettings(): Promise<SystemSettings> {
  return api.get<SystemSettings, SystemSettings>("/rag/settings");
}

// ─── 可编辑配置 API ───

export interface ConfigItem {
  key: string;
  value: string;
  valueType: string;
  description: string;
}

export interface ConfigGroup {
  group: string;
  groupLabel: string;
  items: ConfigItem[];
}

export async function getEditableConfigs(): Promise<ConfigGroup[]> {
  return api.get<ConfigGroup[], ConfigGroup[]>("/system/configs");
}

export async function updateConfigGroup(group: string, data: Record<string, string>): Promise<void> {
  return api.put(`/system/configs/${group}`, data);
}

export async function initConfigFromYaml(): Promise<void> {
  return api.post("/system/configs/init-from-yaml");
}
