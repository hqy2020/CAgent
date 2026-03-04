import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import type { ReferenceItem } from "@/types";

interface ReferenceDetailDialogProps {
  reference: ReferenceItem | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function ReferenceDetailDialog({
  reference,
  open,
  onOpenChange,
}: ReferenceDetailDialogProps) {
  if (!reference) return null;

  const chunks = reference.chunks ?? [];

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
