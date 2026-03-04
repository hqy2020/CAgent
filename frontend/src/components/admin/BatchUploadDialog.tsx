import { useCallback, useEffect, useRef, useState } from "react";
import { X, Upload, CheckCircle2, XCircle, Loader2, FileText } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Progress } from "@/components/ui/progress";

import type { BatchUploadTask, BatchUploadCreatePayload } from "@/services/knowledgeService";
import { createBatchUpload, uploadBatchItem, startBatchProcess, getBatchProgress } from "@/services/knowledgeService";
import { toast } from "sonner";
import { getErrorMessage } from "@/utils/error";

interface BatchUploadDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  kbId: string;
  onComplete?: () => void;
}

type Stage = "select" | "uploading" | "processing" | "done";

const CHUNK_STRATEGY_OPTIONS = [
  { value: "fixed_size", label: "fixed_size" },
  { value: "structure_aware", label: "structure_aware" },
];

const PROCESS_MODE_OPTIONS = [
  { value: "chunk", label: "分块策略" },
  { value: "pipeline", label: "数据通道" },
];

const formatSize = (size: number) => {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
};

export function BatchUploadDialog({ open, onOpenChange, kbId, onComplete }: BatchUploadDialogProps) {
  const [stage, setStage] = useState<Stage>("select");
  const [files, setFiles] = useState<File[]>([]);
  const [processMode, setProcessMode] = useState("chunk");
  const [chunkStrategy, setChunkStrategy] = useState("structure_aware");
  const [chunkSize, setChunkSize] = useState<number | undefined>();
  const [overlapSize, setOverlapSize] = useState<number | undefined>();
  const [targetChars, setTargetChars] = useState<number | undefined>();
  const [maxChars, setMaxChars] = useState<number | undefined>();
  const [minChars, setMinChars] = useState<number | undefined>();
  const [overlapChars, setOverlapChars] = useState<number | undefined>();
  const [pipelineId, setPipelineId] = useState("");

  const [task, setTask] = useState<BatchUploadTask | null>(null);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const reset = useCallback(() => {
    setStage("select");
    setFiles([]);
    setProcessMode("chunk");
    setChunkStrategy("structure_aware");
    setChunkSize(undefined);
    setOverlapSize(undefined);
    setTargetChars(undefined);
    setMaxChars(undefined);
    setMinChars(undefined);
    setOverlapChars(undefined);
    setPipelineId("");
    setTask(null);
    setUploadProgress(0);
    setError(null);
    if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  }, []);

  useEffect(() => {
    if (!open) reset();
  }, [open, reset]);

  useEffect(() => {
    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
  }, []);

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(e.target.files || []);
    const combined = [...files, ...selected];
    if (combined.length > 50) {
      toast.error("单批最多上传 50 个文件");
      return;
    }
    setFiles(combined);
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const removeFile = (index: number) => {
    setFiles((prev) => prev.filter((_, i) => i !== index));
  };

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      const dropped = Array.from(e.dataTransfer.files);
      const combined = [...files, ...dropped];
      if (combined.length > 50) {
        toast.error("单批最多上传 50 个文件");
        return;
      }
      setFiles(combined);
    },
    [files]
  );

  const handleStart = async () => {
    if (files.length === 0) {
      toast.error("请选择至少一个文件");
      return;
    }

    try {
      setStage("uploading");
      setError(null);

      const payload: BatchUploadCreatePayload = {
        fileNames: files.map((f) => f.name),
        processMode,
        chunkStrategy: processMode === "chunk" ? chunkStrategy : undefined,
        pipelineId: processMode === "pipeline" ? pipelineId : undefined,
      };

      if (processMode === "chunk") {
        if (chunkStrategy === "fixed_size") {
          payload.chunkSize = chunkSize;
          payload.overlapSize = overlapSize;
        } else {
          payload.targetChars = targetChars;
          payload.maxChars = maxChars;
          payload.minChars = minChars;
          payload.overlapChars = overlapChars;
        }
      }

      const created = await createBatchUpload(kbId, payload);
      setTask(created);

      // 逐个上传文件
      for (let i = 0; i < files.length; i++) {
        const item = created.items[i];
        await uploadBatchItem(created.id, item.id, files[i]);
        setUploadProgress(i + 1);
      }

      // 触发入库处理
      setStage("processing");
      await startBatchProcess(created.id);

      // 开始轮询进度
      pollingRef.current = setInterval(async () => {
        try {
          const progress = await getBatchProgress(created.id);
          setTask(progress);

          if (progress.status === "completed" || progress.status === "partial_failed") {
            if (pollingRef.current) {
              clearInterval(pollingRef.current);
              pollingRef.current = null;
            }
            setStage("done");
          }
        } catch {
          // 轮询失败不中断
        }
      }, 3000);
    } catch (err) {
      setError(getErrorMessage(err, "批量上传失败"));
      setStage("done");
    }
  };

  const handleClose = () => {
    if (stage === "uploading" || stage === "processing") {
      return; // 处理中不允许关闭
    }
    if (stage === "done") {
      onComplete?.();
    }
    onOpenChange(false);
  };

  const totalCount = task?.totalCount ?? files.length;
  const processedCount = (task?.successCount ?? 0) + (task?.failedCount ?? 0);
  const uploadPercent = totalCount > 0 ? Math.round((uploadProgress / totalCount) * 100) : 0;
  const processPercent = totalCount > 0 ? Math.round((processedCount / totalCount) * 100) : 0;

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="max-w-2xl max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>批量上传文档</DialogTitle>
          <DialogDescription>
            {stage === "select" && "选择多个文件并配置分块参数，一次性批量入库"}
            {stage === "uploading" && "正在上传文件到服务器..."}
            {stage === "processing" && "正在处理文档入库..."}
            {stage === "done" && "批量上传完成"}
          </DialogDescription>
        </DialogHeader>

        {/* Stage: select */}
        {stage === "select" && (
          <div className="space-y-4">
            {/* 文件拖拽区域 */}
            <div
              className="border-2 border-dashed border-muted-foreground/25 rounded-lg p-6 text-center cursor-pointer hover:border-primary/50 transition-colors"
              onDragOver={(e) => e.preventDefault()}
              onDrop={handleDrop}
              onClick={() => fileInputRef.current?.click()}
            >
              <Upload className="mx-auto h-8 w-8 text-muted-foreground/50 mb-2" />
              <p className="text-sm text-muted-foreground">拖拽文件到此处，或点击选择文件</p>
              <p className="text-xs text-muted-foreground/60 mt-1">支持 PDF、Markdown、DOCX 等格式，单文件最大 200MB</p>
              <input
                ref={fileInputRef}
                type="file"
                multiple
                className="hidden"
                onChange={handleFileSelect}
              />
            </div>

            {/* 文件列表 */}
            {files.length > 0 && (
              <div className="border rounded-lg divide-y max-h-40 overflow-y-auto">
                {files.map((file, index) => (
                  <div key={`${file.name}-${index}`} className="flex items-center justify-between px-3 py-2 text-sm">
                    <div className="flex items-center gap-2 min-w-0 flex-1">
                      <FileText className="h-4 w-4 text-muted-foreground shrink-0" />
                      <span className="truncate">{file.name}</span>
                      <span className="text-muted-foreground/60 shrink-0">{formatSize(file.size)}</span>
                    </div>
                    <button type="button" onClick={() => removeFile(index)} className="text-muted-foreground hover:text-red-500 ml-2">
                      <X className="h-4 w-4" />
                    </button>
                  </div>
                ))}
              </div>
            )}

            <p className="text-xs text-muted-foreground">{files.length} 个文件已选择</p>

            {/* 分块配置 */}
            <div className="space-y-3">
              <Label>处理模式</Label>
              <Select value={processMode} onValueChange={setProcessMode}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PROCESS_MODE_OPTIONS.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value}>
                      {opt.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              {processMode === "chunk" && (
                <>
                  <Label>分块策略</Label>
                  <Select value={chunkStrategy} onValueChange={setChunkStrategy}>
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {CHUNK_STRATEGY_OPTIONS.map((opt) => (
                        <SelectItem key={opt.value} value={opt.value}>
                          {opt.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>

                  {chunkStrategy === "fixed_size" && (
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <Label className="text-xs">块大小</Label>
                        <Input type="number" placeholder="512" value={chunkSize ?? ""} onChange={(e) => setChunkSize(e.target.value ? Number(e.target.value) : undefined)} />
                      </div>
                      <div>
                        <Label className="text-xs">重叠大小</Label>
                        <Input type="number" placeholder="128" value={overlapSize ?? ""} onChange={(e) => setOverlapSize(e.target.value ? Number(e.target.value) : undefined)} />
                      </div>
                    </div>
                  )}

                  {chunkStrategy === "structure_aware" && (
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <Label className="text-xs">理想块大小</Label>
                        <Input type="number" placeholder="1400" value={targetChars ?? ""} onChange={(e) => setTargetChars(e.target.value ? Number(e.target.value) : undefined)} />
                      </div>
                      <div>
                        <Label className="text-xs">块上限</Label>
                        <Input type="number" placeholder="1800" value={maxChars ?? ""} onChange={(e) => setMaxChars(e.target.value ? Number(e.target.value) : undefined)} />
                      </div>
                      <div>
                        <Label className="text-xs">块下限</Label>
                        <Input type="number" placeholder="600" value={minChars ?? ""} onChange={(e) => setMinChars(e.target.value ? Number(e.target.value) : undefined)} />
                      </div>
                      <div>
                        <Label className="text-xs">重叠大小</Label>
                        <Input type="number" placeholder="0" value={overlapChars ?? ""} onChange={(e) => setOverlapChars(e.target.value ? Number(e.target.value) : undefined)} />
                      </div>
                    </div>
                  )}
                </>
              )}

              {processMode === "pipeline" && (
                <div>
                  <Label>数据通道 ID</Label>
                  <Input placeholder="输入 Pipeline ID" value={pipelineId} onChange={(e) => setPipelineId(e.target.value)} />
                </div>
              )}
            </div>
          </div>
        )}

        {/* Stage: uploading */}
        {stage === "uploading" && (
          <div className="space-y-4 py-4">
            <div className="flex items-center gap-3">
              <Loader2 className="h-5 w-5 animate-spin text-primary" />
              <span className="text-sm">
                正在上传文件 ({uploadProgress}/{totalCount})
              </span>
            </div>
            <Progress value={uploadPercent} />
            <p className="text-xs text-muted-foreground">请勿关闭页面，大文件上传可能需要较长时间</p>
          </div>
        )}

        {/* Stage: processing */}
        {stage === "processing" && (
          <div className="space-y-4 py-4">
            <div className="flex items-center gap-3">
              <Loader2 className="h-5 w-5 animate-spin text-primary" />
              <span className="text-sm">
                正在处理入库 ({processedCount}/{totalCount})
              </span>
            </div>
            <Progress value={processPercent} />
            {task && task.failedCount > 0 && (
              <p className="text-xs text-red-500">{task.failedCount} 个文件处理失败</p>
            )}
          </div>
        )}

        {/* Stage: done */}
        {stage === "done" && (
          <div className="space-y-4 py-4">
            {error ? (
              <div className="flex items-center gap-2 text-red-500">
                <XCircle className="h-5 w-5" />
                <span className="text-sm">{error}</span>
              </div>
            ) : (
              <>
                <div className="flex items-center gap-2">
                  {task?.status === "completed" ? (
                    <CheckCircle2 className="h-5 w-5 text-emerald-500" />
                  ) : (
                    <XCircle className="h-5 w-5 text-amber-500" />
                  )}
                  <span className="text-sm font-medium">
                    {task?.status === "completed" ? "全部处理完成" : "处理完成（部分失败）"}
                  </span>
                </div>
                <div className="grid grid-cols-3 gap-2 text-sm">
                  <div className="text-center p-2 bg-muted rounded">
                    <div className="font-medium">{task?.totalCount ?? 0}</div>
                    <div className="text-xs text-muted-foreground">总数</div>
                  </div>
                  <div className="text-center p-2 bg-emerald-500/10 rounded">
                    <div className="font-medium text-emerald-600">{task?.successCount ?? 0}</div>
                    <div className="text-xs text-muted-foreground">成功</div>
                  </div>
                  <div className="text-center p-2 bg-red-500/10 rounded">
                    <div className="font-medium text-red-600">{task?.failedCount ?? 0}</div>
                    <div className="text-xs text-muted-foreground">失败</div>
                  </div>
                </div>

                {/* 失败项详情 */}
                {task?.items?.filter((i) => i.status === "failed").map((item) => (
                  <div key={item.id} className="flex items-start gap-2 text-sm border rounded p-2">
                    <XCircle className="h-4 w-4 text-red-500 mt-0.5 shrink-0" />
                    <div className="min-w-0">
                      <p className="font-medium truncate">{item.fileName}</p>
                      <p className="text-xs text-muted-foreground">{item.errorMessage || "未知错误"}</p>
                    </div>
                  </div>
                ))}
              </>
            )}
          </div>
        )}

        <DialogFooter>
          {stage === "select" && (
            <>
              <Button variant="outline" onClick={() => onOpenChange(false)}>
                取消
              </Button>
              <Button onClick={handleStart} disabled={files.length === 0}>
                开始上传 ({files.length})
              </Button>
            </>
          )}
          {(stage === "uploading" || stage === "processing") && (
            <p className="text-xs text-muted-foreground">处理中，请耐心等待...</p>
          )}
          {stage === "done" && (
            <Button onClick={handleClose}>关闭</Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
