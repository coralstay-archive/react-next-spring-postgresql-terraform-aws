import { Badge } from "@/components/ui/badge";

/** 삭제·숨김 글 배지. 이런 글은 ADMIN 에게만 보이므로(서버 B2) 플래그가 켜져 있으면 그대로 표시한다. */
export function PostFlags({ deleted, hidden }: { deleted: boolean; hidden: boolean }) {
  return (
    <>
      {deleted && (
        <Badge variant="destructive" data-testid="badge-deleted">
          삭제됨
        </Badge>
      )}
      {hidden && (
        <Badge variant="outline" data-testid="badge-hidden">
          숨김
        </Badge>
      )}
    </>
  );
}
