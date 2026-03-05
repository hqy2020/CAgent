import type { ReactNode } from "react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Pencil, Save, X, Download } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import type { ConfigGroup, ConfigItem } from "@/services/settingsService";
import { getEditableConfigs, updateConfigGroup, initConfigFromYaml } from "@/services/settingsService";
import { getErrorMessage } from "@/utils/error";

// ─── 分组元数据 ───

const GROUP_META: Record<string, { title: string; description: string }> = {
  "rag.default": { title: "RAG 默认配置", description: "向量空间与检索基础参数" },
  "rag.query-rewrite": { title: "查询改写", description: "历史上下文压缩与改写策略" },
  "rag.prompt-progressive": { title: "渐进式披露", description: "控制 core/场景/按需规则层的拼装策略" },
  "rag.rate-limit": { title: "全局限流", description: "并发与租约控制" },
  "rag.memory": { title: "记忆管理", description: "摘要与上下文保留策略" },
  "ai.selection": { title: "模型选择策略", description: "熔断与选择阈值" },
  "ai.stream": { title: "流式响应", description: "输出分片大小" },
};

// ─── InfoItem ───

function InfoItem({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-slate-200/70 bg-white px-4 py-3">
      <span className="text-xs text-slate-500">{label}</span>
      <div className="text-sm font-medium text-slate-800">{value}</div>
    </div>
  );
}

const BoolBadge = ({ value }: { value: string }) => {
  const isTrue = value === "true" || value === "1";
  return <Badge variant={isTrue ? "default" : "outline"}>{isTrue ? "启用" : "禁用"}</Badge>;
};

// ─── EditableCard ───

function EditableCard({
  group,
  items,
  onSave,
}: {
  group: string;
  items: ConfigItem[];
  onSave: (group: string, values: Record<string, string>) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [formValues, setFormValues] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const meta = GROUP_META[group] || { title: group, description: "" };

  const startEdit = () => {
    const values: Record<string, string> = {};
    items.forEach((item) => {
      values[item.key] = item.value ?? "";
    });
    setFormValues(values);
    setEditing(true);
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      await onSave(group, formValues);
      setEditing(false);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle>{meta.title}</CardTitle>
            <CardDescription>{meta.description}</CardDescription>
          </div>
          <div className="flex gap-2">
            {editing ? (
              <>
                <Button variant="outline" size="sm" onClick={() => setEditing(false)} disabled={saving}>
                  <X className="mr-1 h-3.5 w-3.5" /> 取消
                </Button>
                <Button size="sm" onClick={handleSave} disabled={saving}>
                  <Save className="mr-1 h-3.5 w-3.5" /> {saving ? "保存中..." : "保存"}
                </Button>
              </>
            ) : (
              <Button variant="outline" size="sm" onClick={startEdit}>
                <Pencil className="mr-1 h-3.5 w-3.5" /> 编辑
              </Button>
            )}
          </div>
        </div>
      </CardHeader>
      <CardContent className="grid gap-4 md:grid-cols-3">
        {items.map((item) => {
          if (editing) {
            const isBool = item.valueType === "BOOLEAN";
            if (isBool) {
              const checked = formValues[item.key] === "true" || formValues[item.key] === "1";
              return (
                <div key={item.key} className="flex flex-col gap-1 rounded-lg border border-indigo-200 bg-indigo-50/30 px-4 py-3">
                  <span className="text-xs text-slate-500">{item.description || item.key}</span>
                  <div className="flex items-center gap-2 pt-1">
                    <Checkbox
                      id={`${group}-${item.key}`}
                      checked={checked}
                      onCheckedChange={(v) =>
                        setFormValues((prev) => ({ ...prev, [item.key]: v ? "true" : "false" }))
                      }
                    />
                    <label htmlFor={`${group}-${item.key}`} className="text-sm">
                      {checked ? "启用" : "禁用"}
                    </label>
                  </div>
                </div>
              );
            }
            return (
              <div key={item.key} className="flex flex-col gap-1 rounded-lg border border-indigo-200 bg-indigo-50/30 px-4 py-3">
                <span className="text-xs text-slate-500">{item.description || item.key}</span>
                <Input
                  className="mt-1 h-8 text-sm"
                  type={item.valueType === "INTEGER" || item.valueType === "LONG" ? "number" : "text"}
                  value={formValues[item.key] ?? ""}
                  onChange={(e) =>
                    setFormValues((prev) => ({ ...prev, [item.key]: e.target.value }))
                  }
                />
              </div>
            );
          }

          // 只读模式
          const displayValue =
            item.valueType === "BOOLEAN" ? (
              <BoolBadge value={item.value} />
            ) : (
              item.value || "-"
            );
          return <InfoItem key={item.key} label={item.description || item.key} value={displayValue} />;
        })}
      </CardContent>
    </Card>
  );
}

// ─── 主页面 ───

export function SystemSettingsPage() {
  const [groups, setGroups] = useState<ConfigGroup[]>([]);
  const [loading, setLoading] = useState(true);

  const loadConfigs = async () => {
    try {
      setLoading(true);
      const data = await getEditableConfigs();
      setGroups(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载系统配置失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadConfigs();
  }, []);

  const handleSave = async (group: string, values: Record<string, string>) => {
    try {
      await updateConfigGroup(group, values);
      toast.success("配置已保存，运行时已生效");
      await loadConfigs();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
      throw error;
    }
  };

  const handleInitFromYaml = async () => {
    try {
      await initConfigFromYaml();
      toast.success("初始化完成");
      await loadConfigs();
    } catch (error) {
      toast.error(getErrorMessage(error, "初始化失败"));
    }
  };

  if (loading) {
    return (
      <div className="admin-page">
        <div className="text-sm text-muted-foreground">加载中...</div>
      </div>
    );
  }

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">系统配置</h1>
          <p className="admin-page-subtitle">点击"编辑"修改配置，保存后运行时立即生效</p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={handleInitFromYaml}>
            <Download className="mr-2 h-4 w-4" /> 从 YAML 初始化
          </Button>
        </div>
      </div>

      {groups.map((g) => (
        <EditableCard key={g.group} group={g.group} items={g.items} onSave={handleSave} />
      ))}
    </div>
  );
}
