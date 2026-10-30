import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = { title: "페이지를 찾을 수 없습니다 - 게시판" };

/**
 * 없는 경로(TASK-34#1, FE-34). 정적 export 에서는 `out/404.html` 이 되고, CloudFront Function 이 모르는 경로를 이 파일로 보낸다(TASK-34#2).
 */
export default function NotFound() {
  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col items-start gap-4 px-4 py-10">
      <h1 className="text-2xl font-semibold">페이지를 찾을 수 없습니다</h1>
      <p className="text-muted-foreground text-sm">주소가 바뀌었거나 없는 페이지입니다. 주소를 확인하거나 목록에서 다시 찾아 주세요.</p>
      <Link href="/" className="text-sm font-medium underline underline-offset-4">
        목록으로
      </Link>
    </main>
  );
}
