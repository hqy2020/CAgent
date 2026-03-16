import { api } from "@/services/api";

export type EvalDataset = {
  id: string;
  name: string;
  description: string | null;
  caseCount: number;
  createdBy: string;
  createTime: string;
  updateTime: string;
};

export type EvalDatasetCase = {
  id: string;
  datasetId: string;
  query: string;
  expectedAnswer: string | null;
  relevantChunkIds: string[];
  intent: string | null;
  createTime: string;
};

export type EvalRun = {
  id: string;
  datasetId: string;
  datasetName: string;
  status: string;
  totalCases: number;
  completedCases: number;
  avgHitRate: number | null;
  avgMrr: number | null;
  avgRecall: number | null;
  avgPrecision: number | null;
  avgFaithfulness: number | null;
  avgRelevancy: number | null;
  avgCorrectness: number | null;
  badCaseCount: number;
  errorMessage: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createTime: string;
};

export type EvalRunResult = {
  id: string;
  runId: string;
  caseId: string;
  query: string;
  expectedAnswer: string | null;
  hitRate: number | null;
  mrr: number | null;
  recallScore: number | null;
  precisionScore: number | null;
  retrievedChunkIds: string[];
  generatedAnswer: string | null;
  faithfulnessScore: number | null;
  faithfulnessReason: string | null;
  relevancyScore: number | null;
  relevancyReason: string | null;
  correctnessScore: number | null;
  correctnessReason: string | null;
  isFallback: boolean;
  isBadCase: boolean;
  rootCause: string | null;
  latencyMs: number | null;
};

export type EvalRunReport = {
  run: EvalRun;
  hitRate: number | null;
  mrr: number | null;
  recall: number | null;
  precision: number | null;
  faithfulness: number | null;
  relevancy: number | null;
  hallucinationRate: number | null;
  correctness: number | null;
  correctnessPassRate: number | null;
  fallbackRate: number | null;
  faithfulnessDistribution: Record<number, number>;
  relevancyDistribution: Record<number, number>;
  correctnessDistribution: Record<number, number>;
};

export type EvalRunCompare = {
  baseRun: EvalRun;
  compareRun: EvalRun;
  hitRateDelta: number | null;
  mrrDelta: number | null;
  recallDelta: number | null;
  precisionDelta: number | null;
  faithfulnessDelta: number | null;
  relevancyDelta: number | null;
  correctnessDelta: number | null;
  badCaseCountDelta: number | null;
};

// Dataset CRUD
export async function createDataset(data: { name: string; description?: string }): Promise<EvalDataset> {
  return api.post<EvalDataset, EvalDataset>("/admin/evaluation/datasets", data);
}

export async function listDatasets(): Promise<EvalDataset[]> {
  return api.get<EvalDataset[], EvalDataset[]>("/admin/evaluation/datasets");
}

export async function getDataset(id: string): Promise<{ dataset: EvalDataset; cases: EvalDatasetCase[] }> {
  return api.get<any, any>(`/admin/evaluation/datasets/${id}`);
}

export async function deleteDataset(id: string): Promise<void> {
  return api.delete(`/admin/evaluation/datasets/${id}`);
}

export async function addCase(datasetId: string, data: {
  query: string;
  expectedAnswer?: string;
  relevantChunkIds?: string[];
  intent?: string;
}): Promise<EvalDatasetCase> {
  return api.post<EvalDatasetCase, EvalDatasetCase>(`/admin/evaluation/datasets/${datasetId}/cases`, data);
}

export async function batchImportCases(datasetId: string, cases: Array<{
  query: string;
  expectedAnswer?: string;
  relevantChunkIds?: string[];
  intent?: string;
}>): Promise<number> {
  return api.post<number, number>(`/admin/evaluation/datasets/${datasetId}/cases/batch`, { cases });
}

export async function deleteCase(datasetId: string, caseId: string): Promise<void> {
  return api.delete(`/admin/evaluation/datasets/${datasetId}/cases/${caseId}`);
}

// Run operations
export async function triggerRun(datasetId: string): Promise<EvalRun> {
  return api.post<EvalRun, EvalRun>("/admin/evaluation/runs", { datasetId });
}

export async function listRuns(): Promise<EvalRun[]> {
  return api.get<EvalRun[], EvalRun[]>("/admin/evaluation/runs");
}

export async function getRun(runId: string): Promise<EvalRun> {
  return api.get<EvalRun, EvalRun>(`/admin/evaluation/runs/${runId}`);
}

export async function getRunReport(runId: string): Promise<EvalRunReport> {
  return api.get<EvalRunReport, EvalRunReport>(`/admin/evaluation/runs/${runId}/report`);
}

export async function getRunResults(runId: string, page: number = 1, size: number = 20): Promise<EvalRunResult[]> {
  return api.get<EvalRunResult[], EvalRunResult[]>(`/admin/evaluation/runs/${runId}/results`, {
    params: { page, size }
  });
}

export async function getBadCases(runId: string): Promise<EvalRunResult[]> {
  return api.get<EvalRunResult[], EvalRunResult[]>(`/admin/evaluation/runs/${runId}/bad-cases`);
}

export async function generateCases(datasetId: string, kbId: string, count: number = 10): Promise<number> {
  return api.post<number, number>(`/admin/evaluation/datasets/${datasetId}/generate`, null, {
    params: { kbId, count }
  });
}

export async function compareRuns(baseRunId: string, compareRunId: string): Promise<EvalRunCompare> {
  return api.get<EvalRunCompare, EvalRunCompare>("/admin/evaluation/runs/compare", {
    params: { baseRunId, compareRunId }
  });
}

// Conversation Q&A import
export type ConversationQAPair = {
  messageId: string;
  conversationId: string;
  conversationTitle: string;
  query: string;
  answer: string | null;
  vote: number | null;
  createTime: string;
};

export type ConversationQAPairPage = {
  records: ConversationQAPair[];
  total: number;
  current: number;
  size: number;
};

export async function listConversationQAPairs(params: {
  keyword?: string;
  current?: number;
  size?: number;
}): Promise<ConversationQAPairPage> {
  return api.get<ConversationQAPairPage, ConversationQAPairPage>("/admin/evaluation/datasets/conversations", {
    params
  });
}

export async function importFromChat(datasetId: string, messageIds: string[]): Promise<number> {
  return api.post<number, number>(`/admin/evaluation/datasets/${datasetId}/import-from-chat`, {
    messageIds: messageIds.map(Number)
  });
}
