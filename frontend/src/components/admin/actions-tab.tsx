"use client";

import { useState } from "react";

import { Pager } from "@/components/admin/pager";
import { ApiErrorAlert } from "@/components/board/api-error-alert";
import { ACTION_LABELS, TARGET_LABELS, useAdminActions } from "@/lib/admin";
import { formatDate } from "@/lib/board";

/** 감사 로그 탭 (TASK-15#1): `GET /api/admin/actions` 최근순 페이지. */
export function ActionsTab() {
  const [page, setPage] = useState(0);
  const list = useAdminActions(page);

  return (
    <div className="flex flex-col gap-4">
      {list.isError ? (
        <ApiErrorAlert error={list.error} testId="admin-actions-error" />
      ) : list.isPending ? (
        <p role="status" className="text-muted-foreground text-sm">
          불러오는 중…
        </p>
      ) : list.data.items.length === 0 ? (
        <p className="text-muted-foreground py-8 text-center text-sm" data-testid="admin-actions-empty">
          기록이 없습니다.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <table className="w-full text-left text-sm" data-testid="admin-action-table">
            <thead className="bg-muted/50 text-muted-foreground">
              <tr>
                <th className="px-3 py-2 font-medium">시각</th>
                <th className="px-3 py-2 font-medium">관리자</th>
                <th className="px-3 py-2 font-medium">작업</th>
                <th className="px-3 py-2 font-medium">대상</th>
                <th className="px-3 py-2 font-medium">사유</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {list.data.items.map((entry) => (
                <tr key={entry.id} data-testid="admin-action-row">
                  <td className="text-muted-foreground px-3 py-2 whitespace-nowrap">{formatDate(entry.createdAt)}</td>
                  <td className="px-3 py-2">{entry.adminNickname ?? `#${entry.adminId}`}</td>
                  <td className="px-3 py-2">{ACTION_LABELS[entry.action] ?? entry.action}</td>
                  <td className="px-3 py-2 whitespace-nowrap">
                    {TARGET_LABELS[entry.targetType] ?? entry.targetType} #{entry.targetId}
                  </td>
                  <td className="text-muted-foreground px-3 py-2 break-all">{entry.reason ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {list.data && <Pager page={page} totalPages={list.data.totalPages} onChange={setPage} testId="admin-actions" />}
    </div>
  );
}
