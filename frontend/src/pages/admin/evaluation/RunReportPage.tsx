import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Loader2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getErrorMessage } from "@/utils/error";
import type { EvalRunReport } from "@/services/evaluationService";
import { getRunReport } from "@/services/evaluationService";

import { MetricCard } from "./components/MetricCard";
import { ScoreDistributionChart } from "./components/ScoreDistributionChart";
import { BadCaseTable } from "./components/BadCaseTable";

const STATUS_MAP: Record<string, { label: string; className: string }> = {
  PENDING: { label: "等待中", className: "bg-gray-100 text-gray-700" },
  RUNNING: { label: "运行中", className: "bg-blue-100 text-blue-700" },
  COMPLETED: { label: "已完成", className: "bg-green-100 text-green-700" },
  FAILED: { label: "失败", className: "bg-red-100 text-red-700" },
};

export function RunReportPage() {
  const { id: runId } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [report, setReport] = useState<EvalRunReport | null>(null);
  const [loading, setLoading] = useState(false);

  const fetchReport = useCallback(async () => {
    if (!runId) return;
    setLoading(true);
    try {
      const data = await getRunReport(runId);
      setReport(data);
    } catch (e) {
      toast.error(getErrorMessage(e, "加载评测报告失败"));
    } finally {
      setLoading(false);
    }
  }, [runId]);

  useEffect(() => {
    fetchReport();
  }, [fetchReport]);

  // Auto-refresh while RUNNING
  useEffect(() => {
    if (!report || report.run.status !== "RUNNING") return;
    const timer = setInterval(fetchReport, 5000);
    return () => clearInterval(timer);
  }, [report, fetchReport]);

  if (loading && !report) {
    return (
      <div className="flex items-center justify-center py-24 text-muted-foreground">
        <Loader2 className="h-5 w-5 animate-spin mr-2" />
        加载中...
      </div>
    );
  }

  if (!report) {
    return (
      <div className="text-center py-24 text-muted-foreground">评测报告不存在或加载失败</div>
    );
  }

  const status = STATUS_MAP[report.run.status] || STATUS_MAP.PENDING;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">评测报告</h1>
          <div className="flex items-center gap-2 mt-1 text-sm text-muted-foreground">
            <span>数据集: {report.run.datasetName}</span>
            <span>|</span>
            <span>
              进度: {report.run.completedCases}/{report.run.totalCases}
            </span>
            <span>|</span>
            <span>状态:</span>
            <Badge className={status.className}>{status.label}</Badge>
          </div>
          {report.run.errorMessage && (
            <p className="text-sm text-red-500 mt-1">{report.run.errorMessage}</p>
          )}
        </div>
        <Button variant="outline" onClick={() => navigate(-1)}>
          <ArrowLeft className="h-4 w-4 mr-1" />
          返回
        </Button>
      </div>

      {/* Stage 1: Retrieval */}
      <Card className="border-blue-200">
        <CardHeader className="bg-blue-50">
          <CardTitle className="text-blue-700">阶段一：检索评测</CardTitle>
        </CardHeader>
        <CardContent className="pt-6">
          <div className="grid grid-cols-4 gap-4">
            <MetricCard label="Hit Rate" value={report.hitRate} threshold={0.85} format="percent" />
            <MetricCard label="MRR" value={report.mrr} threshold={0.7} format="percent" />
            <MetricCard label="Recall" value={report.recall} threshold={0.75} format="percent" />
            <MetricCard
              label="Precision"
              value={report.precision}
              threshold={0.5}
              format="percent"
            />
          </div>
        </CardContent>
      </Card>

      {/* Stage 2: Generation */}
      <Card className="border-green-200">
        <CardHeader className="bg-green-50">
          <CardTitle className="text-green-700">阶段二：生成评测</CardTitle>
        </CardHeader>
        <CardContent className="pt-6">
          <div className="grid grid-cols-3 gap-4 mb-6">
            <MetricCard
              label="忠实度"
              value={report.faithfulness}
              threshold={4.0}
              format="score5"
            />
            <MetricCard
              label="相关性"
              value={report.relevancy}
              threshold={4.0}
              format="score5"
            />
            <MetricCard
              label="幻觉率"
              value={report.hallucinationRate}
              threshold={0.15}
              format="percent"
              reverse
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <ScoreDistributionChart
              title="忠实度分布"
              data={report.faithfulnessDistribution}
              color="#22c55e"
            />
            <ScoreDistributionChart
              title="相关性分布"
              data={report.relevancyDistribution}
              color="#3b82f6"
            />
          </div>
        </CardContent>
      </Card>

      {/* Stage 3: End-to-End */}
      <Card className="border-orange-200">
        <CardHeader className="bg-orange-50">
          <CardTitle className="text-orange-700">阶段三：端到端评测</CardTitle>
        </CardHeader>
        <CardContent className="pt-6">
          <div className="grid grid-cols-3 gap-4 mb-6">
            <MetricCard
              label="正确率均分"
              value={report.correctness}
              threshold={4.0}
              format="score5"
            />
            <MetricCard
              label="达标率(>=4分)"
              value={report.correctnessPassRate}
              threshold={0.8}
              format="percent"
            />
            <MetricCard
              label="兜底率"
              value={report.fallbackRate}
              threshold={0.1}
              format="percent"
              reverse
            />
          </div>
          {runId && <BadCaseTable runId={runId} />}
        </CardContent>
      </Card>
    </div>
  );
}
