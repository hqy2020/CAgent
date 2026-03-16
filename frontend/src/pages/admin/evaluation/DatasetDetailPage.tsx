import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Plus, Upload, Play, Trash2, Loader2, MessageSquare, ThumbsUp, ThumbsDown, Search } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Checkbox } from "@/components/ui/checkbox";
import { getErrorMessage } from "@/utils/error";
import type { EvalDataset, EvalDatasetCase, ConversationQAPair } from "@/services/evaluationService";
import {
  getDataset,
  addCase,
  batchImportCases,
  deleteCase,
  triggerRun,
  listConversationQAPairs,
  importFromChat,
} from "@/services/evaluationService";

export function DatasetDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [dataset, setDataset] = useState<EvalDataset | null>(null);
  const [cases, setCases] = useState<EvalDatasetCase[]>([]);
  const [loading, setLoading] = useState(false);

  // Add case dialog
  const [addOpen, setAddOpen] = useState(false);
  const [adding, setAdding] = useState(false);
  const [caseQuery, setCaseQuery] = useState("");
  const [caseExpected, setCaseExpected] = useState("");
  const [caseIntent, setCaseIntent] = useState("");

  // Batch import dialog
  const [importOpen, setImportOpen] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importJson, setImportJson] = useState("");

  // Delete case
  const [deleteCaseTarget, setDeleteCaseTarget] = useState<EvalDatasetCase | null>(null);
  const [deletingCase, setDeletingCase] = useState(false);

  // Trigger run
  const [triggering, setTriggering] = useState(false);

  // Chat import dialog
  const [chatImportOpen, setChatImportOpen] = useState(false);
  const [chatImporting, setChatImporting] = useState(false);
  const [chatPairs, setChatPairs] = useState<ConversationQAPair[]>([]);
  const [chatLoading, setChatLoading] = useState(false);
  const [chatKeyword, setChatKeyword] = useState("");
  const [chatFilter, setChatFilter] = useState<"all" | "up" | "down">("all");
  const [chatSelected, setChatSelected] = useState<Set<string>>(new Set());
  const [chatPage, setChatPage] = useState(1);
  const [chatTotal, setChatTotal] = useState(0);
  const chatPageSize = 10;

  const fetchDataset = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const data = await getDataset(id);
      setDataset(data.dataset);
      setCases(data.cases);
    } catch (e) {
      toast.error(getErrorMessage(e, "加载数据集详情失败"));
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    fetchDataset();
  }, [fetchDataset]);

  const handleAddCase = async () => {
    if (!id || !caseQuery.trim()) {
      toast.error("请输入问题");
      return;
    }
    setAdding(true);
    try {
      await addCase(id, {
        query: caseQuery.trim(),
        expectedAnswer: caseExpected.trim() || undefined,
        intent: caseIntent.trim() || undefined,
      });
      toast.success("用例添加成功");
      setAddOpen(false);
      setCaseQuery("");
      setCaseExpected("");
      setCaseIntent("");
      fetchDataset();
    } catch (e) {
      toast.error(getErrorMessage(e, "添加用例失败"));
    } finally {
      setAdding(false);
    }
  };

  const handleBatchImport = async () => {
    if (!id || !importJson.trim()) {
      toast.error("请输入 JSON 数据");
      return;
    }
    let parsed: Array<{ query: string; expectedAnswer?: string; intent?: string }>;
    try {
      parsed = JSON.parse(importJson.trim());
      if (!Array.isArray(parsed)) throw new Error("不是数组");
    } catch {
      toast.error("JSON 格式无效，请确保输入的是数组格式");
      return;
    }
    setImporting(true);
    try {
      const count = await batchImportCases(id, parsed);
      toast.success(`成功导入 ${count} 条用例`);
      setImportOpen(false);
      setImportJson("");
      fetchDataset();
    } catch (e) {
      toast.error(getErrorMessage(e, "批量导入失败"));
    } finally {
      setImporting(false);
    }
  };

  const handleDeleteCase = async () => {
    if (!id || !deleteCaseTarget) return;
    setDeletingCase(true);
    try {
      await deleteCase(id, deleteCaseTarget.id);
      toast.success("用例已删除");
      setDeleteCaseTarget(null);
      fetchDataset();
    } catch (e) {
      toast.error(getErrorMessage(e, "删除用例失败"));
    } finally {
      setDeletingCase(false);
    }
  };

  const fetchChatPairs = useCallback(async (page: number = 1, keyword: string = "") => {
    setChatLoading(true);
    try {
      const data = await listConversationQAPairs({
        keyword: keyword || undefined,
        current: page,
        size: chatPageSize,
      });
      setChatPairs(data.records);
      setChatTotal(data.total);
      setChatPage(page);
    } catch (e) {
      toast.error(getErrorMessage(e, "加载对话数据失败"));
    } finally {
      setChatLoading(false);
    }
  }, []);

  const handleOpenChatImport = () => {
    setChatImportOpen(true);
    setChatSelected(new Set());
    setChatKeyword("");
    setChatFilter("all");
    fetchChatPairs(1, "");
  };

  const handleChatSearch = () => {
    fetchChatPairs(1, chatKeyword);
  };

  const handleChatToggle = (messageId: string) => {
    setChatSelected((prev) => {
      const next = new Set(prev);
      if (next.has(messageId)) {
        next.delete(messageId);
      } else {
        next.add(messageId);
      }
      return next;
    });
  };

  const handleChatSelectAll = (checked: boolean) => {
    if (checked) {
      const filtered = getFilteredChatPairs();
      setChatSelected(new Set(filtered.map((p) => p.messageId)));
    } else {
      setChatSelected(new Set());
    }
  };

  const getFilteredChatPairs = useCallback(() => {
    if (chatFilter === "up") return chatPairs.filter((p) => p.vote === 1);
    if (chatFilter === "down") return chatPairs.filter((p) => p.vote === -1);
    return chatPairs;
  }, [chatPairs, chatFilter]);

  const handleImportFromChat = async () => {
    if (!id || chatSelected.size === 0) return;
    setChatImporting(true);
    try {
      const count = await importFromChat(id, Array.from(chatSelected));
      toast.success(`成功从对话导入 ${count} 条用例`);
      setChatImportOpen(false);
      fetchDataset();
    } catch (e) {
      toast.error(getErrorMessage(e, "从对话导入失败"));
    } finally {
      setChatImporting(false);
    }
  };

  const handleTriggerRun = async () => {
    if (!id) return;
    setTriggering(true);
    try {
      await triggerRun(id);
      toast.success("评测已触发，正在跳转到评测记录");
      navigate("/admin/evaluation");
    } catch (e) {
      toast.error(getErrorMessage(e, "触发评测失败"));
    } finally {
      setTriggering(false);
    }
  };

  if (loading && !dataset) {
    return (
      <div className="flex items-center justify-center py-24 text-muted-foreground">
        <Loader2 className="h-5 w-5 animate-spin mr-2" />
        加载中...
      </div>
    );
  }

  if (!dataset) {
    return (
      <div className="text-center py-24 text-muted-foreground">数据集不存在或加载失败</div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Button variant="outline" size="sm" onClick={() => navigate("/admin/evaluation")}>
            <ArrowLeft className="h-4 w-4 mr-1" />
            返回
          </Button>
          <div>
            <h1 className="text-2xl font-bold">{dataset.name}</h1>
            <p className="text-sm text-muted-foreground mt-1">
              {dataset.description || "暂无描述"} | 用例数: {dataset.caseCount}
            </p>
          </div>
        </div>
        <Button onClick={handleTriggerRun} disabled={triggering || cases.length === 0}>
          {triggering ? (
            <Loader2 className="h-4 w-4 mr-1 animate-spin" />
          ) : (
            <Play className="h-4 w-4 mr-1" />
          )}
          触发评测
        </Button>
      </div>

      {/* Cases */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle>评测用例</CardTitle>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" onClick={handleOpenChatImport}>
                <MessageSquare className="h-4 w-4 mr-1" />
                从对话导入
              </Button>
              <Button variant="outline" size="sm" onClick={() => setImportOpen(true)}>
                <Upload className="h-4 w-4 mr-1" />
                JSON 批量导入
              </Button>
              <Button size="sm" onClick={() => setAddOpen(true)}>
                <Plus className="h-4 w-4 mr-1" />
                添加用例
              </Button>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          {cases.length === 0 ? (
            <div className="text-center py-12 text-muted-foreground">
              暂无用例，请添加或导入评测用例
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>问题</TableHead>
                  <TableHead>期望答案</TableHead>
                  <TableHead className="w-[100px]">意图</TableHead>
                  <TableHead className="w-[160px]">创建时间</TableHead>
                  <TableHead className="w-[60px]">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {cases.map((c) => (
                  <TableRow key={c.id}>
                    <TableCell className="max-w-[300px]">
                      <div className="truncate">{c.query}</div>
                    </TableCell>
                    <TableCell className="max-w-[300px]">
                      <div className="truncate text-muted-foreground">
                        {c.expectedAnswer || "—"}
                      </div>
                    </TableCell>
                    <TableCell className="text-sm text-muted-foreground">
                      {c.intent || "—"}
                    </TableCell>
                    <TableCell className="text-sm text-muted-foreground">
                      {c.createTime}
                    </TableCell>
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="text-destructive hover:text-destructive"
                        onClick={() => setDeleteCaseTarget(c)}
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Add Case Dialog */}
      <Dialog open={addOpen} onOpenChange={setAddOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>添加用例</DialogTitle>
            <DialogDescription>添加一条评测用例到当前数据集。</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div className="space-y-2">
              <label className="text-sm font-medium">问题 *</label>
              <Textarea
                placeholder="输入评测问题"
                value={caseQuery}
                onChange={(e) => setCaseQuery(e.target.value)}
                rows={3}
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">期望答案</label>
              <Textarea
                placeholder="输入期望的参考答案（可选）"
                value={caseExpected}
                onChange={(e) => setCaseExpected(e.target.value)}
                rows={3}
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">意图</label>
              <Input
                placeholder="例如：spring_aop（可选）"
                value={caseIntent}
                onChange={(e) => setCaseIntent(e.target.value)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAddOpen(false)}>
              取消
            </Button>
            <Button onClick={handleAddCase} disabled={adding}>
              {adding && <Loader2 className="h-4 w-4 mr-1 animate-spin" />}
              添加
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Batch Import Dialog */}
      <Dialog open={importOpen} onOpenChange={setImportOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>JSON 批量导入</DialogTitle>
            <DialogDescription>
              输入 JSON 数组格式的用例数据。每条记录包含 query（必填）、expectedAnswer（可选）、intent（可选）。
            </DialogDescription>
          </DialogHeader>
          <div className="py-4">
            <Textarea
              placeholder={`[\n  {\n    "query": "什么是 Spring AOP？",\n    "expectedAnswer": "Spring AOP 是...",\n    "intent": "spring_aop"\n  }\n]`}
              value={importJson}
              onChange={(e) => setImportJson(e.target.value)}
              rows={12}
              className="font-mono text-sm"
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setImportOpen(false)}>
              取消
            </Button>
            <Button onClick={handleBatchImport} disabled={importing}>
              {importing && <Loader2 className="h-4 w-4 mr-1 animate-spin" />}
              导入
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete Case Confirmation */}
      <AlertDialog
        open={!!deleteCaseTarget}
        onOpenChange={(open) => !open && setDeleteCaseTarget(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              确定要删除该用例吗？此操作不可撤销。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deletingCase}>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDeleteCase} disabled={deletingCase}>
              {deletingCase && <Loader2 className="h-4 w-4 mr-1 animate-spin" />}
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Chat Import Dialog */}
      <Dialog open={chatImportOpen} onOpenChange={setChatImportOpen}>
        <DialogContent className="max-w-4xl max-h-[80vh] flex flex-col">
          <DialogHeader>
            <DialogTitle>从对话历史导入</DialogTitle>
            <DialogDescription>
              从历史对话中选取 Q&A 对导入为评测用例。
            </DialogDescription>
          </DialogHeader>
          <div className="flex items-center gap-2 py-2">
            <div className="relative flex-1">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <Input
                placeholder="搜索问题内容..."
                value={chatKeyword}
                onChange={(e) => setChatKeyword(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleChatSearch()}
                className="pl-9"
              />
            </div>
            <Button variant="outline" size="sm" onClick={handleChatSearch}>
              搜索
            </Button>
            <div className="flex gap-1 ml-2">
              <Button
                variant={chatFilter === "all" ? "default" : "outline"}
                size="sm"
                onClick={() => setChatFilter("all")}
              >
                全部
              </Button>
              <Button
                variant={chatFilter === "up" ? "default" : "outline"}
                size="sm"
                onClick={() => setChatFilter("up")}
              >
                <ThumbsUp className="h-3 w-3 mr-1" />
                已点赞
              </Button>
              <Button
                variant={chatFilter === "down" ? "default" : "outline"}
                size="sm"
                onClick={() => setChatFilter("down")}
              >
                <ThumbsDown className="h-3 w-3 mr-1" />
                已点踩
              </Button>
            </div>
          </div>
          <div className="flex-1 overflow-auto border rounded-md">
            {chatLoading ? (
              <div className="flex items-center justify-center py-12 text-muted-foreground">
                <Loader2 className="h-5 w-5 animate-spin mr-2" />
                加载中...
              </div>
            ) : getFilteredChatPairs().length === 0 ? (
              <div className="text-center py-12 text-muted-foreground">
                暂无对话数据
              </div>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[40px]">
                      <Checkbox
                        checked={
                          getFilteredChatPairs().length > 0 &&
                          getFilteredChatPairs().every((p) => chatSelected.has(p.messageId))
                        }
                        onCheckedChange={(checked) => handleChatSelectAll(!!checked)}
                      />
                    </TableHead>
                    <TableHead>问题</TableHead>
                    <TableHead>回答</TableHead>
                    <TableHead className="w-[60px]">反馈</TableHead>
                    <TableHead className="w-[140px]">时间</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {getFilteredChatPairs().map((pair) => (
                    <TableRow
                      key={pair.messageId}
                      className={pair.vote === -1 ? "bg-red-50 dark:bg-red-950/20" : ""}
                    >
                      <TableCell>
                        <Checkbox
                          checked={chatSelected.has(pair.messageId)}
                          onCheckedChange={() => handleChatToggle(pair.messageId)}
                        />
                      </TableCell>
                      <TableCell className="max-w-[250px]">
                        <div className="truncate" title={pair.query}>
                          {pair.query}
                        </div>
                        {pair.conversationTitle && (
                          <div className="text-xs text-muted-foreground truncate">
                            {pair.conversationTitle}
                          </div>
                        )}
                      </TableCell>
                      <TableCell className="max-w-[250px]">
                        <div className="truncate text-muted-foreground" title={pair.answer || ""}>
                          {pair.answer || "—"}
                        </div>
                      </TableCell>
                      <TableCell>
                        {pair.vote === 1 && <ThumbsUp className="h-4 w-4 text-green-500" />}
                        {pair.vote === -1 && <ThumbsDown className="h-4 w-4 text-red-500" />}
                        {pair.vote == null && <span className="text-muted-foreground">—</span>}
                      </TableCell>
                      <TableCell className="text-sm text-muted-foreground">
                        {pair.createTime}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </div>
          <div className="flex items-center justify-between pt-2">
            <div className="text-sm text-muted-foreground">
              已选 {chatSelected.size} 条 | 共 {chatTotal} 条
            </div>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={chatPage <= 1 || chatLoading}
                onClick={() => fetchChatPairs(chatPage - 1, chatKeyword)}
              >
                上一页
              </Button>
              <span className="text-sm text-muted-foreground">
                {chatPage} / {Math.max(1, Math.ceil(chatTotal / chatPageSize))}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={chatPage >= Math.ceil(chatTotal / chatPageSize) || chatLoading}
                onClick={() => fetchChatPairs(chatPage + 1, chatKeyword)}
              >
                下一页
              </Button>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setChatImportOpen(false)}>
              取消
            </Button>
            <Button onClick={handleImportFromChat} disabled={chatImporting || chatSelected.size === 0}>
              {chatImporting && <Loader2 className="h-4 w-4 mr-1 animate-spin" />}
              导入 {chatSelected.size > 0 ? `(${chatSelected.size})` : ""}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
