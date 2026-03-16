import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { RefreshCw, FileText, Loader2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { getErrorMessage } from "@/utils/error";
import type { EvalRun } from "@/services/evaluationService";
import { listRuns } from "@/services/evaluationService";

const STATUS_MAP: Record<string, { label: string; variant: string; className: string }> = {
  PENDING: { label: "等待中", variant: "secondary", className: "bg-gray-100 text-gray-700" },
  RUNNING: { label: "运行中", variant: "default", className: "bg-blue-100 text-blue-700" },
  COMPLETED: { label: "已完成", variant: "default", className: "bg-green-100 text-green-700" },
  FAILED: { label: "失败", variant: "destructive", className: "bg-red-100 text-red-700" },
};

function formatMetric(value: number | null, format: "percent" | "score5"): string {
  if (value == null) return "—";
  if (format === "percent") return `${(value * 100).toFixed(1)}%`;
  return `${value.toFixed(2)}/5.0`;
}

export function RunListTab() {
  const navigate = useNavigate();
  const [runs, setRuns] = useState<EvalRun[]>([]);
  const [loading, setLoading] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchRuns = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listRuns();
      setRuns(data);
    } catch (e) {
      toast.error(getErrorMessage(e, "加载评测记录失败"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchRuns();
  }, [fetchRuns]);

  // Auto-refresh when any run is RUNNING
  useEffect(() => {
    const hasRunning = runs.some((r) => r.status === "RUNNING");
    if (hasRunning) {
      timerRef.current = setInterval(fetchRuns, 5000);
    } else if (timerRef.current) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [runs, fetchRuns]);

  return (
    <Card>
      <CardContent className="pt-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold">评测记录</h2>
          <Button variant="outline" size="sm" onClick={fetchRuns} disabled={loading}>
            <RefreshCw className={`h-4 w-4 mr-1 ${loading ? "animate-spin" : ""}`} />
            刷新
          </Button>
        </div>

        {loading && runs.length === 0 ? (
          <div className="flex items-center justify-center py-12 text-muted-foreground">
            <Loader2 className="h-5 w-5 animate-spin mr-2" />
            加载中...
          </div>
        ) : runs.length === 0 ? (
          <div className="text-center py-12 text-muted-foreground">
            暂无评测记录，请先在数据集中触发评测
          </div>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>数据集</TableHead>
                  <TableHead className="w-[80px]">状态</TableHead>
                  <TableHead className="w-[80px]">进度</TableHead>
                  <TableHead className="w-[80px]">Hit Rate</TableHead>
                  <TableHead className="w-[70px]">MRR</TableHead>
                  <TableHead className="w-[70px]">忠实度</TableHead>
                  <TableHead className="w-[70px]">正确率</TableHead>
                  <TableHead className="w-[80px]">Bad Cases</TableHead>
                  <TableHead className="w-[160px]">开始时间</TableHead>
                  <TableHead className="w-[80px]">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {runs.map((run) => {
                  const status = STATUS_MAP[run.status] || STATUS_MAP.PENDING;
                  return (
                    <TableRow key={run.id}>
                      <TableCell className="font-medium">{run.datasetName}</TableCell>
                      <TableCell>
                        <Badge className={status.className}>{status.label}</Badge>
                      </TableCell>
                      <TableCell className="text-sm">
                        {run.completedCases}/{run.totalCases}
                      </TableCell>
                      <TableCell className="text-sm">
                        {formatMetric(run.avgHitRate, "percent")}
                      </TableCell>
                      <TableCell className="text-sm">
                        {formatMetric(run.avgMrr, "percent")}
                      </TableCell>
                      <TableCell className="text-sm">
                        {formatMetric(run.avgFaithfulness, "score5")}
                      </TableCell>
                      <TableCell className="text-sm">
                        {formatMetric(run.avgCorrectness, "score5")}
                      </TableCell>
                      <TableCell className="text-sm">{run.badCaseCount}</TableCell>
                      <TableCell className="text-sm text-muted-foreground">
                        {run.startedAt || "—"}
                      </TableCell>
                      <TableCell>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => navigate(`/admin/evaluation/runs/${run.id}`)}
                        >
                          <FileText className="h-4 w-4 mr-1" />
                          查看报告
                        </Button>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
