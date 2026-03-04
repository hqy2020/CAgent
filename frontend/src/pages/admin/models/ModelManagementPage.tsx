import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Plus, Pencil, Trash2, Star } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/utils/error";
import type {
  ModelProvider,
  ModelProviderPayload,
  ModelCandidate,
  ModelCandidatePayload,
} from "@/services/modelService";
import {
  getProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  getCandidates,
  createCandidate,
  updateCandidate,
  deleteCandidate,
  setDefaultModel,
  setDeepThinkingModel,
} from "@/services/modelService";

// ─── Tab 定义 ───

type TabKey = "providers" | "chat" | "embedding" | "rerank";

const TABS: { key: TabKey; label: string }[] = [
  { key: "providers", label: "服务提供方" },
  { key: "chat", label: "Chat 模型" },
  { key: "embedding", label: "Embedding 模型" },
  { key: "rerank", label: "Rerank 模型" },
];

// ─── 空表单 ───

const emptyProvider: ModelProviderPayload = {
  providerKey: "",
  name: "",
  baseUrl: "",
  apiKey: "",
  endpoints: {},
  enabled: 1,
  sortOrder: 0,
};

const emptyCandidate: ModelCandidatePayload = {
  modelId: "",
  modelType: "chat",
  providerKey: "",
  modelName: "",
  customUrl: "",
  dimension: undefined,
  priority: 100,
  enabled: 1,
  supportsThinking: 0,
};

export function ModelManagementPage() {
  const [activeTab, setActiveTab] = useState<TabKey>("providers");
  const [providers, setProviders] = useState<ModelProvider[]>([]);
  const [candidates, setCandidates] = useState<ModelCandidate[]>([]);
  const [loading, setLoading] = useState(false);

  // Provider dialog
  const [providerDialogOpen, setProviderDialogOpen] = useState(false);
  const [editingProvider, setEditingProvider] = useState<ModelProvider | null>(null);
  const [providerForm, setProviderForm] = useState<ModelProviderPayload>(emptyProvider);
  const [endpointRows, setEndpointRows] = useState<{ key: string; value: string }[]>([]);
  const [providerSaving, setProviderSaving] = useState(false);

  // Candidate dialog
  const [candidateDialogOpen, setCandidateDialogOpen] = useState(false);
  const [editingCandidate, setEditingCandidate] = useState<ModelCandidate | null>(null);
  const [candidateForm, setCandidateForm] = useState<ModelCandidatePayload>(emptyCandidate);
  const [candidateSaving, setCandidateSaving] = useState(false);

  // Delete
  const [deleteTarget, setDeleteTarget] = useState<{ type: "provider" | "candidate"; id: string } | null>(null);

  const modelType = activeTab === "providers" ? null : activeTab;

  // ─── 加载数据 ───

  const loadProviders = async () => {
    try {
      const data = await getProviders();
      setProviders(data || []);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载提供方失败"));
    }
  };

  const loadCandidates = async () => {
    if (!modelType) return;
    try {
      const data = await getCandidates(modelType);
      setCandidates(data || []);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载模型列表失败"));
    }
  };

  const loadData = async () => {
    setLoading(true);
    try {
      await loadProviders();
      if (modelType) await loadCandidates();
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [activeTab]);

  // ─── Provider CRUD ───

  const openCreateProvider = () => {
    setEditingProvider(null);
    setProviderForm({ ...emptyProvider });
    setEndpointRows([]);
    setProviderDialogOpen(true);
  };

  const openEditProvider = (p: ModelProvider) => {
    setEditingProvider(p);
    setProviderForm({
      providerKey: p.providerKey,
      name: p.name,
      baseUrl: p.baseUrl,
      apiKey: p.apiKey || "",
      enabled: p.enabled,
      sortOrder: p.sortOrder,
    });
    const rows = Object.entries(p.endpoints || {}).map(([key, value]) => ({ key, value }));
    setEndpointRows(rows.length > 0 ? rows : []);
    setProviderDialogOpen(true);
  };

  const handleProviderSave = async () => {
    if (!providerForm.providerKey || !providerForm.name) {
      toast.error("标识Key 和 名称为必填项");
      return;
    }
    setProviderSaving(true);
    try {
      const endpoints: Record<string, string> = {};
      endpointRows.forEach((row) => {
        if (row.key.trim()) endpoints[row.key.trim()] = row.value;
      });
      const payload = { ...providerForm, endpoints };

      if (editingProvider) {
        await updateProvider(editingProvider.id, payload);
        toast.success("更新成功");
      } else {
        await createProvider(payload);
        toast.success("创建成功");
      }
      setProviderDialogOpen(false);
      await loadProviders();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setProviderSaving(false);
    }
  };

  // ─── Candidate CRUD ───

  const openCreateCandidate = () => {
    setEditingCandidate(null);
    setCandidateForm({ ...emptyCandidate, modelType: modelType || "chat" });
    setCandidateDialogOpen(true);
  };

  const openEditCandidate = (c: ModelCandidate) => {
    setEditingCandidate(c);
    setCandidateForm({
      modelId: c.modelId,
      modelType: c.modelType,
      providerKey: c.providerKey,
      modelName: c.modelName,
      customUrl: c.customUrl || "",
      dimension: c.dimension ?? undefined,
      priority: c.priority,
      enabled: c.enabled,
      supportsThinking: c.supportsThinking,
    });
    setCandidateDialogOpen(true);
  };

  const handleCandidateSave = async () => {
    if (!candidateForm.modelId || !candidateForm.providerKey || !candidateForm.modelName) {
      toast.error("模型ID、Provider 和 模型名称为必填项");
      return;
    }
    setCandidateSaving(true);
    try {
      if (editingCandidate) {
        await updateCandidate(editingCandidate.id, candidateForm);
        toast.success("更新成功");
      } else {
        await createCandidate(candidateForm);
        toast.success("创建成功");
      }
      setCandidateDialogOpen(false);
      await loadCandidates();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setCandidateSaving(false);
    }
  };

  // ─── 默认模型 ───

  const handleSetDefault = async (id: string) => {
    try {
      await setDefaultModel(id);
      toast.success("已设为默认模型");
      await loadCandidates();
    } catch (error) {
      toast.error(getErrorMessage(error, "设置失败"));
    }
  };

  const handleSetDeepThinking = async (id: string) => {
    try {
      await setDeepThinkingModel(id);
      toast.success("已设为深度思考模型");
      await loadCandidates();
    } catch (error) {
      toast.error(getErrorMessage(error, "设置失败"));
    }
  };

  // ─── 删除 ───

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      if (deleteTarget.type === "provider") {
        await deleteProvider(deleteTarget.id);
        toast.success("提供方已删除");
        await loadProviders();
      } else {
        await deleteCandidate(deleteTarget.id);
        toast.success("模型已删除");
        await loadCandidates();
      }
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    } finally {
      setDeleteTarget(null);
    }
  };

  // ─── 渲染 ───

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">模型管理</h1>
          <p className="admin-page-subtitle">管理 AI 模型提供方和候选模型配置</p>
        </div>
        <div className="admin-page-actions">
          {activeTab === "providers" ? (
            <Button onClick={openCreateProvider}>
              <Plus className="mr-2 h-4 w-4" /> 添加提供方
            </Button>
          ) : (
            <Button onClick={openCreateCandidate}>
              <Plus className="mr-2 h-4 w-4" /> 添加模型
            </Button>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 rounded-lg border border-slate-200 bg-slate-50 p-1">
        {TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            onClick={() => setActiveTab(tab.key)}
            className={cn(
              "rounded-md px-4 py-2 text-sm font-medium transition-colors",
              activeTab === tab.key
                ? "bg-white text-slate-900 shadow-sm"
                : "text-slate-500 hover:text-slate-700"
            )}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Content */}
      <Card>
        <CardContent className="pt-6">
          {loading ? (
            <div className="py-8 text-center text-sm text-slate-400">加载中...</div>
          ) : activeTab === "providers" ? (
            <ProviderTable
              providers={providers}
              onEdit={openEditProvider}
              onDelete={(id) => setDeleteTarget({ type: "provider", id })}
            />
          ) : (
            <CandidateTable
              candidates={candidates}
              modelType={modelType!}
              onEdit={openEditCandidate}
              onDelete={(id) => setDeleteTarget({ type: "candidate", id })}
              onSetDefault={handleSetDefault}
              onSetDeepThinking={handleSetDeepThinking}
            />
          )}
        </CardContent>
      </Card>

      {/* Provider Dialog */}
      <Dialog open={providerDialogOpen} onOpenChange={setProviderDialogOpen}>
        <DialogContent className="sm:max-w-[540px]">
          <DialogHeader>
            <DialogTitle>{editingProvider ? "编辑提供方" : "添加提供方"}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label>标识 Key *</Label>
                <Input
                  value={providerForm.providerKey}
                  onChange={(e) => setProviderForm((p) => ({ ...p, providerKey: e.target.value }))}
                  placeholder="如 ollama"
                  disabled={!!editingProvider}
                />
              </div>
              <div className="space-y-2">
                <Label>显示名称 *</Label>
                <Input
                  value={providerForm.name}
                  onChange={(e) => setProviderForm((p) => ({ ...p, name: e.target.value }))}
                  placeholder="如 Ollama 本地"
                />
              </div>
            </div>
            <div className="space-y-2">
              <Label>Base URL</Label>
              <Input
                value={providerForm.baseUrl}
                onChange={(e) => setProviderForm((p) => ({ ...p, baseUrl: e.target.value }))}
                placeholder="如 http://localhost:11434"
              />
            </div>
            <div className="space-y-2">
              <Label>API Key</Label>
              <Input
                type="password"
                value={providerForm.apiKey || ""}
                onChange={(e) => setProviderForm((p) => ({ ...p, apiKey: e.target.value }))}
                placeholder={editingProvider ? "留空则不更新" : "可选"}
              />
            </div>
            <div className="space-y-2">
              <Label>端点配置</Label>
              {endpointRows.map((row, idx) => (
                <div key={idx} className="flex gap-2">
                  <Input
                    className="w-1/3"
                    value={row.key}
                    onChange={(e) => {
                      const next = [...endpointRows];
                      next[idx] = { ...next[idx], key: e.target.value };
                      setEndpointRows(next);
                    }}
                    placeholder="端点名"
                  />
                  <Input
                    className="flex-1"
                    value={row.value}
                    onChange={(e) => {
                      const next = [...endpointRows];
                      next[idx] = { ...next[idx], value: e.target.value };
                      setEndpointRows(next);
                    }}
                    placeholder="路径"
                  />
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => setEndpointRows((r) => r.filter((_, i) => i !== idx))}
                  >
                    <Trash2 className="h-4 w-4 text-slate-400" />
                  </Button>
                </div>
              ))}
              <Button
                variant="outline"
                size="sm"
                onClick={() => setEndpointRows((r) => [...r, { key: "", value: "" }])}
              >
                <Plus className="mr-1 h-3 w-3" /> 添加端点
              </Button>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label>排序</Label>
                <Input
                  type="number"
                  value={providerForm.sortOrder ?? 0}
                  onChange={(e) => setProviderForm((p) => ({ ...p, sortOrder: Number(e.target.value) }))}
                />
              </div>
              <div className="flex items-end gap-2 pb-1">
                <Checkbox
                  id="provider-enabled"
                  checked={providerForm.enabled === 1}
                  onCheckedChange={(v) => setProviderForm((p) => ({ ...p, enabled: v ? 1 : 0 }))}
                />
                <Label htmlFor="provider-enabled">启用</Label>
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setProviderDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={handleProviderSave} disabled={providerSaving}>
              {providerSaving ? "保存中..." : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Candidate Dialog */}
      <Dialog open={candidateDialogOpen} onOpenChange={setCandidateDialogOpen}>
        <DialogContent className="sm:max-w-[540px]">
          <DialogHeader>
            <DialogTitle>{editingCandidate ? "编辑模型" : "添加模型"}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label>模型 ID *</Label>
                <Input
                  value={candidateForm.modelId}
                  onChange={(e) => setCandidateForm((c) => ({ ...c, modelId: e.target.value }))}
                  placeholder="如 minimax-m2.5"
                  disabled={!!editingCandidate}
                />
              </div>
              <div className="space-y-2">
                <Label>Provider *</Label>
                <Select
                  value={candidateForm.providerKey}
                  onValueChange={(v) => setCandidateForm((c) => ({ ...c, providerKey: v }))}
                >
                  <SelectTrigger>
                    <SelectValue placeholder="选择提供方" />
                  </SelectTrigger>
                  <SelectContent>
                    {providers.map((p) => (
                      <SelectItem key={p.providerKey} value={p.providerKey}>
                        {p.name} ({p.providerKey})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="space-y-2">
              <Label>模型名称 *</Label>
              <Input
                value={candidateForm.modelName}
                onChange={(e) => setCandidateForm((c) => ({ ...c, modelName: e.target.value }))}
                placeholder="如 MiniMax-M1"
              />
            </div>
            <div className="space-y-2">
              <Label>自定义 URL</Label>
              <Input
                value={candidateForm.customUrl || ""}
                onChange={(e) => setCandidateForm((c) => ({ ...c, customUrl: e.target.value }))}
                placeholder="可选，覆盖 Provider URL"
              />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label>优先级</Label>
                <Input
                  type="number"
                  value={candidateForm.priority ?? 100}
                  onChange={(e) => setCandidateForm((c) => ({ ...c, priority: Number(e.target.value) }))}
                />
              </div>
              {modelType === "embedding" && (
                <div className="space-y-2">
                  <Label>Dimension</Label>
                  <Input
                    type="number"
                    value={candidateForm.dimension ?? ""}
                    onChange={(e) =>
                      setCandidateForm((c) => ({
                        ...c,
                        dimension: e.target.value ? Number(e.target.value) : undefined,
                      }))
                    }
                  />
                </div>
              )}
            </div>
            <div className="flex gap-6">
              <div className="flex items-center gap-2">
                <Checkbox
                  id="candidate-enabled"
                  checked={candidateForm.enabled === 1}
                  onCheckedChange={(v) => setCandidateForm((c) => ({ ...c, enabled: v ? 1 : 0 }))}
                />
                <Label htmlFor="candidate-enabled">启用</Label>
              </div>
              {modelType === "chat" && (
                <div className="flex items-center gap-2">
                  <Checkbox
                    id="candidate-thinking"
                    checked={candidateForm.supportsThinking === 1}
                    onCheckedChange={(v) => setCandidateForm((c) => ({ ...c, supportsThinking: v ? 1 : 0 }))}
                  />
                  <Label htmlFor="candidate-thinking">支持思考链</Label>
                </div>
              )}
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCandidateDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={handleCandidateSave} disabled={candidateSaving}>
              {candidateSaving ? "保存中..." : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete Dialog */}
      <AlertDialog open={!!deleteTarget} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              删除后将无法恢复，确认继续？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete}>删除</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

// ─── Provider Table ───

function ProviderTable({
  providers,
  onEdit,
  onDelete,
}: {
  providers: ModelProvider[];
  onEdit: (p: ModelProvider) => void;
  onDelete: (id: string) => void;
}) {
  if (providers.length === 0) {
    return <div className="py-8 text-center text-sm text-slate-400">暂无提供方，点击右上角添加</div>;
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="w-[120px]">名称</TableHead>
          <TableHead className="w-[100px]">标识 Key</TableHead>
          <TableHead className="w-[200px]">Base URL</TableHead>
          <TableHead className="w-[120px]">API Key</TableHead>
          <TableHead>端点</TableHead>
          <TableHead className="w-[80px]">状态</TableHead>
          <TableHead className="w-[100px]">操作</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {providers.map((p) => (
          <TableRow key={p.id}>
            <TableCell className="font-medium">{p.name}</TableCell>
            <TableCell className="text-slate-500">{p.providerKey}</TableCell>
            <TableCell className="max-w-[200px] truncate text-xs text-slate-600">{p.baseUrl}</TableCell>
            <TableCell className="text-xs text-slate-400">{p.apiKey || "-"}</TableCell>
            <TableCell>
              <div className="space-y-0.5 text-xs text-slate-500">
                {Object.entries(p.endpoints || {}).map(([k, v]) => (
                  <div key={k}>{k}: {v}</div>
                ))}
              </div>
            </TableCell>
            <TableCell>
              <Badge variant={p.enabled === 1 ? "default" : "outline"}>
                {p.enabled === 1 ? "启用" : "禁用"}
              </Badge>
            </TableCell>
            <TableCell>
              <div className="flex gap-1">
                <Button variant="ghost" size="icon" onClick={() => onEdit(p)}>
                  <Pencil className="h-4 w-4" />
                </Button>
                <Button variant="ghost" size="icon" onClick={() => onDelete(p.id)}>
                  <Trash2 className="h-4 w-4 text-rose-500" />
                </Button>
              </div>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

// ─── Candidate Table ───

function CandidateTable({
  candidates,
  modelType,
  onEdit,
  onDelete,
  onSetDefault,
  onSetDeepThinking,
}: {
  candidates: ModelCandidate[];
  modelType: string;
  onEdit: (c: ModelCandidate) => void;
  onDelete: (id: string) => void;
  onSetDefault: (id: string) => void;
  onSetDeepThinking: (id: string) => void;
}) {
  if (candidates.length === 0) {
    return <div className="py-8 text-center text-sm text-slate-400">暂无模型配置，点击右上角添加</div>;
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="w-[180px]">模型 ID</TableHead>
          <TableHead className="w-[100px]">Provider</TableHead>
          <TableHead className="w-[180px]">Model Name</TableHead>
          <TableHead className="w-[80px]">Priority</TableHead>
          {modelType === "embedding" && <TableHead className="w-[90px]">Dimension</TableHead>}
          {modelType === "chat" && <TableHead className="w-[80px]">Thinking</TableHead>}
          <TableHead className="w-[80px]">默认</TableHead>
          {modelType === "chat" && <TableHead className="w-[80px]">深度思考</TableHead>}
          <TableHead className="w-[70px]">状态</TableHead>
          <TableHead className="w-[100px]">操作</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {candidates.map((c) => (
          <TableRow key={c.id}>
            <TableCell className="font-medium">{c.modelId}</TableCell>
            <TableCell className="text-slate-500">{c.providerKey}</TableCell>
            <TableCell className="text-sm">{c.modelName}</TableCell>
            <TableCell>{c.priority}</TableCell>
            {modelType === "embedding" && <TableCell>{c.dimension ?? "-"}</TableCell>}
            {modelType === "chat" && (
              <TableCell>{c.supportsThinking === 1 ? "支持" : "-"}</TableCell>
            )}
            <TableCell>
              <button
                type="button"
                onClick={() => c.isDefault !== 1 && onSetDefault(c.id)}
                className={cn(
                  "inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs transition",
                  c.isDefault === 1
                    ? "bg-amber-100 text-amber-700"
                    : "cursor-pointer text-slate-400 hover:text-amber-600"
                )}
              >
                <Star className={cn("h-3 w-3", c.isDefault === 1 && "fill-amber-500")} />
                {c.isDefault === 1 ? "默认" : "设为默认"}
              </button>
            </TableCell>
            {modelType === "chat" && (
              <TableCell>
                <button
                  type="button"
                  onClick={() => c.isDeepThinking !== 1 && onSetDeepThinking(c.id)}
                  className={cn(
                    "inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs transition",
                    c.isDeepThinking === 1
                      ? "bg-indigo-100 text-indigo-700"
                      : "cursor-pointer text-slate-400 hover:text-indigo-600"
                  )}
                >
                  {c.isDeepThinking === 1 ? "深度思考" : "设为"}
                </button>
              </TableCell>
            )}
            <TableCell>
              <Badge variant={c.enabled === 1 ? "default" : "outline"}>
                {c.enabled === 1 ? "启用" : "禁用"}
              </Badge>
            </TableCell>
            <TableCell>
              <div className="flex gap-1">
                <Button variant="ghost" size="icon" onClick={() => onEdit(c)}>
                  <Pencil className="h-4 w-4" />
                </Button>
                <Button variant="ghost" size="icon" onClick={() => onDelete(c.id)}>
                  <Trash2 className="h-4 w-4 text-rose-500" />
                </Button>
              </div>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
