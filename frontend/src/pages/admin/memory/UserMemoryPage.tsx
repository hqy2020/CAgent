import { useEffect, useState } from "react";
import { toast } from "sonner";
import {
  listUserMemories,
  updateUserMemory,
  archiveUserMemory,
  deleteUserMemory,
  type UserMemory
} from "@/services/memoryService";
import { useAuthStore } from "@/stores/authStore";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";

const TYPE_LABELS: Record<string, string> = {
  PINNED: "用户保存",
  INSIGHT: "自动提取",
  DIGEST: "会话摘要"
};

const TYPE_COLORS: Record<string, string> = {
  PINNED: "bg-blue-100 text-blue-700",
  INSIGHT: "bg-green-100 text-green-700",
  DIGEST: "bg-amber-100 text-amber-700"
};

export function UserMemoryPage() {
  const { user } = useAuthStore();
  const [memories, setMemories] = useState<UserMemory[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<string>("");
  const [editingMemory, setEditingMemory] = useState<UserMemory | null>(null);
  const [editContent, setEditContent] = useState("");
  const [saving, setSaving] = useState(false);

  const userId = user?.id ? Number(user.id) : 0;

  const fetchMemories = async () => {
    if (!userId) return;
    setLoading(true);
    try {
      const data = await listUserMemories(userId, filter || undefined);
      setMemories(data);
    } catch {
      toast.error("加载记忆列表失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchMemories();
  }, [userId, filter]);

  const handleEdit = (memory: UserMemory) => {
    setEditingMemory(memory);
    setEditContent(memory.content);
  };

  const handleSave = async () => {
    if (!editingMemory) return;
    setSaving(true);
    try {
      await updateUserMemory(editingMemory.id, editContent);
      toast.success("记忆已更新");
      setEditingMemory(null);
      fetchMemories();
    } catch {
      toast.error("更新失败");
    } finally {
      setSaving(false);
    }
  };

  const handleArchive = async (id: string) => {
    try {
      await archiveUserMemory(id);
      toast.success("记忆已归档");
      fetchMemories();
    } catch {
      toast.error("归档失败");
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await deleteUserMemory(id);
      toast.success("记忆已删除");
      fetchMemories();
    } catch {
      toast.error("删除失败");
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-slate-900">用户记忆</h2>
          <p className="mt-1 text-sm text-slate-500">
            管理跨会话的用户长期记忆，包括用户保存、自动提取和会话摘要
          </p>
        </div>
      </div>

      <div className="flex items-center gap-3">
        <select
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          className="rounded-md border border-slate-200 bg-white px-3 py-2 text-sm"
        >
          <option value="">全部类型</option>
          <option value="PINNED">用户保存</option>
          <option value="INSIGHT">自动提取</option>
          <option value="DIGEST">会话摘要</option>
        </select>
        <span className="text-sm text-slate-500">
          共 {memories.length} 条记忆
        </span>
      </div>

      {loading ? (
        <div className="py-12 text-center text-sm text-slate-400">加载中...</div>
      ) : memories.length === 0 ? (
        <div className="py-12 text-center text-sm text-slate-400">暂无记忆数据</div>
      ) : (
        <div className="space-y-3">
          {memories.map((memory) => (
            <div
              key={memory.id}
              className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm"
            >
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0 flex-1">
                  <div className="mb-2 flex items-center gap-2">
                    <span
                      className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${
                        TYPE_COLORS[memory.memoryType] || "bg-slate-100 text-slate-600"
                      }`}
                    >
                      {TYPE_LABELS[memory.memoryType] || memory.memoryType}
                    </span>
                    <span className="text-xs text-slate-400">
                      {new Date(memory.createTime).toLocaleString()}
                    </span>
                    <span className="text-xs text-slate-400">
                      权重 {memory.weight}
                    </span>
                  </div>
                  <p className="whitespace-pre-wrap text-sm text-slate-700">
                    {memory.content}
                  </p>
                </div>
                <div className="flex gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleEdit(memory)}
                  >
                    编辑
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleArchive(memory.id)}
                  >
                    归档
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="text-rose-600 hover:text-rose-700"
                    onClick={() => handleDelete(memory.id)}
                  >
                    删除
                  </Button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      <Dialog open={editingMemory !== null} onOpenChange={(open) => !open && setEditingMemory(null)}>
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle>编辑记忆</DialogTitle>
          </DialogHeader>
          <textarea
            value={editContent}
            onChange={(e) => setEditContent(e.target.value)}
            className="min-h-[120px] w-full rounded-md border border-slate-200 p-3 text-sm"
            placeholder="记忆内容"
          />
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditingMemory(null)}>
              取消
            </Button>
            <Button onClick={handleSave} disabled={saving}>
              {saving ? "保存中..." : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
