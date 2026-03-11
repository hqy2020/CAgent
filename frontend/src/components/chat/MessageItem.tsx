import * as React from "react";
import { Brain, ChevronDown, ExternalLink, FileText, Wrench } from "lucide-react";

import { FeedbackButtons } from "@/components/chat/FeedbackButtons";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { ReferenceDetailDialog } from "@/components/chat/ReferenceDetailDialog";
import { ThinkingIndicator } from "@/components/chat/ThinkingIndicator";
import { useAnimatedText } from "@/hooks/useAnimatedText";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";
import type { Message, ReferenceItem } from "@/types";

interface MessageItemProps {
  message: Message;
  isLast?: boolean;
}

function formatStepStatus(status?: string) {
  switch ((status || "").toUpperCase()) {
    case "RUNNING":
      return "进行中";
    case "SUCCESS":
      return "已完成";
    case "FAILED":
      return "失败";
    case "CONFIRM_REQUIRED":
      return "待确认";
    default:
      return status || "-";
  }
}

function buildReferenceDetailHref(reference: ReferenceItem, isAdmin: boolean) {
  if (reference.documentUrl) {
    return reference.documentUrl;
  }
  if (!reference.knowledgeBaseId || !reference.documentId) {
    return null;
  }
  const basePath = isAdmin ? "/admin/knowledge" : "/workspace/knowledge";
  return `${basePath}/${encodeURIComponent(reference.knowledgeBaseId)}/docs/${encodeURIComponent(reference.documentId)}`;
}

export const MessageItem = React.memo(function MessageItem({ message, isLast }: MessageItemProps) {
  const PREVIEW_TOGGLE_THRESHOLD = 140;
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
  const [expandedPreviewKeys, setExpandedPreviewKeys] = React.useState<Set<string>>(new Set());
  const [selectedRef, setSelectedRef] = React.useState<ReferenceItem | null>(null);
  const hasThinking = Boolean(message.thinking && message.thinking.trim().length > 0);
  const animatedContent = useAnimatedText(message.content, isStreamingMsg && !isThinking);
  const hasContent = animatedContent.trim().length > 0;
  const hasTimeline = Boolean(message.agentTimeline && message.agentTimeline.length > 0);
  const isWaiting = isStreamingMsg && !isThinking && !hasContent && !hasTimeline;
  const sendMessage = useChatStore((state) => state.sendMessage);
  const isAdmin = useAuthStore((state) => state.user?.role === "admin");

  React.useEffect(() => {
    setExpandedPreviewKeys(new Set());
  }, [message.id]);

  const togglePreview = React.useCallback((key: string) => {
    setExpandedPreviewKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }, []);

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
          {message.agentTimeline && message.agentTimeline.length > 0 ? (
            <div className="overflow-hidden rounded-lg border border-[#C7D2FE] bg-[#E0E7FF]">
              <div className="flex items-center gap-2 px-4 py-3">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-[#C7D2FE]">
                  <Wrench className="h-4 w-4 text-[#4338CA]" />
                </div>
                <span className="text-sm font-medium text-[#4338CA]">处理进度</span>
                <span className="rounded-full bg-[#C7D2FE] px-2 py-0.5 text-xs text-[#4338CA]">
                  {message.agentTimeline.length}条
                </span>
              </div>
              <div className="border-t border-[#C7D2FE] px-4 py-3">
                <ul className="space-y-2 text-xs text-[#3730A3]">
                  {message.agentTimeline.map((item, index) => {
                    if (item.kind === "observe") {
                      return (
                        <li key={`observe-${index}`} className="rounded bg-white/70 px-2 py-1">
                          <p className="font-medium">
                            观察（loop {item.payload.loop} / step {item.payload.stepIndex}）
                          </p>
                          {"summary" in item.payload && item.payload.summary ? (
                            <p className="mt-1">{item.payload.summary}</p>
                          ) : null}
                          {"items" in item.payload && item.payload.items && item.payload.items.length > 0 ? (
                            <p className="mt-1">
                              {item.payload.items
                                .map((observe) => `${observe.source}[${observe.status}]`)
                                .join(" | ")}
                            </p>
                          ) : null}
                        </li>
                      );
                    }
                    if (item.kind === "plan") {
                      return (
                        <li key={`plan-${index}`} className="rounded bg-white/70 px-2 py-1">
                          <p className="font-medium">规划（loop {item.payload.loop}）：{item.payload.goal}</p>
                          {"steps" in item.payload && item.payload.steps && item.payload.steps.length > 0 ? (
                            <p className="mt-1">
                              {item.payload.steps
                                .map((step) => `${step.stepIndex}.${step.type}`)
                                .join(" | ")}
                            </p>
                          ) : null}
                        </li>
                      );
                    }
                    if (item.kind === "step") {
                      const isQueueStep = item.payload.stepIndex === 0 && item.payload.type === "排队";
                      return (
                        <li key={`step-${index}`} className="rounded bg-white/70 px-2 py-1">
                          <p className="font-medium">
                            {isQueueStep ? item.payload.type : `步骤 ${item.payload.stepIndex}（${item.payload.type}）`}
                            <span className="ml-1">[{formatStepStatus(item.payload.status)}]</span>
                          </p>
                          {"summary" in item.payload && item.payload.summary ? (
                            <p className="mt-1">{item.payload.summary}</p>
                          ) : null}
                        </li>
                      );
                    }
                    return (
                      <li key={`replan-${index}`} className="rounded bg-white/70 px-2 py-1">
                        <p className="font-medium">重规划（loop {item.payload.loop}）</p>
                        {"reason" in item.payload && item.payload.reason ? (
                          <p className="mt-1">{item.payload.reason}</p>
                        ) : null}
                      </li>
                    );
                  })}
                </ul>
              </div>
            </div>
          ) : null}
          {message.pendingProposal ? (
            <div className="overflow-hidden rounded-lg border border-[#FECACA] bg-[#FEE2E2]">
              <div className="flex items-center gap-2 px-4 py-3">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-[#FECACA]">
                  <Wrench className="h-4 w-4 text-[#B91C1C]" />
                </div>
                <span className="text-sm font-medium text-[#B91C1C]">待确认写操作</span>
              </div>
              <div className="border-t border-[#FECACA] px-4 py-3 text-xs text-[#7F1D1D]">
                <p>proposalId: {message.pendingProposal.proposalId}</p>
                <p className="mt-1">tool: {message.pendingProposal.toolId}</p>
                {message.pendingProposal.targetPath ? (
                  <p className="mt-1">目标路径: {message.pendingProposal.targetPath}</p>
                ) : null}
                {message.pendingProposal.riskHint ? (
                  <p className="mt-1">风险提示: {message.pendingProposal.riskHint}</p>
                ) : null}
                <div className="mt-3 flex items-center gap-2">
                  <button
                    type="button"
                    className="rounded bg-[#DC2626] px-2 py-1 text-white hover:bg-[#B91C1C]"
                    onClick={() => void sendMessage(`/confirm ${message.pendingProposal?.proposalId}`)}
                  >
                    确认执行
                  </button>
                  <button
                    type="button"
                    className="rounded border border-[#DC2626] px-2 py-1 text-[#DC2626] hover:bg-[#FECACA]"
                    onClick={() => void sendMessage(`/reject ${message.pendingProposal?.proposalId}`)}
                  >
                    拒绝执行
                  </button>
                </div>
              </div>
            </div>
          ) : null}
          {message.workflow ? (
            <div className="overflow-hidden rounded-lg border border-[#FDE68A] bg-[#FEF3C7]">
              <div className="flex items-center gap-2 px-4 py-3">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-[#FDE68A]">
                  <Wrench className="h-4 w-4 text-[#B45309]" />
                </div>
                <span className="text-sm font-medium text-[#B45309]">工作流执行</span>
                <span className="rounded-full bg-[#FDE68A] px-2 py-0.5 text-xs text-[#92400E]">
                  {message.workflow.workflowId || "-"}
                </span>
              </div>
              <div className="border-t border-[#FDE68A] px-4 pb-3 pt-2 text-xs text-[#92400E]">
                <p>
                  操作数：
                  <span className="ml-1 font-medium">{message.workflow.opsCount ?? 0}</span>
                </p>
                <div className="mt-2">
                  <p className="text-[#B45309]">变更文件：</p>
                  {message.workflow.changedFiles && message.workflow.changedFiles.length > 0 ? (
                    <ul className="mt-1 list-disc space-y-1 pl-4">
                      {message.workflow.changedFiles.map((file) => (
                        <li key={file} className="break-all">
                          {file}
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="mt-1">无文件变更</p>
                  )}
                </div>
                {message.workflow.warnings && message.workflow.warnings.length > 0 ? (
                  <div className="mt-2">
                    <p className="text-[#B45309]">告警：</p>
                    <ul className="mt-1 list-disc space-y-1 pl-4">
                      {message.workflow.warnings.map((warning, index) => (
                        <li key={`${warning}-${index}`}>{warning}</li>
                      ))}
                    </ul>
                  </div>
                ) : null}
              </div>
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
                    {message.references.map((ref) => {
                      const previewKey = ref.documentId;
                      const previewText = ref.textPreview?.trim() || "";
                      const canTogglePreview = previewText.length > PREVIEW_TOGGLE_THRESHOLD;
                      const previewExpanded = expandedPreviewKeys.has(previewKey);
                      const detailHref = buildReferenceDetailHref(ref, isAdmin);

                      return (
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
                          {detailHref ? (
                            <a
                              href={detailHref}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="mt-1 inline-flex items-center gap-1 text-xs text-[#16A34A] hover:text-[#15803D] hover:underline"
                              onClick={(event) => {
                                event.stopPropagation();
                              }}
                            >
                              打开完整文档
                              <ExternalLink className="h-3 w-3" />
                            </a>
                          ) : null}
                          {previewText ? (
                            <div className="mt-1">
                              <p
                                data-testid={`reference-preview-${previewKey}`}
                                className={cn(
                                  "text-xs leading-relaxed text-gray-500",
                                  !previewExpanded && "line-clamp-2"
                                )}
                              >
                                {previewText}
                              </p>
                              {canTogglePreview ? (
                                <button
                                  type="button"
                                  data-testid={`reference-preview-toggle-${previewKey}`}
                                  className="mt-1 text-xs text-[#16A34A] hover:text-[#15803D]"
                                  onClick={(event) => {
                                    event.stopPropagation();
                                    togglePreview(previewKey);
                                  }}
                                >
                                  {previewExpanded ? "收起" : "展开全文"}
                                </button>
                              ) : null}
                            </div>
                          ) : null}
                        </li>
                      );
                    })}
                  </ul>
                </div>
              ) : null}
            </div>
          ) : null}
          {showFeedback ? (
            <FeedbackButtons
              messageId={message.id}
              feedback={message.feedback ?? null}
              feedbackReason={message.feedbackReason ?? null}
              feedbackComment={message.feedbackComment ?? null}
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
