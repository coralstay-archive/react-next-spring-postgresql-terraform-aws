"use client";

import { useRouter, useSearchParams } from "next/navigation";

import { ActionsTab } from "@/components/admin/actions-tab";
import { CategoriesTab } from "@/components/admin/categories-tab";
import { PostsTab } from "@/components/admin/posts-tab";
import { UsersTab } from "@/components/admin/users-tab";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

export const ADMIN_TABS = [
  { value: "users", label: "사용자" },
  { value: "posts", label: "글" },
  { value: "categories", label: "카테고리" },
  { value: "actions", label: "감사 로그" },
] as const;

export type AdminTab = (typeof ADMIN_TABS)[number]["value"];

export function parseAdminTab(raw: string | null): AdminTab {
  return ADMIN_TABS.some((t) => t.value === raw) ? (raw as AdminTab) : "users";
}

/**
 * 관리자 화면 `/admin/` (TASK-15#1). 탭은 URL `?tab=users|posts|categories|actions` 와 동기화한다(공유·새로 고침 유지).
 * 선택된 탭만 그려서 그 탭의 API 만 부른다. 가드(`RequireAdmin`)는 page.tsx 가 감싼다.
 */
export function AdminView() {
  const params = useSearchParams();
  const router = useRouter();
  const tab = parseAdminTab(params.get("tab"));

  return (
    <div className="flex w-full flex-col gap-6" data-testid="admin-view">
      <h1 className="text-2xl font-semibold tracking-tight">관리자</h1>
      <Tabs
        value={tab}
        onValueChange={(value) => router.replace(`/admin/?tab=${value}`, { scroll: false })}
        className="gap-4"
      >
        <TabsList aria-label="관리 메뉴">
          {ADMIN_TABS.map((t) => (
            <TabsTrigger key={t.value} value={t.value} data-testid={`admin-tab-${t.value}`}>
              {t.label}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="users">
          <UsersTab />
        </TabsContent>
        <TabsContent value="posts">
          <PostsTab />
        </TabsContent>
        <TabsContent value="categories">
          <CategoriesTab />
        </TabsContent>
        <TabsContent value="actions">
          <ActionsTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
