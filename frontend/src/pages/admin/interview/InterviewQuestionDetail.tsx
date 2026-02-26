import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Trash2 } from "lucide-react";
import { toast } from "sonner";
import ReactMarkdown from "react-markdown";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
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
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/utils/error";
import {
  deleteInterviewQuestion,
  getInterviewQuestion,
  type InterviewQuestion
} from "@/services/interviewService";

const DIFFICULTY_MAP: Record<number, { label: string; color: string }> = {
  1: { label: "简单", color: "bg-emerald-100 text-emerald-700" },
  2: { label: "中等", color: "bg-amber-100 text-amber-700" },
  3: { label: "困难", color: "bg-red-100 text-red-700" }
};

export function InterviewQuestionDetail() {
  const { questionId } = useParams<{ questionId: string }>();
  const navigate = useNavigate();

  const [question, setQuestion] = useState<InterviewQuestion | null>(null);
  const [loading, setLoading] = useState(true);
  const [showAnswer, setShowAnswer] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

  useEffect(() => {
    if (!questionId) return;
    const load = async () => {
      try {
        setLoading(true);
        const data = await getInterviewQuestion(questionId);
        setQuestion(data);
      } catch (error) {
        toast.error(getErrorMessage(error, "加载题目失败"));
      } finally {
        setLoading(false);
      }
    };
    load();
  }, [questionId]);

  const handleDelete = async () => {
    if (!questionId) return;
    try {
      await deleteInterviewQuestion(questionId);
      toast.success("删除成功");
      navigate("/admin/interview");
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    }
  };

  if (loading) {
    return (
      <div className="admin-page">
        <div className="py-20 text-center text-muted-foreground">加载中...</div>
      </div>
    );
  }

  if (!question) {
    return (
      <div className="admin-page">
        <div className="py-20 text-center text-muted-foreground">题目不存在</div>
      </div>
    );
  }

  const diff = DIFFICULTY_MAP[question.difficulty] || DIFFICULTY_MAP[1];
  const tags = question.tags?.split(",").filter(Boolean) || [];

  return (
    <div className="admin-page">
      <div className="flex items-center gap-3 mb-6">
        <Button variant="outline" size="sm" onClick={() => navigate("/admin/interview")}>
          <ArrowLeft className="w-4 h-4 mr-1" />
          返回
        </Button>
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
        <div className="flex-1" />
        <Button
          variant="ghost"
          size="sm"
          className="text-destructive hover:text-destructive"
          onClick={() => setDeleteOpen(true)}
        >
          <Trash2 className="w-4 h-4 mr-1" />
          删除
        </Button>
      </div>

      {/* 题目区域 */}
      <Card className="mb-4">
        <CardContent className="py-5">
          <h2 className="text-base font-semibold mb-3">题目</h2>
          <div className="prose prose-sm max-w-none">
            <ReactMarkdown>{question.question}</ReactMarkdown>
          </div>
        </CardContent>
      </Card>

      {/* 答案区域 */}
      <Card>
        <CardContent className="py-5">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-base font-semibold">参考答案</h2>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setShowAnswer((p) => !p)}
            >
              {showAnswer ? "收起答案" : "展开答案"}
            </Button>
          </div>
          {showAnswer ? (
            <div className="prose prose-sm max-w-none">
              <ReactMarkdown>{question.answer}</ReactMarkdown>
            </div>
          ) : (
            <div className="py-8 text-center text-muted-foreground text-sm">
              先想想再看答案，点击上方按钮展开
            </div>
          )}
        </CardContent>
      </Card>

      <AlertDialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              确定要删除此题目吗？此操作不可撤销。
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
