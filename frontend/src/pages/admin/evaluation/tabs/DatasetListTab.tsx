import { forwardRef, useCallback, useEffect, useImperativeHandle, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Trash2, Eye, Loader2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { getErrorMessage } from "@/utils/error";
import type { EvalDataset } from "@/services/evaluationService";
import { createDataset, listDatasets, deleteDataset } from "@/services/evaluationService";

export type DatasetListTabRef = {
  refresh: () => void;
  openCreate: () => void;
};

export const DatasetListTab = forwardRef<DatasetListTabRef>(function DatasetListTab(_props, ref) {
  const navigate = useNavigate();
  const [datasets, setDatasets] = useState<EvalDataset[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<EvalDataset | null>(null);
  const [deleting, setDeleting] = useState(false);

  const fetchDatasets = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listDatasets();
      setDatasets(data);
    } catch (e) {
      toast.error(getErrorMessage(e, "加载数据集列表失败"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchDatasets();
  }, [fetchDatasets]);

  useImperativeHandle(ref, () => ({
    refresh: fetchDatasets,
    openCreate: () => setCreateOpen(true),
  }));

  const handleCreate = async () => {
    if (!name.trim()) {
      toast.error("请输入数据集名称");
      return;
    }
    setCreating(true);
    try {
      await createDataset({ name: name.trim(), description: description.trim() || undefined });
      toast.success("数据集创建成功");
      setCreateOpen(false);
      setName("");
      setDescription("");
      fetchDatasets();
    } catch (e) {
      toast.error(getErrorMessage(e, "创建数据集失败"));
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteDataset(deleteTarget.id);
      toast.success("数据集已删除");
      setDeleteTarget(null);
      fetchDatasets();
    } catch (e) {
      toast.error(getErrorMessage(e, "删除数据集失败"));
    } finally {
      setDeleting(false);
    }
  };

  return (
    <>
      <Card>
        <CardContent className="pt-6">
          {loading && datasets.length === 0 ? (
            <div className="flex items-center justify-center py-12 text-muted-foreground">
              <Loader2 className="h-5 w-5 animate-spin mr-2" />
              加载中...
            </div>
          ) : datasets.length === 0 ? (
            <div className="text-center py-12 text-muted-foreground">
              暂无数据集，请点击「新建数据集」创建
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>名称</TableHead>
                  <TableHead>描述</TableHead>
                  <TableHead className="w-[80px]">用例数</TableHead>
                  <TableHead className="w-[160px]">创建时间</TableHead>
                  <TableHead className="w-[120px]">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {datasets.map((ds) => (
                  <TableRow key={ds.id}>
                    <TableCell className="font-medium">{ds.name}</TableCell>
                    <TableCell className="text-muted-foreground max-w-[300px] truncate">
                      {ds.description || "—"}
                    </TableCell>
                    <TableCell>{ds.caseCount}</TableCell>
                    <TableCell className="text-sm text-muted-foreground">
                      {ds.createTime}
                    </TableCell>
                    <TableCell>
                      <div className="flex gap-1">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => navigate(`/admin/evaluation/datasets/${ds.id}`)}
                        >
                          <Eye className="h-4 w-4 mr-1" />
                          查看
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive"
                          onClick={() => setDeleteTarget(ds)}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Create Dialog */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>新建数据集</DialogTitle>
            <DialogDescription>创建一个新的评测数据集，用于管理评测用例。</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div className="space-y-2">
              <label className="text-sm font-medium">名称</label>
              <Input
                placeholder="例如：Spring 框架 QA 评测集"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">描述</label>
              <Textarea
                placeholder="数据集的用途描述（可选）"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={3}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCreateOpen(false)}>
              取消
            </Button>
            <Button onClick={handleCreate} disabled={creating}>
              {creating && <Loader2 className="h-4 w-4 mr-1 animate-spin" />}
              创建
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation */}
      <AlertDialog open={!!deleteTarget} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除</AlertDialogTitle>
            <AlertDialogDescription>
              确定要删除数据集「{deleteTarget?.name}」吗？此操作不可撤销，所有关联的用例也将被删除。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleting}>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} disabled={deleting}>
              {deleting && <Loader2 className="h-4 w-4 mr-1 animate-spin" />}
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
});
