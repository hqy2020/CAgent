import { useEffect, useMemo, useRef, useState } from "react";
import {
  Activity,
  BellRing,
  Bot,
  CirclePlay,
  ExternalLink,
  Flame,
  Globe2,
  Newspaper,
  Pencil,
  RadioTower,
  RefreshCw,
  Search,
  Send,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Twitter,
  Zap
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";
import {
  createHotspotMonitor,
  createHotspotSocket,
  getHotspotReport,
  listHotspotEvents,
  listHotspotMonitors,
  listHotspotRuns,
  scanHotspotMonitor,
  toggleHotspotMonitor,
  updateHotspotMonitor,
  type HotspotItem,
  type HotspotMonitor,
  type HotspotMonitorEvent,
  type HotspotMonitorPayload,
  type HotspotMonitorRun,
  type HotspotReport,
  type HotspotSocketMessage,
  type HotspotSource
} from "@/services/hotspotService";

type SourceOption = {
  value: HotspotSource;
  label: string;
  icon: typeof Globe2;
  tone: string;
};

type MonitorFormState = HotspotMonitorPayload;

const SOURCE_OPTIONS: SourceOption[] = [
  { value: "twitter", label: "Twitter", icon: Twitter, tone: "from-sky-500/15 to-sky-500/5 text-sky-700" },
  { value: "bing", label: "Bing", icon: Globe2, tone: "from-cyan-500/15 to-cyan-500/5 text-cyan-700" },
  { value: "hackernews", label: "Hacker News", icon: Newspaper, tone: "from-orange-500/15 to-orange-500/5 text-orange-700" },
  { value: "sogou", label: "搜狗", icon: Search, tone: "from-emerald-500/15 to-emerald-500/5 text-emerald-700" },
  { value: "bilibili", label: "Bilibili", icon: CirclePlay, tone: "from-pink-500/15 to-pink-500/5 text-pink-700" },
  { value: "weibo", label: "微博", icon: RadioTower, tone: "from-red-500/15 to-red-500/5 text-red-700" },
  { value: "baidu", label: "百度", icon: Globe2, tone: "from-blue-500/15 to-blue-500/5 text-blue-700" },
  { value: "duckduckgo", label: "DuckDuckGo", icon: Globe2, tone: "from-amber-500/15 to-amber-500/5 text-amber-700" },
  { value: "reddit", label: "Reddit", icon: Flame, tone: "from-orange-500/15 to-red-500/5 text-orange-700" }
];

const DEFAULT_REPORT_SOURCES: HotspotSource[] = SOURCE_OPTIONS.map((item) => item.value);
const DEFAULT_MONITOR_SOURCES: HotspotSource[] = ["twitter", "bing", "hackernews", "sogou", "bilibili", "weibo", "duckduckgo", "reddit"];

const DEFAULT_MONITOR_FORM: MonitorFormState = {
  keyword: "",
  sources: DEFAULT_MONITOR_SOURCES,
  enabled: true,
  email: "",
  emailEnabled: false,
  websocketEnabled: true,
  scanIntervalMinutes: 30,
  relevanceThreshold: 0.55,
  credibilityThreshold: 0.45
};

const formatDateTime = (timestamp?: number | string | null) => {
  if (!timestamp) return "-";
  const value = new Date(timestamp);
  if (Number.isNaN(value.getTime())) return "-";
  return value.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });
};

const formatRelativeTime = (timestamp?: string | null) => {
  if (!timestamp) return "时间未知";
  const value = new Date(timestamp).getTime();
  if (Number.isNaN(value)) return "时间未知";
  const diffMinutes = Math.round((Date.now() - value) / 60000);
  if (diffMinutes <= 1) return "刚刚";
  if (diffMinutes < 60) return `${diffMinutes} 分钟前`;
  const diffHours = Math.round(diffMinutes / 60);
  if (diffHours < 24) return `${diffHours} 小时前`;
  const diffDays = Math.round(diffHours / 24);
  return `${diffDays} 天前`;
};

const formatMetric = (value?: number | null) => {
  if (value == null || value <= 0) return null;
  return value.toLocaleString("zh-CN");
};

const formatPercent = (value?: number | null) => {
  if (value == null || Number.isNaN(value)) return "-";
  return `${Math.round(value * 100)}%`;
};

const verdictTone = (verdict?: string | null) => {
  switch (verdict) {
    case "较可信":
      return "bg-emerald-100 text-emerald-700";
    case "待核验":
      return "bg-amber-100 text-amber-700";
    case "高风险":
      return "bg-rose-100 text-rose-700";
    case "弱相关":
      return "bg-slate-100 text-slate-600";
    default:
      return "bg-slate-100 text-slate-600";
  }
};

const sourceLabelOf = (source: HotspotSource) =>
  SOURCE_OPTIONS.find((item) => item.value === source)?.label || source;

function SourceSelector({
  selected,
  onToggle
}: {
  selected: HotspotSource[];
  onToggle: (source: HotspotSource) => void;
}) {
  return (
    <div className="flex flex-wrap gap-2">
      {SOURCE_OPTIONS.map((option) => {
        const active = selected.includes(option.value);
        const Icon = option.icon;
        return (
          <Button
            key={option.value}
            type="button"
            size="sm"
            variant={active ? "default" : "outline"}
            onClick={() => onToggle(option.value)}
            className={cn(active && "admin-primary-gradient")}
          >
            <Icon className="h-3.5 w-3.5" />
            {option.label}
          </Button>
        );
      })}
    </div>
  );
}

function HotspotMetric({
  label,
  value
}: {
  label: string;
  value?: string | null;
}) {
  if (!value) return null;
  return (
    <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-medium text-slate-600">
      {label} {value}
    </span>
  );
}

function QuickReportCard({ item }: { item: HotspotItem }) {
  const sourceTone = SOURCE_OPTIONS.find((option) => option.value === item.source)?.tone || "";

  return (
    <Card className="overflow-hidden border-slate-200/80 bg-white/95 shadow-sm transition-all hover:-translate-y-0.5 hover:shadow-lg">
      <CardHeader className="space-y-4 pb-4">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="outline" className={cn("border-0 bg-gradient-to-r", sourceTone)}>
            {item.sourceLabel}
          </Badge>
          <Badge variant="secondary" className="bg-slate-100 text-slate-600">
            {formatRelativeTime(item.publishedAt)}
          </Badge>
          {item.verdict ? (
            <Badge variant="secondary" className={verdictTone(item.verdict)}>
              {item.verdict}
            </Badge>
          ) : null}
          {item.hotScore != null && item.hotScore > 0 ? (
            <Badge variant="secondary" className="bg-amber-100/80 text-amber-700">
              热度 {item.hotScore.toFixed(1)}
            </Badge>
          ) : null}
        </div>
        <div className="space-y-2">
          <CardTitle className="text-lg leading-7 text-slate-900">{item.title}</CardTitle>
          <p className="text-sm leading-6 text-slate-600">
            {item.analysisSummary || item.summary || "该条结果未返回摘要，可直接打开原始链接查看详情。"}
          </p>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap gap-2">
          <HotspotMetric label="相关性" value={formatPercent(item.relevanceScore)} />
          <HotspotMetric label="可信度" value={formatPercent(item.credibilityScore)} />
          <HotspotMetric label="浏览" value={formatMetric(item.viewCount)} />
          <HotspotMetric label="点赞" value={formatMetric(item.likeCount)} />
          <HotspotMetric label="评论" value={formatMetric(item.commentCount)} />
          <HotspotMetric label="评分" value={formatMetric(item.score)} />
          <HotspotMetric label="弹幕" value={formatMetric(item.danmakuCount)} />
        </div>

        {item.analysisReason ? (
          <div className="rounded-2xl bg-slate-50 px-3 py-2 text-xs leading-5 text-slate-600">
            <span className="font-medium text-slate-700">AI 分析:</span> {item.analysisReason}
          </div>
        ) : null}

        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-4">
          <div className="space-y-1 text-xs text-slate-500">
            <div>
              来源: <span className="font-medium text-slate-700">{item.sourceLabel}</span>
            </div>
            <div>
              发布时间: <span className="font-medium text-slate-700">{formatDateTime(item.publishedAt)}</span>
            </div>
            {item.authorName ? (
              <div>
                作者:{" "}
                <span className="font-medium text-slate-700">
                  {item.authorName}
                  {item.authorUsername ? ` / ${item.authorUsername}` : ""}
                </span>
              </div>
            ) : null}
          </div>
          <Button asChild size="sm" className="admin-primary-gradient">
            <a href={item.url} target="_blank" rel="noreferrer">
              打开原文
              <ExternalLink className="h-3.5 w-3.5" />
            </a>
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

function AlertEventCard({ item }: { item: HotspotMonitorEvent }) {
  return (
    <Card className="border-slate-200/80 bg-white/95 shadow-sm">
      <CardContent className="space-y-3 p-4">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="outline">{item.sourceLabel}</Badge>
          <Badge variant="secondary" className={verdictTone(item.verdict)}>
            {item.verdict || "待核验"}
          </Badge>
          <Badge variant="secondary" className="bg-slate-100 text-slate-600">
            {formatRelativeTime(item.createTime)}
          </Badge>
        </div>
        <div className="space-y-1">
          <div className="text-sm font-semibold text-slate-900">{item.title}</div>
          <div className="text-xs text-slate-500">监控词: {item.keyword}</div>
        </div>
        <div className="text-sm leading-6 text-slate-600">
          {item.analysisSummary || item.summary || "暂无分析摘要"}
        </div>
        <div className="flex flex-wrap gap-2 text-xs text-slate-500">
          <HotspotMetric label="相关性" value={formatPercent(item.relevanceScore)} />
          <HotspotMetric label="可信度" value={formatPercent(item.credibilityScore)} />
          <HotspotMetric label="入库时间" value={formatDateTime(item.createTime)} />
        </div>
        <Button asChild size="sm" variant="outline">
          <a href={item.url} target="_blank" rel="noreferrer">
            查看原文
            <ExternalLink className="h-3.5 w-3.5" />
          </a>
        </Button>
      </CardContent>
    </Card>
  );
}

export function HotspotRadarPage() {
  const [queryInput, setQueryInput] = useState("AI");
  const [query, setQuery] = useState("AI");
  const [selectedSources, setSelectedSources] = useState<HotspotSource[]>(DEFAULT_REPORT_SOURCES);
  const [report, setReport] = useState<HotspotReport | null>(null);
  const [reportLoading, setReportLoading] = useState(true);
  const [reportRefreshing, setReportRefreshing] = useState(false);
  const [reportError, setReportError] = useState<string | null>(null);

  const [monitorForm, setMonitorForm] = useState<MonitorFormState>(DEFAULT_MONITOR_FORM);
  const [editingMonitorId, setEditingMonitorId] = useState<string | null>(null);
  const [monitorSaving, setMonitorSaving] = useState(false);
  const [monitorLoading, setMonitorLoading] = useState(false);
  const [monitors, setMonitors] = useState<HotspotMonitor[]>([]);

  const [eventLoading, setEventLoading] = useState(false);
  const [events, setEvents] = useState<HotspotMonitorEvent[]>([]);
  const [runs, setRuns] = useState<HotspotMonitorRun[]>([]);

  const [socketState, setSocketState] = useState<"connecting" | "connected" | "closed" | "error">("connecting");
  const socketRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<number | null>(null);
  const reconnectEnabledRef = useRef(true);
  const connectAttemptRef = useRef(0);

  const warnings = report?.warnings || [];
  const reportItems = report?.items || [];

  const activeMonitorCount = useMemo(
    () => monitors.filter((item) => item.enabled).length,
    [monitors]
  );

  const totalNewEvents = useMemo(
    () => runs.slice(0, 5).reduce((sum, item) => sum + (item.newEventCount || 0), 0),
    [runs]
  );

  const connectSocket = () => {
    if (reconnectTimerRef.current != null) {
      window.clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    connectAttemptRef.current += 1;
    const attemptId = connectAttemptRef.current;
    if (socketRef.current) {
      socketRef.current.onopen = null;
      socketRef.current.onmessage = null;
      socketRef.current.onclose = null;
      socketRef.current.onerror = null;
      socketRef.current.close();
    }
    try {
      setSocketState("connecting");
      const socket = createHotspotSocket();
      socketRef.current = socket;
      socket.onopen = () => {
        if (socketRef.current !== socket || connectAttemptRef.current !== attemptId) {
          return;
        }
        setSocketState("connected");
      };
      socket.onmessage = (event) => {
        if (socketRef.current !== socket || connectAttemptRef.current !== attemptId) {
          return;
        }
        try {
          const payload = JSON.parse(event.data) as HotspotSocketMessage;
          if (payload.type === "connected" || payload.type === "pong") {
            setSocketState("connected");
            return;
          }
          if (payload.type === "hotspot-alert" && payload.events?.length) {
            setEvents((prev) => [...payload.events, ...prev].slice(0, 20));
            toast.success(`命中监控词 ${payload.keyword}，新增 ${payload.events.length} 条热点`);
            void refreshRuns();
            void refreshMonitors();
          }
        } catch {
          setSocketState("error");
        }
      };
      socket.onclose = () => {
        if (socketRef.current === socket) {
          socketRef.current = null;
        }
        if (connectAttemptRef.current !== attemptId) {
          return;
        }
        setSocketState("closed");
        if (!reconnectEnabledRef.current) {
          return;
        }
        reconnectTimerRef.current = window.setTimeout(connectSocket, 3000);
      };
      socket.onerror = () => {
        if (connectAttemptRef.current !== attemptId) {
          return;
        }
        setSocketState("error");
      };
    } catch (error) {
      setSocketState("error");
      toast.error(error instanceof Error ? error.message : "热点推送连接失败");
    }
  };

  const refreshReport = async (nextQuery: string, nextSources: HotspotSource[], silent = false) => {
    if (!silent) {
      setReportLoading(true);
    } else {
      setReportRefreshing(true);
    }
    setReportError(null);
    try {
      const data = await getHotspotReport({
        query: nextQuery,
        sources: nextSources,
        limit: 12,
        analyze: true
      });
      setReport(data);
      setQuery(nextQuery);
    } catch (error) {
      setReportError(error instanceof Error ? error.message : "热点快报加载失败");
    } finally {
      setReportLoading(false);
      setReportRefreshing(false);
    }
  };

  const refreshMonitors = async () => {
    setMonitorLoading(true);
    try {
      const data = await listHotspotMonitors(1, 20);
      setMonitors(data.records || []);
    } finally {
      setMonitorLoading(false);
    }
  };

  const refreshEvents = async () => {
    setEventLoading(true);
    try {
      const data = await listHotspotEvents({ pageNo: 1, pageSize: 20 });
      setEvents(data.records || []);
    } finally {
      setEventLoading(false);
    }
  };

  const refreshRuns = async () => {
    const data = await listHotspotRuns({ pageNo: 1, pageSize: 10 });
    setRuns(data.records || []);
  };

  useEffect(() => {
    reconnectEnabledRef.current = true;
    void refreshReport("AI", DEFAULT_REPORT_SOURCES);
    void refreshMonitors();
    void refreshEvents();
    void refreshRuns();
    connectSocket();
    return () => {
      reconnectEnabledRef.current = false;
      if (reconnectTimerRef.current != null) {
        window.clearTimeout(reconnectTimerRef.current);
      }
      if (socketRef.current) {
        socketRef.current.onopen = null;
        socketRef.current.onmessage = null;
        socketRef.current.onclose = null;
        socketRef.current.onerror = null;
        socketRef.current.close();
        socketRef.current = null;
      }
    };
  }, []);

  const handleReportSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const normalizedQuery = queryInput.trim() || "AI";
    setQueryInput(normalizedQuery);
    await refreshReport(normalizedQuery, selectedSources);
  };

  const toggleReportSource = (source: HotspotSource) => {
    setSelectedSources((prev) => {
      if (prev.includes(source)) {
        return prev.length === 1 ? prev : prev.filter((item) => item !== source);
      }
      return [...prev, source];
    });
  };

  const toggleMonitorSource = (source: HotspotSource) => {
    setMonitorForm((prev) => {
      if (prev.sources.includes(source)) {
        return {
          ...prev,
          sources: prev.sources.length === 1 ? prev.sources : prev.sources.filter((item) => item !== source)
        };
      }
      return {
        ...prev,
        sources: [...prev.sources, source]
      };
    });
  };

  const resetMonitorForm = () => {
    setEditingMonitorId(null);
    setMonitorForm(DEFAULT_MONITOR_FORM);
  };

  const handleMonitorSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const payload: HotspotMonitorPayload = {
      ...monitorForm,
      keyword: monitorForm.keyword.trim(),
      email: monitorForm.email.trim(),
      sources: monitorForm.sources
    };
    if (!payload.keyword) {
      toast.error("请输入监控关键词");
      return;
    }
    setMonitorSaving(true);
    try {
      if (editingMonitorId) {
        await updateHotspotMonitor(editingMonitorId, payload);
        toast.success("热点监控已更新");
      } else {
        await createHotspotMonitor(payload);
        toast.success("热点监控已创建");
      }
      resetMonitorForm();
      await Promise.all([refreshMonitors(), refreshRuns()]);
    } finally {
      setMonitorSaving(false);
    }
  };

  const handleToggleMonitor = async (monitor: HotspotMonitor) => {
    await toggleHotspotMonitor(monitor.id, !monitor.enabled);
    toast.success(monitor.enabled ? "监控已暂停" : "监控已启用");
    await refreshMonitors();
  };

  const handleScanMonitor = async (monitor: HotspotMonitor) => {
    const run = await scanHotspotMonitor(monitor.id);
    toast.success(run.status === "SUCCESS" ? "手动扫描完成" : "手动扫描失败");
    await Promise.all([refreshMonitors(), refreshEvents(), refreshRuns()]);
  };

  const handleEditMonitor = (monitor: HotspotMonitor) => {
    setEditingMonitorId(monitor.id);
    setMonitorForm({
      keyword: monitor.keyword,
      sources: monitor.sources,
      enabled: monitor.enabled,
      email: monitor.email || "",
      emailEnabled: monitor.emailEnabled,
      websocketEnabled: monitor.websocketEnabled,
      scanIntervalMinutes: monitor.scanIntervalMinutes,
      relevanceThreshold: monitor.relevanceThreshold,
      credibilityThreshold: monitor.credibilityThreshold
    });
  };

  return (
    <div className="admin-page space-y-6">
      <Card className="overflow-hidden border-sky-200/80 bg-[radial-gradient(circle_at_top_right,_rgba(14,165,233,0.18),_transparent_34%),linear-gradient(135deg,rgba(255,255,255,0.98),rgba(240,249,255,0.98))] shadow-sm">
        <CardHeader className="gap-5 pb-4">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
            <div className="space-y-3">
              <Badge variant="outline" className="w-fit border-sky-200 bg-white/70 text-sky-700">
                Hotspot Tracking
              </Badge>
              <div className="space-y-2">
                <CardTitle className="flex items-center gap-2 text-3xl text-slate-950">
                  <BellRing className="h-7 w-7 text-sky-500" />
                  多源热点追踪
                </CardTitle>
                <p className="max-w-3xl text-sm leading-6 text-slate-600">
                  输入要监控的关键词后，系统会从 Twitter、Bing、Hacker News、搜狗、B 站、微博、百度、DuckDuckGo、Reddit
                  等 8+ 信息源聚合抓取内容，结合 AI 做真假识别和相关性分析，并通过 WebSocket 实时推送与邮件通知。
                </p>
              </div>
            </div>
            <div className="grid gap-2 sm:grid-cols-2 lg:w-[380px]">
              <div className="rounded-3xl border border-white/70 bg-white/80 px-4 py-3 shadow-sm">
                <div className="text-xs text-slate-500">推送状态</div>
                <div className="mt-1 flex items-center gap-2 text-sm font-medium text-slate-800">
                  <span
                    className={cn(
                      "h-2.5 w-2.5 rounded-full",
                      socketState === "connected"
                        ? "bg-emerald-500"
                        : socketState === "connecting"
                          ? "bg-amber-400"
                          : "bg-rose-400"
                    )}
                  />
                  {socketState === "connected" ? "WebSocket 已连接" : socketState === "connecting" ? "正在连接" : "连接已断开"}
                </div>
              </div>
              <div className="rounded-3xl border border-white/70 bg-white/80 px-4 py-3 shadow-sm">
                <div className="text-xs text-slate-500">活跃监控</div>
                <div className="mt-1 text-2xl font-semibold text-slate-950">{activeMonitorCount}</div>
              </div>
              <div className="rounded-3xl border border-white/70 bg-white/80 px-4 py-3 shadow-sm">
                <div className="text-xs text-slate-500">最近告警</div>
                <div className="mt-1 text-2xl font-semibold text-slate-950">{events.length}</div>
              </div>
              <div className="rounded-3xl border border-white/70 bg-white/80 px-4 py-3 shadow-sm">
                <div className="text-xs text-slate-500">最近新增</div>
                <div className="mt-1 text-2xl font-semibold text-slate-950">{totalNewEvents}</div>
              </div>
            </div>
          </div>
        </CardHeader>
      </Card>

      <div className="grid gap-6 xl:grid-cols-[1.25fr,0.95fr]">
        <Card className="overflow-hidden border-slate-200/80 bg-white/95 shadow-sm">
          <CardHeader className="space-y-4">
            <div className="flex items-center justify-between gap-3">
              <div>
                <CardTitle className="flex items-center gap-2 text-xl text-slate-950">
                  <Sparkles className="h-5 w-5 text-sky-500" />
                  即时热点快报
                </CardTitle>
                <p className="mt-1 text-sm text-slate-500">
                  当前也会复用到聊天里的联网搜索能力。
                </p>
              </div>
              <Button
                variant="outline"
                onClick={() => void refreshReport(query, selectedSources, true)}
                disabled={reportLoading || reportRefreshing}
              >
                <RefreshCw className={cn("h-4 w-4", reportRefreshing && "animate-spin")} />
                刷新
              </Button>
            </div>

            <form className="space-y-4" onSubmit={(event) => void handleReportSubmit(event)}>
              <div className="flex flex-col gap-3 lg:flex-row">
                <Input
                  value={queryInput}
                  onChange={(event) => setQueryInput(event.target.value)}
                  placeholder="输入要追踪的关键词，比如 OpenAI、Cursor、DeepSeek、RAG"
                  className="h-12 bg-white/80"
                />
                <Button type="submit" className="admin-primary-gradient h-12 px-6" disabled={reportLoading}>
                  <Search className="h-4 w-4" />
                  生成快报
                </Button>
              </div>
              <SourceSelector selected={selectedSources} onToggle={toggleReportSource} />
            </form>

            <div className="grid gap-3 md:grid-cols-3">
              <div className="rounded-3xl border border-slate-100 bg-slate-50 px-4 py-3">
                <div className="text-xs text-slate-500">命中结果</div>
                <div className="mt-1 text-2xl font-semibold text-slate-950">{report?.total ?? 0}</div>
              </div>
              <div className="rounded-3xl border border-slate-100 bg-slate-50 px-4 py-3">
                <div className="text-xs text-slate-500">扩展关键词</div>
                <div className="mt-1 text-sm font-medium text-slate-800">
                  {(report?.expandedQueries || []).slice(0, 4).join(" / ") || "-"}
                </div>
              </div>
              <div className="rounded-3xl border border-slate-100 bg-slate-50 px-4 py-3">
                <div className="text-xs text-slate-500">最近更新</div>
                <div className="mt-1 text-sm font-medium text-slate-800">{formatDateTime(report?.generatedAt)}</div>
              </div>
            </div>

            {warnings.length > 0 ? (
              <div className="rounded-3xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-700">
                <div className="mb-1 flex items-center gap-2 font-medium">
                  <ShieldAlert className="h-4 w-4" />
                  抓取提示
                </div>
                <div className="space-y-1">
                  {warnings.map((warning) => (
                    <div key={warning}>{warning}</div>
                  ))}
                </div>
              </div>
            ) : null}
            {reportError ? (
              <div className="rounded-3xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
                {reportError}
              </div>
            ) : null}
          </CardHeader>

          <CardContent>
            {reportLoading ? (
              <div className="rounded-3xl border border-dashed border-slate-200 bg-slate-50 px-6 py-16 text-center text-sm text-slate-500">
                正在聚合 8+ 数据源并执行 AI 分析...
              </div>
            ) : reportItems.length === 0 ? (
              <div className="rounded-3xl border border-dashed border-slate-200 bg-slate-50 px-6 py-16 text-center text-sm text-slate-500">
                当前没有抓到可展示的热点，请尝试更换关键词或放宽筛选。
              </div>
            ) : (
              <div className="grid gap-4 xl:grid-cols-2">
                {reportItems.map((item) => (
                  <QuickReportCard key={`${item.source}-${item.url}`} item={item} />
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <div className="space-y-6">
          <Card className="border-slate-200/80 bg-white/95 shadow-sm">
            <CardHeader className="space-y-4">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <CardTitle className="flex items-center gap-2 text-xl text-slate-950">
                    <Bot className="h-5 w-5 text-sky-500" />
                    监控任务
                  </CardTitle>
                  <p className="mt-1 text-sm text-slate-500">
                    监控词入库后会由定时任务自动扫描，并通过 WebSocket / 邮件通知。
                  </p>
                </div>
                {editingMonitorId ? (
                  <Button variant="outline" onClick={resetMonitorForm}>
                    取消编辑
                  </Button>
                ) : null}
              </div>

              <form className="space-y-4" onSubmit={(event) => void handleMonitorSubmit(event)}>
                <div className="grid gap-3 md:grid-cols-2">
                  <Input
                    value={monitorForm.keyword}
                    onChange={(event) => setMonitorForm((prev) => ({ ...prev, keyword: event.target.value }))}
                    placeholder="监控关键词，例如 Cursor 1.0"
                  />
                  <Input
                    value={monitorForm.email}
                    onChange={(event) => setMonitorForm((prev) => ({ ...prev, email: event.target.value }))}
                    placeholder="通知邮箱（可选）"
                    type="email"
                  />
                  <Input
                    value={monitorForm.scanIntervalMinutes}
                    onChange={(event) => setMonitorForm((prev) => ({ ...prev, scanIntervalMinutes: Number(event.target.value || 30) }))}
                    placeholder="扫描间隔"
                    type="number"
                    min={5}
                    max={1440}
                  />
                  <Input
                    value={monitorForm.relevanceThreshold}
                    onChange={(event) => setMonitorForm((prev) => ({ ...prev, relevanceThreshold: Number(event.target.value || 0.55) }))}
                    placeholder="相关性阈值"
                    type="number"
                    min={0.1}
                    max={1}
                    step={0.05}
                  />
                </div>
                <div className="grid gap-3 md:grid-cols-2">
                  <Input
                    value={monitorForm.credibilityThreshold}
                    onChange={(event) => setMonitorForm((prev) => ({ ...prev, credibilityThreshold: Number(event.target.value || 0.45) }))}
                    placeholder="可信度阈值"
                    type="number"
                    min={0.1}
                    max={1}
                    step={0.05}
                  />
                  <div className="flex items-center gap-4 rounded-2xl border border-slate-200 px-3 py-2.5">
                    <label className="flex items-center gap-2 text-sm text-slate-600">
                      <Checkbox
                        checked={monitorForm.enabled}
                        onCheckedChange={(checked) => setMonitorForm((prev) => ({ ...prev, enabled: checked === true }))}
                      />
                      启用任务
                    </label>
                    <label className="flex items-center gap-2 text-sm text-slate-600">
                      <Checkbox
                        checked={monitorForm.websocketEnabled}
                        onCheckedChange={(checked) => setMonitorForm((prev) => ({ ...prev, websocketEnabled: checked === true }))}
                      />
                      WebSocket 推送
                    </label>
                    <label className="flex items-center gap-2 text-sm text-slate-600">
                      <Checkbox
                        checked={monitorForm.emailEnabled}
                        onCheckedChange={(checked) => setMonitorForm((prev) => ({ ...prev, emailEnabled: checked === true }))}
                      />
                      邮件通知
                    </label>
                  </div>
                </div>
                <SourceSelector selected={monitorForm.sources} onToggle={toggleMonitorSource} />
                <div className="flex items-center gap-3">
                  <Button type="submit" className="admin-primary-gradient" disabled={monitorSaving}>
                    <Send className="h-4 w-4" />
                    {editingMonitorId ? "更新监控" : "创建监控"}
                  </Button>
                  <div className="text-xs text-slate-500">
                    AI 会先做相关性分析，再将命中结果落库并推送。
                  </div>
                </div>
              </form>
            </CardHeader>
          </Card>

          <Card className="border-slate-200/80 bg-white/95 shadow-sm">
            <CardHeader className="flex flex-row items-center justify-between">
              <div>
                <CardTitle className="flex items-center gap-2 text-xl text-slate-950">
                  <Activity className="h-5 w-5 text-sky-500" />
                  任务列表
                </CardTitle>
                <p className="mt-1 text-sm text-slate-500">
                  支持立即扫描、启停和重新编辑。
                </p>
              </div>
              <Button variant="outline" onClick={() => void refreshMonitors()} disabled={monitorLoading}>
                <RefreshCw className={cn("h-4 w-4", monitorLoading && "animate-spin")} />
                刷新
              </Button>
            </CardHeader>
            <CardContent>
              <Table className="min-w-[760px]">
                <TableHeader>
                  <TableRow>
                    <TableHead>关键词</TableHead>
                    <TableHead>数据源</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead>间隔</TableHead>
                    <TableHead>下次扫描</TableHead>
                    <TableHead>最近命中</TableHead>
                    <TableHead className="text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {monitors.map((monitor) => (
                    <TableRow key={monitor.id}>
                      <TableCell className="font-medium">
                        <div className="space-y-1">
                          <div>{monitor.keyword}</div>
                          {monitor.lastError ? (
                            <div className="max-w-[240px] truncate text-xs text-rose-500" title={monitor.lastError}>
                              {monitor.lastError}
                            </div>
                          ) : null}
                        </div>
                      </TableCell>
                      <TableCell className="max-w-[220px] text-xs text-slate-600">
                        {monitor.sources.map(sourceLabelOf).join(" / ")}
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-2">
                          <Badge variant="secondary" className={monitor.enabled ? "bg-emerald-100 text-emerald-700" : "bg-slate-100 text-slate-600"}>
                            {monitor.enabled ? "运行中" : "已暂停"}
                          </Badge>
                          {monitor.websocketEnabled ? (
                            <Badge variant="secondary" className="bg-sky-100 text-sky-700">
                              WebSocket
                            </Badge>
                          ) : null}
                          {monitor.emailEnabled ? (
                            <Badge variant="secondary" className="bg-amber-100 text-amber-700">
                              Email
                            </Badge>
                          ) : null}
                        </div>
                      </TableCell>
                      <TableCell>{monitor.scanIntervalMinutes} 分钟</TableCell>
                      <TableCell>{formatDateTime(monitor.nextRunTime)}</TableCell>
                      <TableCell>{monitor.lastResultCount ?? 0}</TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-2">
                          <Button size="sm" variant="outline" onClick={() => void handleEditMonitor(monitor)}>
                            <Pencil className="h-3.5 w-3.5" />
                            编辑
                          </Button>
                          <Button size="sm" variant="outline" onClick={() => void handleScanMonitor(monitor)}>
                            <Zap className="h-3.5 w-3.5" />
                            立即扫描
                          </Button>
                          <Button size="sm" onClick={() => void handleToggleMonitor(monitor)} className={cn(!monitor.enabled && "admin-primary-gradient")}>
                            {monitor.enabled ? "暂停" : "启用"}
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                  {monitors.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={7} className="py-10 text-center text-sm text-slate-500">
                        还没有监控任务，先创建一个监控词。
                      </TableCell>
                    </TableRow>
                  ) : null}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </div>
      </div>

      <div className="grid gap-6 xl:grid-cols-[1.15fr,0.85fr]">
        <Card className="border-slate-200/80 bg-white/95 shadow-sm">
          <CardHeader className="flex flex-row items-center justify-between">
            <div>
              <CardTitle className="flex items-center gap-2 text-xl text-slate-950">
                <BellRing className="h-5 w-5 text-sky-500" />
                实时告警流
              </CardTitle>
              <p className="mt-1 text-sm text-slate-500">
                WebSocket 到达后会直接插入这里，无需刷新页面。
              </p>
            </div>
            <Button variant="outline" onClick={() => void refreshEvents()} disabled={eventLoading}>
              <RefreshCw className={cn("h-4 w-4", eventLoading && "animate-spin")} />
              刷新
            </Button>
          </CardHeader>
          <CardContent>
            {events.length === 0 ? (
              <div className="rounded-3xl border border-dashed border-slate-200 bg-slate-50 px-6 py-16 text-center text-sm text-slate-500">
                还没有产生热点告警。创建监控任务后，命中结果会在这里实时出现。
              </div>
            ) : (
              <div className="grid gap-4 md:grid-cols-2">
                {events.slice(0, 12).map((item) => (
                  <AlertEventCard key={item.id} item={item} />
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card className="border-slate-200/80 bg-white/95 shadow-sm">
          <CardHeader className="space-y-2">
            <CardTitle className="flex items-center gap-2 text-xl text-slate-950">
              <ShieldCheck className="h-5 w-5 text-sky-500" />
              执行记录
            </CardTitle>
            <p className="text-sm text-slate-500">
              最近 10 次扫描结果，方便判断抓取质量和 AI 过滤效果。
            </p>
          </CardHeader>
          <CardContent className="space-y-3">
            {runs.length === 0 ? (
              <div className="rounded-3xl border border-dashed border-slate-200 bg-slate-50 px-6 py-12 text-center text-sm text-slate-500">
                暂无执行记录。
              </div>
            ) : (
              runs.map((run) => (
                <div key={run.id} className="rounded-3xl border border-slate-100 bg-slate-50 px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="secondary" className={run.status === "SUCCESS" ? "bg-emerald-100 text-emerald-700" : "bg-rose-100 text-rose-700"}>
                      {run.status}
                    </Badge>
                    <Badge variant="outline">{run.keyword}</Badge>
                    <span className="text-xs text-slate-500">{formatDateTime(run.startedAt)}</span>
                  </div>
                  <div className="mt-3 grid gap-2 text-sm text-slate-600">
                    <div>抓取 {run.fetchedCount} 条，筛出 {run.qualifiedCount} 条，新增 {run.newEventCount} 条</div>
                    <div className="text-xs text-slate-500">
                      数据源: {run.sources.map(sourceLabelOf).join(" / ") || "-"}
                    </div>
                    <div className="text-xs text-slate-500">
                      扩展词: {run.expandedQueries.join(" / ") || "-"}
                    </div>
                    {run.warning ? (
                      <div className="rounded-2xl bg-amber-50 px-3 py-2 text-xs text-amber-700">
                        {run.warning}
                      </div>
                    ) : null}
                  </div>
                </div>
              ))
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
