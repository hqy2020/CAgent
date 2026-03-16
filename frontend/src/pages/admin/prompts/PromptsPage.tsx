import { useEffect, useState } from "react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import {
  listPromptTemplates,
  updatePromptTemplate,
  resetPromptTemplate,
  togglePromptTemplate,
  type PromptTemplate,
} from "@/services/promptService";
import { getErrorMessage } from "@/utils/error";

export function PromptsPage() {
  const [templates, setTemplates] = useState<PromptTemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [editContent, setEditContent] = useState("");
  const [saving, setSaving] = useState(false);

  const categories = [
    { value: null, label: "全部" },
    { value: "SYSTEM", label: "系统" },
    { value: "SCENE", label: "场景" },
    { value: "FLOW", label: "流程" },
    { value: "EVAL", label: "评测" },
  ];

  const loadTemplates = async () => {
    try {
      setLoading(true);
      const data = await listPromptTemplates(selectedCategory || undefined);
      setTemplates(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载提示词列表失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadTemplates();
  }, [selectedCategory]);

  const selectedTemplate = templates.find((t) => t.promptKey === selectedKey);

  useEffect(() => {
    if (selectedTemplate) {
      setEditContent(selectedTemplate.content);
    }
  }, [selectedKey, selectedTemplate?.version]);

  const handleSave = async () => {
    if (!selectedKey) return;
    try {
      setSaving(true);
      await updatePromptTemplate(selectedKey, editContent);
      toast.success("保存成功");
      loadTemplates();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setSaving(false);
    }
  };

  const handleReset = async () => {
    if (!selectedKey) return;
    try {
      await resetPromptTemplate(selectedKey);
      toast.success("已重置为默认版本");
      loadTemplates();
    } catch (error) {
      toast.error(getErrorMessage(error, "重置失败"));
    }
  };

  const handleToggle = async () => {
    if (!selectedKey) return;
    try {
      await togglePromptTemplate(selectedKey);
      loadTemplates();
    } catch (error) {
      toast.error(getErrorMessage(error, "切换失败"));
    }
  };

  const variables: { name: string; desc: string }[] = selectedTemplate?.variables
    ? (() => {
        try {
          return JSON.parse(selectedTemplate.variables);
        } catch {
          return [];
        }
      })()
    : [];

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold">提示词管理</h1>
        <p className="text-sm text-muted-foreground mt-1">
          管理系统中所有提示词模板，支持在线编辑和版本控制
        </p>
      </div>

      <div className="flex gap-4" style={{ height: "calc(100vh - 220px)" }}>
        {/* Left panel */}
        <div className="w-[300px] flex flex-col border rounded-lg bg-white">
          {/* Category filters */}
          <div className="p-3 border-b flex flex-wrap gap-1">
            {categories.map((cat) => (
              <Button
                key={cat.label}
                variant={selectedCategory === cat.value ? "default" : "outline"}
                size="sm"
                onClick={() => setSelectedCategory(cat.value)}
                className="text-xs h-7"
              >
                {cat.label}
              </Button>
            ))}
          </div>
          {/* Template list */}
          <div className="flex-1 overflow-y-auto">
            {loading ? (
              <div className="p-4 text-center text-muted-foreground">加载中...</div>
            ) : templates.length === 0 ? (
              <div className="p-4 text-center text-muted-foreground">暂无数据</div>
            ) : (
              templates.map((t) => (
                <div
                  key={t.promptKey}
                  onClick={() => setSelectedKey(t.promptKey)}
                  className={`p-3 border-b cursor-pointer hover:bg-gray-50 transition-colors ${
                    selectedKey === t.promptKey ? "bg-blue-50 border-l-2 border-l-blue-500" : ""
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium">{t.name}</span>
                    <Badge
                      variant={t.enabled ? "default" : "secondary"}
                      className="text-xs"
                    >
                      {t.enabled ? "DB" : "文件"}
                    </Badge>
                  </div>
                  <div className="text-xs text-muted-foreground mt-1">{t.promptKey}</div>
                  <div className="flex items-center gap-2 mt-1">
                    <Badge variant="outline" className="text-xs">
                      {t.category}
                    </Badge>
                    <span className="text-xs text-muted-foreground">v{t.version}</span>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* Right panel */}
        <div className="flex-1 border rounded-lg bg-white flex flex-col">
          {selectedTemplate ? (
            <>
              {/* Header */}
              <div className="p-4 border-b">
                <div className="flex items-center justify-between">
                  <div>
                    <h2 className="text-lg font-semibold">{selectedTemplate.name}</h2>
                    <p className="text-sm text-muted-foreground">
                      {selectedTemplate.description}
                    </p>
                  </div>
                  <div className="flex items-center gap-3">
                    <div className="flex items-center gap-2">
                      <span className="text-sm text-muted-foreground">启用DB版本</span>
                      <Switch
                        checked={selectedTemplate.enabled}
                        onCheckedChange={handleToggle}
                      />
                    </div>
                    <Badge variant="outline">v{selectedTemplate.version}</Badge>
                  </div>
                </div>
                {/* Variable descriptions */}
                {variables.length > 0 && (
                  <div className="mt-3 p-2 bg-gray-50 rounded text-xs">
                    <span className="font-medium">变量说明：</span>
                    {variables.map((v, i) => (
                      <span key={i} className="ml-2">
                        <code className="bg-gray-200 px-1 rounded">{`{${v.name}}`}</code>{" "}
                        {v.desc}
                      </span>
                    ))}
                  </div>
                )}
              </div>
              {/* Editor */}
              <div className="flex-1 p-4">
                <Textarea
                  value={editContent}
                  onChange={(e) => setEditContent(e.target.value)}
                  className="h-full min-h-[400px] font-mono text-sm resize-none"
                  placeholder="输入提示词内容..."
                />
              </div>
              {/* Actions */}
              <div className="p-4 border-t flex items-center justify-between">
                <Button variant="outline" onClick={handleReset} size="sm">
                  重置为默认
                </Button>
                <Button onClick={handleSave} disabled={saving} size="sm">
                  {saving ? "保存中..." : "保存"}
                </Button>
              </div>
            </>
          ) : (
            <div className="flex-1 flex items-center justify-center text-muted-foreground">
              请从左侧选择一个提示词模板
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
