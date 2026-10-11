import { Button } from "@/components/ui/button";

export default function Home() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 px-6 py-24">
      <h1 className="text-3xl font-semibold tracking-tight">게시판</h1>
      <p className="text-muted-foreground">
        Next.js 정적 export + Spring Boot + PostgreSQL 게시판입니다.
      </p>
      <div>
        <Button>시작하기</Button>
      </div>
    </main>
  );
}
