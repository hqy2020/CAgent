import { api } from "@/services/api";
import { storage } from "@/utils/storage";

export type HotspotSource =
  | "twitter"
  | "bing"
  | "hackernews"
  | "sogou"
  | "bilibili"
  | "weibo"
  | "baidu"
  | "duckduckgo"
  | "reddit";

export type HotspotItem = {
  title: string;
  summary: string;
  url: string;
  source: HotspotSource;
  sourceLabel: string;
  publishedAt: string | null;
  hotScore: number | null;
  viewCount: number | null;
  likeCount: number | null;
  commentCount: number | null;
  score: number | null;
  danmakuCount: number | null;
  authorName: string | null;
  authorUsername: string | null;
  relevanceScore?: number | null;
  credibilityScore?: number | null;
  verdict?: string | null;
  analysisSummary?: string | null;
  analysisReason?: string | null;
  matchedKeywords?: string[] | null;
};

export type HotspotReport = {
  query: string;
  generatedAt: number;
  total: number;
  sources: HotspotSource[];
  sourceCounts: Record<string, number>;
  warnings: string[];
  expandedQueries: string[];
  analyzed: boolean;
  items: HotspotItem[];
};

export type HotspotMonitor = {
  id: string;
  keyword: string;
  sources: HotspotSource[];
  enabled: boolean;
  email: string | null;
  emailEnabled: boolean;
  websocketEnabled: boolean;
  scanIntervalMinutes: number;
  relevanceThreshold: number;
  credibilityThreshold: number;
  lastScanTime: string | null;
  lastSuccessTime: string | null;
  lastError: string | null;
  lastResultCount: number;
  nextRunTime: string | null;
  createTime: string | null;
  updateTime: string | null;
};

export type HotspotMonitorEvent = {
  id: string;
  monitorId: string;
  keyword: string;
  title: string;
  summary: string;
  url: string;
  source: HotspotSource;
  sourceLabel: string;
  publishedAt: string | null;
  hotScore: number | null;
  relevanceScore: number | null;
  credibilityScore: number | null;
  verdict: string | null;
  analysisSummary: string | null;
  analysisReason: string | null;
  authorName: string | null;
  notifiedWebsocket: boolean;
  notifiedEmail: boolean;
  createTime: string | null;
};

export type HotspotMonitorRun = {
  id: string;
  monitorId: string;
  keyword: string;
  status: string;
  warning: string | null;
  sources: HotspotSource[];
  expandedQueries: string[];
  fetchedCount: number;
  qualifiedCount: number;
  newEventCount: number;
  startedAt: string | null;
  finishedAt: string | null;
};

export type HotspotMonitorPayload = {
  keyword: string;
  sources: HotspotSource[];
  enabled: boolean;
  email: string;
  emailEnabled: boolean;
  websocketEnabled: boolean;
  scanIntervalMinutes: number;
  relevanceThreshold: number;
  credibilityThreshold: number;
};

export type HotspotSocketMessage =
  | { type: "connected"; userId: string; timestamp: number }
  | { type: "pong"; timestamp: number }
  | { type: "hotspot-alert"; timestamp: number; monitorId: string; keyword: string; events: HotspotMonitorEvent[] };

type PageResult<T> = {
  records: T[];
  total: number;
  current: number;
  size: number;
  pages: number;
};

export async function getHotspotReport(params: {
  query?: string;
  sources?: HotspotSource[];
  limit?: number;
  analyze?: boolean;
}): Promise<HotspotReport> {
  const { query, sources, limit, analyze } = params;
  return api.get<HotspotReport, HotspotReport>("/hotspots/report", {
    params: {
      query,
      sources: sources?.join(","),
      limit,
      analyze
    }
  });
}

export async function listHotspotMonitors(pageNo = 1, pageSize = 20): Promise<PageResult<HotspotMonitor>> {
  return api.get<PageResult<HotspotMonitor>, PageResult<HotspotMonitor>>("/hotspots/monitors", {
    params: { pageNo, pageSize }
  });
}

export async function createHotspotMonitor(payload: HotspotMonitorPayload): Promise<string> {
  return api.post<string, string, HotspotMonitorPayload>("/hotspots/monitors", payload);
}

export async function updateHotspotMonitor(id: string, payload: HotspotMonitorPayload): Promise<void> {
  return api.put<void, void, HotspotMonitorPayload>(`/hotspots/monitors/${id}`, payload);
}

export async function toggleHotspotMonitor(id: string, enabled: boolean): Promise<void> {
  return api.post<void, void>(`/hotspots/monitors/${id}/toggle`, undefined, {
    params: { enabled }
  });
}

export async function scanHotspotMonitor(id: string): Promise<HotspotMonitorRun> {
  return api.post<HotspotMonitorRun, HotspotMonitorRun>(`/hotspots/monitors/${id}/scan`);
}

export async function listHotspotEvents(params?: {
  monitorId?: string;
  pageNo?: number;
  pageSize?: number;
}): Promise<PageResult<HotspotMonitorEvent>> {
  return api.get<PageResult<HotspotMonitorEvent>, PageResult<HotspotMonitorEvent>>("/hotspots/events", {
    params
  });
}

export async function listHotspotRuns(params?: {
  monitorId?: string;
  pageNo?: number;
  pageSize?: number;
}): Promise<PageResult<HotspotMonitorRun>> {
  return api.get<PageResult<HotspotMonitorRun>, PageResult<HotspotMonitorRun>>("/hotspots/runs", {
    params
  });
}

export function createHotspotSocket() {
  const token = storage.getToken();
  if (!token) {
    throw new Error("未登录，无法建立热点推送连接");
  }
  const explicit = import.meta.env.VITE_HOTSPOT_WS_URL as string | undefined;
  const base = explicit || `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/api/ragent/ws/hotspots`;
  return new WebSocket(`${base}?token=${encodeURIComponent(token)}`);
}
