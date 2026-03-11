import { useEffect, useRef, useState } from "react";
import { Copy, ThumbsDown, ThumbsUp } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import { MESSAGE_FEEDBACK_REASONS, type FeedbackValue } from "@/types";

interface FeedbackButtonsProps {
  messageId: string;
  feedback: FeedbackValue;
  feedbackReason?: string | null;
  feedbackComment?: string | null;
  content: string;
  className?: string;
  alwaysVisible?: boolean;
}

const DEFAULT_REASON = MESSAGE_FEEDBACK_REASONS[0];

function resolveReason(feedback: FeedbackValue, reason?: string | null) {
  return feedback === "dislike" && reason ? reason : DEFAULT_REASON;
}

export function FeedbackButtons({
  messageId,
  feedback,
  feedbackReason,
  feedbackComment,
  content,
  className,
  alwaysVisible
}: FeedbackButtonsProps) {
  const submitFeedback = useChatStore((state) => state.submitFeedback);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [selectedReason, setSelectedReason] = useState<string>(resolveReason(feedback, feedbackReason));
  const [comment, setComment] = useState(feedbackComment ?? "");
  const selectedReasonRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    if (!dialogOpen) return;
    setSelectedReason(resolveReason(feedback, feedbackReason));
    setComment(feedback === "dislike" ? feedbackComment ?? "" : "");
  }, [dialogOpen, feedback, feedbackReason, feedbackComment]);

  useEffect(() => {
    if (!dialogOpen) return;
    const frameId = window.requestAnimationFrame(() => {
      selectedReasonRef.current?.focus();
    });
    return () => window.cancelAnimationFrame(frameId);
  }, [dialogOpen, selectedReason]);

  const handlePositiveFeedback = () => {
    if (feedback === "like") return;
    submitFeedback(messageId, { value: "like" }).catch(() => null);
  };

  const handleNegativeFeedback = () => {
    setSelectedReason(resolveReason(feedback, feedbackReason));
    setComment(feedback === "dislike" ? feedbackComment ?? "" : "");
    setDialogOpen(true);
  };

  const handleSubmitNegativeFeedback = async () => {
    try {
      await submitFeedback(messageId, {
        value: "dislike",
        reason: selectedReason,
        comment
      });
      setDialogOpen(false);
    } catch {
      // Store already surfaces error toast.
    }
  };

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(content);
      toast.success("复制成功");
    } catch {
      toast.error("复制失败");
    }
  };

  return (
    <>
      <div
        className={cn(
          "flex flex-col items-start gap-1 transition-opacity",
          alwaysVisible ? "opacity-100" : "opacity-0 group-hover:opacity-100",
          className
        )}
      >
        <div className="flex items-center gap-1">
          <Button
            variant="ghost"
            size="icon"
            onClick={handleCopy}
            aria-label="复制内容"
            className="h-8 w-8 text-[#999999] hover:bg-[#F5F5F5] hover:text-[#666666]"
          >
            <Copy className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            onClick={handlePositiveFeedback}
            aria-label="点赞"
            className={cn(
              "h-8 w-8 text-[#999999] hover:bg-[#F5F5F5] hover:text-[#10B981]",
              feedback === "like" && "text-[#10B981]"
            )}
          >
            <ThumbsUp className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            onClick={handleNegativeFeedback}
            aria-label="点踩"
            className={cn(
              "h-8 w-8 text-[#999999] hover:bg-[#F5F5F5] hover:text-[#EF4444]",
              feedback === "dislike" && "text-[#EF4444]"
            )}
          >
            <ThumbsDown className="h-4 w-4" />
          </Button>
        </div>

        {feedback === "dislike" && feedbackReason ? (
          <p
            className="max-w-[280px] truncate pl-1 text-[11px] text-[#EF4444]"
            title={feedbackComment ? `${feedbackReason} · ${feedbackComment}` : feedbackReason}
          >
            已反馈：{feedbackReason}
          </p>
        ) : null}
      </div>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent
          className="sm:max-w-[520px]"
          onOpenAutoFocus={(event) => {
            event.preventDefault();
          }}
        >
          <DialogHeader>
            <DialogTitle>这条回答哪里不满意？</DialogTitle>
            <DialogDescription>
              选择一个最接近的问题类型，后台会按问题与回答一起统计分析。
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-3">
            <div className="grid gap-2 sm:grid-cols-2">
              {MESSAGE_FEEDBACK_REASONS.map((reason) => {
                const active = selectedReason === reason;
                return (
                  <button
                    key={reason}
                    type="button"
                    ref={active ? selectedReasonRef : null}
                    onClick={() => setSelectedReason(reason)}
                    className={cn(
                      "rounded-2xl border px-4 py-3 text-left text-sm transition-colors",
                      active
                        ? "border-[#FCA5A5] bg-[#FEF2F2] text-[#B91C1C]"
                        : "border-slate-200 bg-white text-slate-600 hover:border-slate-300 hover:bg-slate-50"
                    )}
                  >
                    {reason}
                  </button>
                );
              })}
            </div>

            <div className="space-y-2">
              <p className="text-xs font-medium text-slate-600">补充说明（可选）</p>
              <Textarea
                value={comment}
                onChange={(event) => setComment(event.target.value)}
                rows={4}
                maxLength={240}
                placeholder="例如：引用错知识库、问题本来需要联网搜索、结论下得太满等。"
                className="resize-none"
              />
              <div className="text-right text-[11px] text-slate-400">{comment.length}/240</div>
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void handleSubmitNegativeFeedback()}>提交反馈</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
