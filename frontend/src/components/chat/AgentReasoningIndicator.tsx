import { ChevronDown, Cpu, Loader2 } from "lucide-react";

import { cn } from "@/lib/utils";

interface AgentReasoningIndicatorProps {
  content?: string;
  duration?: number;
  isStreaming?: boolean;
  expanded?: boolean;
  onToggle?: () => void;
}

export function AgentReasoningIndicator({
  content,
  duration,
  isStreaming = false,
  expanded = true,
  onToggle
}: AgentReasoningIndicatorProps) {
  const statusLabel = isStreaming ? "推理中" : "已完成";

  return (
    <div className="overflow-hidden rounded-2xl border border-indigo-200 bg-indigo-50/95 shadow-sm">
      <div className="flex items-start gap-3 px-4 py-3">
        <div
          className={cn(
            "flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border",
            isStreaming
              ? "border-indigo-200 bg-indigo-100 text-indigo-600"
              : "border-slate-200 bg-white text-slate-600"
          )}
        >
          {isStreaming ? <Loader2 className="h-4 w-4 animate-spin" /> : <Cpu className="h-4 w-4" />}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-semibold text-slate-800">Agent 推理过程</span>
            <span
              className={cn(
                "rounded-full px-2 py-0.5 text-xs font-medium",
                isStreaming ? "bg-indigo-100 text-indigo-700" : "bg-slate-200 text-slate-600"
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
              ? "Agent 正在分析、规划并执行任务。"
              : expanded
                ? "推理过程已展开，可折叠。"
                : "推理过程已折叠，点击展开查看。"}
          </p>
        </div>
        {!isStreaming && onToggle ? (
          <button
            type="button"
            data-testid="agent-reasoning-toggle"
            aria-expanded={expanded}
            onClick={onToggle}
            className="inline-flex shrink-0 items-center gap-1 rounded-full border border-slate-200 bg-white px-3 py-1 text-xs font-medium text-slate-600 transition-colors hover:border-slate-300 hover:bg-slate-100 hover:text-slate-800"
          >
            {expanded ? "收起推理" : "展开推理"}
            <ChevronDown
              className={cn("h-3.5 w-3.5 transition-transform", expanded && "rotate-180")}
            />
          </button>
        ) : null}
      </div>
      {isStreaming || expanded ? (
        <div className="border-t border-indigo-200 bg-white/85 px-4 py-4">
          <div
            data-testid="agent-reasoning-content"
            className="whitespace-pre-wrap break-words text-sm leading-7 text-slate-700"
          >
            {content || ""}
            {isStreaming ? (
              <span className="ml-1 inline-block h-4 w-1.5 animate-pulse rounded-sm bg-indigo-500 align-middle" />
            ) : null}
          </div>
        </div>
      ) : (
        <div
          data-testid="agent-reasoning-collapsed-note"
          className="border-t border-indigo-200 bg-white/70 px-4 py-3 text-xs text-slate-500"
        >
          推理过程已折叠，点击展开查看完整推理。
        </div>
      )}
    </div>
  );
}
