"use client";

import { useState, type FormEvent } from "react";

import { ConfirmDialog } from "@/components/admin/confirm-dialog";
import { Pager } from "@/components/admin/pager";
import { ApiErrorAlert, RefetchErrorAlert } from "@/components/board/api-error-alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useAdminUsers, useUserStatusAction, type AdminUser, type UserFilters, type UserStatus } from "@/lib/admin";
import { formatDate } from "@/lib/board";
import { javaStrip } from "@/lib/board-query";

type Pending = { user: AdminUser; action: "suspend" | "unsuspend" };

/** 사용자 탭 (TASK-15#1): 검색(닉네임·이메일·전화), 상태 필터, 페이지, 정지·해제(확인 + 사유). */
export function UsersTab() {
  const [filters, setFilters] = useState<UserFilters>({ page: 0 });
  const [text, setText] = useState("");
  // 닫히는 동안에도 문구가 바뀌지 않도록 대상은 남겨 두고 열림만 따로 둔다.
  const [pending, setPending] = useState<Pending | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogKey, setDialogKey] = useState(0);
  const list = useAdminUsers(filters);
  const mutation = useUserStatusAction();

  const search = (event: FormEvent) => {
    event.preventDefault();
    setFilters((f) => ({ ...f, q: javaStrip(text) || undefined, page: 0 }));
  };

  const open = (user: AdminUser, action: Pending["action"]) => {
    mutation.reset();
    setDialogKey((k) => k + 1);
    setPending({ user, action });
    setDialogOpen(true);
  };

  const confirm = (reason: string) => {
    if (!pending) return;
    mutation.mutate(
      { id: pending.user.id, action: pending.action, reason },
      { onSuccess: () => setDialogOpen(false) },
    );
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <form role="search" onSubmit={search} className="flex gap-2">
          <Input
            type="search"
            aria-label="사용자 검색"
            placeholder="닉네임·이메일·전화번호"
            data-testid="admin-user-search-input"
            value={text}
            onChange={(e) => setText(e.target.value)}
            className="w-64"
          />
          <Button type="submit" variant="outline" data-testid="admin-user-search-submit">
            검색
          </Button>
        </form>
        <select
          aria-label="상태"
          data-testid="admin-user-status-filter"
          className="border-input h-8 rounded-lg border bg-transparent px-2 text-sm"
          value={filters.status ?? ""}
          onChange={(e) =>
            setFilters((f) => ({ ...f, status: (e.target.value || undefined) as UserStatus | undefined, page: 0 }))
          }
        >
          <option value="">전체 상태</option>
          <option value="ACTIVE">활성</option>
          <option value="SUSPENDED">정지</option>
        </select>
      </div>

      {list.isError && list.data && (
        <RefetchErrorAlert
          error={list.error}
          retrying={list.isFetching}
          onRetry={() => void list.refetch()}
          testId="admin-users-refetch-error"
        />
      )}
      {list.isError && !list.data ? (
        <ApiErrorAlert error={list.error} testId="admin-users-error" />
      ) : list.isPending ? (
        <p role="status" className="text-muted-foreground text-sm">
          불러오는 중…
        </p>
      ) : list.data.items.length === 0 ? (
        <p className="text-muted-foreground py-8 text-center text-sm" data-testid="admin-users-empty">
          사용자가 없습니다.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <table className="w-full text-left text-sm" data-testid="admin-user-table">
            <thead className="bg-muted/50 text-muted-foreground">
              <tr>
                <th className="px-3 py-2 font-medium">ID</th>
                <th className="px-3 py-2 font-medium">닉네임</th>
                <th className="px-3 py-2 font-medium">연락처</th>
                <th className="px-3 py-2 font-medium">로그인 수단</th>
                <th className="px-3 py-2 font-medium">상태</th>
                <th className="px-3 py-2 font-medium">가입</th>
                <th className="px-3 py-2 font-medium">
                  <span className="sr-only">작업</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {list.data.items.map((user) => (
                <tr key={user.id} data-testid="admin-user-row" data-user-id={user.id}>
                  <td className="px-3 py-2 tabular-nums">{user.id}</td>
                  <td className="px-3 py-2">
                    <span className="font-medium">{user.nickname}</span>
                    {user.role === "ADMIN" && (
                      <Badge variant="secondary" className="ml-1" data-testid="admin-user-role">
                        관리자
                      </Badge>
                    )}
                  </td>
                  <td className="text-muted-foreground px-3 py-2">
                    {user.email && <div>{user.email}</div>}
                    {user.phone && <div>{user.phone}</div>}
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex flex-wrap gap-1">
                      {user.providers.map((p) => (
                        <Badge key={p} variant="outline" data-testid="admin-user-provider">
                          {p}
                        </Badge>
                      ))}
                    </div>
                  </td>
                  <td className="px-3 py-2">
                    <Badge
                      variant={user.status === "SUSPENDED" ? "destructive" : "secondary"}
                      data-testid="admin-user-status"
                    >
                      {user.status === "SUSPENDED" ? "정지" : "활성"}
                    </Badge>
                  </td>
                  <td className="text-muted-foreground px-3 py-2 whitespace-nowrap">{formatDate(user.createdAt)}</td>
                  <td className="px-3 py-2 text-right">
                    {user.status === "SUSPENDED" ? (
                      <Button size="sm" variant="outline" data-testid="admin-user-unsuspend" onClick={() => open(user, "unsuspend")}>
                        정지 해제
                      </Button>
                    ) : (
                      <Button size="sm" variant="destructive" data-testid="admin-user-suspend" onClick={() => open(user, "suspend")}>
                        정지
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {list.data && (
        <Pager
          page={filters.page}
          totalPages={list.data.totalPages}
          onChange={(page) => setFilters((f) => ({ ...f, page }))}
          testId="admin-users"
          settled={!list.isPlaceholderData}
        />
      )}

      <ConfirmDialog
        key={`dialog-${dialogKey}`}
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        title={pending?.action === "unsuspend" ? "정지를 해제할까요?" : "사용자를 정지할까요?"}
        description={
          pending?.action === "unsuspend"
            ? `${pending.user.nickname}(#${pending.user.id})의 정지를 해제합니다. 끊긴 세션은 되살아나지 않으니 다시 로그인해야 합니다.`
            : `${pending?.user.nickname ?? ""}(#${pending?.user.id ?? ""})을 정지합니다. 모든 세션과 토큰이 즉시 끊기고 로그인할 수 없게 됩니다.`
        }
        confirmLabel={pending?.action === "unsuspend" ? "정지 해제" : "정지"}
        destructive={pending?.action === "suspend"}
        withReason
        pending={mutation.isPending}
        error={mutation.error}
        onConfirm={confirm}
      />
    </div>
  );
}
