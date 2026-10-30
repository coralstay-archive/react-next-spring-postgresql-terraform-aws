import { useEffect } from "react";

import { Button } from "@/components/ui/button";
import { ADMIN_PAGE_SIZE } from "@/lib/admin";
import { maxPageCount } from "@/lib/board-query";

/**
 * 관리 목록 이전·다음. `page` 는 0부터(API 와 같다), 화면에는 1부터 보인다.
 *
 * 범위 클램프(TASK-33#3, FE-33): 처리·재조회로 결과가 줄어 지금 쪽이 `totalPages` 밖이 되면 마지막 쪽(0건이면 첫 쪽)으로 `onChange` 한다.
 * 서버 OFFSET 상한(TASK-33#4, `page × size ≤ 10,000` — 관리자 목록도 같다)을 넘는 쪽으로는 '다음' 을 막고, 넘은 쪽은 상한 쪽으로 옮긴다.
 * `maxPages` 는 요청할 수 있는 쪽 수(기본은 관리 목록 20개씩 = 501쪽). `settled` 가 false(keepPreviousData 의 이전 쪽 데이터를 보여 주는 중)면 그 totalPages 는 지금 쪽 것이 아니라 옮기지 않는다.
 */
export function Pager({
  page,
  totalPages,
  onChange,
  testId,
  settled = true,
  maxPages = maxPageCount(ADMIN_PAGE_SIZE),
}: {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
  testId: string;
  settled?: boolean;
  maxPages?: number;
}) {
  const reachable = Math.min(totalPages, maxPages);
  const last = Math.max(reachable - 1, 0);
  const outOfRange = settled && page > last;
  useEffect(() => {
    if (outOfRange) onChange(last);
  }, [outOfRange, last, onChange]);

  if (totalPages <= 1 && page === 0) return null;
  const capped = page + 1 >= maxPages && totalPages > maxPages;
  return (
    <div className="flex flex-col items-center gap-2">
      <nav aria-label="페이지" className="flex items-center justify-center gap-3 text-sm">
        <Button
          variant="outline"
          size="sm"
          disabled={page <= 0}
          onClick={() => onChange(page - 1)}
          data-testid={`${testId}-prev`}
        >
          이전
        </Button>
        <span data-testid={`${testId}-page`}>
          {page + 1} / {Math.max(totalPages, 1)}
        </span>
        <Button
          variant="outline"
          size="sm"
          disabled={page + 1 >= reachable}
          onClick={() => onChange(page + 1)}
          data-testid={`${testId}-next`}
        >
          다음
        </Button>
      </nav>
      {capped && (
        <p className="text-muted-foreground text-sm" data-testid={`${testId}-cap`}>
          {maxPages}쪽 이후는 볼 수 없습니다. 검색·필터로 좁혀 보세요.
        </p>
      )}
    </div>
  );
}
