import { useCallback, useEffect, useState } from "react";
import { ChevronDown, ChevronRight, Loader2 } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { getErrorMessage } from "@/utils/error";
import type { EvalRunResult } from "@/services/evaluationService";
import { getBadCases } from "@/services/evaluationService";

const ROOT_CAUSE_MAP: Record<string, { label: string; className: string }> = {
  RETRIEVAL: { label: "检索失败", className: "bg-red-100 text-red-700" },
  GENERATION: { label: "生成幻觉", className: "bg-yellow-100 text-yellow-700" },
  KNOWLEDGE_GAP: { label: "知识缺失", className: "bg-gray-100 text-gray-700" },
};

function formatScore(value: number | null): string {
  if (value == null) return "\u2014";
  return value.toFixed(2);
}

type BadCaseTableProps = {
  runId: string;
};

export function BadCaseTable({ runId }: BadCaseTableProps) {
  const [badCases, setBadCases] = useState<EvalRunResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  const fetchBadCases = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getBadCases(runId);
      setBadCases(data);
    } catch (e) {
      toast.error(getErrorMessage(e, "加载 Bad Cases 失败"));
    } finally {
      setLoading(false);
    }
  }, [runId]);

  useEffect(() => {
    fetchBadCases();
  }, [fetchBadCases]);

  const toggleExpand = (id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-8 text-muted-foreground">
        <Loader2 className="h-5 w-5 animate-spin mr-2" />
        加载 Bad Cases...
      </div>
    );
  }

  if (badCases.length === 0) {
    return (
      <div className="text-center py-8 text-muted-foreground">
        没有 Bad Cases，所有用例表现良好
      </div>
    );
  }

  return (
    <div>
      <h3 className="text-sm font-semibold mb-3">Bad Cases ({badCases.length})</h3>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-[30px]" />
            <TableHead>问题</TableHead>
            <TableHead>期望答案</TableHead>
            <TableHead>实际答案</TableHead>
            <TableHead className="w-[60px]">命中</TableHead>
            <TableHead className="w-[60px]">忠实度</TableHead>
            <TableHead className="w-[60px]">相关性</TableHead>
            <TableHead className="w-[60px]">正确率</TableHead>
            <TableHead className="w-[80px]">归因</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {badCases.map((bc) => {
            const isExpanded = expandedIds.has(bc.id);
            const rootCause = bc.rootCause ? ROOT_CAUSE_MAP[bc.rootCause] : null;
            return (
              <>
                <TableRow
                  key={bc.id}
                  className="cursor-pointer hover:bg-muted/50"
                  onClick={() => toggleExpand(bc.id)}
                >
                  <TableCell className="px-2">
                    {isExpanded ? (
                      <ChevronDown className="h-4 w-4" />
                    ) : (
                      <ChevronRight className="h-4 w-4" />
                    )}
                  </TableCell>
                  <TableCell className="max-w-[200px]">
                    <div className="truncate text-sm">{bc.query}</div>
                  </TableCell>
                  <TableCell className="max-w-[200px]">
                    <div className="truncate text-sm text-muted-foreground">
                      {bc.expectedAnswer || "\u2014"}
                    </div>
                  </TableCell>
                  <TableCell className="max-w-[200px]">
                    <div className="truncate text-sm text-muted-foreground">
                      {bc.generatedAnswer || "\u2014"}
                    </div>
                  </TableCell>
                  <TableCell className="text-sm">
                    {bc.hitRate != null ? (bc.hitRate > 0 ? "是" : "否") : "\u2014"}
                  </TableCell>
                  <TableCell className="text-sm">{formatScore(bc.faithfulnessScore)}</TableCell>
                  <TableCell className="text-sm">{formatScore(bc.relevancyScore)}</TableCell>
                  <TableCell className="text-sm">{formatScore(bc.correctnessScore)}</TableCell>
                  <TableCell>
                    {rootCause ? (
                      <Badge className={rootCause.className}>{rootCause.label}</Badge>
                    ) : (
                      "\u2014"
                    )}
                  </TableCell>
                </TableRow>
                {isExpanded && (
                  <TableRow key={`${bc.id}-detail`}>
                    <TableCell colSpan={9} className="bg-muted/30 px-6 py-4">
                      <div className="space-y-3 text-sm">
                        {bc.faithfulnessReason && (
                          <div>
                            <span className="font-medium text-green-700">忠实度评判：</span>
                            <span className="text-muted-foreground ml-2">
                              {bc.faithfulnessReason}
                            </span>
                          </div>
                        )}
                        {bc.relevancyReason && (
                          <div>
                            <span className="font-medium text-blue-700">相关性评判：</span>
                            <span className="text-muted-foreground ml-2">
                              {bc.relevancyReason}
                            </span>
                          </div>
                        )}
                        {bc.correctnessReason && (
                          <div>
                            <span className="font-medium text-orange-700">正确率评判：</span>
                            <span className="text-muted-foreground ml-2">
                              {bc.correctnessReason}
                            </span>
                          </div>
                        )}
                        {!bc.faithfulnessReason &&
                          !bc.relevancyReason &&
                          !bc.correctnessReason && (
                            <div className="text-muted-foreground">暂无评判理由</div>
                          )}
                      </div>
                    </TableCell>
                  </TableRow>
                )}
              </>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
}
