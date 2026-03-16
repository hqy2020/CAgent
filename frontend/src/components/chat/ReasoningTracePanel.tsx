import * as React from "react";
import { ChevronDown, Brain } from "lucide-react";

import { cn } from "@/lib/utils";
import type { ReasoningTracePayload } from "@/types";

interface ReasoningTracePanelProps {
  traces: ReasoningTracePayload[];
  isStreaming?: boolean;
}

const ROLE_COLORS: Record<string, string> = {
  system: "text-gray-500",
  user: "text-blue-600",
  assistant: "text-green-600"
};

function TraceCard({ trace, index }: { trace: ReasoningTracePayload; index: number }) {
  const [expanded, setExpanded] = React.useState(false);

  return (
    <div className="rounded-md border border-gray-200 bg-white/80">
      <button
        type="button"
        onClick={() => setExpanded((prev) => !prev)}
        className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm transition-colors hover:bg-gray-50"
      >
        <span className="shrink-0 rounded bg-purple-100 px-1.5 py-0.5 text-xs font-medium text-purple-700">
          {index + 1}
        </span>
        <span className="flex-1 font-medium text-gray-700 truncate">
          {trace.stepLabel || trace.step || "未知步骤"}
        </span>
        {trace.usage ? (
          <span className="shrink-0 text-xs text-gray-400">
            {trace.usage.totalTokens.toLocaleString()} tokens
          </span>
        ) : null}
        <ChevronDown
          className={cn(
            "h-3.5 w-3.5 shrink-0 text-gray-400 transition-transform",
            expanded && "rotate-180"
          )}
        />
      </button>
      {expanded ? (
        <div className="border-t border-gray-100 px-3 py-2 space-y-2">
          {trace.messages && trace.messages.length > 0 ? (
            <div className="space-y-1">
              <p className="text-xs font-medium text-gray-500">Prompt</p>
              <div className="max-h-60 overflow-auto rounded bg-gray-50 p-2 text-xs leading-relaxed">
                {trace.messages.map((msg, mi) => (
                  <div key={mi} className="mb-1">
                    <span className={cn("font-semibold", ROLE_COLORS[msg.role] || "text-gray-600")}>
                      [{msg.role}]
                    </span>{" "}
                    <span className="text-gray-700 whitespace-pre-wrap break-words">
                      {msg.content.length > 800 ? msg.content.slice(0, 800) + "..." : msg.content}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          ) : null}
          {trace.response ? (
            <div className="space-y-1">
              <p className="text-xs font-medium text-gray-500">Response</p>
              <div className="max-h-40 overflow-auto rounded bg-green-50 p-2 text-xs leading-relaxed text-gray-700 whitespace-pre-wrap break-words">
                {trace.response.length > 1000 ? trace.response.slice(0, 1000) + "..." : trace.response}
              </div>
            </div>
          ) : null}
          {trace.usage ? (
            <p className="text-xs text-gray-400">
              prompt: {trace.usage.promptTokens.toLocaleString()} | completion: {trace.usage.completionTokens.toLocaleString()} | total: {trace.usage.totalTokens.toLocaleString()}
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

export const ReasoningTracePanel = React.memo(function ReasoningTracePanel({
  traces,
  isStreaming
}: ReasoningTracePanelProps) {
  const [expanded, setExpanded] = React.useState(true);

  React.useEffect(() => {
    if (!isStreaming && traces.length > 0) {
      setExpanded(false);
    }
  }, [isStreaming, traces.length]);

  if (!traces || traces.length === 0) return null;

  return (
    <div className="overflow-hidden rounded-lg border border-purple-200 bg-purple-50/50">
      <button
        type="button"
        onClick={() => setExpanded((prev) => !prev)}
        className="flex w-full items-center gap-2 px-4 py-3 text-left transition-colors hover:bg-purple-100/30"
      >
        <div className="flex flex-1 items-center gap-2">
          <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-purple-200">
            <Brain className="h-4 w-4 text-purple-700" />
          </div>
          <span className="text-sm font-medium text-purple-700">推理过程</span>
          <span className="rounded-full bg-purple-200 px-2 py-0.5 text-xs text-purple-700">
            {traces.length} 步
          </span>
          {isStreaming ? (
            <span className="ml-1 inline-block h-2 w-2 animate-pulse rounded-full bg-purple-500" />
          ) : null}
        </div>
        <ChevronDown
          className={cn(
            "h-4 w-4 text-purple-500 transition-transform",
            expanded && "rotate-180"
          )}
        />
      </button>
      {expanded ? (
        <div className="border-t border-purple-200 px-4 py-3 space-y-2">
          {traces.map((trace, i) => (
            <TraceCard key={`${trace.step}-${i}`} trace={trace} index={i} />
          ))}
        </div>
      ) : null}
    </div>
  );
});
