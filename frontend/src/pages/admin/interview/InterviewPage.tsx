import { useCallback, useEffect, useState } from "react";
import {
  ChevronDown,
  ChevronRight,
  GraduationCap,
  Plus,
  RefreshCw,
  Search,
  Trash2,
  Pencil
} from "lucide-react";
import { toast } from "sonner";
import ReactMarkdown from "react-markdown";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/utils/error";
import {
  createInterviewCategory,
  createInterviewQuestion,
  deleteInterviewCategory,
  deleteInterviewQuestion,
  getInterviewCategories,
  getInterviewQuestion,
  getInterviewQuestionsPage,
  updateInterviewCategory,
  updateInterviewQuestion,
  type InterviewCategory,
  type InterviewQuestionListItem,
  type PageResult
} from "@/services/interviewService";

const DIFFICULTY_MAP: Record<number, { label: string; color: string }> = {
  1: { label: "简单", color: "bg-emerald-100 text-emerald-700" },
  2: { label: "中等", color: "bg-amber-100 text-amber-700" },
  3: { label: "困难", color: "bg-red-100 text-red-700" }
};

const PAGE_SIZE = 20;

type DialogMode = "create-category" | "edit-category" | "create-question" | "edit-question" | null;

export function InterviewPage() {
  const [categories, setCategories] = useState<InterviewCategory[]>([]);
  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(null);
  const [questions, setQuestions] = useState<PageResult<InterviewQuestionListItem> | null>(null);
  const [loading, setLoading] = useState(true);
  const [questionsLoading, setQuestionsLoading] = useState(false);
  const [pageNo, setPageNo] = useState(1);
  const [difficulty, setDifficulty] = useState<string>("");
  const [keyword, setKeyword] = useState("");
  const [searchInput, setSearchInput] = useState("");

  // 展开答案
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [loadedAnswers, setLoadedAnswers] = useState<Record<string, string>>({});
  const [answerLoading, setAnswerLoading] = useState<Set<string>>(new Set());

  // Dialog
  const [dialogMode, setDialogMode] = useState<DialogMode>(null);
  const [categoryForm, setCategoryForm] = useState({ name: "", description: "", icon: "", sortOrder: 0 });
  const [questionForm, setQuestionForm] = useState({
    categoryId: "",
    question: "",
    answer: "",
    difficulty: 1,
    tags: ""
  });
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingCategoryId, setEditingCategoryId] = useState<string | null>(null);

  // 删除
  const [deleteTarget, setDeleteTarget] = useState<{
    type: "category" | "question";
    id: string;
    name: string;
  } | null>(null);

  const loadCategories = useCallback(async () => {
    try {
      const data = await getInterviewCategories();
      setCategories(data || []);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载分类失败"));
    }
  }, []);

  const loadQuestions = useCallback(
    async (page = pageNo) => {
      try {
        setQuestionsLoading(true);
        const data = await getInterviewQuestionsPage(
          page,
          PAGE_SIZE,
          selectedCategoryId || undefined,
          difficulty ? Number(difficulty) : undefined,
          keyword || undefined
        );
        setQuestions(data);
      } catch (error) {
        toast.error(getErrorMessage(error, "加载题目失败"));
      } finally {
        setQuestionsLoading(false);
      }
    },
    [pageNo, selectedCategoryId, difficulty, keyword]
  );

  useEffect(() => {
    const init = async () => {
      setLoading(true);
      await loadCategories();
      setLoading(false);
    };
    init();
  }, [loadCategories]);

  useEffect(() => {
    loadQuestions(pageNo);
  }, [pageNo, selectedCategoryId, difficulty, keyword]);

  const toggleAnswer = async (id: string) => {
    if (expandedIds.has(id)) {
      setExpandedIds((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
      return;
    }
    if (!loadedAnswers[id]) {
      setAnswerLoading((prev) => new Set(prev).add(id));
      try {
        const detail = await getInterviewQuestion(id);
        setLoadedAnswers((prev) => ({ ...prev, [id]: detail.answer }));
      } catch (error) {
        toast.error(getErrorMessage(error, "加载答案失败"));
        setAnswerLoading((prev) => {
          const next = new Set(prev);
          next.delete(id);
          return next;
        });
        return;
      } finally {
        setAnswerLoading((prev) => {
          const next = new Set(prev);
          next.delete(id);
          return next;
        });
      }
    }
    setExpandedIds((prev) => new Set(prev).add(id));
  };

  const handleSearch = () => {
    setPageNo(1);
    setKeyword(searchInput.trim());
  };

  const handleCategorySelect = (id: string | null) => {
    setSelectedCategoryId(id);
    setPageNo(1);
  };

  const openCreateCategoryDialog = () => {
    setCategoryForm({ name: "", description: "", icon: "", sortOrder: 0 });
    setEditingCategoryId(null);
    setDialogMode("create-category");
  };

  const openEditCategoryDialog = (cat: InterviewCategory) => {
    setCategoryForm({
      name: cat.name,
      description: cat.description || "",
      icon: cat.icon || "",
      sortOrder: cat.sortOrder
    });
    setEditingCategoryId(cat.id);
    setDialogMode("edit-category");
  };

  const openCreateQuestionDialog = () => {
    setQuestionForm({
      categoryId: selectedCategoryId || (categories[0]?.id ?? ""),
      question: "",
      answer: "",
      difficulty: 1,
      tags: ""
    });
    setEditingId(null);
    setDialogMode("create-question");
  };

  const openEditQuestionDialog = async (item: InterviewQuestionListItem) => {
    try {
      const detail = await getInterviewQuestion(item.id);
      setQuestionForm({
        categoryId: detail.categoryId,
        question: detail.question,
        answer: detail.answer,
        difficulty: detail.difficulty,
        tags: detail.tags || ""
      });
      setEditingId(item.id);
      setDialogMode("edit-question");
    } catch (error) {
      toast.error(getErrorMessage(error, "加载题目详情失败"));
    }
  };

  const handleDialogSubmit = async () => {
    try {
      if (dialogMode === "create-category") {
        await createInterviewCategory({
          name: categoryForm.name,
          description: categoryForm.description || undefined,
          icon: categoryForm.icon || undefined,
          sortOrder: categoryForm.sortOrder
        });
        toast.success("分类创建成功");
      } else if (dialogMode === "edit-category" && editingCategoryId) {
        await updateInterviewCategory({
          id: editingCategoryId,
          name: categoryForm.name,
          description: categoryForm.description || undefined,
          icon: categoryForm.icon || undefined,
          sortOrder: categoryForm.sortOrder
        });
        toast.success("分类更新成功");
      } else if (dialogMode === "create-question") {
        if (!questionForm.question.trim()) {
          toast.error("请输入题目");
          return;
        }
        await createInterviewQuestion({
          categoryId: questionForm.categoryId,
          question: questionForm.question,
          answer: questionForm.answer,
          difficulty: questionForm.difficulty,
          tags: questionForm.tags || undefined
        });
        toast.success("题目创建成功");
      } else if (dialogMode === "edit-question" && editingId) {
        await updateInterviewQuestion({
          id: editingId,
          categoryId: questionForm.categoryId,
          question: questionForm.question,
          answer: questionForm.answer,
          difficulty: questionForm.difficulty,
          tags: questionForm.tags || undefined
        });
        toast.success("题目更新成功");
      }
      setDialogMode(null);
      await loadCategories();
      await loadQuestions(dialogMode?.includes("category") ? pageNo : 1);
    } catch (error) {
      toast.error(getErrorMessage(error, "操作失败"));
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      if (deleteTarget.type === "category") {
        await deleteInterviewCategory(deleteTarget.id);
      } else {
        await deleteInterviewQuestion(deleteTarget.id);
      }
      toast.success("删除成功");
      setDeleteTarget(null);
      await loadCategories();
      await loadQuestions(1);
      setPageNo(1);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    }
  };

  const totalQuestions = categories.reduce((sum, c) => sum + (c.questionCount || 0), 0);
  const records = questions?.records || [];

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">面试题库</h1>
          <p className="admin-page-subtitle">RAgent 项目相关的面试题与参考答案</p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={openCreateCategoryDialog}>
            <Plus className="w-4 h-4 mr-2" />
            新建分类
          </Button>
          <Button className="admin-primary-gradient" onClick={openCreateQuestionDialog}>
            <Plus className="w-4 h-4 mr-2" />
            新建题目
          </Button>
        </div>
      </div>

      {/* 统计卡片 */}
      <div className="grid grid-cols-4 gap-4 mb-6">
        <Card>
          <CardContent className="flex items-center gap-3 py-4">
            <div className="rounded-lg bg-indigo-50 p-2">
              <GraduationCap className="h-5 w-5 text-indigo-600" />
            </div>
            <div>
              <p className="text-2xl font-bold">{totalQuestions}</p>
              <p className="text-sm text-muted-foreground">总题数</p>
            </div>
          </CardContent>
        </Card>
        {[1, 2, 3].map((d) => {
          const info = DIFFICULTY_MAP[d];
          return (
            <Card key={d}>
              <CardContent className="flex items-center gap-3 py-4">
                <div className={cn("rounded-lg p-2", d === 1 ? "bg-emerald-50" : d === 2 ? "bg-amber-50" : "bg-red-50")}>
                  <GraduationCap className={cn("h-5 w-5", d === 1 ? "text-emerald-600" : d === 2 ? "text-amber-600" : "text-red-600")} />
                </div>
                <div>
                  <p className="text-2xl font-bold">-</p>
                  <p className="text-sm text-muted-foreground">{info.label}</p>
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>

      {/* 筛选栏 */}
      <div className="flex items-center gap-3 mb-4">
        <Input
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          placeholder="搜索题目关键词..."
          className="w-[260px]"
          onKeyDown={(e) => e.key === "Enter" && handleSearch()}
        />
        <Button variant="outline" onClick={handleSearch}>
          <Search className="w-4 h-4 mr-1" />
          搜索
        </Button>
        <Select value={difficulty} onValueChange={(v) => { setDifficulty(v === "all" ? "" : v); setPageNo(1); }}>
          <SelectTrigger className="w-[120px]">
            <SelectValue placeholder="难度筛选" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">全部难度</SelectItem>
            <SelectItem value="1">简单</SelectItem>
            <SelectItem value="2">中等</SelectItem>
            <SelectItem value="3">困难</SelectItem>
          </SelectContent>
        </Select>
        <Button variant="outline" onClick={() => { setPageNo(1); loadQuestions(1); }}>
          <RefreshCw className="w-4 h-4 mr-1" />
          刷新
        </Button>
      </div>

      {/* 主体区域 */}
      <div className="flex gap-4" style={{ minHeight: "calc(100vh - 420px)" }}>
        {/* 左侧分类列表 */}
        <Card className="w-[240px] shrink-0 overflow-auto">
          <CardContent className="py-3 px-2">
            <button
              type="button"
              onClick={() => handleCategorySelect(null)}
              className={cn(
                "w-full flex items-center justify-between px-3 py-2 rounded text-sm hover:bg-slate-50",
                selectedCategoryId === null && "bg-indigo-50 text-indigo-700 font-medium"
              )}
            >
              <span>全部分类</span>
              <Badge variant="secondary" className="text-xs">{totalQuestions}</Badge>
            </button>
            {loading ? (
              <div className="py-4 text-center text-muted-foreground text-sm">加载中...</div>
            ) : (
              categories.map((cat) => (
                <div key={cat.id} className="group flex items-center">
                  <button
                    type="button"
                    onClick={() => handleCategorySelect(cat.id)}
                    className={cn(
                      "flex-1 flex items-center justify-between px-3 py-2 rounded text-sm hover:bg-slate-50",
                      selectedCategoryId === cat.id && "bg-indigo-50 text-indigo-700 font-medium"
                    )}
                  >
                    <span className="truncate">{cat.name}</span>
                    <Badge variant="secondary" className="text-xs">{cat.questionCount || 0}</Badge>
                  </button>
                  <div className="hidden group-hover:flex items-center gap-0.5 pr-1">
                    <button
                      type="button"
                      onClick={() => openEditCategoryDialog(cat)}
                      className="p-1 rounded hover:bg-slate-100"
                      title="编辑分类"
                    >
                      <Pencil className="h-3 w-3 text-slate-400" />
                    </button>
                    <button
                      type="button"
                      onClick={() => setDeleteTarget({ type: "category", id: cat.id, name: cat.name })}
                      className="p-1 rounded hover:bg-red-50"
                      title="删除分类"
                    >
                      <Trash2 className="h-3 w-3 text-red-400" />
                    </button>
                  </div>
                </div>
              ))
            )}
          </CardContent>
        </Card>

        {/* 右侧题目列表 */}
        <div className="flex-1 space-y-3">
          {questionsLoading ? (
            <Card>
              <CardContent className="py-12 text-center text-muted-foreground">加载中...</CardContent>
            </Card>
          ) : records.length === 0 ? (
            <Card>
              <CardContent className="py-12 text-center text-muted-foreground">
                暂无题目，点击上方按钮新建
              </CardContent>
            </Card>
          ) : (
            records.map((item) => {
              const diff = DIFFICULTY_MAP[item.difficulty] || DIFFICULTY_MAP[1];
              const isExpanded = expandedIds.has(item.id);
              const isLoadingAnswer = answerLoading.has(item.id);
              const tags = item.tags?.split(",").filter(Boolean) || [];

              return (
                <Card key={item.id} className="hover:shadow-sm transition-shadow">
                  <CardContent className="py-4">
                    <div className="flex items-start justify-between gap-3">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-2">
                          <span className={cn("text-xs px-2 py-0.5 rounded-full font-medium", diff.color)}>
                            {diff.label}
                          </span>
                          {tags.map((tag) => (
                            <span
                              key={tag}
                              className="text-xs px-2 py-0.5 rounded-full bg-slate-100 text-slate-600"
                            >
                              {tag}
                            </span>
                          ))}
                        </div>
                        <button
                          type="button"
                          onClick={() => toggleAnswer(item.id)}
                          className="text-left text-sm font-medium hover:text-indigo-600 transition-colors"
                        >
                          {item.question}
                        </button>
                      </div>
                      <div className="flex items-center gap-1 shrink-0">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => toggleAnswer(item.id)}
                        >
                          {isExpanded ? (
                            <ChevronDown className="h-4 w-4" />
                          ) : (
                            <ChevronRight className="h-4 w-4" />
                          )}
                          {isExpanded ? "收起" : "查看答案"}
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => openEditQuestionDialog(item)}
                        >
                          <Pencil className="h-4 w-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive"
                          onClick={() =>
                            setDeleteTarget({
                              type: "question",
                              id: item.id,
                              name: item.question.slice(0, 30) + "..."
                            })
                          }
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    </div>
                    {isExpanded && (
                      <div className="mt-3 pt-3 border-t">
                        {isLoadingAnswer ? (
                          <div className="text-sm text-muted-foreground">加载答案中...</div>
                        ) : (
                          <div className="prose prose-sm max-w-none">
                            <ReactMarkdown>{loadedAnswers[item.id] || ""}</ReactMarkdown>
                          </div>
                        )}
                      </div>
                    )}
                  </CardContent>
                </Card>
              );
            })
          )}

          {/* 分页 */}
          {questions && questions.total > 0 && (
            <div className="flex items-center justify-between text-sm text-slate-500 pt-2">
              <span>共 {questions.total} 题</span>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setPageNo((p) => Math.max(1, p - 1))}
                  disabled={questions.current <= 1}
                >
                  上一页
                </Button>
                <span>
                  {questions.current} / {questions.pages}
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setPageNo((p) => Math.min(questions.pages || 1, p + 1))}
                  disabled={questions.current >= questions.pages}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* 分类弹窗 */}
      <Dialog
        open={dialogMode === "create-category" || dialogMode === "edit-category"}
        onOpenChange={(open) => !open && setDialogMode(null)}
      >
        <DialogContent className="sm:max-w-[460px]">
          <DialogHeader>
            <DialogTitle>{dialogMode === "edit-category" ? "编辑分类" : "新建分类"}</DialogTitle>
            <DialogDescription>面试题的分类管理</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <label className="text-sm font-medium">分类名称</label>
              <Input
                value={categoryForm.name}
                onChange={(e) => setCategoryForm((p) => ({ ...p, name: e.target.value }))}
                placeholder="例如：系统架构设计"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">描述</label>
              <Input
                value={categoryForm.description}
                onChange={(e) => setCategoryForm((p) => ({ ...p, description: e.target.value }))}
                placeholder="简要描述分类内容"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogMode(null)}>取消</Button>
            <Button onClick={handleDialogSubmit}>
              {dialogMode === "edit-category" ? "更新" : "创建"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 题目弹窗 */}
      <Dialog
        open={dialogMode === "create-question" || dialogMode === "edit-question"}
        onOpenChange={(open) => !open && setDialogMode(null)}
      >
        <DialogContent className="sm:max-w-[640px]">
          <DialogHeader>
            <DialogTitle>{dialogMode === "edit-question" ? "编辑题目" : "新建题目"}</DialogTitle>
            <DialogDescription>支持 Markdown 格式的题目和答案</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <label className="text-sm font-medium">所属分类</label>
                <Select
                  value={questionForm.categoryId}
                  onValueChange={(v) => setQuestionForm((p) => ({ ...p, categoryId: v }))}
                >
                  <SelectTrigger>
                    <SelectValue placeholder="选择分类" />
                  </SelectTrigger>
                  <SelectContent>
                    {categories.map((c) => (
                      <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <label className="text-sm font-medium">难度</label>
                <Select
                  value={String(questionForm.difficulty)}
                  onValueChange={(v) => setQuestionForm((p) => ({ ...p, difficulty: Number(v) }))}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="1">简单</SelectItem>
                    <SelectItem value="2">中等</SelectItem>
                    <SelectItem value="3">困难</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">标签（逗号分隔）</label>
              <Input
                value={questionForm.tags}
                onChange={(e) => setQuestionForm((p) => ({ ...p, tags: e.target.value }))}
                placeholder="例如：架构,模块化,Monorepo"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">题目</label>
              <Textarea
                value={questionForm.question}
                onChange={(e) => setQuestionForm((p) => ({ ...p, question: e.target.value }))}
                placeholder="请输入面试题目"
                className="min-h-[80px]"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">参考答案（Markdown）</label>
              <Textarea
                value={questionForm.answer}
                onChange={(e) => setQuestionForm((p) => ({ ...p, answer: e.target.value }))}
                placeholder="请输入参考答案，支持 Markdown 格式"
                className="min-h-[160px] font-mono text-sm"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogMode(null)}>取消</Button>
            <Button onClick={handleDialogSubmit}>
              {dialogMode === "edit-question" ? "更新" : "创建"}
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
              确定要删除{deleteTarget?.type === "category" ? "分类" : "题目"}
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
