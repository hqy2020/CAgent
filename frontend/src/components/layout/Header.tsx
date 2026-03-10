import { BookOpen, Menu } from "lucide-react";
import { useNavigate } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { useChatStore } from "@/stores/chatStore";

interface HeaderProps {
  onToggleSidebar: () => void;
}

export function Header({ onToggleSidebar }: HeaderProps) {
  const navigate = useNavigate();
  const { currentSessionId, sessions } = useChatStore();
  const currentSession = sessions.find((session) => session.id === currentSessionId);

  return (
    <header className="sticky top-0 z-20 bg-white">
      <div className="flex h-16 items-center justify-between px-6">
        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="icon"
            onClick={onToggleSidebar}
            aria-label="切换侧边栏"
            className="text-gray-500 hover:bg-gray-100 lg:hidden"
          >
            <Menu className="h-5 w-5" />
          </Button>
          <div>
            <p className="text-base font-medium text-gray-900">{currentSession?.title || "新对话"}</p>
            <p className="text-xs text-gray-500">与你的第二大脑持续对话</p>
          </div>
        </div>
        <Button variant="outline" onClick={() => navigate("/workspace/knowledge")}>
          <BookOpen className="mr-2 h-4 w-4" />
          打开知识库
        </Button>
      </div>
    </header>
  );
}
