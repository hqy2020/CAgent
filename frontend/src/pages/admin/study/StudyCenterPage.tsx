import { useCallback, useEffect, useState } from "react";
import {
  BookOpen,
  ChevronDown,
  ChevronRight,
  Edit3,
  FileText,
  FolderOpen,
  Layers,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  Trash2,
  X
} from "lucide-react";
import { toast } from "sonner";
import ReactMarkdown from "react-markdown";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/utils/error";
import {
  createStudyChapter,
  createStudyDocument,
  createStudyModule,
  deleteStudyChapter,
  deleteStudyDocument,
  deleteStudyModule,
  getStudyDocument,
  getStudyModuleTree,
  updateStudyDocument,
  updateStudyModule,
  type StudyModuleTree,
  type StudyDocument
} from "@/services/studyService";

type TreeSelection = {
  type: "module" | "chapter" | "document";
  moduleId: string;
  chapterId?: string;
  documentId?: string;
};

type DialogMode = "create-module" | "edit-module" | "create-chapter" | "create-document" | null;

export function StudyCenterPage() {
  const [tree, setTree] = useState<StudyModuleTree[]>([]);
  const [loading, setLoading] = useState(true);
  const [expandedModules, setExpandedModules] = useState<Set<string>>(new Set());
  const [expandedChapters, setExpandedChapters] = useState<Set<string>>(new Set());
  const [selection, setSelection] = useState<TreeSelection | null>(null);

  // 文档内容
  const [document, setDocument] = useState<StudyDocument | null>(null);
  const [docLoading, setDocLoading] = useState(false);
  const [editing, setEditing] = useState(false);
  const [editContent, setEditContent] = useState("");
  const [editTitle, setEditTitle] = useState("");
  const [saving, setSaving] = useState(false);

  // Dialog
  const [dialogMode, setDialogMode] = useState<DialogMode>(null);
  const [dialogForm, setDialogForm] = useState({
    name: "",
    description: "",
    icon: "",
    sortOrder: 0,
    title: "",
    summary: "",
    content: "",
    moduleId: "",
    chapterId: ""
  });
  const [editingModuleId, setEditingModuleId] = useState<string | null>(null);

  // 删除
  const [deleteTarget, setDeleteTarget] = useState<{
    type: "module" | "chapter" | "document";
    id: string;
    name: string;
  } | null>(null);

  const loadTree = useCallback(async () => {
    try {
      setLoading(true);
      const data = await getStudyModuleTree();
      setTree(data || []);
      if (data?.length > 0 && expandedModules.size === 0) {
        setExpandedModules(new Set(data.map((m) => m.id)));
      }
    } catch (error) {
      toast.error(getErrorMessage(error, "加载学习模块失败"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadTree();
  }, [loadTree]);

  const loadDocument = async (docId: string) => {
    try {
      setDocLoading(true);
      const doc = await getStudyDocument(docId);
      setDocument(doc);
      setEditing(false);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载文档失败"));
    } finally {
      setDocLoading(false);
    }
  };

  const handleSelect = (sel: TreeSelection) => {
    setSelection(sel);
    if (sel.type === "document" && sel.documentId) {
      loadDocument(sel.documentId);
    } else {
      setDocument(null);
      setEditing(false);
    }
  };

  const toggleModule = (id: string) => {
    setExpandedModules((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleChapter = (id: string) => {
    setExpandedChapters((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const startEdit = () => {
    if (!document) return;
    setEditContent(document.content);
    setEditTitle(document.title);
    setEditing(true);
  };

  const cancelEdit = () => {
    setEditing(false);
    setEditContent("");
    setEditTitle("");
  };

  const saveDocument = async () => {
    if (!document) return;
    try {
      setSaving(true);
      await updateStudyDocument({ id: document.id, title: editTitle, content: editContent });
      toast.success("保存成功");
      setDocument({ ...document, title: editTitle, content: editContent });
      setEditing(false);
      await loadTree();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setSaving(false);
    }
  };

  const openDialog = (mode: DialogMode, context?: { moduleId?: string; chapterId?: string }) => {
    setDialogForm({
      name: "",
      description: "",
      icon: "",
      sortOrder: 0,
      title: "",
      summary: "",
      content: "",
      moduleId: context?.moduleId || "",
      chapterId: context?.chapterId || ""
    });
    setDialogMode(mode);
  };

  const openEditModuleDialog = (mod: StudyModuleTree) => {
    setEditingModuleId(mod.id);
    setDialogForm({
      name: mod.name,
      description: "",
      icon: mod.icon || "",
      sortOrder: 0,
      title: "",
      summary: "",
      content: "",
      moduleId: mod.id,
      chapterId: ""
    });
    setDialogMode("edit-module");
  };

  const handleDialogSubmit = async () => {
    try {
      if (dialogMode === "create-module") {
        await createStudyModule({
          name: dialogForm.name,
          description: dialogForm.description || undefined,
          icon: dialogForm.icon || undefined,
          sortOrder: dialogForm.sortOrder
        });
        toast.success("模块创建成功");
      } else if (dialogMode === "edit-module" && editingModuleId) {
        await updateStudyModule({
          id: editingModuleId,
          name: dialogForm.name,
          description: dialogForm.description || undefined,
          icon: dialogForm.icon || undefined,
          sortOrder: dialogForm.sortOrder
        });
        toast.success("模块更新成功");
      } else if (dialogMode === "create-chapter") {
        await createStudyChapter({
          moduleId: dialogForm.moduleId,
          title: dialogForm.title,
          summary: dialogForm.summary || undefined,
          sortOrder: dialogForm.sortOrder
        });
        toast.success("章节创建成功");
      } else if (dialogMode === "create-document") {
        await createStudyDocument({
          chapterId: dialogForm.chapterId,
          moduleId: dialogForm.moduleId,
          title: dialogForm.title,
          content: dialogForm.content || "# 新文档\n\n在此编写内容..."
        });
        toast.success("文档创建成功");
      }
      setDialogMode(null);
      await loadTree();
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      if (deleteTarget.type === "module") {
        await deleteStudyModule(deleteTarget.id);
      } else if (deleteTarget.type === "chapter") {
        await deleteStudyChapter(deleteTarget.id);
      } else {
        await deleteStudyDocument(deleteTarget.id);
      }
      toast.success("删除成功");
      setDeleteTarget(null);
      if (selection?.documentId === deleteTarget.id || selection?.chapterId === deleteTarget.id || selection?.moduleId === deleteTarget.id) {
        setSelection(null);
        setDocument(null);
      }
      await loadTree();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    }
  };

  // 统计数
  const moduleCount = tree.length;
  const chapterCount = tree.reduce((sum, m) => sum + m.chapters.length, 0);
  const docCount = tree.reduce(
    (sum, m) => sum + m.chapters.reduce((s, c) => s + c.documents.length, 0),
    0
  );

  const dialogTitle: Record<string, string> = {
    "create-module": "新建模块",
    "edit-module": "编辑模块",
    "create-chapter": "新建章节",
    "create-document": "新建文档"
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">学习中心</h1>
          <p className="admin-page-subtitle">系统学习 RAgent 项目架构与核心设计</p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={loadTree}>
            <RefreshCw className="w-4 h-4 mr-2" />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={() => openDialog("create-module")}>
            <Plus className="w-4 h-4 mr-2" />
            新建模块
          </Button>
        </div>
      </div>

      {/* 统计卡片 */}
      <div className="grid grid-cols-3 gap-4 mb-6">
        <Card>
          <CardContent className="flex items-center gap-3 py-4">
            <div className="rounded-lg bg-indigo-50 p-2">
              <Layers className="h-5 w-5 text-indigo-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">{moduleCount}</p>
              <p className="text-sm text-muted-foreground">学习模块</p>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="flex items-center gap-3 py-4">
            <div className="rounded-lg bg-emerald-50 p-2">
              <FolderOpen className="h-5 w-5 text-emerald-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">{chapterCount}</p>
              <p className="text-sm text-muted-foreground">章节</p>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="flex items-center gap-3 py-4">
            <div className="rounded-lg bg-amber-50 p-2">
              <FileText className="h-5 w-5 text-amber-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">{docCount}</p>
              <p className="text-sm text-muted-foreground">文档</p>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* 主体区域 */}
      <div className="flex gap-4" style={{ minHeight: "calc(100vh - 340px)" }}>
        {/* 左侧树形导航 */}
        <Card className="w-[320px] shrink-0 overflow-auto">
          <CardContent className="py-3 px-2">
            {loading ? (
              <div className="py-8 text-center text-muted-foreground">加载中...</div>
            ) : tree.length === 0 ? (
              <div className="py-8 text-center text-muted-foreground">暂无模块</div>
            ) : (
              <div className="space-y-1">
                {tree.map((mod) => {
                  const modExpanded = expandedModules.has(mod.id);
                  const modSelected = selection?.type === "module" && selection.moduleId === mod.id;
                  return (
                    <div key={mod.id}>
                      <div className="group flex items-center">
                        <button
                          type="button"
                          onClick={() => toggleModule(mod.id)}
                          className="p-0.5 hover:bg-slate-100 rounded"
                        >
                          {modExpanded ? (
                            <ChevronDown className="h-4 w-4 text-slate-400" />
                          ) : (
                            <ChevronRight className="h-4 w-4 text-slate-400" />
                          )}
                        </button>
                        <button
                          type="button"
                          onClick={() =>
                            handleSelect({ type: "module", moduleId: mod.id })
                          }
                          className={cn(
                            "flex-1 flex items-center gap-2 px-2 py-1.5 rounded text-sm text-left hover:bg-slate-50",
                            modSelected && "bg-indigo-50 text-indigo-700 font-medium"
                          )}
                        >
                          <BookOpen className="h-4 w-4 shrink-0" />
                          <span className="truncate">{mod.name}</span>
                        </button>
                        <div className="hidden group-hover:flex items-center gap-0.5 pr-1">
                          <button
                            type="button"
                            onClick={() => openEditModuleDialog(mod)}
                            className="p-1 rounded hover:bg-slate-100"
                            title="编辑模块"
                          >
                            <Pencil className="h-3 w-3 text-slate-400" />
                          </button>
                          <button
                            type="button"
                            onClick={() =>
                              openDialog("create-chapter", { moduleId: mod.id })
                            }
                            className="p-1 rounded hover:bg-slate-100"
                            title="新建章节"
                          >
                            <Plus className="h-3 w-3 text-slate-400" />
                          </button>
                          <button
                            type="button"
                            onClick={() =>
                              setDeleteTarget({ type: "module", id: mod.id, name: mod.name })
                            }
                            className="p-1 rounded hover:bg-red-50"
                            title="删除模块"
                          >
                            <Trash2 className="h-3 w-3 text-red-400" />
                          </button>
                        </div>
                      </div>
                      {modExpanded &&
                        mod.chapters.map((ch) => {
                          const chExpanded = expandedChapters.has(ch.id);
                          const chSelected =
                            selection?.type === "chapter" && selection.chapterId === ch.id;
                          return (
                            <div key={ch.id} className="ml-5">
                              <div className="group flex items-center">
                                <button
                                  type="button"
                                  onClick={() => toggleChapter(ch.id)}
                                  className="p-0.5 hover:bg-slate-100 rounded"
                                >
                                  {chExpanded ? (
                                    <ChevronDown className="h-3.5 w-3.5 text-slate-400" />
                                  ) : (
                                    <ChevronRight className="h-3.5 w-3.5 text-slate-400" />
                                  )}
                                </button>
                                <button
                                  type="button"
                                  onClick={() => {
                                    handleSelect({
                                      type: "chapter",
                                      moduleId: mod.id,
                                      chapterId: ch.id
                                    });
                                    if (!chExpanded) toggleChapter(ch.id);
                                  }}
                                  className={cn(
                                    "flex-1 flex items-center gap-2 px-2 py-1 rounded text-sm text-left hover:bg-slate-50",
                                    chSelected && "bg-indigo-50 text-indigo-700 font-medium"
                                  )}
                                >
                                  <FolderOpen className="h-3.5 w-3.5 shrink-0" />
                                  <span className="truncate">{ch.title}</span>
                                </button>
                                <div className="hidden group-hover:flex items-center gap-0.5 pr-1">
                                  <button
                                    type="button"
                                    onClick={() =>
                                      openDialog("create-document", {
                                        moduleId: mod.id,
                                        chapterId: ch.id
                                      })
                                    }
                                    className="p-1 rounded hover:bg-slate-100"
                                    title="新建文档"
                                  >
                                    <Plus className="h-3 w-3 text-slate-400" />
                                  </button>
                                  <button
                                    type="button"
                                    onClick={() =>
                                      setDeleteTarget({ type: "chapter", id: ch.id, name: ch.title })
                                    }
                                    className="p-1 rounded hover:bg-red-50"
                                    title="删除章节"
                                  >
                                    <Trash2 className="h-3 w-3 text-red-400" />
                                  </button>
                                </div>
                              </div>
                              {chExpanded &&
                                ch.documents.map((doc) => {
                                  const docSelected =
                                    selection?.type === "document" &&
                                    selection.documentId === doc.id;
                                  return (
                                    <div key={doc.id} className="ml-5 group flex items-center">
                                      <button
                                        type="button"
                                        onClick={() =>
                                          handleSelect({
                                            type: "document",
                                            moduleId: mod.id,
                                            chapterId: ch.id,
                                            documentId: doc.id
                                          })
                                        }
                                        className={cn(
                                          "flex-1 flex items-center gap-2 px-2 py-1 rounded text-sm text-left hover:bg-slate-50",
                                          docSelected &&
                                            "bg-indigo-50 text-indigo-700 font-medium"
                                        )}
                                      >
                                        <FileText className="h-3.5 w-3.5 shrink-0" />
                                        <span className="truncate">{doc.title}</span>
                                      </button>
                                      <div className="hidden group-hover:flex items-center pr-1">
                                        <button
                                          type="button"
                                          onClick={() =>
                                            setDeleteTarget({
                                              type: "document",
                                              id: doc.id,
                                              name: doc.title
                                            })
                                          }
                                          className="p-1 rounded hover:bg-red-50"
                                          title="删除文档"
                                        >
                                          <Trash2 className="h-3 w-3 text-red-400" />
                                        </button>
                                      </div>
                                    </div>
                                  );
                                })}
                            </div>
                          );
                        })}
                    </div>
                  );
                })}
              </div>
            )}
          </CardContent>
        </Card>

        {/* 右侧内容区 */}
        <Card className="flex-1 overflow-auto">
          <CardContent className="py-4">
            {!selection ? (
              <div className="flex flex-col items-center justify-center py-20 text-muted-foreground">
                <BookOpen className="h-12 w-12 mb-4 opacity-30" />
                <p>请从左侧选择一个文档开始学习</p>
              </div>
            ) : selection.type !== "document" ? (
              <div className="flex flex-col items-center justify-center py-20 text-muted-foreground">
                <FolderOpen className="h-12 w-12 mb-4 opacity-30" />
                <p>请选择一篇文档查看内容</p>
              </div>
            ) : docLoading ? (
              <div className="py-20 text-center text-muted-foreground">加载中...</div>
            ) : document ? (
              <div>
                <div className="flex items-center justify-between mb-4 pb-3 border-b">
                  {editing ? (
                    <Input
                      value={editTitle}
                      onChange={(e) => setEditTitle(e.target.value)}
                      className="text-lg font-semibold max-w-md"
                    />
                  ) : (
                    <h2 className="text-lg font-semibold">{document.title}</h2>
                  )}
                  <div className="flex items-center gap-2">
                    {editing ? (
                      <>
                        <Button variant="outline" size="sm" onClick={cancelEdit}>
                          <X className="w-4 h-4 mr-1" />
                          取消
                        </Button>
                        <Button size="sm" onClick={saveDocument} disabled={saving}>
                          <Save className="w-4 h-4 mr-1" />
                          {saving ? "保存中..." : "保存"}
                        </Button>
                      </>
                    ) : (
                      <Button variant="outline" size="sm" onClick={startEdit}>
                        <Edit3 className="w-4 h-4 mr-1" />
                        编辑
                      </Button>
                    )}
                  </div>
                </div>
                {editing ? (
                  <div className="grid grid-cols-2 gap-4" style={{ minHeight: 400 }}>
                    <Textarea
                      value={editContent}
                      onChange={(e) => setEditContent(e.target.value)}
                      className="font-mono text-sm resize-none"
                      style={{ minHeight: 400 }}
                    />
                    <div className="prose prose-sm max-w-none overflow-auto rounded border p-4 bg-slate-50">
                      <ReactMarkdown>{editContent}</ReactMarkdown>
                    </div>
                  </div>
                ) : (
                  <div className="prose prose-sm max-w-none">
                    <ReactMarkdown>{document.content}</ReactMarkdown>
                  </div>
                )}
              </div>
            ) : null}
          </CardContent>
        </Card>
      </div>

      {/* 创建/编辑弹窗 */}
      <Dialog open={dialogMode !== null} onOpenChange={(open) => !open && setDialogMode(null)}>
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle>{dialogMode ? dialogTitle[dialogMode] : ""}</DialogTitle>
            <DialogDescription>
              {dialogMode === "create-module" || dialogMode === "edit-module"
                ? "模块是学习内容的顶层分类"
                : dialogMode === "create-chapter"
                  ? "章节归属于模块，用于组织文档"
                  : "文档是学习的最小单元，支持 Markdown 格式"}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            {(dialogMode === "create-module" || dialogMode === "edit-module") && (
              <>
                <div className="space-y-2">
                  <label className="text-sm font-medium">模块名称</label>
                  <Input
                    value={dialogForm.name}
                    onChange={(e) => setDialogForm((p) => ({ ...p, name: e.target.value }))}
                    placeholder="例如：RAgent 系统架构"
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-medium">描述</label>
                  <Input
                    value={dialogForm.description}
                    onChange={(e) => setDialogForm((p) => ({ ...p, description: e.target.value }))}
                    placeholder="简要描述模块内容"
                  />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <label className="text-sm font-medium">图标</label>
                    <Input
                      value={dialogForm.icon}
                      onChange={(e) => setDialogForm((p) => ({ ...p, icon: e.target.value }))}
                      placeholder="Lucide 图标名"
                    />
                  </div>
                  <div className="space-y-2">
                    <label className="text-sm font-medium">排序</label>
                    <Input
                      type="number"
                      value={dialogForm.sortOrder}
                      onChange={(e) =>
                        setDialogForm((p) => ({ ...p, sortOrder: Number(e.target.value) }))
                      }
                    />
                  </div>
                </div>
              </>
            )}
            {dialogMode === "create-chapter" && (
              <>
                <div className="space-y-2">
                  <label className="text-sm font-medium">章节标题</label>
                  <Input
                    value={dialogForm.title}
                    onChange={(e) => setDialogForm((p) => ({ ...p, title: e.target.value }))}
                    placeholder="例如：Monorepo 模块划分"
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-medium">摘要</label>
                  <Input
                    value={dialogForm.summary}
                    onChange={(e) => setDialogForm((p) => ({ ...p, summary: e.target.value }))}
                    placeholder="章节的简要说明"
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-medium">排序</label>
                  <Input
                    type="number"
                    value={dialogForm.sortOrder}
                    onChange={(e) =>
                      setDialogForm((p) => ({ ...p, sortOrder: Number(e.target.value) }))
                    }
                  />
                </div>
              </>
            )}
            {dialogMode === "create-document" && (
              <>
                <div className="space-y-2">
                  <label className="text-sm font-medium">文档标题</label>
                  <Input
                    value={dialogForm.title}
                    onChange={(e) => setDialogForm((p) => ({ ...p, title: e.target.value }))}
                    placeholder="例如：RAgent Monorepo 模块划分详解"
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-medium">初始内容（Markdown）</label>
                  <Textarea
                    value={dialogForm.content}
                    onChange={(e) => setDialogForm((p) => ({ ...p, content: e.target.value }))}
                    placeholder="# 标题&#10;&#10;在此编写内容..."
                    className="min-h-[120px] font-mono text-sm"
                  />
                </div>
              </>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogMode(null)}>
              取消
            </Button>
            <Button onClick={handleDialogSubmit}>
              {dialogMode?.startsWith("edit") ? "更新" : "创建"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 删除确认 */}
      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              确定要删除{deleteTarget?.type === "module" ? "模块" : deleteTarget?.type === "chapter" ? "章节" : "文档"}
              「{deleteTarget?.name}」吗？此操作不可撤销。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              className="bg-destructive text-destructive-foreground"
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
