import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { Link, Outlet, useLocation, useNavigate } from "react-router-dom";
import {
  Brain,
  ChevronDown,
  Flame,
  KeyRound,
  LogOut,
  Menu,
  MessageSquare,
  Search,
  Upload,
  Database,
} from "lucide-react";
import { toast } from "sonner";

import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import {
  getKnowledgeBases,
  searchKnowledgeDocuments,
  type KnowledgeBase,
  type KnowledgeDocumentSearchItem,
} from "@/services/knowledgeService";
import { changePassword } from "@/services/userService";
import { useAuthStore } from "@/stores/authStore";

type NavItem = {
  path: string;
  label: string;
  icon: typeof Database;
};

const navItems: NavItem[] = [
  {
    path: "/workspace/knowledge",
    label: "知识库",
    icon: Database,
  },
  {
    path: "/workspace/ingestion",
    label: "导入流水线",
    icon: Upload,
  },
  {
    path: "/workspace/hotspots",
    label: "热点雷达",
    icon: Flame,
  },
];

const breadcrumbMap: Record<string, string> = {
  knowledge: "知识库",
  ingestion: "导入流水线",
  hotspots: "热点雷达",
};

export function WorkspaceLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [passwordSubmitting, setPasswordSubmitting] = useState(false);
  const [passwordForm, setPasswordForm] = useState({
    currentPassword: "",
    newPassword: "",
    confirmPassword: "",
  });
  const [kbQuery, setKbQuery] = useState("");
  const [kbOptions, setKbOptions] = useState<KnowledgeBase[]>([]);
  const [docOptions, setDocOptions] = useState<KnowledgeDocumentSearchItem[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchFocused, setSearchFocused] = useState(false);
  const blurTimeoutRef = useRef<number | null>(null);
  const searchInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!searchFocused) return;
    const keyword = kbQuery.trim();
    if (!keyword) {
      setKbOptions([]);
      setDocOptions([]);
      setSearchLoading(false);
      return;
    }

    let active = true;
    const handle = window.setTimeout(() => {
      setSearchLoading(true);
      Promise.all([getKnowledgeBases(1, 6, keyword), searchKnowledgeDocuments(keyword, 6)])
        .then(([kbData, docData]) => {
          if (!active) return;
          setKbOptions(kbData || []);
          setDocOptions(docData || []);
        })
        .catch(() => {
          if (active) {
            setKbOptions([]);
            setDocOptions([]);
          }
        })
        .finally(() => {
          if (active) {
            setSearchLoading(false);
          }
        });
    }, 200);

    return () => {
      active = false;
      window.clearTimeout(handle);
    };
  }, [kbQuery, searchFocused]);

  const breadcrumbs = useMemo(() => {
    const segments = location.pathname.split("/").filter(Boolean);
    const items: { label: string; to?: string }[] = [{ label: "工作区", to: "/workspace/knowledge" }];

    if (segments[0] !== "workspace") {
      return items;
    }

    const section = segments[1];
    if (section) {
      items.push({
        label: breadcrumbMap[section] || section,
        to: `/workspace/${section}`,
      });
    }

    if (section === "knowledge" && segments.length > 2) {
      items.push({ label: "文档" });
    }

    if (section === "knowledge" && segments.includes("docs")) {
      items.push({ label: "切片" });
    }

    return items;
  }, [location.pathname]);

  const avatarName = user?.username || user?.userId || "用户";
  const avatarUrl = user?.avatar?.trim() || "/logo.png";

  const handleLogout = async () => {
    await logout();
    navigate("/login");
  };

  const handlePasswordSubmit = async () => {
    if (!passwordForm.currentPassword || !passwordForm.newPassword) {
      toast.error("请输入当前密码和新密码");
      return;
    }
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      toast.error("两次输入的新密码不一致");
      return;
    }
    try {
      setPasswordSubmitting(true);
      await changePassword({
        currentPassword: passwordForm.currentPassword,
        newPassword: passwordForm.newPassword,
      });
      toast.success("密码已更新");
      setPasswordOpen(false);
      setPasswordForm({ currentPassword: "", newPassword: "", confirmPassword: "" });
    } catch (error) {
      toast.error((error as Error).message || "修改密码失败");
    } finally {
      setPasswordSubmitting(false);
    }
  };

  const handleSearchSelect = (kb: KnowledgeBase) => {
    searchInputRef.current?.blur();
    navigate(`/workspace/knowledge/${kb.id}`);
    setSearchFocused(false);
    setKbQuery("");
    setKbOptions([]);
    setDocOptions([]);
  };

  const handleDocumentSelect = (doc: KnowledgeDocumentSearchItem) => {
    searchInputRef.current?.blur();
    navigate(`/workspace/knowledge/${doc.kbId}/docs/${doc.id}`);
    setSearchFocused(false);
    setKbQuery("");
    setKbOptions([]);
    setDocOptions([]);
  };

  const handleSearchFocus = () => {
    if (blurTimeoutRef.current) {
      window.clearTimeout(blurTimeoutRef.current);
      blurTimeoutRef.current = null;
    }
    setSearchFocused(true);
  };

  const handleSearchBlur = () => {
    if (blurTimeoutRef.current) {
      window.clearTimeout(blurTimeoutRef.current);
    }
    blurTimeoutRef.current = window.setTimeout(() => {
      setSearchFocused(false);
    }, 150);
  };

  const handleSearchKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter") {
      const keyword = kbQuery.trim();
      if (kbOptions.length > 0) {
        handleSearchSelect(kbOptions[0]);
        return;
      }
      if (docOptions.length > 0) {
        handleDocumentSelect(docOptions[0]);
        return;
      }
      if (keyword) {
        searchInputRef.current?.blur();
        navigate(`/workspace/knowledge?name=${encodeURIComponent(keyword)}`);
        setSearchFocused(false);
      }
    }
    if (event.key === "Escape") {
      searchInputRef.current?.blur();
      setSearchFocused(false);
    }
  };

  const showSuggest = searchFocused && kbQuery.trim().length > 0;

  return (
    <div className="min-h-screen bg-[#F6F7FB]">
      <div
        className={cn(
          "fixed inset-0 z-30 bg-slate-950/20 backdrop-blur-sm transition-opacity lg:hidden",
          mobileNavOpen ? "opacity-100" : "pointer-events-none opacity-0",
        )}
        onClick={() => setMobileNavOpen(false)}
      />

      <div className="flex min-h-screen">
        <aside
          className={cn(
            "fixed inset-y-0 left-0 z-40 flex w-72 flex-col border-r border-slate-200 bg-white px-4 py-5 transition-transform lg:static lg:translate-x-0",
            mobileNavOpen ? "translate-x-0" : "-translate-x-full",
          )}
        >
          <div className="flex items-center gap-3 border-b border-slate-100 pb-4">
            <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-[#2563EB] text-white shadow-[0_12px_30px_rgba(37,99,235,0.25)]">
              <Brain className="h-5 w-5" />
            </div>
            <div>
              <h1 className="text-base font-semibold text-slate-900">第二大脑</h1>
              <p className="text-xs text-slate-500">GardenOfOpeningClouds Workspace</p>
            </div>
          </div>

          <div className="mt-5 rounded-3xl border border-slate-200 bg-gradient-to-br from-[#EFF6FF] via-white to-[#FEF9C3] p-4">
            <p className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500">工作区</p>
            <p className="mt-2 text-sm leading-6 text-slate-700">
              管理知识库、导入资料、追踪输入信号，所有入口都收敛到你的第二大脑。
            </p>
            <Button
              className="mt-4 w-full justify-start rounded-2xl bg-[#2563EB] hover:bg-[#1D4ED8]"
              onClick={() => {
                navigate("/workspace/knowledge");
                setMobileNavOpen(false);
              }}
            >
              <Database className="mr-2 h-4 w-4" />
              打开知识库
            </Button>
          </div>

          <nav className="mt-6 space-y-2">
            {navItems.map((item) => {
              const Icon = item.icon;
              const active =
                location.pathname === item.path || location.pathname.startsWith(`${item.path}/`);
              return (
                <Link
                  key={item.path}
                  to={item.path}
                  onClick={() => setMobileNavOpen(false)}
                  className={cn(
                    "flex items-center gap-3 rounded-2xl px-4 py-3 text-sm font-medium transition-colors",
                    active
                      ? "bg-[#E0ECFF] text-[#1D4ED8]"
                      : "text-slate-600 hover:bg-slate-50 hover:text-slate-900",
                  )}
                >
                  <Icon className="h-4 w-4" />
                  {item.label}
                </Link>
              );
            })}
          </nav>

          <div className="mt-auto border-t border-slate-100 pt-4">
            <div className="flex items-center gap-3 rounded-2xl bg-slate-50 px-3 py-3">
              <Avatar
                name={avatarName}
                src={avatarUrl}
                className="h-10 w-10 border-slate-200 bg-blue-50 text-sm font-semibold text-blue-600"
              />
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-slate-900">{avatarName}</p>
                <p className="truncate text-xs text-slate-500">个人知识空间</p>
              </div>
            </div>
          </div>
        </aside>

        <div className="flex min-w-0 flex-1 flex-col">
          <header className="sticky top-0 z-20 border-b border-slate-200 bg-white/90 backdrop-blur">
            <div className="flex items-center gap-3 px-4 py-4 lg:px-8">
              <Button
                variant="ghost"
                size="icon"
                className="lg:hidden"
                onClick={() => setMobileNavOpen((prev) => !prev)}
                aria-label="切换工作区导航"
              >
                <Menu className="h-5 w-5" />
              </Button>

              <div className="relative max-w-xl flex-1">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <Input
                  ref={searchInputRef}
                  value={kbQuery}
                  onChange={(event) => setKbQuery(event.target.value)}
                  onFocus={handleSearchFocus}
                  onBlur={handleSearchBlur}
                  onKeyDown={handleSearchKeyDown}
                  placeholder="搜索知识库或文档..."
                  className="rounded-2xl border-slate-200 bg-slate-50 pl-10 pr-4"
                />
                {showSuggest ? (
                  <div
                    className="absolute left-0 right-0 top-[calc(100%+10px)] rounded-2xl border border-slate-200 bg-white p-2 shadow-xl"
                    onMouseDown={(event) => event.preventDefault()}
                  >
                    {searchLoading && kbOptions.length === 0 && docOptions.length === 0 ? (
                      <div className="rounded-xl px-3 py-2 text-sm text-slate-400">搜索中...</div>
                    ) : null}
                    {kbOptions.map((kb) => (
                      <button
                        key={kb.id}
                        type="button"
                        onMouseDown={(event) => {
                          event.preventDefault();
                          handleSearchSelect(kb);
                        }}
                        className="flex w-full items-center justify-between rounded-xl px-3 py-2 text-left hover:bg-slate-50"
                      >
                        <span className="font-medium text-slate-900">{kb.name}</span>
                        <span className="text-xs text-slate-400">{kb.collectionName || "未设置集合"}</span>
                      </button>
                    ))}
                    {docOptions.map((doc) => (
                      <button
                        key={doc.id}
                        type="button"
                        onMouseDown={(event) => {
                          event.preventDefault();
                          handleDocumentSelect(doc);
                        }}
                        className="flex w-full items-center justify-between rounded-xl px-3 py-2 text-left hover:bg-slate-50"
                      >
                        <span className="font-medium text-slate-900">{doc.docName}</span>
                        <span className="text-xs text-slate-400">{doc.kbName || `知识库 ${doc.kbId}`}</span>
                      </button>
                    ))}
                    {!searchLoading && kbOptions.length === 0 && docOptions.length === 0 ? (
                      <div className="rounded-xl px-3 py-2 text-sm text-slate-400">暂无匹配结果</div>
                    ) : null}
                  </div>
                ) : null}
              </div>

              <Button variant="outline" className="hidden sm:inline-flex" onClick={() => navigate("/chat")}>
                <MessageSquare className="mr-2 h-4 w-4" />
                返回对话
              </Button>

              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button
                    type="button"
                    className="flex items-center gap-2 rounded-full border border-slate-200 bg-white px-2.5 py-1.5 text-sm text-slate-600 shadow-sm"
                    aria-label="用户菜单"
                  >
                    <Avatar
                      name={avatarName}
                      src={avatarUrl}
                      className="h-8 w-8 border-slate-200 bg-blue-50 text-xs font-semibold text-blue-600"
                    />
                    <span className="hidden sm:inline">{avatarName}</span>
                    <ChevronDown className="h-4 w-4 text-slate-400" />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" sideOffset={8} className="w-44">
                  <div className="px-3 py-2 text-xs text-slate-500">个人知识空间</div>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => setPasswordOpen(true)}>
                    <KeyRound className="mr-2 h-4 w-4" />
                    修改密码
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={handleLogout} className="text-rose-600 focus:text-rose-600">
                    <LogOut className="mr-2 h-4 w-4" />
                    退出登录
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          </header>

          <main className="flex-1 px-4 py-6 lg:px-8">
            <nav className="mb-4 flex flex-wrap items-center gap-2 text-sm text-slate-500">
              {breadcrumbs.map((item, index) => {
                const isLast = index === breadcrumbs.length - 1;
                return (
                  <span key={`${item.label}-${index}`} className="flex items-center gap-2">
                    {item.to && !isLast ? <Link to={item.to}>{item.label}</Link> : <span>{item.label}</span>}
                    {!isLast ? <span>/</span> : null}
                  </span>
                );
              })}
            </nav>
            <Outlet />
          </main>
        </div>
      </div>

      <Dialog
        open={passwordOpen}
        onOpenChange={(open) => {
          setPasswordOpen(open);
          if (!open) {
            setPasswordForm({ currentPassword: "", newPassword: "", confirmPassword: "" });
          }
        }}
      >
        <DialogContent className="sm:max-w-[420px]">
          <DialogHeader>
            <DialogTitle>修改密码</DialogTitle>
            <DialogDescription>请输入当前密码与新密码</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">当前密码</label>
              <Input
                type="password"
                value={passwordForm.currentPassword}
                onChange={(event) =>
                  setPasswordForm((prev) => ({ ...prev, currentPassword: event.target.value }))
                }
                placeholder="请输入当前密码"
                autoComplete="current-password"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">新密码</label>
              <Input
                type="password"
                value={passwordForm.newPassword}
                onChange={(event) =>
                  setPasswordForm((prev) => ({ ...prev, newPassword: event.target.value }))
                }
                placeholder="请输入新密码"
                autoComplete="new-password"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">确认新密码</label>
              <Input
                type="password"
                value={passwordForm.confirmPassword}
                onChange={(event) =>
                  setPasswordForm((prev) => ({ ...prev, confirmPassword: event.target.value }))
                }
                placeholder="再次输入新密码"
                autoComplete="new-password"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPasswordOpen(false)}>
              取消
            </Button>
            <Button onClick={handlePasswordSubmit} disabled={passwordSubmitting}>
              {passwordSubmitting ? "保存中..." : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
