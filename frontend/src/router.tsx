import { Navigate, createBrowserRouter } from "react-router-dom";

import { LoginPage } from "@/pages/LoginPage";
import { ChatPage } from "@/pages/ChatPage";
import { NotFoundPage } from "@/pages/NotFoundPage";
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

function RedirectIfAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  if (isAuthenticated) {
    return <Navigate to="/chat" replace />;
  }
  return children;
}

function HomeRedirect() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  return <Navigate to={isAuthenticated ? "/chat" : "/login"} replace />;
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
