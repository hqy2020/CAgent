import { Brain, ChevronDown, Loader2 } from "lucide-react";

import { cn } from "@/lib/utils";

interface ThinkingIndicatorProps {
  content?: string;
  duration?: number;
  isStreaming?: boolean;
  expanded?: boolean;
  onToggle?: () => void;
}

export function ThinkingIndicator({
  content,
  duration,
  isStreaming = false,
  expanded = true,
  onToggle
}: ThinkingIndicatorProps) {
  const statusLabel = isStreaming ? "生成中" : "已完成";

  return (
    <div className="overflow-hidden rounded-2xl border border-slate-200 bg-slate-50/95 shadow-sm">
      <div className="flex items-start gap-3 px-4 py-3">
        <div
          className={cn(
            "flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border",
            isStreaming
              ? "border-blue-200 bg-blue-100 text-blue-600"
              : "border-slate-200 bg-white text-slate-600"
          )}
        >
          {isStreaming ? <Loader2 className="h-4 w-4 animate-spin" /> : <Brain className="h-4 w-4" />}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-semibold text-slate-800">思考过程</span>
            <span
              className={cn(
                "rounded-full px-2 py-0.5 text-xs font-medium",
                isStreaming ? "bg-blue-100 text-blue-700" : "bg-slate-200 text-slate-600"
              )}
            >
              {statusLabel}
            </span>
            {duration ? (
              <span className="rounded-full bg-white px-2 py-0.5 text-xs text-slate-500 ring-1 ring-slate-200">
                {duration}秒
              </span>
            ) : null}
          </div>
          <p className="mt-1 text-xs leading-5 text-slate-500">
            {isStreaming
              ? "思考内容正在实时明文输出。"
              : expanded
                ? "完整思考过程已展开，可折叠，不影响主答案阅读。"
                : "完整思考过程已折叠，不影响主答案阅读。"}
          </p>
        </div>
        {!isStreaming && onToggle ? (
          <button
            type="button"
            data-testid="thinking-toggle"
            aria-expanded={expanded}
            onClick={onToggle}
            className="inline-flex shrink-0 items-center gap-1 rounded-full border border-slate-200 bg-white px-3 py-1 text-xs font-medium text-slate-600 transition-colors hover:border-slate-300 hover:bg-slate-100 hover:text-slate-800"
          >
            {expanded ? "收起思考" : "展开思考"}
            <ChevronDown
              className={cn("h-3.5 w-3.5 transition-transform", expanded && "rotate-180")}
            />
          </button>
        ) : null}
      </div>
      {isStreaming || expanded ? (
        <div className="border-t border-slate-200 bg-white/85 px-4 py-4">
          <div
            data-testid="thinking-content"
            className="whitespace-pre-wrap break-words text-sm leading-7 text-slate-700"
          >
            {content || ""}
            {isStreaming ? (
              <span className="ml-1 inline-block h-4 w-1.5 animate-pulse rounded-sm bg-blue-500 align-middle" />
            ) : null}
          </div>
        </div>
      ) : (
        <div
          data-testid="thinking-collapsed-note"
          className="border-t border-slate-200 bg-white/70 px-4 py-3 text-xs text-slate-500"
        >
          思考过程已折叠，主答案保持完整显示。
        </div>
      )}
    </div>
  );
}
