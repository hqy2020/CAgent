import { useRef, useState } from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { DatasetListTab, type DatasetListTabRef } from "./tabs/DatasetListTab";
import { RunListTab } from "./tabs/RunListTab";
import { Button } from "@/components/ui/button";
import { Plus, RefreshCw } from "lucide-react";

export function EvaluationPage() {
  const [activeTab, setActiveTab] = useState("datasets");
  const datasetTabRef = useRef<DatasetListTabRef>(null);

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold">RAG 评测系统</h1>
          <p className="text-sm text-muted-foreground mt-1">
            三阶段评估：检索评测 → 生成评测 → 端到端评测
          </p>
        </div>
        {activeTab === "datasets" && (
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => datasetTabRef.current?.refresh()}>
              <RefreshCw className="h-4 w-4 mr-1" />
              刷新
            </Button>
            <Button onClick={() => datasetTabRef.current?.openCreate()}>
              <Plus className="h-4 w-4 mr-1" />
              新建数据集
            </Button>
          </div>
        )}
      </div>
      <Tabs value={activeTab} onValueChange={setActiveTab}>
        <TabsList>
          <TabsTrigger value="datasets">数据集管理</TabsTrigger>
          <TabsTrigger value="runs">评测记录</TabsTrigger>
        </TabsList>
        <TabsContent value="datasets">
          <DatasetListTab ref={datasetTabRef} />
        </TabsContent>
        <TabsContent value="runs">
          <RunListTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
