import { Navigate, createBrowserRouter } from "react-router-dom";

import { LoginPage } from "@/pages/LoginPage";
import { ChatPage } from "@/pages/ChatPage";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { AdminLayout } from "@/pages/admin/AdminLayout";
import { DashboardPage } from "@/pages/admin/dashboard/DashboardPage";
import { HotspotRadarPage as AdminHotspotRadarPage } from "@/pages/admin/hotspots/HotspotRadarPage";
import { KnowledgeListPage as AdminKnowledgeListPage } from "@/pages/admin/knowledge/KnowledgeListPage";
import { KnowledgeDocumentsPage as AdminKnowledgeDocumentsPage } from "@/pages/admin/knowledge/KnowledgeDocumentsPage";
import { KnowledgeChunksPage as AdminKnowledgeChunksPage } from "@/pages/admin/knowledge/KnowledgeChunksPage";
import { IntentTreePage } from "@/pages/admin/intent-tree/IntentTreePage";
import { IntentListPage } from "@/pages/admin/intent-tree/IntentListPage";
import { IntentEditPage } from "@/pages/admin/intent-tree/IntentEditPage";
import { IngestionPage as AdminIngestionPage } from "@/pages/admin/ingestion/IngestionPage";
import { RagTracePage } from "@/pages/admin/traces/RagTracePage";
import { RagTraceDetailPage } from "@/pages/admin/traces/RagTraceDetailPage";
import { SystemSettingsPage } from "@/pages/admin/settings/SystemSettingsPage";
import { SampleQuestionPage } from "@/pages/admin/sample-questions/SampleQuestionPage";
import { UserListPage } from "@/pages/admin/users/UserListPage";
import { ModelManagementPage } from "@/pages/admin/models/ModelManagementPage";
import { EvaluationPage } from "@/pages/admin/evaluation/EvaluationPage";
import { DatasetDetailPage } from "@/pages/admin/evaluation/DatasetDetailPage";
import { RunReportPage } from "@/pages/admin/evaluation/RunReportPage";
import { PromptsPage } from "@/pages/admin/prompts/PromptsPage";
import { UserMemoryPage } from "@/pages/admin/memory/UserMemoryPage";
import { UserProfilePage } from "@/pages/admin/memory/UserProfilePage";
import { HotspotRadarPage } from "@/pages/workspace/hotspots/HotspotRadarPage";
import { KnowledgeListPage } from "@/pages/workspace/knowledge/KnowledgeListPage";
import { KnowledgeDocumentsPage } from "@/pages/workspace/knowledge/KnowledgeDocumentsPage";
import { KnowledgeChunksPage } from "@/pages/workspace/knowledge/KnowledgeChunksPage";
import { IngestionPage } from "@/pages/workspace/ingestion/IngestionPage";
import { WorkspaceLayout } from "@/pages/workspace/WorkspaceLayout";
import { useAuthStore } from "@/stores/authStore";

function RequireAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  return children;
}

function RequireAdmin({ children }: { children: JSX.Element }) {
  const user = useAuthStore((state) => state.user);
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (user?.role !== "admin") {
    return <Navigate to="/chat" replace />;
  }

  return children;
}

function RedirectIfAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const user = useAuthStore((state) => state.user);
  if (isAuthenticated) {
    return <Navigate to={user?.role === "admin" ? "/admin/dashboard" : "/chat"} replace />;
  }
  return children;
}

function HomeRedirect() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const user = useAuthStore((state) => state.user);
  return <Navigate to={isAuthenticated ? (user?.role === "admin" ? "/admin/dashboard" : "/chat") : "/login"} replace />;
}

export const router = createBrowserRouter([
  {
    path: "/",
    element: <HomeRedirect />
  },
  {
    path: "/login",
    element: (
      <RedirectIfAuth>
        <LoginPage />
      </RedirectIfAuth>
    )
  },
  {
    path: "/chat",
    element: (
      <RequireAuth>
        <ChatPage />
      </RequireAuth>
    )
  },
  {
    path: "/chat/:sessionId",
    element: (
      <RequireAuth>
        <ChatPage />
      </RequireAuth>
    )
  },
  {
    path: "/admin",
    element: (
      <RequireAdmin>
        <AdminLayout />
      </RequireAdmin>
    ),
    children: [
      {
        index: true,
        element: <Navigate to="/admin/dashboard" replace />
      },
      {
        path: "dashboard",
        element: <DashboardPage />
      },
      {
        path: "hotspots",
        element: <AdminHotspotRadarPage />
      },
      {
        path: "knowledge",
        element: <AdminKnowledgeListPage />
      },
      {
        path: "knowledge/:kbId",
        element: <AdminKnowledgeDocumentsPage />
      },
      {
        path: "knowledge/:kbId/docs/:docId",
        element: <AdminKnowledgeChunksPage />
      },
      {
        path: "intent-tree",
        element: <IntentTreePage />
      },
      {
        path: "intent-list",
        element: <IntentListPage />
      },
      {
        path: "intent-list/:id/edit",
        element: <IntentEditPage />
      },
      {
        path: "ingestion",
        element: <AdminIngestionPage />
      },
      {
        path: "traces",
        element: <RagTracePage />
      },
      {
        path: "traces/:traceId",
        element: <RagTraceDetailPage />
      },
      {
        path: "settings",
        element: <SystemSettingsPage />
      },
      {
        path: "models",
        element: <ModelManagementPage />
      },
      {
        path: "sample-questions",
        element: <SampleQuestionPage />
      },
      {
        path: "users",
        element: <UserListPage />
      },
      {
        path: "evaluation",
        element: <EvaluationPage />
      },
      {
        path: "evaluation/datasets/:id",
        element: <DatasetDetailPage />
      },
      {
        path: "evaluation/runs/:id",
        element: <RunReportPage />
      },
      {
        path: "prompts",
        element: <PromptsPage />
      },
      {
        path: "memories",
        element: <UserMemoryPage />
      },
      {
        path: "profiles",
        element: <UserProfilePage />
      },
    ]
  },
  {
    path: "/workspace",
    element: (
      <RequireAuth>
        <WorkspaceLayout />
      </RequireAuth>
    ),
    children: [
      {
        index: true,
        element: <Navigate to="/workspace/knowledge" replace />
      },
      {
        path: "hotspots",
        element: <HotspotRadarPage />
      },
      {
        path: "knowledge",
        element: <KnowledgeListPage />
      },
      {
        path: "knowledge/:kbId",
        element: <KnowledgeDocumentsPage />
      },
      {
        path: "knowledge/:kbId/docs/:docId",
        element: <KnowledgeChunksPage />
      },
      {
        path: "ingestion",
        element: <IngestionPage />
      },
    ]
  },
  {
    path: "*",
    element: <NotFoundPage />
  }
]);
