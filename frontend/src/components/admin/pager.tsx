import { Button } from "@/components/ui/button";

/** 관리 목록 이전·다음. `page` 는 0부터(API 와 같다), 화면에는 1부터 보인다. */
export function Pager({
  page,
  totalPages,
  onChange,
  testId,
}: {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
  testId: string;
}) {
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
