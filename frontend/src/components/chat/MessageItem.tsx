import * as React from "react";
import { Brain, ChevronDown, FileText } from "lucide-react";

import { FeedbackButtons } from "@/components/chat/FeedbackButtons";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { ReferenceDetailDialog } from "@/components/chat/ReferenceDetailDialog";
import { ThinkingIndicator } from "@/components/chat/ThinkingIndicator";
import { useAnimatedText } from "@/hooks/useAnimatedText";
import { cn } from "@/lib/utils";
import type { Message, ReferenceItem } from "@/types";

interface MessageItemProps {
  message: Message;
  isLast?: boolean;
}

export const MessageItem = React.memo(function MessageItem({ message, isLast }: MessageItemProps) {
  const isUser = message.role === "user";
  const isStreamingMsg = message.status === "streaming";
  const showFeedback =
    message.role === "assistant" &&
    !isStreamingMsg &&
    message.id &&
    !message.id.startsWith("assistant-");
  const isThinking = Boolean(message.isThinking);
  const [thinkingExpanded, setThinkingExpanded] = React.useState(false);
  const [refsExpanded, setRefsExpanded] = React.useState(false);
  const [selectedRef, setSelectedRef] = React.useState<ReferenceItem | null>(null);
  const hasThinking = Boolean(message.thinking && message.thinking.trim().length > 0);
  const animatedContent = useAnimatedText(message.content, isStreamingMsg && !isThinking);
  const hasContent = animatedContent.trim().length > 0;
  const isWaiting = isStreamingMsg && !isThinking && !hasContent;

  if (isUser) {
    return (
      <div className="flex">
        <div className="user-message">
          <p className="whitespace-pre-wrap break-words">{message.content}</p>
        </div>
      </div>
    );
  }

  const thinkingDuration = message.thinkingDuration ? `${message.thinkingDuration}秒` : "";
  return (
    <div className="group flex">
      <div className="min-w-0 flex-1 space-y-4">
        {isThinking ? (
          <ThinkingIndicator content={message.thinking} duration={message.thinkingDuration} />
        ) : null}
        {!isThinking && hasThinking ? (
          <div className="overflow-hidden rounded-lg border border-[#BFDBFE] bg-[#DBEAFE]">
            <button
              type="button"
              onClick={() => setThinkingExpanded((prev) => !prev)}
              className="flex w-full items-center gap-2 px-4 py-3 text-left transition-colors hover:bg-[#BFDBFE]/30"
            >
              <div className="flex flex-1 items-center gap-2">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-[#BFDBFE]">
                  <Brain className="h-4 w-4 text-[#2563EB]" />
                </div>
                <span className="text-sm font-medium text-[#2563EB]">深度思考</span>
                {thinkingDuration ? (
                  <span className="rounded-full bg-[#BFDBFE] px-2 py-0.5 text-xs text-[#2563EB]">
                    {thinkingDuration}
                  </span>
                ) : null}
              </div>
              <ChevronDown
                className={cn(
                  "h-4 w-4 text-[#3B82F6] transition-transform",
                  thinkingExpanded && "rotate-180"
                )}
              />
            </button>
            {thinkingExpanded ? (
              <div className="border-t border-[#BFDBFE] px-4 pb-4">
                <div className="mt-3 whitespace-pre-wrap text-sm leading-relaxed text-[#1E40AF]">
                  {message.thinking}
                </div>
              </div>
            ) : null}
          </div>
        ) : null}
        <div className="space-y-2">
          {isWaiting ? (
            <div className="ai-wait" aria-label="思考中">
              <span className="ai-wait-dots" aria-hidden="true">
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
              </span>
            </div>
          ) : null}
          {hasContent ? (
            <div className={cn(isStreamingMsg && !isThinking && "streaming-cursor")}>
              <MarkdownRenderer content={animatedContent} />
            </div>
          ) : null}
          {message.status === "error" ? (
            <p className="text-xs text-rose-500">生成已中断。</p>
          ) : null}
          {message.references && message.references.length > 0 ? (
            <div className="overflow-hidden rounded-lg border border-[#BBF7D0] bg-[#DCFCE7]">
              <button
                type="button"
                onClick={() => setRefsExpanded((prev) => !prev)}
                className="flex w-full items-center gap-2 px-4 py-3 text-left transition-colors hover:bg-[#BBF7D0]/30"
              >
                <div className="flex flex-1 items-center gap-2">
                  <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-[#BBF7D0]">
                    <FileText className="h-4 w-4 text-[#16A34A]" />
                  </div>
                  <span className="text-sm font-medium text-[#16A34A]">参考文档</span>
                  <span className="rounded-full bg-[#BBF7D0] px-2 py-0.5 text-xs text-[#16A34A]">
                    {message.references.length}篇
                  </span>
                </div>
                <ChevronDown
                  className={cn(
                    "h-4 w-4 text-[#22C55E] transition-transform",
                    refsExpanded && "rotate-180"
                  )}
                />
              </button>
              {refsExpanded ? (
                <div className="border-t border-[#BBF7D0] px-4 pb-3">
                  <ul className="mt-2 space-y-2">
                    {message.references.map((ref) => (
                      <li
                        key={ref.documentId}
                        className="cursor-pointer rounded-md bg-white/60 px-3 py-2 text-sm transition-colors hover:bg-white/90"
                        onClick={() => setSelectedRef(ref)}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-medium text-gray-800 truncate">
                            {ref.documentName || "未知文档"}
                          </span>
                          {ref.score != null ? (
                            <span className="shrink-0 rounded bg-[#BBF7D0] px-1.5 py-0.5 text-xs text-[#16A34A]">
                              {Math.round(ref.score * 100)}%
                            </span>
                          ) : null}
                        </div>
                        {ref.knowledgeBaseName ? (
                          <p className="mt-0.5 text-xs text-gray-500">
                            来自：{ref.knowledgeBaseName}
                          </p>
                        ) : null}
                        {ref.textPreview ? (
                          <p className="mt-1 text-xs leading-relaxed text-gray-500 line-clamp-2">
                            {ref.textPreview}
                          </p>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
            </div>
          ) : null}
          {showFeedback ? (
            <FeedbackButtons
              messageId={message.id}
              feedback={message.feedback ?? null}
              content={message.content}
              alwaysVisible={Boolean(isLast)}
            />
          ) : null}
        </div>
      </div>
      <ReferenceDetailDialog
        reference={selectedRef}
        open={selectedRef !== null}
        onOpenChange={(open) => { if (!open) setSelectedRef(null); }}
      />
    </div>
  );
});
