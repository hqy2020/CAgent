import { ExternalLink } from "lucide-react";

import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import {
  DialogDescription,
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useAuthStore } from "@/stores/authStore";
import type { ReferenceItem } from "@/types";

interface ReferenceDetailDialogProps {
  reference: ReferenceItem | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
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

export function ReferenceDetailDialog({
  reference,
  open,
  onOpenChange,
}: ReferenceDetailDialogProps) {
  const isAdmin = useAuthStore((state) => state.user?.role === "admin");

  if (!reference) return null;

  const chunks = reference.chunks ?? [];
  const detailHref = buildReferenceDetailHref(reference, isAdmin);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-3xl max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 pr-8">
            <span className="truncate">{reference.documentName || "未知文档"}</span>
            {reference.score != null && (
              <span className="shrink-0 rounded-full bg-[#BBF7D0] px-2 py-0.5 text-xs font-normal text-[#16A34A]">
                {Math.round(reference.score * 100)}%
              </span>
            )}
          </DialogTitle>
          {reference.knowledgeBaseName && (
            <p className="text-sm text-muted-foreground">
              来自：{reference.knowledgeBaseName}
            </p>
          )}
          {detailHref ? (
            <a
              href={detailHref}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-sm text-[#16A34A] hover:text-[#15803D] hover:underline"
            >
              打开完整文档
              <ExternalLink className="h-3.5 w-3.5" />
            </a>
          ) : null}
          <DialogDescription className="sr-only">
            参考文档片段详情与完整文档跳转入口
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {chunks.length === 0 && reference.textPreview && (
            <div className="rounded-lg border border-border/60 bg-muted/30 p-4">
              <p className="text-sm text-muted-foreground">{reference.textPreview}</p>
            </div>
          )}
          {chunks.map((chunk, index) => (
            <div key={index}>
              {index > 0 && <hr className="mb-4 border-border/40" />}
              <div className="flex items-center gap-2 mb-2">
                <span className="text-xs font-medium text-muted-foreground">
                  片段 {index + 1}
                </span>
                {chunk.score != null && (
                  <span className="rounded bg-[#BBF7D0] px-1.5 py-0.5 text-xs text-[#16A34A]">
                    {Math.round(chunk.score * 100)}%
                  </span>
                )}
              </div>
              <div className="rounded-lg border border-border/60 bg-muted/30 p-4">
                <MarkdownRenderer content={chunk.content || ""} />
              </div>
            </div>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  );
}
