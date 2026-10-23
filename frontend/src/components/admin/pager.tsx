import { useEffect } from "react";

import { Button } from "@/components/ui/button";

/**
 * 관리 목록 이전·다음. `page` 는 0부터(API 와 같다), 화면에는 1부터 보인다.
 *
 * 범위 클램프(TASK-33#3, FE-33): 처리·재조회로 결과가 줄어 지금 쪽이 `totalPages` 밖이 되면 마지막 쪽(0건이면 첫 쪽)으로 `onChange` 한다.
 * `settled` 가 false(keepPreviousData 의 이전 쪽 데이터를 보여 주는 중)면 그 totalPages 는 지금 쪽 것이 아니라 옮기지 않는다.
 */
export function Pager({
  page,
  totalPages,
  onChange,
  testId,
  settled = true,
}: {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
  testId: string;
  settled?: boolean;
}) {
  const last = Math.max(totalPages - 1, 0);
  const outOfRange = settled && page > last;
  useEffect(() => {
    if (outOfRange) onChange(last);
  }, [outOfRange, last, onChange]);

  if (totalPages <= 1 && page === 0) return null;
  return (
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
        disabled={page + 1 >= totalPages}
        onClick={() => onChange(page + 1)}
        data-testid={`${testId}-next`}
      >
        다음
      </Button>
    </nav>
  );
}
