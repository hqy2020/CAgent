import { useEffect, useState } from "react";
import { toast } from "sonner";
import {
  getUserProfile,
  updateUserProfile,
  type UserProfile
} from "@/services/memoryService";
import { useAuthStore } from "@/stores/authStore";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export function UserProfilePage() {
  const { user } = useAuthStore();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState({
    displayName: "",
    occupation: "",
    interests: "",
    preferences: "",
    facts: "",
    summary: ""
  });

  const userId = user?.id ? Number(user.id) : 0;

  const fetchProfile = async () => {
    if (!userId) return;
    setLoading(true);
    try {
      const data = await getUserProfile(userId);
      setProfile(data);
      setForm({
        displayName: data.displayName || "",
        occupation: data.occupation || "",
        interests: data.interests || "",
        preferences: data.preferences || "",
        facts: data.facts || "",
        summary: data.summary || ""
      });
    } catch {
      toast.error("加载用户画像失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchProfile();
  }, [userId]);

  const handleSave = async () => {
    setSaving(true);
    try {
      await updateUserProfile(userId, form);
      toast.success("画像已更新");
      setEditing(false);
      fetchProfile();
    } catch {
      toast.error("更新失败");
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="py-12 text-center text-sm text-slate-400">加载中...</div>;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-slate-900">用户画像</h2>
          <p className="mt-1 text-sm text-slate-500">
            查看和编辑用户画像信息，画像会在每次会话后由 LLM 自动更新
          </p>
        </div>
        <div className="flex gap-2">
          {editing ? (
            <>
              <Button variant="outline" onClick={() => setEditing(false)}>
                取消
              </Button>
              <Button onClick={handleSave} disabled={saving}>
                {saving ? "保存中..." : "保存"}
              </Button>
            </>
          ) : (
            <Button onClick={() => setEditing(true)}>编辑</Button>
          )}
        </div>
      </div>

      {profile && (
        <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
          <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">称呼</label>
              {editing ? (
                <Input
                  value={form.displayName}
                  onChange={(e) => setForm({ ...form, displayName: e.target.value })}
                />
              ) : (
                <p className="text-sm text-slate-600">{profile.displayName || "-"}</p>
              )}
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">职业</label>
              {editing ? (
                <Input
                  value={form.occupation}
                  onChange={(e) => setForm({ ...form, occupation: e.target.value })}
                />
              ) : (
                <p className="text-sm text-slate-600">{profile.occupation || "-"}</p>
              )}
            </div>
            <div className="space-y-2 md:col-span-2">
              <label className="text-sm font-medium text-slate-700">兴趣标签 (JSON 数组)</label>
              {editing ? (
                <textarea
                  value={form.interests}
                  onChange={(e) => setForm({ ...form, interests: e.target.value })}
                  className="w-full rounded-md border border-slate-200 p-3 text-sm"
                  rows={2}
                />
              ) : (
                <p className="whitespace-pre-wrap text-sm text-slate-600">
                  {profile.interests || "-"}
                </p>
              )}
            </div>
            <div className="space-y-2 md:col-span-2">
              <label className="text-sm font-medium text-slate-700">偏好设置 (JSON 对象)</label>
              {editing ? (
                <textarea
                  value={form.preferences}
                  onChange={(e) => setForm({ ...form, preferences: e.target.value })}
                  className="w-full rounded-md border border-slate-200 p-3 text-sm"
                  rows={3}
                />
              ) : (
                <p className="whitespace-pre-wrap text-sm text-slate-600">
                  {profile.preferences || "-"}
                </p>
              )}
            </div>
            <div className="space-y-2 md:col-span-2">
              <label className="text-sm font-medium text-slate-700">已知事实 (JSON 数组)</label>
              {editing ? (
                <textarea
                  value={form.facts}
                  onChange={(e) => setForm({ ...form, facts: e.target.value })}
                  className="w-full rounded-md border border-slate-200 p-3 text-sm"
                  rows={3}
                />
              ) : (
                <p className="whitespace-pre-wrap text-sm text-slate-600">
                  {profile.facts || "-"}
                </p>
              )}
            </div>
            <div className="space-y-2 md:col-span-2">
              <label className="text-sm font-medium text-slate-700">画像摘要</label>
              {editing ? (
                <textarea
                  value={form.summary}
                  onChange={(e) => setForm({ ...form, summary: e.target.value })}
                  className="w-full rounded-md border border-slate-200 p-3 text-sm"
                  rows={3}
                />
              ) : (
                <p className="whitespace-pre-wrap text-sm text-slate-600">
                  {profile.summary || "-"}
                </p>
              )}
            </div>
          </div>
          <div className="mt-4 flex items-center gap-4 text-xs text-slate-400">
            <span>版本 v{profile.version}</span>
            <span>更新于 {new Date(profile.updateTime).toLocaleString()}</span>
          </div>
        </div>
      )}
    </div>
  );
}
